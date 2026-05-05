package ru.ravel.ItDesk.plugins;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;


@Data
@Builder
public class PluginExecutionContext {
	private Long organizationId;
	private Long currentUserId;
	private String entityType;
	private Long entityId;

	@Builder.Default
	private Map<String, Object> entity = new HashMap<>();

	@Builder.Default
	private Map<String, Object> payload = new HashMap<>();

	@Builder.Default
	private Map<String, Object> data = new HashMap<>();

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();

		map.put("organizationId", organizationId);
		map.put("currentUserId", currentUserId);
		map.put("entityType", entityType);
		map.put("entityId", entityId);
		map.put("entity", entity);
		map.put("payload", payload);
		map.put("data", data);

		if ("CLIENT".equals(entityType)) {
			map.put("client", entity);
		}

		if ("TASK".equals(entityType)) {
			map.put("task", entity);
		}

		if ("MESSAGE".equals(entityType)) {
			map.put("message", entity);
		}

		return map;
	}
}