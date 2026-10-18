package ru.ravel.ItDesk.plugins;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Выключенный плагин не загружается, но его манифест и запрошенные права доступны
 * для настройки секретов и одобрения разрешений до включения.
 */
class PluginRegistryDisabledPluginTest {

	@TempDir
	Path pluginsRoot;

	private PluginPermissionService permissionService;
	private PluginRegistryService registry;

	@BeforeEach
	void setUp() throws Exception {
		permissionService = mock(PluginPermissionService.class);
		registry = new PluginRegistryService(mock(GroovyPluginRuntime.class), permissionService, mock(PluginSecretStore.class));
		ReflectionTestUtils.setField(registry, "pluginsDir", pluginsRoot.toString());

		Path plugin = Files.createDirectories(pluginsRoot.resolve("sui-traffic-info"));
		Files.writeString(plugin.resolve("manifest.yml"), """
				schemaVersion: "1"
				plugin:
				  key: "sui-traffic-info"
				  name: "S-UI traffic info"
				permissions:
				  - "client:read"
				""");
		Files.writeString(plugin.resolve(".disabled"), "disabled");
	}

	@Test
	void disabledPluginManifestIsAvailable() {
		registry.loadPlugins();

		assertFalse(registry.getManifests().containsKey("sui-traffic-info"), "disabled plugin is not loaded");
		var manifest = registry.findInstalledManifest("sui-traffic-info").orElseThrow();
		assertEquals("S-UI traffic info", manifest.getPlugin().getName());
		verify(permissionService).syncRequested(eq("sui-traffic-info"), eq(List.of("client:read")));
	}

	@Test
	void invalidManifestOfDisabledPluginDoesNotBreakLoading() throws Exception {
		Path legacy = Files.createDirectories(pluginsRoot.resolve("legacy-plugin"));
		Files.writeString(legacy.resolve("manifest.yml"), """
				schemaVersion: "1"
				plugin:
				  key: "legacy-plugin"
				permissions:
				  - "network:s-ui"
				""");
		Files.writeString(legacy.resolve(".disabled"), "disabled");

		registry.loadPlugins(); // не падает из-за устаревшего формата прав у выключенного плагина
		assertThrows(IllegalStateException.class, () -> registry.findInstalledManifest("legacy-plugin"));
	}

	@Test
	void unknownPluginIsEmpty() {
		assertTrue(registry.findInstalledManifest("missing-plugin").isEmpty());
	}
}
