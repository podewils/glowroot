/*
 * Copyright 2011-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.init;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.jar.JarFile;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.internal.GlowrootService;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.config.PluginDescriptor;
import org.glowroot.agent.impl.AdviceCache;
import org.glowroot.agent.impl.Aggregator;
import org.glowroot.agent.impl.ConfigServiceImpl;
import org.glowroot.agent.impl.GlowrootServiceImpl;
import org.glowroot.agent.impl.ServiceRegistryImpl;
import org.glowroot.agent.impl.ServiceRegistryImpl.ConfigServiceFactory;
import org.glowroot.agent.impl.TimerNameCache;
import org.glowroot.agent.impl.TransactionCollector;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.impl.TransactionServiceImpl;
import org.glowroot.agent.impl.UserProfileScheduler;
import org.glowroot.agent.impl.WeavingTimerServiceImpl;
import org.glowroot.agent.live.LiveJvmServiceImpl;
import org.glowroot.agent.live.LiveTraceRepositoryImpl;
import org.glowroot.agent.live.LiveWeavingServiceImpl;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.agent.util.OptionalService;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.agent.util.Tickers;
import org.glowroot.agent.weaving.AnalyzedWorld;
import org.glowroot.agent.weaving.ExtraBootResourceFinder;
import org.glowroot.agent.weaving.IsolatedWeavingClassLoader;
import org.glowroot.agent.weaving.WeaverImpl;
import org.glowroot.agent.weaving.WeavingClassFileTransformer;
import org.glowroot.agent.weaving.WeavingTimerService;
import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.Collector;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class AgentModule {

    private static final Logger logger = LoggerFactory.getLogger(AgentModule.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    // 1 minute
    private static final long ROLLUP_0_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.0.intervalMillis", 60 * 1000);

    private final PluginCache pluginCache;
    private final ConfigService configService;
    private final AnalyzedWorld analyzedWorld;
    private final TransactionRegistry transactionRegistry;
    private final AdviceCache adviceCache;
    private final WeavingTimerService weavingTimerService;

    private final TransactionCollector transactionCollector;
    private final Aggregator aggregator;

    private final ImmediateTraceStoreWatcher immedateTraceStoreWatcher;

    private final ScheduledExecutorService scheduledExecutor;
    private final GaugeCollector gaugeCollector;
    private final StackTraceCollector stackTraceCollector;

    private final boolean jvmRetransformClassesSupported;

    private final LiveTraceRepository liveTraceRepository;
    private final LiveWeavingService liveWeavingService;
    private final LiveJvmService liveJvmService;

    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

    // accepts @Nullable Ticker to deal with shading issues when called from GlowrootModule
    public AgentModule(Clock clock, @Nullable Ticker nullableTicker, final PluginCache pluginCache,
            final ConfigService configService, Collector collector,
            @Nullable Instrumentation instrumentation, File baseDir) throws Exception {

        Ticker ticker = nullableTicker == null ? Tickers.getTicker() : nullableTicker;
        this.pluginCache = pluginCache;
        this.configService = configService;
        transactionRegistry = new TransactionRegistry();

        ExtraBootResourceFinder extraBootResourceFinder =
                createExtraBootResourceFinder(instrumentation, pluginCache.pluginJars());

        adviceCache = new AdviceCache(pluginCache.pluginDescriptors(), pluginCache.pluginJars(),
                configService.getInstrumentationConfigs(), instrumentation, baseDir);
        analyzedWorld = new AnalyzedWorld(adviceCache.getAdvisorsSupplier(),
                adviceCache.getShimTypes(), adviceCache.getMixinTypes(), extraBootResourceFinder);
        final TimerNameCache timerNameCache = new TimerNameCache();
        weavingTimerService =
                new WeavingTimerServiceImpl(transactionRegistry, configService, timerNameCache);

        WeaverImpl weaver = new WeaverImpl(adviceCache.getAdvisorsSupplier(),
                adviceCache.getShimTypes(), adviceCache.getMixinTypes(), analyzedWorld,
                weavingTimerService);

        if (instrumentation == null) {
            // instrumentation is null when debugging with LocalContainer
            IsolatedWeavingClassLoader isolatedWeavingClassLoader =
                    (IsolatedWeavingClassLoader) Thread.currentThread().getContextClassLoader();
            checkNotNull(isolatedWeavingClassLoader);
            isolatedWeavingClassLoader.setWeaver(weaver);
            jvmRetransformClassesSupported = false;
        } else {
            ClassFileTransformer transformer = new WeavingClassFileTransformer(weaver);
            if (instrumentation.isRetransformClassesSupported()) {
                instrumentation.addTransformer(transformer, true);
                jvmRetransformClassesSupported = true;
            } else {
                instrumentation.addTransformer(transformer);
                jvmRetransformClassesSupported = false;
            }
        }

        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                .setNameFormat("Glowroot-Background-%d").build();
        scheduledExecutor = Executors.newScheduledThreadPool(2, threadFactory);

        aggregator = new Aggregator(scheduledExecutor, collector, configService,
                ROLLUP_0_INTERVAL_MILLIS, clock);
        transactionCollector = new TransactionCollector(scheduledExecutor, configService, collector,
                aggregator, clock, ticker);

        OptionalService<ThreadAllocatedBytes> threadAllocatedBytes = ThreadAllocatedBytes.create();

        Random random = new Random();
        UserProfileScheduler userProfileScheduler =
                new UserProfileScheduler(scheduledExecutor, configService, random);
        GlowrootService glowrootService = new GlowrootServiceImpl(transactionRegistry);
        TransactionServiceImpl transactionService = TransactionServiceImpl.create(
                transactionRegistry, transactionCollector, configService, timerNameCache,
                threadAllocatedBytes.getService(), userProfileScheduler, ticker, clock);
        ConfigServiceFactory configServiceFactory = new ConfigServiceFactory() {
            @Override
            public org.glowroot.agent.plugin.api.config.ConfigService create(String pluginId) {
                checkNotNull(configService);
                checkNotNull(pluginCache);
                return ConfigServiceImpl.create(configService, pluginCache.pluginDescriptors(),
                        pluginId);
            }
        };
        ServiceRegistryImpl.init(glowrootService, timerNameCache, transactionService,
                configServiceFactory);

        lazyPlatformMBeanServer = new LazyPlatformMBeanServer();
        gaugeCollector = new GaugeCollector(configService, collector, lazyPlatformMBeanServer,
                clock, ticker);
        // using fixed rate to keep gauge collections close to on the second mark
        long gaugeCollectionIntervalMillis = configService.getGaugeCollectionIntervalMillis();
        long initialDelay = gaugeCollectionIntervalMillis
                - (clock.currentTimeMillis() % gaugeCollectionIntervalMillis);
        gaugeCollector.scheduleWithFixedDelay(initialDelay, gaugeCollectionIntervalMillis,
                MILLISECONDS);
        stackTraceCollector = StackTraceCollector.create(transactionRegistry, configService,
                scheduledExecutor, random);

        immedateTraceStoreWatcher = new ImmediateTraceStoreWatcher(scheduledExecutor,
                transactionRegistry, transactionCollector, configService, ticker);
        immedateTraceStoreWatcher.scheduleWithFixedDelay(scheduledExecutor, 0,
                ImmediateTraceStoreWatcher.PERIOD_MILLIS, MILLISECONDS);

        liveTraceRepository = new LiveTraceRepositoryImpl(transactionRegistry, transactionCollector,
                clock, ticker);
        liveWeavingService = new LiveWeavingServiceImpl(analyzedWorld, instrumentation,
                configService, adviceCache, jvmRetransformClassesSupported);
        liveJvmService = new LiveJvmServiceImpl(lazyPlatformMBeanServer, transactionRegistry,
                transactionCollector, threadAllocatedBytes.getAvailability());

        initPlugins(pluginCache.pluginDescriptors());

        List<PluginDescriptor> pluginDescriptors = pluginCache.pluginDescriptors();
        List<String> pluginNames = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            pluginNames.add(pluginDescriptor.name());
        }
        if (!pluginNames.isEmpty()) {
            startupLogger.info("Glowroot plugins loaded: {}", Joiner.on(", ").join(pluginNames));
        }
    }

    public ConfigService getConfigService() {
        return configService;
    }

    public AdviceCache getAdviceCache() {
        return adviceCache;
    }

    public LazyPlatformMBeanServer getLazyPlatformMBeanServer() {
        return lazyPlatformMBeanServer;
    }

    public LiveTraceRepository getLiveTraceRepository() {
        return liveTraceRepository;
    }

    public LiveWeavingService getLiveWeavingService() {
        return liveWeavingService;
    }

    public LiveJvmService getLiveJvmService() {
        return liveJvmService;
    }

    public WeavingTimerService getWeavingTimerService() {
        return weavingTimerService;
    }

    public List<PluginDescriptor> getPluginDescriptors() {
        return pluginCache.pluginDescriptors();
    }

    public ScheduledExecutorService getScheduledExecutor() {
        return scheduledExecutor;
    }

    private static @Nullable ExtraBootResourceFinder createExtraBootResourceFinder(
            @Nullable Instrumentation instrumentation, List<File> pluginJars) throws IOException {
        if (instrumentation == null) {
            return null;
        }
        for (File pluginJar : pluginJars) {
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(pluginJar));
        }
        return new ExtraBootResourceFinder(pluginJars);
    }

    // now init plugins to give them a chance to do something in their static initializer
    // e.g. append their package to jboss.modules.system.pkgs
    private static void initPlugins(List<PluginDescriptor> pluginDescriptors) {
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            for (String aspect : pluginDescriptor.aspects()) {
                try {
                    Class.forName(aspect, true, AgentModule.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    // this would have already been logged as a warning during advice construction
                    logger.debug(e.getMessage(), e);
                }
            }
        }
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        immedateTraceStoreWatcher.cancel();
        aggregator.close();
        gaugeCollector.close();
        stackTraceCollector.close();
        scheduledExecutor.shutdown();
        if (!scheduledExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate agent scheduled executor");
        }
    }
}
