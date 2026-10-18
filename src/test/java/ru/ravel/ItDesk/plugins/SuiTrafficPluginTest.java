package ru.ravel.ItDesk.plugins;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Плагин sui-traffic-info из каталога plugins/ проходит валидацию манифеста
 * и ходит в s-ui только через брокер песочницы.
 */
class SuiTrafficPluginTest {

	private static final Path PLUGIN_SOURCE = Path.of("plugins", "sui-traffic-info");
	private static final String API_PREFIX = "https://ruvp.vvetech.eu:444/apiv2/";

	@TempDir
	Path pluginsRoot;

	private PluginPermissionService permissionService;
	private GroovyPluginRuntime groovyRuntime;
	private PluginRegistryService registry;

	@BeforeEach
	void setUp() throws Exception {
		permissionService = mock(PluginPermissionService.class);
		groovyRuntime = mock(GroovyPluginRuntime.class);
		registry = new PluginRegistryService(groovyRuntime, permissionService, mock(PluginSecretStore.class));
		ReflectionTestUtils.setField(registry, "pluginsDir", pluginsRoot.toString());
		// Без .disabled из рабочей копии: плагин должен загрузиться как включённый.
		Path plugin = Files.createDirectories(pluginsRoot.resolve("sui-traffic-info"));
		for (String file : List.of("manifest.yml", "ui.yml", "SuiTrafficPlugin.groovy")) {
			Files.copy(PLUGIN_SOURCE.resolve(file), plugin.resolve(file));
		}
	}

	@Test
	void manifestIsValidAndLoads() {
		registry.loadPlugins();

		PluginManifest manifest = registry.getManifests().get("sui-traffic-info");
		assertNotNull(manifest, "plugin is loaded");
		verify(permissionService).syncRequested(eq("sui-traffic-info"), eq(List.of(
				"client:read",
				"http:get:" + API_PREFIX,
				"secret:sui-api-token"
		)));
		verify(groovyRuntime).loadPlugin(eq("sui-traffic-info"), any(), eq(manifest));

		PluginSecretBinding token = PluginSecretBinding.from(manifest.getSecrets().getFirst());
		assertTrue(token.header());
		assertEquals("Token", token.parameter());
		assertEquals(List.of(URI.create(API_PREFIX)), token.sendTo());
	}

	@Test
	void rendersTrafficThroughSandboxBroker() throws Exception {
		long createdAt = 1_700_000_000L;
		FakeSandbox sandbox = new FakeSandbox(Map.of(
				"ok", true,
				"status", 200,
				"body", """
						{"success":true,"msg":"","obj":{"clients":[
						  {"id":1,"name":"other","enable":true,"up":1,"down":1,"volume":0,"createdAt":1},
						  {"id":2,"name":"123456","enable":true,"up":1048576,"down":2097152,"volume":10485760,"createdAt":%d}
						]}}
						""".formatted(createdAt)
		));

		Object result = invokeGetText(Map.of(
				"client", Map.of("id", 7, "telegramId", "123456"),
				"payload", Map.of(),
				"sandbox", sandbox
		));

		String created = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
				.withZone(ZoneId.systemDefault())
				.format(Instant.ofEpochSecond(createdAt));
		assertEquals(Map.of("text", String.join("\n",
				"Профиль: 123456 (включен)",
				"Создан: " + created,
				"Трафик: 3.0 МБ",
				"↑ 1.0 МБ / ↓ 2.0 МБ",
				"Лимит: 10 МБ",
				"Осталось: 7.0 МБ"
		)), result);

		assertEquals(1, sandbox.calls.size());
		assertEquals("GET", sandbox.calls.getFirst().get("method"));
		assertEquals(API_PREFIX + "clients", sandbox.calls.getFirst().get("url"));
		assertFalse(sandbox.calls.getFirst().containsKey("body"), "token is injected by the broker, not sent by the plugin");
	}

	@Test
	void explainsMissingSecret() throws Exception {
		FakeSandbox sandbox = new FakeSandbox(null);

		Object result = invokeGetText(Map.of(
				"client", Map.of("telegramId", "123456"),
				"sandbox", sandbox
		));

		assertEquals(
				Map.of("text", "Ошибка получения данных: не задан API-токен s-ui (секрет sui-api-token)"),
				result
		);
	}

	/** Скрипт компилируется той же строгой конфигурацией, что и в песочнице без ОС-изоляции. */
	private Object invokeGetText(Map<String, Object> context) throws Exception {
		CompilerConfiguration configuration =
				ReflectionTestUtils.invokeMethod(GroovySandboxMain.class, "compilerConfiguration", false);
		GroovyShell shell = new GroovyShell(getClass().getClassLoader(), new Binding(), configuration);
		Object plugin = shell.evaluate(PLUGIN_SOURCE.resolve("SuiTrafficPlugin.groovy").toFile());
		return InvokerHelper.invokeMethod(plugin, "getText", context);
	}

	/** Повторяет контракт PluginSandboxApi.http: ответ брокера или отказ с кодом. */
	public static class FakeSandbox {
		final List<Map<?, ?>> calls = new ArrayList<>();
		private final Map<String, Object> response;

		FakeSandbox(Map<String, Object> response) {
			this.response = response;
		}

		public Map<String, Object> http(Map<?, ?> call) {
			calls.add(call);
			if (response == null) {
				throw new IllegalStateException("Sandbox HTTP request denied: SECRET_NOT_CONFIGURED");
			}
			return response;
		}
	}
}
