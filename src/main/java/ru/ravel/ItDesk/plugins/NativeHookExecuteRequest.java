package ru.ravel.ItDesk.plugins;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;


@Data
public class NativeHookExecuteRequest {
	private String hook;
	private String entityType;
	private Long entityId;

	private Map<String, Object> payload = new HashMap<>();
}