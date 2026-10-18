package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.ravel.ItDesk.plugins.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


@RestController
@RequestMapping("/api/v1/plugins")
@Hidden
@Tag(name = "Плагины", description = "Установка, включение, отключение и выполнение native hooks плагинов.")
public class PluginController {

	private final PluginRegistryService pluginRegistryService;
	private final PluginHookBus pluginHookBus;
	private final PluginContextFactory pluginContextFactory;
	private final GroovyPluginRuntime groovyPluginRuntime;
	private final PluginPermissionService pluginPermissionService;
	private final PluginSecretStore pluginSecretStore;
	private final ru.ravel.ItDesk.service.SecurityAuditService securityAuditService;


	public PluginController(
			PluginRegistryService pluginRegistryService,
			PluginHookBus pluginHookBus,
			PluginContextFactory pluginContextFactory,
			GroovyPluginRuntime groovyPluginRuntime,
			PluginPermissionService pluginPermissionService,
			PluginSecretStore pluginSecretStore,
			ru.ravel.ItDesk.service.SecurityAuditService securityAuditService
	) {
		this.pluginRegistryService = pluginRegistryService;
		this.pluginHookBus = pluginHookBus;
		this.pluginContextFactory = pluginContextFactory;
		this.groovyPluginRuntime = groovyPluginRuntime;
		this.pluginPermissionService = pluginPermissionService;
		this.pluginSecretStore = pluginSecretStore;
		this.securityAuditService = securityAuditService;
	}


	private void auditPlugin(String pluginKey, String action) {
		securityAuditService.record(ru.ravel.ItDesk.model.SecurityAuditEventType.PLUGIN_CHANGED, "Плагин " + pluginKey, action);
	}


	@Operation(
			summary = "Секреты плагина",
			description = "Возвращает секреты, объявленные плагином, и признак того, задано ли значение. Сами значения не возвращаются никогда."
	)
	@GetMapping("/{pluginKey}/secrets")
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> getPluginSecrets(@PathVariable String pluginKey) {
		// секреты настраиваются и для выключенного плагина — до его включения
		PluginManifest manifest = requireInstalledManifest(pluginKey);

		List<Map<String, Object>> secrets = new ArrayList<>();
		if (manifest.getSecrets() != null) {
			for (PluginManifest.PluginSecretDeclaration declaration : manifest.getSecrets()) {
				secrets.add(Map.of(
						"name", String.valueOf(declaration.getName()),
						"title", String.valueOf(declaration.getTitle()),
						"description", String.valueOf(declaration.getDescription()),
						"injection", String.valueOf(declaration.getInjection()),
						"parameter", String.valueOf(declaration.getParameter()),
						"sendTo", declaration.getSendTo() == null ? List.of() : declaration.getSendTo(),
						"configured", pluginSecretStore.isConfigured(pluginKey, declaration.getName())
				));
			}
		}
		return Map.of("secrets", secrets);
	}


	@Operation(
			summary = "Задать значение секрета плагина",
			description = "Сохраняет значение секрета в зашифрованном виде. Плагин значение не получает:"
					+ " брокер подставляет его в исходящий запрос на объявленные в манифесте адреса."
	)
	@PutMapping("/{pluginKey}/secrets/{name}")
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> setPluginSecret(
			@PathVariable String pluginKey,
			@PathVariable String name,
			@RequestBody PluginSecretRequest request
	) {
		requireDeclaredSecret(pluginKey, name);
		pluginSecretStore.save(pluginKey, name, request.getValue(), currentActor());
		auditPlugin(pluginKey, "Задан секрет " + name);
		return Map.of("status", "OK");
	}


	@Operation(summary = "Удалить значение секрета плагина")
	@DeleteMapping("/{pluginKey}/secrets/{name}")
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> deletePluginSecret(
			@PathVariable String pluginKey,
			@PathVariable String name
	) {
		pluginSecretStore.delete(pluginKey, name);
		auditPlugin(pluginKey, "Удалён секрет " + name);
		return Map.of("status", "OK");
	}


	@Operation(
			summary = "Права плагина",
			description = "Возвращает права, запрошенные плагином в манифесте, с признаком одобрения администратором."
	)
	@GetMapping("/{pluginKey}/permissions")
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> getPluginPermissions(@PathVariable String pluginKey) {
		PluginManifest manifest = requireInstalledManifest(pluginKey);
		if (!pluginRegistryService.getManifests().containsKey(pluginKey)) {
			// выключенный плагин не загружался — заявку на права берём из манифеста на диске,
			// чтобы их можно было одобрить до включения
			pluginPermissionService.syncRequested(pluginKey, manifest.getPermissions());
		}
		return Map.of("permissions", pluginPermissionService.describe(pluginKey));
	}


	@Operation(
			summary = "Одобрить права плагина",
			description = "Одобряет перечисленные права. Одобрить можно только то, что плагин запросил в манифесте."
	)
	@PostMapping("/{pluginKey}/permissions/approve")
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> approvePluginPermissions(
			@PathVariable String pluginKey,
			@RequestBody PluginPermissionRequest request
	) {
		pluginPermissionService.approve(pluginKey, request.getPermissions(), currentActor());
		auditPlugin(pluginKey, "Одобрены права: " + request.getPermissions());
		return Map.of("status", "OK");
	}


	@Operation(
			summary = "Отозвать права плагина",
			description = "Отзывает ранее одобренные права. Плагин остаётся установленным, но теряет доступ."
	)
	@PostMapping("/{pluginKey}/permissions/revoke")
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> revokePluginPermissions(
			@PathVariable String pluginKey,
			@RequestBody PluginPermissionRequest request
	) {
		pluginPermissionService.revoke(pluginKey, request.getPermissions(), currentActor());
		auditPlugin(pluginKey, "Отозваны права: " + request.getPermissions());
		return Map.of("status", "OK");
	}


	private PluginManifest requireInstalledManifest(String pluginKey) {
		Optional<PluginManifest> manifest;
		try {
			manifest = pluginRegistryService.findInstalledManifest(pluginKey);
		} catch (IllegalStateException e) {
			// манифест есть, но не проходит проверку (например, устаревший формат прав)
			String reason = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Манифест плагина некорректен: " + reason);
		}
		return manifest.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Плагин не найден: " + pluginKey));
	}


	/**
	 * Текст причины в теле ответа: стандартный ответ Spring его не содержит, а страница плагинов показывает message.
	 */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e) {
		return ResponseEntity.status(e.getStatusCode())
				.body(Map.of("message", Objects.toString(e.getReason(), e.getStatusCode().toString())));
	}


	private void requireDeclaredSecret(String pluginKey, String name) {
		PluginManifest manifest = requireInstalledManifest(pluginKey);
		String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		if (manifest.getSecrets() != null) {
			for (PluginManifest.PluginSecretDeclaration declaration : manifest.getSecrets()) {
				String declared = declaration.getName() == null
						? ""
						: declaration.getName().trim().toLowerCase(Locale.ROOT);
				if (declared.equals(normalized)) {
					return;
				}
			}
		}
		throw new IllegalArgumentException("Плагин не объявлял секрет: " + name);
	}


	private String currentActor() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication == null ? "unknown" : authentication.getName();
	}


	@Operation(hidden = true, summary = "Получить frontend-схему плагинов", description = "Возвращает зарегистрированные frontend extensions установленных плагинов.")
	@GetMapping("/frontend-schema")
	@PreAuthorize("@access.can('PLUGIN_USE')")
	public Map<String, Object> getFrontendSchema() {
		return Map.of("extensions", pluginRegistryService.getFrontendExtensions());
	}


	@Operation(hidden = true, summary = "Выполнить native hook", description = "Выполняет зарегистрированный native hook плагинов в сформированном контексте выполнения.")
	@PostMapping("/native-hook/execute")
	@PreAuthorize("@access.can('PLUGIN_USE')")
	public Map<String, Object> executeNativeHook(@RequestBody NativeHookExecuteRequest request) {
		PluginExecutionContext context = pluginContextFactory.createContext(request);
		List<Object> results = pluginHookBus.executeUiHook(request.getHook(), context, currentRoles());
		return Map.of(
				"status", "OK",
				"results", results
		);
	}


	private Set<String> currentRoles() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication.getAuthorities() == null) {
			return Set.of();
		}
		Set<String> roles = new LinkedHashSet<>();
		authentication.getAuthorities().forEach(authority -> roles.add(authority.getAuthority()));
		return Set.copyOf(roles);
	}


	@Operation(summary = "Перезагрузить все плагины", description = "Перечитывает и перезапускает реестр установленных плагинов.")
	@PostMapping("/reload")
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> reloadPlugins() {
		pluginRegistryService.reloadPlugins();
		return Map.of("status", "OK");
	}


	@Operation(
			summary = "Получить список плагинов",
			description = "Возвращает метаданные установленных плагинов и текущий уровень изоляции песочницы."
	)
	@GetMapping
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> getPlugins() {
		return Map.of(
				"plugins", pluginRegistryService.getInstalledPlugins(),
				"sandbox", Map.of(
						"isolation", groovyPluginRuntime.getIsolation().name(),
						"reason", groovyPluginRuntime.getIsolationReason()
				)
		);
	}


	@Operation(summary = "Установить плагин", description = "Устанавливает плагин из загруженного файла.")
	@PostMapping(value = "/install", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> installPlugin(@RequestParam("file") MultipartFile file) {
		pluginRegistryService.installPlugin(file);
		auditPlugin(Objects.toString(file.getOriginalFilename(), "?"), "Установлен");
		return Map.of("status", "OK");
	}


	@Operation(summary = "Перезагрузить плагин", description = "Перезапускает конкретный установленный плагин по его ключу.")
	@PostMapping("/{pluginKey}/reload")
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> reloadPlugin(@PathVariable String pluginKey) {
		pluginRegistryService.reloadPlugin(pluginKey);
		return Map.of("status", "OK");
	}


	@Operation(summary = "Включить плагин", description = "Включает конкретный установленный плагин.")
	@PostMapping("/{pluginKey}/enable")
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> enablePlugin(@PathVariable String pluginKey) {
		pluginRegistryService.enablePlugin(pluginKey);
		auditPlugin(pluginKey, "Включён");
		return Map.of("status", "OK");
	}


	@Operation(summary = "Отключить плагин", description = "Отключает конкретный установленный плагин.")
	@PostMapping("/{pluginKey}/disable")
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> disablePlugin(@PathVariable String pluginKey) {
		pluginRegistryService.disablePlugin(pluginKey);
		auditPlugin(pluginKey, "Выключен");
		return Map.of("status", "OK");
	}


	@Operation(summary = "Удалить плагин", description = "Удаляет установленный плагин по его ключу.")
	@DeleteMapping("/{pluginKey}")
	@PreAuthorize("@access.can('PLUGIN_MANAGE')")
	public Map<String, Object> deletePlugin(@PathVariable String pluginKey) {
		pluginRegistryService.deletePlugin(pluginKey);
		auditPlugin(pluginKey, "Удалён");
		return Map.of("status", "OK");
	}

}