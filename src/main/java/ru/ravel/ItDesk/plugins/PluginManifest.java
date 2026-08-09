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
	private List<PluginSecretDeclaration> secrets = new ArrayList<>();
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

	/**
	 * Описание секрета, который нужен плагину: как он называется, что о нём
	 * написать администратору, куда и в каком виде его подставлять.
	 * Значение секрета в манифест не попадает никогда.
	 */
	@Data
	public static class PluginSecretDeclaration {
		private String name;
		private String title;
		private String description;
		/** header или query. */
		private String injection = "header";
		/** Имя заголовка или query-параметра. */
		private String parameter;
		/** Например «Bearer ». */
		private String valuePrefix;
		/** Префиксы URL, на которые допустимо отправлять этот секрет. */
		private List<String> sendTo = new ArrayList<>();
	}

	@Data
	public static class PluginHookDefinition {
		private String name;
		private String handler;
		private Boolean enabled = true;
		/**
		 * Можно ли вызывать хук из интерфейса.
		 * null — вызываем, только если хук привязан к frontend-расширению
		 * плагина: так ведут себя ранее написанные плагины.
		 */
		private Boolean ui;
		/** Роли, которым доступен вызов из интерфейса. Пусто — все роли. */
		private List<String> roles = new ArrayList<>();
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