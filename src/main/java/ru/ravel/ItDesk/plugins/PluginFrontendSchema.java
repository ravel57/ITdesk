package ru.ravel.ItDesk.plugins;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Data
public class PluginFrontendSchema {
	private String schemaVersion;
	private List<PluginUiExtension> extensions = new ArrayList<>();

	@Data
	public static class PluginUiExtension {
		private String pluginKey;
		private String key;
		private String point;
		private String entityType;
		private Integer order = 0;
		private String component;
		private Map<String, Object> props;
	}
}