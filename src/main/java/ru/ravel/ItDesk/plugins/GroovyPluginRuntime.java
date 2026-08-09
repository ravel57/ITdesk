package ru.ravel.ItDesk.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Webapp-side facade for untrusted Groovy plugins.
 *
 * The webapp never starts user code directly. Every invocation is sent through
 * a Unix-domain socket to the dedicated plugin-runner container. The runner
 * validates the request and starts GroovySandboxMain inside bubblewrap.
 *
 * There is intentionally no PROCESS_ONLY fallback here: if the runner or its
 * OS sandbox is unavailable, plugin execution fails closed.
 */
@Component
public class GroovyPluginRuntime implements PluginRuntime {

	private static final long DEFAULT_TIMEOUT_MS = 20_000L;
	private static final long MIN_TIMEOUT_MS = 100L;
	private static final long MAX_TIMEOUT_MS = 30_000L;
	private static final int DEFAULT_MEMORY_MB = 128;
	private static final int MIN_MEMORY_MB = 32;
	private static final int MAX_MEMORY_MB = 256;
	private static final int MAX_CONTEXT_BYTES = PluginRunnerProtocol.MAX_PAYLOAD_BYTES;

	public enum Isolation {
		OS_SANDBOX,
		/** Kept for source/binary compatibility. This runtime never enters it. */
		PROCESS_ONLY,
		UNAVAILABLE
	}

	private final Logger logger = LoggerFactory.getLogger(getClass());
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final Map<String, SandboxPluginInfo> plugins = new ConcurrentHashMap<>();
	private final PluginSandboxBroker sandboxBroker;
	private final PluginPermissionService permissionService;
	private final PluginRunnerClient runnerClient;

	private volatile Isolation isolation = Isolation.UNAVAILABLE;
	private volatile String isolationReason = "plugin-runner has not been probed";

	public GroovyPluginRuntime(
			PluginSandboxBroker sandboxBroker,
			PluginPermissionService permissionService,
			PluginRunnerClient runnerClient
	) {
		this.sandboxBroker = sandboxBroker;
		this.permissionService = permissionService;
		this.runnerClient = runnerClient;
	}

	@PostConstruct
	void detectIsolation() {
		refreshRunnerStatus();
	}

	private boolean refreshRunnerStatus() {
		if (runnerClient.ping(2_000L)) {
			isolation = Isolation.OS_SANDBOX;
			isolationReason = "dedicated plugin-runner + bubblewrap";
			logger.info("Plugin isolation: OS_SANDBOX (dedicated plugin-runner sidecar)");
			return true;
		}

		isolation = Isolation.UNAVAILABLE;
		isolationReason = "dedicated plugin-runner is unavailable";
		logger.error("Plugins are disabled: dedicated plugin-runner is unavailable."
				+ " Degraded/process-only execution is intentionally not supported.");
		return false;
	}

	public Isolation getIsolation() {
		return isolation;
	}

	public String getIsolationReason() {
		return isolationReason;
	}

	@Override
	public void loadPlugin(String pluginKey, File pluginDir, PluginManifest manifest) {
		if (manifest.getRuntime() == null) {
			return;
		}

		String entrypoint = manifest.getRuntime().getEntrypoint();
		if (entrypoint == null || entrypoint.isBlank()) {
			throw new IllegalArgumentException("runtime.entrypoint is required for plugin: " + pluginKey);
		}

		try {
			Path root = pluginDir.toPath().toRealPath();
			if (root.getFileName() == null
					|| !root.getFileName().toString().matches("[A-Za-z0-9._-]{1,128}")) {
				throw new IllegalArgumentException("Plugin directory name is not safe for runner IPC");
			}

			Path candidate = root.resolve(entrypoint).normalize();
			if (!candidate.startsWith(root)
					|| !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(candidate)) {
				throw new IllegalArgumentException("Groovy entrypoint must be a regular file inside the plugin directory");
			}

			Path realEntrypoint = candidate.toRealPath();
			if (!realEntrypoint.startsWith(root)) {
				throw new IllegalArgumentException("Groovy entrypoint escapes the plugin directory");
			}

			SandboxPluginInfo info = new SandboxPluginInfo();
			info.setPluginKey(pluginKey);
			info.setPluginDirectory(root.getFileName().toString());
			info.setEntrypoint(root.relativize(realEntrypoint).toString());

			long requestedTimeout = manifest.getRuntime().getTimeoutMs() == null
					? DEFAULT_TIMEOUT_MS
					: manifest.getRuntime().getTimeoutMs();
			int requestedMemory = manifest.getRuntime().getMemoryMb() == null
					? DEFAULT_MEMORY_MB
					: manifest.getRuntime().getMemoryMb();
			info.setTimeoutMs(Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, requestedTimeout)));
			info.setMemoryMb(Math.max(MIN_MEMORY_MB, Math.min(MAX_MEMORY_MB, requestedMemory)));

			Set<String> permissions = new LinkedHashSet<>();
			if (manifest.getPermissions() != null) {
				for (String permission : manifest.getPermissions()) {
					if (permission == null || permission.isBlank()) {
						continue;
					}
					permissions.add(PluginPermission.parse(permission).canonical());
				}
			}
			info.setPermissions(Set.copyOf(permissions));

			List<PluginSecretBinding> secretBindings = new ArrayList<>();
			if (manifest.getSecrets() != null) {
				for (PluginManifest.PluginSecretDeclaration declaration : manifest.getSecrets()) {
					secretBindings.add(PluginSecretBinding.from(declaration));
				}
			}
			info.setSecretBindings(List.copyOf(secretBindings));

			plugins.put(pluginKey, info);
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to validate Groovy plugin entrypoint: " + pluginKey, e);
		}
	}

	@Override
	public Object invoke(String pluginKey, String handlerName, Map<String, Object> context) {
		SandboxPluginInfo info = plugins.get(pluginKey);
		if (info == null) {
			throw new IllegalStateException("Plugin is not loaded: " + pluginKey);
		}
		if (handlerName == null || !handlerName.matches("[A-Za-z_$][A-Za-z0-9_$]{0,127}")) {
			throw new IllegalArgumentException("Invalid plugin handler name");
		}
		if (isolation != Isolation.OS_SANDBOX && !refreshRunnerStatus()) {
			throw new IllegalStateException("Plugin sandbox is unavailable: " + isolationReason);
		}

		String brokerToken = null;
		try {
			Set<String> effectivePermissions = grantedPermissions(info);
			brokerToken = sandboxBroker.issueGrant(
					info.getPluginKey(),
					brokerPermissions(effectivePermissions),
					info.getSecretBindings(),
					info.getTimeoutMs()
			);

			Map<String, Object> safeContext = restrictEntityContext(context, effectivePermissions);
			byte[] input = objectMapper.writeValueAsBytes(safeContext);
			if (input.length > MAX_CONTEXT_BYTES) {
				throw new IllegalArgumentException("Plugin context exceeds 1 MiB");
			}

			byte[] output = runnerClient.invoke(new PluginRunnerProtocol.InvokeRequest(
					info.getPluginKey(),
					info.getPluginDirectory(),
					info.getEntrypoint(),
					handlerName,
					info.getTimeoutMs(),
					info.getMemoryMb(),
					brokerToken,
					input
			));

			isolation = Isolation.OS_SANDBOX;
			isolationReason = "dedicated plugin-runner + bubblewrap";

			if (output.length == 0) {
				return Map.of();
			}
			return objectMapper.readValue(output, Object.class);
		} catch (Exception e) {
			if (!runnerClient.ping(500L)) {
				isolation = Isolation.UNAVAILABLE;
				isolationReason = "dedicated plugin-runner is unavailable";
			}
			throw new IllegalStateException(
					"Failed to invoke sandbox plugin: plugin=" + pluginKey + ", handler=" + handlerName,
					e
			);
		} finally {
			if (brokerToken != null) {
				sandboxBroker.revokeGrant(brokerToken);
			}
		}
	}

	/** Only capabilities that were both requested and approved are effective. */
	private Set<String> grantedPermissions(SandboxPluginInfo info) {
		Set<String> approved = permissionService.approvedPermissions(info.getPluginKey());
		Set<String> effective = new LinkedHashSet<>();

		for (String requested : info.getPermissions()) {
			PluginPermission parsed = PluginPermission.tryParse(requested).orElse(null);
			if (parsed != null && approved.contains(parsed.canonical())) {
				effective.add(parsed.canonical());
			}
		}
		return Set.copyOf(effective);
	}

	/** The broker receives only network and secret capabilities. */
	private Set<String> brokerPermissions(Set<String> effectivePermissions) {
		Set<String> broker = new LinkedHashSet<>();
		for (String raw : effectivePermissions) {
			PluginPermission parsed = PluginPermission.tryParse(raw).orElse(null);
			if (parsed != null
					&& (parsed.kind() == PluginPermission.Kind.HTTP
					|| parsed.kind() == PluginPermission.Kind.SECRET)) {
				broker.add(parsed.canonical());
			}
		}
		return Set.copyOf(broker);
	}

	/**
	 * Entity data is fail-closed. Without an approved <resource>:read capability
	 * neither the generic entity map nor known aliases/identifiers are sent to
	 * untrusted code.
	 */
	private Map<String, Object> restrictEntityContext(
			Map<String, Object> context,
			Set<String> effectivePermissions
	) {
		Map<String, Object> result = new LinkedHashMap<>(context == null ? Map.of() : context);
		Object rawEntityType = result.get("entityType");
		if (rawEntityType == null) {
			return result;
		}

		String resource = rawEntityType.toString().trim().toLowerCase(Locale.ROOT);
		if (resource.matches("[a-z][a-z0-9._-]{0,63}")
				&& effectivePermissions.contains(resource + ":read")) {
			return result;
		}

		result.remove("entity");
		result.remove("entityId");
		result.remove("organizationId");
		if (resource.matches("[a-z][a-z0-9._-]{0,63}")) {
			result.remove(resource);
		}
		result.remove("client");
		result.remove("task");
		result.remove("message");
		return result;
	}

	@Override
	public void remove(String pluginKey) {
		plugins.remove(pluginKey);
	}

	@Override
	public void clear() {
		plugins.clear();
	}

	@Data
	private static class SandboxPluginInfo {
		private String pluginKey;
		private String pluginDirectory;
		private String entrypoint;
		private Long timeoutMs;
		private Integer memoryMb;
		private Set<String> permissions = Set.of();
		private List<PluginSecretBinding> secretBindings = List.of();
	}
}
