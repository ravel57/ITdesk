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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


@Service
public class PluginRegistryService {

	private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
	private final GroovyPluginRuntime groovyPluginRuntime;

	@Value("${uldesk.plugins.dir}")
	private String pluginsDir;

	@Getter
	private final Map<String, PluginManifest> manifests = new ConcurrentHashMap<>();

	@Getter
	private final Map<String, PluginFrontendSchema> frontendSchemas = new ConcurrentHashMap<>();


	public PluginRegistryService(GroovyPluginRuntime groovyPluginRuntime) {
		this.groovyPluginRuntime = groovyPluginRuntime;
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
			if (new File(pluginDir, ".disabled").exists()) {
				return;
			}
			File manifestFile = new File(pluginDir, "manifest.yml");
			if (!manifestFile.exists()) {
				return;
			}
			PluginManifest manifest = yamlMapper.readValue(manifestFile, PluginManifest.class);
			validateManifest(manifest, pluginDir);
			String pluginKey = manifest.getPlugin().getKey();
			manifests.put(pluginKey, manifest);
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
		File uiFile = new File(pluginDir, entrypoint);
		if (!uiFile.exists()) {
			throw new IllegalStateException("UI schema not found: %s".formatted(uiFile.getAbsolutePath()));
		}
		PluginFrontendSchema schema = yamlMapper.readValue(uiFile, PluginFrontendSchema.class);
		if (schema.getExtensions() != null) {
			for (PluginFrontendSchema.PluginUiExtension extension : schema.getExtensions()) {
				extension.setPluginKey(pluginKey);
				applyHookFrontendSettings(extension, manifest);
			}
		}
		frontendSchemas.put(pluginKey, schema);
	}


	private void applyHookFrontendSettings(
			PluginFrontendSchema.PluginUiExtension extension,
			PluginManifest manifest
	) {
		if (!"remote-text".equals(extension.getComponent())) {
			return;
		}
		if (extension.getProps() == null) {
			return;
		}
		Object hookObject = extension.getProps().get("hook");
		if (hookObject == null) {
			return;
		}
		String hookName = hookObject.toString();
		if (manifest.getHooks() == null) {
			return;
		}
		for (PluginManifest.PluginHookDefinition hook : manifest.getHooks()) {
			if (!Objects.equals(hook.getName(), hookName)) {
				continue;
			}
			if (hook.getCacheTtlMs() != null && !extension.getProps().containsKey("cacheTtlMs")) {
				extension.getProps().put("cacheTtlMs", hook.getCacheTtlMs());
			}
			if (hook.getRefreshIntervalMs() != null && !extension.getProps().containsKey("refreshIntervalMs")) {
				extension.getProps().put("refreshIntervalMs", hook.getRefreshIntervalMs());
			}
			return;
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
		File groovyFile = new File(pluginDir, entrypoint);
		if (!groovyFile.exists()) {
			throw new IllegalStateException("Groovy entrypoint not found: %s".formatted(groovyFile.getAbsolutePath()));
		}
		groovyPluginRuntime.loadPlugin(pluginKey, pluginDir, manifest);
	}


	private void validateManifest(PluginManifest manifest, File pluginDir) {
		if (manifest.getPlugin() == null) {
			throw new IllegalStateException("plugin section is required: %s".formatted(pluginDir.getAbsolutePath()));
		}
		if (manifest.getPlugin().getKey() == null || manifest.getPlugin().getKey().isBlank()) {
			throw new IllegalStateException("plugin.key is required: %s".formatted(pluginDir.getAbsolutePath()));
		}
		if (manifest.getSchemaVersion() == null || manifest.getSchemaVersion().isBlank()) {
			throw new IllegalStateException("schemaVersion is required: %s".formatted(pluginDir.getAbsolutePath()));
		}
	}


	public List<PluginFrontendSchema.PluginUiExtension> getFrontendExtensions() {
		List<PluginFrontendSchema.PluginUiExtension> result = new ArrayList<>();
		for (PluginFrontendSchema schema : frontendSchemas.values()) {
			if (schema.getExtensions() != null) {
				result.addAll(schema.getExtensions());
			}
		}
		result.sort(Comparator.comparing(extension -> Optional.ofNullable(extension.getOrder()).orElse(0)));
		return result;
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
				PluginManifest manifest = yamlMapper.readValue(manifestFile, PluginManifest.class);
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
		String filename = file.getOriginalFilename();
		if (filename == null || !filename.endsWith(".zip")) {
			throw new IllegalArgumentException("Only .zip plugins are supported");
		}
		Path pluginsRoot = Paths.get(pluginsDir).toAbsolutePath().normalize();
		try {
			Files.createDirectories(pluginsRoot);
			Path tempDir = Files.createTempDirectory("uldesk-plugin-");
			unzip(file, tempDir);
			Path manifestPath = findFile(tempDir, "manifest.yml");
			if (manifestPath == null) {
				deleteDirectory(tempDir);
				throw new IllegalArgumentException("manifest.yml not found in zip");
			}
			PluginManifest manifest = yamlMapper.readValue(manifestPath.toFile(), PluginManifest.class);
			validateManifest(manifest, manifestPath.getParent().toFile());
			String pluginKey = manifest.getPlugin().getKey();
			validatePluginKey(pluginKey);
			Path targetDir = pluginsRoot.resolve(pluginKey).normalize();
			if (!targetDir.startsWith(pluginsRoot)) {
				deleteDirectory(tempDir);
				throw new IllegalArgumentException("Invalid plugin key: %s".formatted(pluginKey));
			}
			deleteDirectory(targetDir);
			Files.createDirectories(targetDir);
			Path pluginSourceDir = manifestPath.getParent();
			copyDirectory(pluginSourceDir, targetDir);
			deleteDirectory(tempDir);
			reloadPlugins();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to install plugin", e);
		}
	}


	public synchronized void reloadPlugin(String pluginKey) {
		validatePluginKey(pluginKey);
		manifests.remove(pluginKey);
		frontendSchemas.remove(pluginKey);
		groovyPluginRuntime.remove(pluginKey);
		File pluginDir = Paths.get(pluginsDir).resolve(pluginKey).toFile();
		if (!pluginDir.exists() || !pluginDir.isDirectory()) {
			throw new IllegalArgumentException("Plugin not found: %s".formatted(pluginKey));
		}
		loadPluginDirectory(pluginDir);
	}


	public synchronized void enablePlugin(String pluginKey) {
		validatePluginKey(pluginKey);
		Path disabledMarker = Paths.get(pluginsDir).resolve(pluginKey).resolve(".disabled");
		try {
			Files.deleteIfExists(disabledMarker);
			reloadPlugin(pluginKey);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to enable plugin: %s".formatted(pluginKey), e);
		}
	}


	public synchronized void disablePlugin(String pluginKey) {
		validatePluginKey(pluginKey);
		Path pluginDir = Paths.get(pluginsDir).resolve(pluginKey);
		Path disabledMarker = pluginDir.resolve(".disabled");
		if (!Files.exists(pluginDir)) {
			throw new IllegalArgumentException("Plugin not found: %s".formatted(pluginKey));
		}
		try {
			Files.writeString(disabledMarker, "disabled");
			manifests.remove(pluginKey);
			frontendSchemas.remove(pluginKey);
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
		groovyPluginRuntime.remove(pluginKey);
		try {
			deleteDirectory(pluginDir);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to delete plugin: %s".formatted(pluginKey), e);
		}
	}


	private void unzip(MultipartFile file, Path targetDir) throws IOException {
		try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream())) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				Path entryPath = targetDir.resolve(entry.getName()).normalize();

				if (!entryPath.startsWith(targetDir)) {
					throw new IllegalArgumentException("Invalid zip entry: %s".formatted(entry.getName()));
				}

				if (entry.isDirectory()) {
					Files.createDirectories(entryPath);
				} else {
					Files.createDirectories(entryPath.getParent());
					Files.copy(zipInputStream, entryPath, StandardCopyOption.REPLACE_EXISTING);
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
		if (pluginKey == null || !pluginKey.matches("[a-zA-Z0-9._-]+")) {
			throw new IllegalArgumentException("Invalid plugin key: %s".formatted(pluginKey));
		}
	}


	public record RegisteredHook(
			String pluginKey,
			String hookName,
			String handlerName
	) {
	}

}