package ru.ravel.ItDesk.plugins;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;


@Data
public class PluginManifest {
	private String schemaVersion;
	private PluginInfo plugin;
	private PluginRuntime runtime;
	private FrontendConfig frontend;
	private List<String> permissions = new ArrayList<>();
	private List<PluginHookDefinition> hooks = new ArrayList<>();
	private List<PluginExtensionPointDefinition> extensionPoints = new ArrayList<>();

	@Data
	public static class PluginInfo {
		private String key;
		private String name;
		private String description;
		private String version;
		private String author;
	}

	@Data
	public static class PluginRuntime {
		private String type;
		private String entrypoint;
		private Long timeoutMs;
		private Integer memoryMb;
	}

	@Data
	public static class FrontendConfig {
		private String type;
		private String entrypoint;
	}

	@Data
	public static class PluginHookDefinition {
		private String name;
		private String handler;
		private Boolean enabled = true;
		private Long cacheTtlMs;
		private Long refreshIntervalMs;
	}

	@Data
	public static class PluginExtensionPointDefinition {
		private String point;
		private String entityType;
		private String source;
	}
}