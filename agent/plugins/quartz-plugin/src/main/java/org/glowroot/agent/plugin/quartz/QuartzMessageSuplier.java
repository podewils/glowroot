package org.glowroot.agent.plugin.quartz;

import java.util.LinkedHashMap;
import java.util.Map;

import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.quartz.JobDataMap;

public class QuartzMessageSuplier extends MessageSupplier {

	private final String jobName;
	private final JobDataMap jobDataMap;

	public QuartzMessageSuplier(String jobName, JobDataMap jobDataMap) {
		this.jobName = jobName;
		this.jobDataMap = jobDataMap;
	}

	@Override
	public Message get() {
		Map<String, Object> detail = new LinkedHashMap<String, Object>();
		detail.put("Job Params", jobDataMap);
		return Message.create(jobName, detail);
	}

	private String getJobParams(JobDataMap dataMap) {
		if (dataMap == null || dataMap.isEmpty()) {
			return "empty";
		}
		StringBuilder jobParams = new StringBuilder("[ ");
		
		for (String key : dataMap.getKeys()) {
			String value = dataMap.get(key) != null ? dataMap.get(key).toString() : "";
			jobParams.append("<br>");
			jobParams.append(String.format("%s = %s", key, value));
		}
		jobParams.append(" ]");
		return jobParams.toString();
	}

	public String getJobName() {
		return this.jobName;
	}

	public String getJobDataMap() {
		return this.getJobDataMap();
	}

}
