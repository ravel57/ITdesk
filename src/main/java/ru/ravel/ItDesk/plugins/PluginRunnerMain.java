package ru.ravel.ItDesk.plugins;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Minimal sidecar process. It does not contain Spring, DB credentials or the
 * secret master key. Its only job is to validate an invocation request and
 * start GroovySandboxMain inside bubblewrap.
 */
public final class PluginRunnerMain {

	private static final long MIN_TIMEOUT_MS = 100L;
	private static final long MAX_TIMEOUT_MS = 30_000L;
	private static final int MIN_MEMORY_MB = 32;
	private static final int MAX_MEMORY_MB = 256;
	private static final int MAX_OUTPUT_BYTES = PluginRunnerProtocol.MAX_PAYLOAD_BYTES;
	private static final String SANDBOX_BROKER_SOCKET = "/run/uldesk/plugin-broker.sock";
	private static final String KEY_PATTERN = "[A-Za-z0-9._-]{1,128}";
	private static final String HANDLER_PATTERN = "[A-Za-z_$][A-Za-z0-9_$]{0,127}";

	private final Path socketPath;
	private final Path brokerSocketPath;
	private final Path pluginsRoot;
	private final Path appRoot;
	private final Path javaHome;
	private final Path bwrap;
	private final int uid;
	private final ThreadPoolExecutor workers;

	private PluginRunnerMain() throws Exception {
		this.uid = currentUid();
		if (uid == 0) {
			throw new IllegalStateException("Plugin runner refuses to run as root");
		}

		this.socketPath = absoluteEnv("ULDESK_PLUGIN_RUNNER_SOCKET", "/run/uldesk-ipc/plugin-runner.sock");
		this.brokerSocketPath = absoluteEnv("ULDESK_PLUGIN_BROKER_SOCKET", "/run/uldesk-ipc/plugin-broker.sock");
		this.pluginsRoot = requireDirectory(absoluteEnv("ULDESK_PLUGINS_DIR", "/home/java/plugins"));
		this.appRoot = requireDirectory(absoluteEnv("ULDESK_APP_DIR", "/home/java/app"));
		this.javaHome = requireDirectory(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize());
		this.bwrap = requireBwrap(absoluteEnv("ULDESK_BWRAP", "/usr/bin/bwrap"));

		int concurrency = intEnv("ULDESK_PLUGIN_RUNNER_MAX_CONCURRENT", 4, 1, 32);
		this.workers = new ThreadPoolExecutor(
				concurrency,
				concurrency,
				0L,
				TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(Math.max(8, concurrency * 4)),
				r -> {
					Thread t = new Thread(r, "plugin-runner-worker");
					t.setDaemon(true);
					return t;
				},
				new ThreadPoolExecutor.AbortPolicy()
		);
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 1 && "--ping".equals(args[0])) {
			System.exit(pingFromEnvironment() ? 0 : 1);
		}
		PluginRunnerMain runner = new PluginRunnerMain();
		runner.probeSandbox();
		runner.serve();
	}

	private static boolean pingFromEnvironment() {
		Path path = absoluteEnv("ULDESK_PLUGIN_RUNNER_SOCKET", "/run/uldesk-ipc/plugin-runner.sock");
		try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
			 DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
			 DataInputStream in = new DataInputStream(Channels.newInputStream(channel))) {
			channel.connect(UnixDomainSocketAddress.of(path));
			PluginRunnerProtocol.writePing(out);
			return PluginRunnerProtocol.readResponse(in).ok();
		} catch (Exception e) {
			return false;
		}
	}

	private void serve() throws Exception {
		prepareSocketPath(socketPath);
		try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
			server.bind(UnixDomainSocketAddress.of(socketPath));
			chmodSocket(socketPath);
			System.out.println("Plugin runner ready: " + socketPath + ", uid=" + uid);
			while (true) {
				SocketChannel channel = server.accept();
				try {
					workers.execute(() -> handle(channel));
				} catch (RuntimeException rejected) {
					try { channel.close(); } catch (Exception ignored) { }
				}
			}
		} finally {
			workers.shutdownNow();
			try { Files.deleteIfExists(socketPath); } catch (Exception ignored) { }
		}
	}

	private void handle(SocketChannel channel) {
		try (SocketChannel ignored = channel;
			 DataInputStream in = new DataInputStream(Channels.newInputStream(channel));
			 DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel))) {
			try {
				byte command = PluginRunnerProtocol.readHeader(in);
				if (command == PluginRunnerProtocol.CMD_PING) {
					PluginRunnerProtocol.writeResponse(out, PluginRunnerProtocol.Response.ok(new byte[0]));
					return;
				}
				if (command != PluginRunnerProtocol.CMD_INVOKE) {
					throw new IllegalArgumentException("UNKNOWN_COMMAND");
				}
				PluginRunnerProtocol.InvokeRequest request = PluginRunnerProtocol.readInvokeBody(in);
				PluginRunnerProtocol.writeResponse(out, PluginRunnerProtocol.Response.ok(invoke(request)));
			} catch (Exception e) {
				PluginRunnerProtocol.writeResponse(out, PluginRunnerProtocol.Response.error(diagnostic(e)));
			}
		} catch (Exception ignored) {
		}
	}

	private byte[] invoke(PluginRunnerProtocol.InvokeRequest request) throws Exception {
		ValidatedInvocation invocation = validate(request);
		List<String> command = createBwrapCommand(invocation, request);
		ProcessBuilder builder = new ProcessBuilder(command);
		Map<String, String> environment = builder.environment();
		environment.clear();
		environment.put("LANG", "C.UTF-8");
		environment.put("LC_ALL", "C.UTF-8");
		environment.put("HOME", "/tmp");
		environment.put("TMPDIR", "/tmp");
		environment.put("ULDESK_PLUGIN_BROKER_SOCKET", SANDBOX_BROKER_SOCKET);
		environment.put("ULDESK_PLUGIN_BROKER_TOKEN", request.brokerToken());

		Process process = builder.start();
		ExecutorService io = Executors.newFixedThreadPool(2);
		try {
			Future<byte[]> stdout = io.submit(() -> readLimited(process.getInputStream(), "stdout"));
			Future<byte[]> stderr = io.submit(() -> readLimited(process.getErrorStream(), "stderr"));
			try (OutputStream stdin = process.getOutputStream()) {
				stdin.write(request.contextJson());
				stdin.flush();
			} catch (Exception writeFailure) {
				/*
				 * If bwrap or the child JVM exits during startup, ProcessPipeOutputStream
				 * reports only "Stream closed". Preserve the child's bounded stderr so
				 * operators see the actual sandbox failure without weakening isolation.
				 */
				if (process.isAlive()) {
					process.waitFor(250, TimeUnit.MILLISECONDS);
				}
				if (process.isAlive()) {
					destroyProcessTree(process);
				}
				byte[] err = awaitOutput(stderr, 1_000L);
				int exitCode = process.isAlive() ? -1 : process.exitValue();
				throw new IllegalStateException(
						"PLUGIN_PROCESS_STDIN_FAILED(exit=" + exitCode + "): "
								+ diagnostic(new String(err, StandardCharsets.UTF_8)),
						writeFailure
				);
			}
			if (!process.waitFor(request.timeoutMs(), TimeUnit.MILLISECONDS)) {
				destroyProcessTree(process);
				throw new IllegalStateException("PLUGIN_TIMEOUT");
			}
			byte[] out = awaitOutput(stdout, 1_000L);
			byte[] err = awaitOutput(stderr, 1_000L);
			if (process.exitValue() != 0) {
				throw new IllegalStateException("PLUGIN_PROCESS_FAILED: " + diagnostic(new String(err, StandardCharsets.UTF_8)));
			}
			return out;
		} finally {
			if (process.isAlive()) {
				destroyProcessTree(process);
			}
			io.shutdownNow();
		}
	}

	private ValidatedInvocation validate(PluginRunnerProtocol.InvokeRequest request) throws Exception {
		if (!request.pluginKey().matches(KEY_PATTERN) || !request.pluginDirectory().matches(KEY_PATTERN)) {
			throw new IllegalArgumentException("INVALID_PLUGIN_KEY");
		}
		if (!request.handler().matches(HANDLER_PATTERN)) {
			throw new IllegalArgumentException("INVALID_HANDLER");
		}
		if (request.timeoutMs() < MIN_TIMEOUT_MS || request.timeoutMs() > MAX_TIMEOUT_MS) {
			throw new IllegalArgumentException("INVALID_TIMEOUT");
		}
		if (request.memoryMb() < MIN_MEMORY_MB || request.memoryMb() > MAX_MEMORY_MB) {
			throw new IllegalArgumentException("INVALID_MEMORY_LIMIT");
		}
		if (request.brokerToken() == null || !request.brokerToken().matches("[A-Za-z0-9_-]{32,128}")) {
			throw new IllegalArgumentException("INVALID_BROKER_TOKEN");
		}
		if (request.contextJson() == null || request.contextJson().length > PluginRunnerProtocol.MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException("INVALID_CONTEXT");
		}

		Path pluginDir = pluginsRoot.resolve(request.pluginDirectory()).normalize();
		if (!pluginDir.startsWith(pluginsRoot) || Files.isSymbolicLink(pluginDir)) {
			throw new IllegalArgumentException("PLUGIN_PATH_ESCAPE");
		}
		Path realPluginDir = requireDirectory(pluginDir);
		if (!realPluginDir.getParent().equals(pluginsRoot)) {
			throw new IllegalArgumentException("PLUGIN_DIRECTORY_MUST_BE_DIRECT_CHILD");
		}

		Path relativeEntrypoint = Path.of(request.entrypoint()).normalize();
		if (relativeEntrypoint.isAbsolute() || relativeEntrypoint.startsWith("..")) {
			throw new IllegalArgumentException("ENTRYPOINT_PATH_ESCAPE");
		}
		Path entrypoint = realPluginDir.resolve(relativeEntrypoint).normalize();
		if (!entrypoint.startsWith(realPluginDir)
				|| !Files.isRegularFile(entrypoint, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(entrypoint)) {
			throw new IllegalArgumentException("INVALID_ENTRYPOINT");
		}
		if (!entrypoint.toRealPath().startsWith(realPluginDir)) {
			throw new IllegalArgumentException("ENTRYPOINT_PATH_ESCAPE");
		}
		if (!Files.exists(brokerSocketPath, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException("BROKER_SOCKET_UNAVAILABLE");
		}
		return new ValidatedInvocation(realPluginDir, relativeEntrypoint.toString());
	}

	private List<String> createBwrapCommand(ValidatedInvocation invocation, PluginRunnerProtocol.InvokeRequest request) {
		List<String> c = new ArrayList<>();
		c.add(bwrap.toString());
		c.add("--die-with-parent");
		c.add("--new-session");
		c.add("--unshare-all");
		/* --unshare-all uses a best-effort userns; make userns mandatory for this security boundary. */
		c.add("--unshare-user");
		/*
		 * Do not mount procfs inside this nested sandbox. Docker's protected /proc
		 * layout can make a nested procfs mount fail with EPERM. An empty /proc is
		 * sufficient for GroovySandboxMain and exposes less process metadata.
		 */
		c.add("--dir"); c.add("/proc");
		c.add("--dev"); c.add("/dev");
		c.add("--size"); c.add("67108864");
		c.add("--tmpfs"); c.add("/tmp");
		c.add("--dir"); c.add("/run");
		c.add("--dir"); c.add("/run/uldesk");
		c.add("--dir"); c.add("/runtime");
		c.add("--dir"); c.add("/usr");
		c.add("--dir"); c.add("/etc");
		c.add("--chdir"); c.add("/tmp");

		bindTry(c, Path.of("/lib"));
		bindTry(c, Path.of("/lib64"));
		bindTry(c, Path.of("/usr/lib"));
		bindTry(c, Path.of("/etc/ld.so.cache"));
		bindTry(c, Path.of("/etc/localtime"));

		/* Keep the JDK at its original absolute path. Some launchers resolve
		 * libjli.so and other native JVM libraries relative to/under JAVA_HOME. */
		c.add("--ro-bind"); c.add(javaHome.toString()); c.add(javaHome.toString());
		c.add("--setenv"); c.add("JAVA_HOME"); c.add(javaHome.toString());
		c.add("--setenv"); c.add("LD_LIBRARY_PATH");
		c.add(javaHome.resolve("lib") + ":" + javaHome.resolve("lib/server"));
		c.add("--ro-bind"); c.add(appRoot.toString()); c.add("/runtime/app");
		c.add("--ro-bind"); c.add(invocation.pluginDir().toString()); c.add("/plugin");
		c.add("--ro-bind"); c.add(brokerSocketPath.toString()); c.add(SANDBOX_BROKER_SOCKET);

		c.add("--");
		c.add(javaHome.resolve("bin/java").toString());
		c.add("-Xmx" + request.memoryMb() + "m");
		c.add("-XX:MaxMetaspaceSize=128m");
		c.add("-XX:MaxDirectMemorySize=16m");
		c.add("-XX:ReservedCodeCacheSize=64m");
		c.add("-XX:+DisableAttachMechanism");
		c.add("-Xss512k");
		c.add("-Djava.io.tmpdir=/tmp");
		c.add("-Duser.home=/tmp");
		c.add("-Duser.timezone=UTC");
		c.add("-Djava.awt.headless=true");
		c.add("-Duldesk.plugin.isolation=os");
		c.add("-cp");
		c.add("/runtime/app/BOOT-INF/classes:/runtime/app/BOOT-INF/lib/*");
		c.add("ru.ravel.ItDesk.plugins.GroovySandboxMain");
		c.add("--plugin-dir"); c.add("/plugin");
		c.add("--entrypoint"); c.add(invocation.entrypoint());
		c.add("--handler"); c.add(request.handler());
		return c;
	}

	private void probeSandbox() throws Exception {
		List<String> command = List.of(
				bwrap.toString(), "--die-with-parent", "--new-session", "--unshare-all", "--unshare-user",
				"--ro-bind", "/", "/", "--", "/bin/true"
		);
		Process p = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		byte[] err;
		try (InputStream stderr = p.getErrorStream()) {
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				destroyProcessTree(p);
				throw new IllegalStateException("bubblewrap probe timed out");
			}
			err = stderr.readNBytes(8192);
		}
		if (p.exitValue() != 0) {
			throw new IllegalStateException("bubblewrap probe failed: " + diagnostic(new String(err, StandardCharsets.UTF_8)));
		}
	}

	private static Path requireBwrap(Path path) throws Exception {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || !Files.isExecutable(path)) {
			throw new IllegalStateException("bubblewrap executable is unavailable: " + path);
		}
		try {
			Object raw = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
			int mode = ((Number) raw).intValue();
			if ((mode & 06000) != 0) {
				throw new IllegalStateException("setuid/setgid bubblewrap is not allowed: " + path);
			}
		} catch (UnsupportedOperationException e) {
			throw new IllegalStateException("Linux unix file attributes are required for plugin runner", e);
		}
		return path.toRealPath();
	}

	private static void bindTry(List<String> command, Path path) {
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			command.add("--ro-bind"); command.add(path.toString()); command.add(path.toString());
		}
	}

	private static byte[] awaitOutput(Future<byte[]> future, long timeoutMs) {
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (Exception ignored) {
			return new byte[0];
		}
	}

	private static byte[] readLimited(InputStream input, String name) throws Exception {
		try (InputStream in = input) {
			byte[] bytes = in.readNBytes(MAX_OUTPUT_BYTES + 1);
			if (bytes.length > MAX_OUTPUT_BYTES) {
				throw new IllegalStateException("PLUGIN_" + name.toUpperCase() + "_TOO_LARGE");
			}
			return bytes;
		}
	}

	private static void destroyProcessTree(Process process) {
		try { process.toHandle().descendants().forEach(h -> { try { h.destroyForcibly(); } catch (Exception ignored) { } }); } catch (Exception ignored) { }
		try { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); } catch (Exception ignored) { }
	}

	private static Path requireDirectory(Path path) throws Exception {
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
			throw new IllegalStateException("Required directory is unavailable or is a symlink: " + path);
		}
		return path.toRealPath();
	}

	private static Path absoluteEnv(String name, String fallback) {
		String value = System.getenv(name);
		Path path = Path.of(value == null || value.isBlank() ? fallback : value).toAbsolutePath().normalize();
		if (!path.isAbsolute()) throw new IllegalArgumentException(name + " must be absolute");
		return path;
	}

	private static int intEnv(String name, int fallback, int min, int max) {
		String raw = System.getenv(name);
		int value = raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw);
		return Math.max(min, Math.min(max, value));
	}

	private static int currentUid() throws Exception {
		for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
			if (line.startsWith("Uid:")) {
				String[] parts = line.substring(4).trim().split("\\s+");
				return Integer.parseInt(parts[0]);
			}
		}
		throw new IllegalStateException("Cannot determine runner uid");
	}

	private static void prepareSocketPath(Path path) throws Exception {
		Path parent = path.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
			throw new IllegalStateException("Runner socket directory is unavailable");
		}
		Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(parent);
		if (permissions.contains(PosixFilePermission.OTHERS_READ)
				|| permissions.contains(PosixFilePermission.OTHERS_WRITE)
				|| permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
			throw new IllegalStateException("Runner socket directory must not be accessible to others");
		}
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			int owner = ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
			if (owner != currentUid()) {
				throw new IllegalStateException("Refusing to remove a runner socket owned by another uid");
			}
			Files.delete(path);
		}
	}

	private static void chmodSocket(Path path) throws Exception {
		Files.setPosixFilePermissions(path, EnumSet.of(
				PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
				PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE
		));
	}

	private static String diagnostic(Exception e) {
		return diagnostic(e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
	}

	private static String diagnostic(String value) {
		String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
		return normalized.length() <= 4096 ? normalized : normalized.substring(0, 4096);
	}

	private record ValidatedInvocation(Path pluginDir, String entrypoint) {
	}
}
