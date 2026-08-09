package ru.ravel.ItDesk.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


@Service
public class PluginRegistryService {

	private static final long MAX_PLUGIN_ZIP_BYTES = 25L * 1024L * 1024L;
	private static final long MAX_PLUGIN_UNCOMPRESSED_BYTES = 100L * 1024L * 1024L;
	private static final long MAX_PLUGIN_ENTRY_BYTES = 25L * 1024L * 1024L;
	private static final int MAX_PLUGIN_ENTRIES = 2048;
	private static final int MAX_ZIP_ENTRY_NAME_LENGTH = 512;
	private static final long MAX_PLUGIN_YAML_BYTES = 1024L * 1024L;
	private static final long MAX_FRONTEND_INTERVAL_MS = 3_600_000L;
	private static final long MIN_FRONTEND_REFRESH_INTERVAL_MS = 1_000L;

	private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
	private final GroovyPluginRuntime groovyPluginRuntime;
	private final PluginPermissionService pluginPermissionService;
	private final PluginSecretStore pluginSecretStore;


	@Value("${uldesk.plugins.dir}")
	private String pluginsDir;


	@Getter
	private final Map<String, PluginManifest> manifests = new ConcurrentHashMap<>();

	@Getter
	private final Map<String, PluginFrontendSchema> frontendSchemas = new ConcurrentHashMap<>();

	/** Имена хуков, на которые ссылается frontend-схема плагина. */
	private final Map<String, Set<String>> frontendHookNames = new ConcurrentHashMap<>();


	public PluginRegistryService(
			GroovyPluginRuntime groovyPluginRuntime,
			PluginPermissionService pluginPermissionService,
			PluginSecretStore pluginSecretStore
	) {
		this.groovyPluginRuntime = groovyPluginRuntime;
		this.pluginPermissionService = pluginPermissionService;
		this.pluginSecretStore = pluginSecretStore;
	}


	@PostConstruct
	public void loadPlugins() {
		File root = new File(pluginsDir);
		if (!root.exists()) {
			root.mkdirs();
			return;
		}
		File[] pluginDirs = root.listFiles(File::isDirectory);
		if (pluginDirs == null) {
			return;
		}
		for (File pluginDir : pluginDirs) {
			loadPluginDirectory(pluginDir);
		}
	}


	private void loadPluginDirectory(File pluginDir) {
		try {
			Path pluginPath = pluginDir.toPath();
			if (Files.isSymbolicLink(pluginPath)) {
				throw new IllegalArgumentException("Plugin directory must not be a symbolic link");
			}
			Path realRoot = Paths.get(pluginsDir).toAbsolutePath().normalize().toRealPath();
			Path realPluginPath = pluginPath.toRealPath();
			if (!realPluginPath.startsWith(realRoot) || realPluginPath.equals(realRoot)) {
				throw new IllegalArgumentException("Plugin directory escapes plugins root");
			}
			if (new File(pluginDir, ".disabled").exists()) {
				return;
			}
			File manifestFile = new File(pluginDir, "manifest.yml");
			if (!manifestFile.exists()) {
				return;
			}
			PluginManifest manifest = readYamlBounded(manifestFile.toPath(), PluginManifest.class);
			validateManifest(manifest, pluginDir);
			String pluginKey = manifest.getPlugin().getKey();
			manifests.put(pluginKey, manifest);
			/*
			 * Заявка плагина синхронизируется с базой при каждой загрузке.
			 * Новые и изменённые права требуют одобрения администратора.
			 */
			pluginPermissionService.syncRequested(pluginKey, manifest.getPermissions());
			loadFrontendSchema(pluginKey, manifest, pluginDir);
			loadGroovyRuntime(pluginKey, manifest, pluginDir);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to load plugin directory: %s".formatted(pluginDir.getAbsolutePath()), e);
		}
	}


	private void loadFrontendSchema(String pluginKey, PluginManifest manifest, File pluginDir) throws Exception {
		if (manifest.getFrontend() == null) {
			return;
		}
		String entrypoint = manifest.getFrontend().getEntrypoint();
		if (entrypoint == null || entrypoint.isBlank()) {
			return;
		}
		Path uiPath = resolvePluginEntrypoint(pluginDir, entrypoint, "UI schema");
		PluginFrontendSchema schema = readYamlBounded(uiPath, PluginFrontendSchema.class);
		Set<String> hookNames = new LinkedHashSet<>();
		if (schema.getExtensions() != null) {
			for (PluginFrontendSchema.PluginUiExtension extension : schema.getExtensions()) {
				extension.setPluginKey(pluginKey);
				applyHookFrontendSettings(extension, manifest);
				sanitizeFrontendSettings(extension);
				stampFrontendCapabilities(pluginKey, extension);
				collectFrontendHookNames(extension, hookNames);
			}
		}
		frontendHookNames.put(pluginKey, Set.copyOf(hookNames));
		frontendSchemas.put(pluginKey, schema);
	}


	private void applyHookFrontendSettings(
			PluginFrontendSchema.PluginUiExtension extension,
			PluginManifest manifest
	) {
		if (extension == null) {
			return;
		}
		applyHookFrontendSettingsToNode(extension.getComponent(), extension.getProps(), manifest);
		applyHookFrontendSettingsNested(extension.getProps(), manifest);
	}


	private void applyHookFrontendSettingsToNode(
			String component,
			Map<String, Object> props,
			PluginManifest manifest
	) {
		if (!"remote-text".equals(component) || props == null) {
			return;
		}
		Object hookObject = props.get("hook");
		if (hookObject == null || manifest.getHooks() == null) {
			return;
		}
		String hookName = hookObject.toString();
		for (PluginManifest.PluginHookDefinition hook : manifest.getHooks()) {
			if (!Objects.equals(hook.getName(), hookName)) {
				continue;
			}
			if (hook.getCacheTtlMs() != null && !props.containsKey("cacheTtlMs")) {
				props.put("cacheTtlMs", hook.getCacheTtlMs());
			}
			if (hook.getRefreshIntervalMs() != null && !props.containsKey("refreshIntervalMs")) {
				props.put("refreshIntervalMs", hook.getRefreshIntervalMs());
			}
			return;
		}
	}


	@SuppressWarnings("unchecked")
	private void applyHookFrontendSettingsNested(Object value, PluginManifest manifest) {
		if (value instanceof Map<?, ?> map) {
			Object component = map.get("component");
			Object propsObject = map.get("props");
			if (component != null && propsObject instanceof Map<?, ?> rawProps) {
				applyHookFrontendSettingsToNode(
						component.toString(),
						(Map<String, Object>) rawProps,
						manifest
				);
			}
			for (Object child : map.values()) {
				applyHookFrontendSettingsNested(child, manifest);
			}
			return;
		}
		if (value instanceof Collection<?> collection) {
			for (Object child : collection) {
				applyHookFrontendSettingsNested(child, manifest);
			}
		}
	}


	private void collectFrontendHookNames(
			PluginFrontendSchema.PluginUiExtension extension,
			Set<String> hookNames
	) {
		if (extension == null) {
			return;
		}
		collectFrontendHookNameFromNode(extension.getComponent(), extension.getProps(), hookNames);
		collectFrontendHookNamesNested(extension.getProps(), hookNames);
	}


	private void collectFrontendHookNameFromNode(
			String component,
			Map<String, Object> props,
			Set<String> hookNames
	) {
		if (!"remote-text".equals(component) || props == null || props.get("hook") == null) {
			return;
		}
		hookNames.add(props.get("hook").toString());
	}


	@SuppressWarnings("unchecked")
	private void collectFrontendHookNamesNested(Object value, Set<String> hookNames) {
		if (value instanceof Map<?, ?> map) {
			Object component = map.get("component");
			Object propsObject = map.get("props");
			if (component != null && propsObject instanceof Map<?, ?> rawProps) {
				collectFrontendHookNameFromNode(
						component.toString(),
						(Map<String, Object>) rawProps,
						hookNames
				);
			}
			for (Object child : map.values()) {
				collectFrontendHookNamesNested(child, hookNames);
			}
			return;
		}
		if (value instanceof Collection<?> collection) {
			for (Object child : collection) {
				collectFrontendHookNamesNested(child, hookNames);
			}
		}
	}


	private void loadGroovyRuntime(String pluginKey, PluginManifest manifest, File pluginDir) {
		if (manifest.getRuntime() == null) {
			return;
		}
		if (!"groovy".equalsIgnoreCase(manifest.getRuntime().getType())) {
			return;
		}
		String entrypoint = manifest.getRuntime().getEntrypoint();
		if (entrypoint == null || entrypoint.isBlank()) {
			return;
		}
		try {
			resolvePluginEntrypoint(pluginDir, entrypoint, "Groovy entrypoint");
		} catch (IOException e) {
			throw new IllegalStateException("Failed to resolve Groovy entrypoint", e);
		}
		groovyPluginRuntime.loadPlugin(pluginKey, pluginDir, manifest);
	}


	private <T> T readYamlBounded(Path path, Class<T> type) throws IOException {
		if (path == null
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(path)) {
			throw new IllegalArgumentException("Plugin YAML must be a regular file");
		}
		long size = Files.size(path);
		if (size < 0 || size > MAX_PLUGIN_YAML_BYTES) {
			throw new IllegalArgumentException("Plugin YAML exceeds 1 MiB");
		}
		try (var input = Files.newInputStream(path)) {
			return yamlMapper.readValue(input, type);
		}
	}


	private void sanitizeFrontendSettings(PluginFrontendSchema.PluginUiExtension extension) {
		if (extension == null) {
			return;
		}
		sanitizeFrontendNode(extension.getComponent(), extension.getProps());
		sanitizeFrontendSettingsNested(extension.getProps());
	}


	private void sanitizeFrontendNode(String component, Map<String, Object> props) {
		if (!"remote-text".equals(component) || props == null) {
			return;
		}
		Object cacheTtl = props.get("cacheTtlMs");
		if (cacheTtl instanceof Number number) {
			long value = Math.max(0L, Math.min(MAX_FRONTEND_INTERVAL_MS, number.longValue()));
			props.put("cacheTtlMs", value);
		}
		Object refresh = props.get("refreshIntervalMs");
		if (refresh instanceof Number number) {
			long raw = number.longValue();
			long value = raw <= 0
					? 0L
					: Math.max(MIN_FRONTEND_REFRESH_INTERVAL_MS, Math.min(MAX_FRONTEND_INTERVAL_MS, raw));
			props.put("refreshIntervalMs", value);
		}
	}


	@SuppressWarnings("unchecked")
	private void sanitizeFrontendSettingsNested(Object value) {
		if (value instanceof Map<?, ?> map) {
			Object component = map.get("component");
			Object propsObject = map.get("props");
			if (component != null && propsObject instanceof Map<?, ?> rawProps) {
				sanitizeFrontendNode(component.toString(), (Map<String, Object>) rawProps);
			}
			for (Object child : map.values()) {
				sanitizeFrontendSettingsNested(child);
			}
			return;
		}
		if (value instanceof Collection<?> collection) {
			for (Object child : collection) {
				sanitizeFrontendSettingsNested(child);
			}
		}
	}


	private Path resolvePluginEntrypoint(File pluginDir, String entrypoint, String label) throws IOException {
		if (entrypoint == null || entrypoint.isBlank() || entrypoint.length() > MAX_ZIP_ENTRY_NAME_LENGTH) {
			throw new IllegalArgumentException(label + " is invalid");
		}
		Path root = pluginDir.toPath().toRealPath();
		Path candidate = root.resolve(entrypoint).normalize();
		if (!candidate.startsWith(root)
				|| !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(candidate)) {
			throw new IllegalArgumentException(label + " must be a regular file inside the plugin directory");
		}
		Path real = candidate.toRealPath();
		if (!real.startsWith(root)) {
			throw new IllegalArgumentException(label + " escapes the plugin directory");
		}
		return real;
	}

	private void validateManifest(PluginManifest manifest, File pluginDir) {
		if (manifest.getPlugin() == null) {
			throw new IllegalStateException("plugin section is required: %s".formatted(pluginDir.getAbsolutePath()));
		}
		if (manifest.getPlugin().getKey() == null || manifest.getPlugin().getKey().isBlank()) {
			throw new IllegalStateException("plugin.key is required: %s".formatted(pluginDir.getAbsolutePath()));
		}
		validatePluginKey(manifest.getPlugin().getKey());
		if (manifest.getSchemaVersion() == null || manifest.getSchemaVersion().isBlank()) {
			throw new IllegalStateException("schemaVersion is required: %s".formatted(pluginDir.getAbsolutePath()));
		}

		List<PluginPermission> permissions = new ArrayList<>();
		if (manifest.getPermissions() != null) {
			for (String raw : manifest.getPermissions()) {
				if (raw != null && !raw.isBlank()) {
					permissions.add(PluginPermission.parse(raw));
				}
			}
		}
		validateEntityPermissions(manifest, permissions);
		validateSecretDeclarations(manifest, permissions);
	}


	private void validateEntityPermissions(
			PluginManifest manifest,
			List<PluginPermission> permissions
	) {
		Set<String> readableResources = new LinkedHashSet<>();
		for (PluginPermission permission : permissions) {
			if (permission.kind() == PluginPermission.Kind.RESOURCE_READ) {
				readableResources.add(permission.resourceName());
			}
		}
		if (manifest.getExtensionPoints() == null) {
			return;
		}
		for (PluginManifest.PluginExtensionPointDefinition extension : manifest.getExtensionPoints()) {
			if (extension.getEntityType() == null || extension.getEntityType().isBlank()) {
				continue;
			}
			String resource = extension.getEntityType().trim().toLowerCase(Locale.ROOT);
			if (!readableResources.contains(resource)) {
				throw new IllegalArgumentException(
						"Extension point " + extension.getPoint() + " с entityType="
								+ extension.getEntityType() + " требует permission " + resource + ":read"
				);
			}
		}
	}


	private void validateSecretDeclarations(
			PluginManifest manifest,
			List<PluginPermission> permissions
	) {
		Set<String> requestedSecrets = new LinkedHashSet<>();
		List<PluginPermission> httpPermissions = new ArrayList<>();
		for (PluginPermission permission : permissions) {
			if (permission.kind() == PluginPermission.Kind.SECRET) {
				requestedSecrets.add(permission.secretName());
			}
			if (permission.kind() == PluginPermission.Kind.HTTP) {
				httpPermissions.add(permission);
			}
		}

		Set<String> declaredSecrets = new LinkedHashSet<>();
		if (manifest.getSecrets() != null) {
			for (PluginManifest.PluginSecretDeclaration declaration : manifest.getSecrets()) {
				PluginSecretBinding binding = PluginSecretBinding.from(declaration);
				if (!declaredSecrets.add(binding.name())) {
					throw new IllegalArgumentException("Секрет объявлен повторно: " + binding.name());
				}
				if (!requestedSecrets.contains(binding.name())) {
					throw new IllegalArgumentException(
							"Для secrets[" + binding.name() + "] требуется permission secret:" + binding.name()
					);
				}
				for (java.net.URI target : binding.sendTo()) {
					boolean covered = httpPermissions.stream()
							.anyMatch(permission -> prefixContains(permission.prefix(), target));
					if (!covered) {
						throw new IllegalArgumentException(
								"secrets[" + binding.name() + "].sendTo " + target
										+ " должен находиться внутри одного из запрошенных http:* permissions"
						);
					}
				}
			}
		}

		for (String secret : requestedSecrets) {
			if (!declaredSecrets.contains(secret)) {
				throw new IllegalArgumentException(
						"Permission secret:" + secret + " требует декларацию secrets с sendTo"
				);
			}
		}
	}


	private boolean prefixContains(java.net.URI parent, java.net.URI child) {
		if (parent == null || child == null) {
			return false;
		}
		if (!Objects.equals(parent.getScheme(), child.getScheme())
				|| !parent.getHost().equalsIgnoreCase(child.getHost())
				|| effectivePort(parent) != effectivePort(child)) {
			return false;
		}
		String parentPath = parent.normalize().getPath();
		String childPath = child.normalize().getPath();
		return childPath != null && parentPath != null && childPath.startsWith(parentPath);
	}


	private int effectivePort(java.net.URI uri) {
		if (uri.getPort() > 0) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}


	public List<PluginFrontendSchema.PluginUiExtension> getFrontendExtensions() {
		List<PluginFrontendSchema.PluginUiExtension> result = new ArrayList<>();
		for (PluginFrontendSchema schema : frontendSchemas.values()) {
			if (schema.getExtensions() == null) {
				continue;
			}
			for (PluginFrontendSchema.PluginUiExtension extension : schema.getExtensions()) {
				String pluginKey = extension.getPluginKey();
				if (!hasEntityReadPermission(pluginKey, extension.getEntityType())) {
					continue;
				}
				/* Approval/revoke вступает в силу без перезапуска приложения. */
				stampFrontendCapabilities(pluginKey, extension);
				result.add(extension);
			}
		}
		result.sort(Comparator.comparing(extension -> Optional.ofNullable(extension.getOrder()).orElse(0)));
		return result;
	}


	private boolean hasEntityReadPermission(String pluginKey, String entityType) {
		if (entityType == null || entityType.isBlank()) {
			return true;
		}
		String resource = entityType.trim().toLowerCase(Locale.ROOT);
		PluginPermission required;
		try {
			required = PluginPermission.parse(resource + ":read");
		} catch (IllegalArgumentException e) {
			return false;
		}
		return pluginPermissionService.approvedPermissions(pluginKey).contains(required.canonical());
	}


	/**
	 * Хуки, доступные для вызова из интерфейса.
	 * <p>
	 * Эндпоинт выполнения хуков открыт всем ролям вплоть до наблюдателя,
	 * поэтому вызвать через него произвольный хук плагина нельзя: только тот,
	 * который плагин сам предназначил для интерфейса, и только тем ролям,
	 * которые он перечислил.
	 */
	public List<RegisteredHook> findUiCallableHooks(String hookName, Collection<String> roles) {
		List<RegisteredHook> result = new ArrayList<>();
		for (PluginManifest manifest : manifests.values()) {
			String pluginKey = manifest.getPlugin().getKey();
			if (manifest.getHooks() == null) {
				continue;
			}
			for (PluginManifest.PluginHookDefinition hook : manifest.getHooks()) {
				if (!Boolean.TRUE.equals(hook.getEnabled())) {
					continue;
				}
				if (!Objects.equals(hook.getName(), hookName)) {
					continue;
				}
				if (!isUiCallable(pluginKey, hook)) {
					continue;
				}
				if (!hasRequiredRole(hook, roles)) {
					continue;
				}
				result.add(new RegisteredHook(pluginKey, hook.getName(), hook.getHandler()));
			}
		}
		return result;
	}


	private boolean isUiCallable(String pluginKey, PluginManifest.PluginHookDefinition hook) {
		if (hook.getUi() != null) {
			return hook.getUi();
		}
		return frontendHookNames.getOrDefault(pluginKey, Set.of()).contains(hook.getName());
	}


	private boolean hasRequiredRole(PluginManifest.PluginHookDefinition hook, Collection<String> roles) {
		if (hook.getRoles() == null || hook.getRoles().isEmpty()) {
			return true;
		}
		if (roles == null || roles.isEmpty()) {
			return false;
		}
		for (String role : hook.getRoles()) {
			if (role == null || role.isBlank()) {
				continue;
			}
			String normalized = role.trim().toUpperCase(Locale.ROOT);
			if (!normalized.startsWith("ROLE_")) {
				normalized = "ROLE_" + normalized;
			}
			if (roles.contains(normalized)) {
				return true;
			}
		}
		return false;
	}


	public List<RegisteredHook> findHooks(String hookName) {
		List<RegisteredHook> result = new ArrayList<>();
		for (PluginManifest manifest : manifests.values()) {
			String pluginKey = manifest.getPlugin().getKey();
			if (manifest.getHooks() == null) {
				continue;
			}
			for (PluginManifest.PluginHookDefinition hook : manifest.getHooks()) {
				if (!Boolean.TRUE.equals(hook.getEnabled())) {
					continue;
				}
				if (Objects.equals(hook.getName(), hookName)) {
					result.add(new RegisteredHook(pluginKey, hook.getName(), hook.getHandler()));
				}
			}
		}
		return result;
	}


	public synchronized void reloadPlugins() {
		manifests.clear();
		frontendSchemas.clear();
		frontendHookNames.clear();
		groovyPluginRuntime.clear();
		loadPlugins();
	}


	public List<Map<String, Object>> getInstalledPlugins() {
		List<Map<String, Object>> result = new ArrayList<>();
		File root = new File(pluginsDir);
		if (!root.exists()) {
			root.mkdirs();
			return result;
		}
		File[] pluginDirs = root.listFiles(File::isDirectory);
		if (pluginDirs == null) {
			return result;
		}
		for (File pluginDir : pluginDirs) {
			File manifestFile = new File(pluginDir, "manifest.yml");
			if (!manifestFile.exists()) {
				continue;
			}
			try {
				PluginManifest manifest = readYamlBounded(manifestFile.toPath(), PluginManifest.class);
				if (manifest.getPlugin() == null || manifest.getPlugin().getKey() == null) {
					continue;
				}
				String pluginKey = manifest.getPlugin().getKey();
				boolean enabled = !new File(pluginDir, ".disabled").exists();

				result.add(Map.of(
						"key", pluginKey,
						"name", manifest.getPlugin().getName() != null ? manifest.getPlugin().getName() : pluginKey,
						"description", manifest.getPlugin().getDescription() != null ? manifest.getPlugin().getDescription() : "",
						"version", manifest.getPlugin().getVersion() != null ? manifest.getPlugin().getVersion() : "",
						"author", manifest.getPlugin().getAuthor() != null ? manifest.getPlugin().getAuthor() : "",
						"enabled", enabled,
						"runtime", manifest.getRuntime() != null ? manifest.getRuntime().getType() : "frontend-only",
						"extensionPoints", manifest.getExtensionPoints() != null
								? manifest.getExtensionPoints().stream()
								.map(PluginManifest.PluginExtensionPointDefinition::getPoint)
								.filter(Objects::nonNull)
								.toList()
								: List.of()
				));
			} catch (Exception e) {
				result.add(Map.of(
						"key", pluginDir.getName(),
						"name", pluginDir.getName(),
						"description", "Ошибка чтения manifest.yml",
						"version", "",
						"author", "",
						"enabled", false,
						"runtime", "error",
						"extensionPoints", List.of()
				));
			}
		}
		result.sort(Comparator.comparing(plugin -> plugin.get("name").toString()));
		return result;
	}


	public synchronized void installPlugin(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new IllegalArgumentException("Plugin file is empty");
		}
		if (file.getSize() > MAX_PLUGIN_ZIP_BYTES) {
			throw new IllegalArgumentException("Plugin archive is too large");
		}
		String filename = file.getOriginalFilename();
		if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			throw new IllegalArgumentException("Only .zip plugins are supported");
		}
		Path pluginsRoot = Paths.get(pluginsDir).toAbsolutePath().normalize();
		Path tempDir = null;
		try {
			Files.createDirectories(pluginsRoot);
			tempDir = Files.createTempDirectory("uldesk-plugin-");
			unzip(file, tempDir);
			Path manifestPath = findFile(tempDir, "manifest.yml");
			if (manifestPath == null) {
				throw new IllegalArgumentException("manifest.yml not found in zip");
			}
			PluginManifest manifest = readYamlBounded(manifestPath, PluginManifest.class);
			validateManifest(manifest, manifestPath.getParent().toFile());
			String pluginKey = manifest.getPlugin().getKey();
			validatePluginKey(pluginKey);
			Path targetDir = pluginsRoot.resolve(pluginKey).normalize();
			if (!targetDir.startsWith(pluginsRoot)) {
				throw new IllegalArgumentException("Invalid plugin key: %s".formatted(pluginKey));
			}
			deleteDirectory(targetDir);
			Files.createDirectories(targetDir);
			Path pluginSourceDir = manifestPath.getParent();
			copyDirectory(pluginSourceDir, targetDir);
			reloadPlugins();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to install plugin", e);
		} finally {
			if (tempDir != null) {
				try {
					deleteDirectory(tempDir);
				} catch (IOException ignored) {
					// OS temp cleanup is the final fallback.
				}
			}
		}
	}

	public synchronized void reloadPlugin(String pluginKey) {
		validatePluginKey(pluginKey);
		manifests.remove(pluginKey);
		frontendSchemas.remove(pluginKey);
		groovyPluginRuntime.remove(pluginKey);
		File pluginDir = resolvePluginDirectory(pluginKey).toFile();
		if (!pluginDir.exists() || !pluginDir.isDirectory()) {
			throw new IllegalArgumentException("Plugin not found: %s".formatted(pluginKey));
		}
		loadPluginDirectory(pluginDir);
	}


	public synchronized void enablePlugin(String pluginKey) {
		validatePluginKey(pluginKey);
		Path disabledMarker = resolvePluginDirectory(pluginKey).resolve(".disabled");
		try {
			Files.deleteIfExists(disabledMarker);
			reloadPlugin(pluginKey);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to enable plugin: %s".formatted(pluginKey), e);
		}
	}


	public synchronized void disablePlugin(String pluginKey) {
		validatePluginKey(pluginKey);
		Path pluginDir = resolvePluginDirectory(pluginKey);
		Path disabledMarker = pluginDir.resolve(".disabled");
		if (!Files.exists(pluginDir)) {
			throw new IllegalArgumentException("Plugin not found: %s".formatted(pluginKey));
		}
		try {
			Files.writeString(disabledMarker, "disabled");
			manifests.remove(pluginKey);
			frontendSchemas.remove(pluginKey);
			frontendHookNames.remove(pluginKey);
			groovyPluginRuntime.remove(pluginKey);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to disable plugin: %s".formatted(pluginKey), e);
		}
	}


	public synchronized void deletePlugin(String pluginKey) {
		validatePluginKey(pluginKey);
		Path pluginsRoot = Paths.get(pluginsDir).toAbsolutePath().normalize();
		Path pluginDir = pluginsRoot.resolve(pluginKey).normalize();
		if (!pluginDir.startsWith(pluginsRoot)) {
			throw new IllegalArgumentException("Invalid plugin key: %s".formatted(pluginKey));
		}
		if (!Files.exists(pluginDir)) {
			throw new IllegalArgumentException("Plugin not found: %s".formatted(pluginKey));
		}
		manifests.remove(pluginKey);
		frontendSchemas.remove(pluginKey);
		frontendHookNames.remove(pluginKey);
		groovyPluginRuntime.remove(pluginKey);
		pluginPermissionService.removePlugin(pluginKey);
		pluginSecretStore.removePlugin(pluginKey);
		try {
			deleteDirectory(pluginDir);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to delete plugin: %s".formatted(pluginKey), e);
		}
	}


	private void unzip(MultipartFile file, Path targetDir) throws IOException {
		long totalBytes = 0L;
		int entryCount = 0;
		byte[] buffer = new byte[8192];
		try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream())) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				entryCount++;
				if (entryCount > MAX_PLUGIN_ENTRIES) {
					throw new IllegalArgumentException("Plugin archive contains too many entries");
				}
				String entryName = entry.getName();
				if (entryName == null || entryName.isBlank() || entryName.length() > MAX_ZIP_ENTRY_NAME_LENGTH) {
					throw new IllegalArgumentException("Invalid zip entry name");
				}
				Path entryPath = targetDir.resolve(entryName).normalize();
				if (!entryPath.startsWith(targetDir)) {
					throw new IllegalArgumentException("Invalid zip entry: %s".formatted(entryName));
				}
				long declaredSize = entry.getSize();
				if (declaredSize > MAX_PLUGIN_ENTRY_BYTES) {
					throw new IllegalArgumentException("Plugin archive entry is too large: " + entryName);
				}

				if (entry.isDirectory()) {
					Files.createDirectories(entryPath);
				} else {
					Files.createDirectories(entryPath.getParent());
					long entryBytes = 0L;
					try (OutputStream output = Files.newOutputStream(
							entryPath,
							StandardOpenOption.CREATE,
							StandardOpenOption.TRUNCATE_EXISTING,
							StandardOpenOption.WRITE
					)) {
						int read;
						while ((read = zipInputStream.read(buffer)) != -1) {
							entryBytes += read;
							totalBytes += read;
							if (entryBytes > MAX_PLUGIN_ENTRY_BYTES
									|| totalBytes > MAX_PLUGIN_UNCOMPRESSED_BYTES) {
								throw new IllegalArgumentException("Plugin archive exceeds extraction limits");
							}
							output.write(buffer, 0, read);
						}
					}
				}
				zipInputStream.closeEntry();
			}
		}
	}


	private Path findFile(Path root, String filename) throws IOException {
		try (Stream<Path> stream = Files.walk(root)) {
			return stream
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().equals(filename))
					.findFirst()
					.orElse(null);
		}
	}


	private void copyDirectory(Path source, Path target) throws IOException {
		try (Stream<Path> stream = Files.walk(source)) {
			for (Path sourcePath : stream.toList()) {
				Path targetPath = target.resolve(source.relativize(sourcePath).toString());

				if (Files.isDirectory(sourcePath)) {
					Files.createDirectories(targetPath);
				} else {
					Files.createDirectories(targetPath.getParent());
					Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}


	private void deleteDirectory(Path path) throws IOException {
		if (path == null || !Files.exists(path)) {
			return;
		}

		try (Stream<Path> stream = Files.walk(path)) {
			List<Path> paths = stream
					.sorted(Comparator.reverseOrder())
					.toList();

			for (Path item : paths) {
				Files.deleteIfExists(item);
			}
		}
	}


	private void validatePluginKey(String pluginKey) {
		if (pluginKey == null
				|| pluginKey.length() > 128
				|| !pluginKey.matches("[a-zA-Z0-9._-]+")
				|| ".".equals(pluginKey)
				|| "..".equals(pluginKey)) {
			throw new IllegalArgumentException("Invalid plugin key: %s".formatted(pluginKey));
		}
	}


	private Path resolvePluginDirectory(String pluginKey) {
		validatePluginKey(pluginKey);
		try {
			Path root = Paths.get(pluginsDir).toAbsolutePath().normalize();
			Files.createDirectories(root);
			Path realRoot = root.toRealPath();
			Path candidate = root.resolve(pluginKey).normalize();
			if (!candidate.startsWith(root) || candidate.equals(root)) {
				throw new IllegalArgumentException("Invalid plugin key: %s".formatted(pluginKey));
			}
			if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
				return candidate;
			}
			if (Files.isSymbolicLink(candidate)) {
				throw new IllegalArgumentException("Plugin directory must not be a symbolic link");
			}
			Path realCandidate = candidate.toRealPath();
			if (!realCandidate.startsWith(realRoot) || realCandidate.equals(realRoot)) {
				throw new IllegalArgumentException("Plugin directory escapes plugins root");
			}
			return realCandidate;
		} catch (IOException e) {
			throw new IllegalStateException("Failed to resolve plugin directory", e);
		}
	}


	private void stampFrontendCapabilities(
			String pluginKey,
			PluginFrontendSchema.PluginUiExtension extension
	) {
		Set<String> granted = new LinkedHashSet<>();
		for (String permission : pluginPermissionService.approvedPermissions(pluginKey)) {
			PluginPermission parsed = PluginPermission.tryParse(permission).orElse(null);
			if (parsed != null && parsed.kind() == PluginPermission.Kind.URL_SCHEME) {
				granted.add(parsed.urlScheme());
			}
		}

		List<String> allowedList = List.copyOf(granted);
		if (extension.getProps() != null) {
			/* Значение из ui.yml всегда перезаписывается доверенным backend. */
			extension.getProps().put("__allowedUrlSchemes", allowedList);
			stampNestedFrontendCapabilities(extension.getProps(), allowedList);
		}
	}


	@SuppressWarnings("unchecked")
	private void stampNestedFrontendCapabilities(
			Object value,
			List<String> allowedSchemes
	) {
		if (value instanceof Map<?, ?> map) {
			Object propsObject = map.get("props");
			if (propsObject instanceof Map<?, ?> rawProps) {
				Map<String, Object> props = (Map<String, Object>) rawProps;
				props.put("__allowedUrlSchemes", allowedSchemes);
			}
			for (Object child : map.values()) {
				stampNestedFrontendCapabilities(child, allowedSchemes);
			}
			return;
		}
		if (value instanceof Collection<?> collection) {
			for (Object child : collection) {
				stampNestedFrontendCapabilities(child, allowedSchemes);
			}
		}
	}


	public record RegisteredHook(
			String pluginKey,
			String hookName,
			String handlerName
	) {
	}

}