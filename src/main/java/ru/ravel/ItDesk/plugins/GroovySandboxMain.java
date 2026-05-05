package ru.ravel.ItDesk.plugins;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.runtime.InvokerHelper;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class GroovySandboxMain {

	public static void main(String[] args) throws Exception {
		Map<String, String> arguments = parseArgs(args);

		String pluginDir = arguments.get("--plugin-dir");
		String entrypoint = arguments.get("--entrypoint");
		String handler = arguments.get("--handler");

		if (pluginDir == null || entrypoint == null || handler == null) {
			throw new IllegalArgumentException("--plugin-dir, --entrypoint and --handler are required");
		}

		ObjectMapper objectMapper = new ObjectMapper();

		Map<String, Object> context = objectMapper.readValue(
				System.in,
				new TypeReference<Map<String, Object>>() {
				}
		);

		File groovyFile = new File(pluginDir, entrypoint);

		if (!groovyFile.exists()) {
			throw new IllegalArgumentException("Groovy file not found: " + groovyFile.getAbsolutePath());
		}

		GroovyShell shell = new GroovyShell();
		Object pluginInstance = shell.evaluate(groovyFile);

		if (pluginInstance == null) {
			throw new IllegalStateException("Groovy plugin returned null");
		}

		Object result = InvokerHelper.invokeMethod(pluginInstance, handler, context);

		objectMapper.writeValue(System.out, result);
	}

	private static Map<String, String> parseArgs(String[] args) {
		Map<String, String> result = new HashMap<>();

		for (int i = 0; i < args.length - 1; i += 2) {
			result.put(args[i], args[i + 1]);
		}

		return result;
	}
}