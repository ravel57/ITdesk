package ru.ravel.ItDesk.plugins;

import java.io.File;
import java.util.Map;

public interface PluginRuntime {

	void loadPlugin(String pluginKey, File pluginDir, PluginManifest manifest);

	Object invoke(String pluginKey, String handlerName, Map<String, Object> context);

	void remove(String pluginKey);

	void clear();
}