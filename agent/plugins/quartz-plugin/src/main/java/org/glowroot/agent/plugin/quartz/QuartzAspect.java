package org.glowroot.agent.plugin.quartz;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.quartz.JobExecutionContext;

public class QuartzAspect {

	@Pointcut(className = "org.quartz.Job", methodName = "execute", 
			methodParameterTypes = { "org.quartz.JobExecutionContext" }, 
			nestingGroup = "quartz job", timerName = "quartz job")
	public static class ExecuteAdvice {
		private static final TimerName timerName = Agent.getTimerName(ExecuteAdvice.class);

		@OnBefore
		public static @Nullable TraceEntry onBefore(OptionalThreadContext context, @BindParameter @Nullable JobExecutionContext jobExecContext) {
			if (jobExecContext == null) {
				return null;
			}

			String jobName = String.format("Quartz job: %s", jobExecContext.getJobDetail().getKey().getName());
			MessageSupplier messageSupplier = new QuartzMessageSuplier(jobName, jobExecContext.getJobDetail().getJobDataMap());
			String transactionType = "Background";

			TraceEntry traceEntry = context.startTransaction(transactionType, jobName, messageSupplier, timerName);

			return traceEntry;
		}

		@OnReturn
		public static void onReturn(OptionalThreadContext context, @BindTraveler @Nullable TraceEntry traceEntry) {
			if (traceEntry != null) {
				traceEntry.end();
			}
			context.setServletRequestInfo(null);
		}

		@OnThrow
		public static void onThrow(@BindThrowable Throwable t, OptionalThreadContext context, @BindTraveler @Nullable TraceEntry traceEntry) {
			if (traceEntry == null) {
				return;
			}
			traceEntry.endWithError(t);
			context.setServletRequestInfo(null);
		}

	}
}
