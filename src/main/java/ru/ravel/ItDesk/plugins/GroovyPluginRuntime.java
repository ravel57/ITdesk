package ru.ravel.ItDesk.plugins;

import groovy.lang.GroovyShell;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Component
public class GroovyPluginRuntime {

	private final Map<String, Object> pluginInstances = new ConcurrentHashMap<>();


	public void loadPlugin(String pluginKey, File groovyFile) {
		try {
			GroovyShell shell = new GroovyShell();
			Object pluginInstance = shell.evaluate(groovyFile);
			if (pluginInstance == null) {
				throw new IllegalStateException("Groovy plugin returned null: " + pluginKey);
			}
			pluginInstances.put(pluginKey, pluginInstance);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to load Groovy plugin: " + pluginKey, e);
		}
	}


	public Object invoke(String pluginKey, String handlerName, Map<String, Object> context) {
		Object pluginInstance = pluginInstances.get(pluginKey);
		if (pluginInstance == null) {
			throw new IllegalStateException("Plugin is not loaded: " + pluginKey);
		}
		try {
			return InvokerHelper.invokeMethod(pluginInstance, handlerName, context);
		} catch (Exception e) {
			throw new IllegalStateException(
					"Failed to invoke plugin handler: plugin=" + pluginKey + ", handler=" + handlerName,
					e
			);
		}
	}


	public void clear() {
		pluginInstances.clear();
	}


	public void remove(String pluginKey) {
		pluginInstances.remove(pluginKey);
	}

}