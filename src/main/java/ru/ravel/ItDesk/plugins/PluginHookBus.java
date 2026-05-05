package ru.ravel.ItDesk.plugins;

import java.util.List;


public interface PluginHookBus {
	List<Object> execute(String hookName, PluginExecutionContext context);
}