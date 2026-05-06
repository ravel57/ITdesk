package ru.ravel.ItDesk.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.nio.file.Path;

@Component
public class GroovyPluginRuntime implements PluginRuntime {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final Map<String, SandboxPluginInfo> plugins = new ConcurrentHashMap<>();


	@Override
	public void loadPlugin(String pluginKey, File pluginDir, PluginManifest manifest) {
		if (manifest.getRuntime() == null) {
			return;
		}
		String entrypoint = manifest.getRuntime().getEntrypoint();
		if (entrypoint == null || entrypoint.isBlank()) {
			throw new IllegalArgumentException("runtime.entrypoint is required for plugin: " + pluginKey);
		}
		File groovyFile = new File(pluginDir, entrypoint);
		if (!groovyFile.exists()) {
			throw new IllegalArgumentException("Groovy entrypoint not found: " + groovyFile.getAbsolutePath());
		}
		SandboxPluginInfo info = new SandboxPluginInfo();
		info.setPluginKey(pluginKey);
		info.setPluginDir(pluginDir);
		info.setEntrypoint(entrypoint);
		info.setTimeoutMs(manifest.getRuntime().getTimeoutMs() != null
				? manifest.getRuntime().getTimeoutMs()
				: 20000L
		);
		info.setMemoryMb(manifest.getRuntime().getMemoryMb() != null
				? manifest.getRuntime().getMemoryMb()
				: 128
		);
		plugins.put(pluginKey, info);
	}

	@Override
	public Object invoke(String pluginKey, String handlerName, Map<String, Object> context) {
		SandboxPluginInfo info = plugins.get(pluginKey);
		if (info == null) {
			throw new IllegalStateException("Plugin is not loaded: " + pluginKey);
		}
		try {
			ProcessBuilder processBuilder = createProcessBuilder(info, handlerName);
			Process process = processBuilder.start();
			byte[] inputJson = objectMapper.writeValueAsBytes(context);
			process.getOutputStream().write(inputJson);
			process.getOutputStream().flush();
			process.getOutputStream().close();
			boolean finished = process.waitFor(
					Duration.ofMillis(info.getTimeoutMs()).toMillis(),
					TimeUnit.MILLISECONDS
			);
			if (!finished) {
				process.destroyForcibly();

				throw new IllegalStateException(
						"Plugin timeout: plugin=" + pluginKey + ", handler=" + handlerName
				);
			}
			String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.exitValue() != 0) {
				throw new IllegalStateException(
						"Plugin process failed: plugin=" + pluginKey +
								", handler=" + handlerName +
								", stderr=" + stderr
				);
			}
			if (stdout.isBlank()) {
				return Map.of();
			}
			return objectMapper.readValue(stdout, Object.class);
		} catch (Exception e) {
			throw new IllegalStateException(
					"Failed to invoke sandbox plugin: plugin=" + pluginKey + ", handler=" + handlerName,
					e
			);
		}
	}


	private ProcessBuilder createProcessBuilder(SandboxPluginInfo info, String handlerName) {
		try {
			String extractedClassPath = getExtractedBootClasspathOrNull();
			Path argsFile;
			if (extractedClassPath != null) {
				argsFile = createDevClasspathArgsFile(info, handlerName, extractedClassPath);
			} else {
				String classPath = System.getProperty("java.class.path");
				if (classPath == null || classPath.isBlank()) {
					throw new IllegalStateException("java.class.path is empty");
				}
				if (isRunningFromBootJar(classPath)) {
					argsFile = createBootJarArgsFile(info, handlerName, getApplicationJarPath(classPath));
				} else {
					argsFile = createDevClasspathArgsFile(info, handlerName, classPath);
				}
			}
			return new ProcessBuilder(getJavaExecutable(), "@%s".formatted(argsFile.toAbsolutePath()));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to create sandbox process builder", e);
		}
	}


	private String getExtractedBootClasspathOrNull() {
		File appDir = new File("/home/java/app");
		if (!appDir.exists()) {
			return null;
		}
		List<String> classPathParts = new ArrayList<>();
		addIfExists(classPathParts, "/home/java/app/application/BOOT-INF/classes");
		addJarsIfExists(classPathParts, "/home/java/app/dependencies/BOOT-INF/lib");
		addJarsIfExists(classPathParts, "/home/java/app/snapshot-dependencies/BOOT-INF/lib");
		if (classPathParts.isEmpty()) {
			addIfExists(classPathParts, "/home/java/app/BOOT-INF/classes");
			addJarsIfExists(classPathParts, "/home/java/app/BOOT-INF/lib");
		}
		if (classPathParts.isEmpty()) {
			return null;
		}
		return String.join(File.pathSeparator, classPathParts);
	}


	private void addIfExists(List<String> classPathParts, String path) {
		File file = new File(path);
		if (file.exists()) {
			classPathParts.add(file.getAbsolutePath());
		}
	}


	private void addJarsIfExists(List<String> classPathParts, String dirPath) {
		File dir = new File(dirPath);
		if (!dir.exists() || !dir.isDirectory()) {
			return;
		}
		File[] jars = dir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
		if (jars == null) {
			return;
		}
		for (File jar : jars) {
			classPathParts.add(jar.getAbsolutePath());
		}
	}


	private Path createDevClasspathArgsFile(
			SandboxPluginInfo info,
			String handlerName,
			String classPath
	) throws Exception {
		Path argsFile = Files.createTempFile("uldesk-plugin-sandbox-dev-", ".args");
		List<String> lines = new ArrayList<>();
		lines.add("-Xmx" + info.getMemoryMb() + "m");
		lines.add("-cp");
		lines.add(quoteArg(classPath));
		lines.add("ru.ravel.ItDesk.plugins.GroovySandboxMain");
		lines.add("--plugin-dir");
		lines.add(quoteArg(info.getPluginDir().getAbsolutePath()));
		lines.add("--entrypoint");
		lines.add(quoteArg(info.getEntrypoint()));
		lines.add("--handler");
		lines.add(quoteArg(handlerName));
		Files.writeString(argsFile, String.join(System.lineSeparator(), lines));
		argsFile.toFile().deleteOnExit();
		return argsFile;
	}


	private Path createBootJarArgsFile(
			SandboxPluginInfo info,
			String handlerName,
			String applicationJarPath
	) throws Exception {
		Path argsFile = Files.createTempFile("uldesk-plugin-sandbox-jar-", ".args");
		List<String> lines = new ArrayList<>();
		lines.add("-Xmx" + info.getMemoryMb() + "m");
		lines.add("-Dloader.main=ru.ravel.ItDesk.plugins.GroovySandboxMain");
		lines.add("-jar");
		lines.add(quoteArg(applicationJarPath));
		lines.add("--plugin-dir");
		lines.add(quoteArg(info.getPluginDir().getAbsolutePath()));
		lines.add("--entrypoint");
		lines.add(quoteArg(info.getEntrypoint()));
		lines.add("--handler");
		lines.add(quoteArg(handlerName));
		Files.writeString(argsFile, String.join(System.lineSeparator(), lines));
		argsFile.toFile().deleteOnExit();
		return argsFile;
	}


	private String quoteArg(String value) {
		if (value == null) {
			return "\"\"";
		}
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}


	private boolean isRunningFromBootJar(String classPath) {
		return getApplicationJarPathOrNull(classPath) != null;
	}


	private String getApplicationJarPath(String classPath) {
		String jarPath = getApplicationJarPathOrNull(classPath);
		if (jarPath == null) {
			throw new IllegalStateException("Application jar not found in classpath: " + classPath);
		}
		return jarPath;
	}


	private String getApplicationJarPathOrNull(String classPath) {
		String[] parts = classPath.split(File.pathSeparator);
		for (String part : parts) {
			File file = new File(part);
			if (!file.exists() || !file.isFile()) {
				continue;
			}
			if (!file.getName().endsWith(".jar")) {
				continue;
			}
			if (isSpringBootExecutableJar(file)) {
				return file.getAbsolutePath();
			}
		}
		return null;
	}


	private boolean isSpringBootExecutableJar(File file) {
		try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(file)) {
			return jarFile.getEntry("BOOT-INF/classes/ru/ravel/ItDesk/plugins/GroovySandboxMain.class") != null;
		} catch (Exception e) {
			return false;
		}
	}


	@Override
	public void remove(String pluginKey) {
		plugins.remove(pluginKey);
	}


	@Override
	public void clear() {
		plugins.clear();
	}


	private String getJavaExecutable() {
		String javaHome = System.getProperty("java.home");
		if (javaHome == null || javaHome.isBlank()) {
			return "java";
		}
		return javaHome + File.separator + "bin" + File.separator + "java";
	}


	@Data
	private static class SandboxPluginInfo {
		private String pluginKey;
		private File pluginDir;
		private String entrypoint;
		private Long timeoutMs;
		private Integer memoryMb;
	}

}