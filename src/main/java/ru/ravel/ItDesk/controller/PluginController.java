package ru.ravel.ItDesk.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.ravel.ItDesk.plugins.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plugins")
public class PluginController {

	private final PluginRegistryService pluginRegistryService;
	private final PluginHookBus pluginHookBus;
	private final PluginContextFactory pluginContextFactory;


	public PluginController(
			PluginRegistryService pluginRegistryService,
			PluginHookBus pluginHookBus,
			PluginContextFactory pluginContextFactory
	) {
		this.pluginRegistryService = pluginRegistryService;
		this.pluginHookBus = pluginHookBus;
		this.pluginContextFactory = pluginContextFactory;
	}


	@GetMapping("/frontend-schema")
	public Map<String, Object> getFrontendSchema() {
		return Map.of("extensions", pluginRegistryService.getFrontendExtensions());
	}


	@PostMapping("/native-hook/execute")
	public Map<String, Object> executeNativeHook(@RequestBody NativeHookExecuteRequest request) {
		PluginExecutionContext context = pluginContextFactory.createContext(request);
		List<Object> results = pluginHookBus.execute(request.getHook(), context);
		return Map.of(
				"status", "OK",
				"results", results
		);
	}


	@PostMapping("/reload")
	public Map<String, Object> reloadPlugins() {
		pluginRegistryService.reloadPlugins();
		return Map.of("status", "OK");
	}


	@GetMapping
	public Map<String, Object> getPlugins() {
		return Map.of("plugins", pluginRegistryService.getInstalledPlugins());
	}


	@PostMapping(value = "/install", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> installPlugin(@RequestParam("file") MultipartFile file) {
		pluginRegistryService.installPlugin(file);
		return Map.of("status", "OK");
	}


	@PostMapping("/{pluginKey}/reload")
	public Map<String, Object> reloadPlugin(@PathVariable String pluginKey) {
		pluginRegistryService.reloadPlugin(pluginKey);
		return Map.of("status", "OK");
	}


	@PostMapping("/{pluginKey}/enable")
	public Map<String, Object> enablePlugin(@PathVariable String pluginKey) {
		pluginRegistryService.enablePlugin(pluginKey);
		return Map.of("status", "OK");
	}


	@PostMapping("/{pluginKey}/disable")
	public Map<String, Object> disablePlugin(@PathVariable String pluginKey) {
		pluginRegistryService.disablePlugin(pluginKey);
		return Map.of("status", "OK");
	}


	@DeleteMapping("/{pluginKey}")
	public Map<String, Object> deletePlugin(@PathVariable String pluginKey) {
		pluginRegistryService.deletePlugin(pluginKey);
		return Map.of("status", "OK");
	}

}