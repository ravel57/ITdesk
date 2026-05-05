package ru.ravel.ItDesk.plugins;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;


@Service
public class DefaultPluginHookBus implements PluginHookBus {

	private final PluginRegistryService pluginRegistryService;
	private final GroovyPluginRuntime groovyPluginRuntime;


	public DefaultPluginHookBus(
			PluginRegistryService pluginRegistryService,
			GroovyPluginRuntime groovyPluginRuntime
	) {
		this.pluginRegistryService = pluginRegistryService;
		this.groovyPluginRuntime = groovyPluginRuntime;
	}


	@Override
	public List<Object> execute(String hookName, PluginExecutionContext context) {
		List<PluginRegistryService.RegisteredHook> hooks = pluginRegistryService.findHooks(hookName);
		List<Object> results = new ArrayList<>();
		for (PluginRegistryService.RegisteredHook hook : hooks) {
			Object result = groovyPluginRuntime.invoke(
					hook.pluginKey(),
					hook.handlerName(),
					context.toMap()
			);
			results.add(result);
		}
		return results;
	}
}