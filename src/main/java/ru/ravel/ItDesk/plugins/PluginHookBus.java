package ru.ravel.ItDesk.plugins;

import java.util.Collection;
import java.util.List;


public interface PluginHookBus {

	/** Вызов из интерфейса: с проверкой доступности хука и ограничением частоты. */
	List<Object> executeUiHook(String hookName, PluginExecutionContext context, Collection<String> roles);

	/** Внутренний вызов: из автоматизаций и системных событий. */
	List<Object> execute(String hookName, PluginExecutionContext context);
}