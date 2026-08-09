package ru.ravel.ItDesk.plugins;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


@Service
public class DefaultPluginHookBus implements PluginHookBus {

	private final PluginRegistryService pluginRegistryService;
	private final GroovyPluginRuntime groovyPluginRuntime;
	private final PluginHookRateLimiter hookRateLimiter;


	public DefaultPluginHookBus(
			PluginRegistryService pluginRegistryService,
			GroovyPluginRuntime groovyPluginRuntime,
			PluginHookRateLimiter hookRateLimiter
	) {
		this.pluginRegistryService = pluginRegistryService;
		this.groovyPluginRuntime = groovyPluginRuntime;
		this.hookRateLimiter = hookRateLimiter;
	}


	@Override
	public List<Object> executeUiHook(
			String hookName,
			PluginExecutionContext context,
			Collection<String> roles
	) {
		List<PluginRegistryService.RegisteredHook> hooks =
				pluginRegistryService.findUiCallableHooks(hookName, roles);

		/*
		 * Ответ одинаков и для несуществующего хука, и для недоступного:
		 * эндпоинт не должен работать перебором имён хуков.
		 */
		if (hooks.isEmpty()) {
			throw new AccessDeniedException("Хук недоступен: " + hookName);
		}

		if (!hookRateLimiter.allow(context.getCurrentUserId(), hookName)) {
			throw new ResponseStatusException(
					HttpStatus.TOO_MANY_REQUESTS,
					"Слишком частые вызовы плагина"
			);
		}

		List<Object> results = new ArrayList<>();
		for (PluginRegistryService.RegisteredHook hook : hooks) {
			results.add(groovyPluginRuntime.invoke(
					hook.pluginKey(),
					hook.handlerName(),
					context.toMap()
			));
		}
		return results;
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