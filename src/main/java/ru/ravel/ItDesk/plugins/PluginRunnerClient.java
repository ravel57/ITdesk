package ru.ravel.ItDesk.plugins;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class PluginRunnerClient {

	private final Path socketPath;
	private final ExecutorService ioExecutor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "plugin-runner-client");
		t.setDaemon(true);
		return t;
	});

	public PluginRunnerClient(
			@Value("${uldesk.plugins.runner.socket:/run/uldesk-ipc/plugin-runner.sock}") String socketPath
	) {
		this.socketPath = Path.of(socketPath).toAbsolutePath().normalize();
	}

	public boolean ping(long timeoutMs) {
		try {
			return execute(() -> {
				try (SocketChannel channel = connect();
					 DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
					 DataInputStream in = new DataInputStream(Channels.newInputStream(channel))) {
					PluginRunnerProtocol.writePing(out);
					return PluginRunnerProtocol.readResponse(in).ok();
				}
			}, timeoutMs);
		} catch (Exception e) {
			return false;
		}
	}

	public byte[] invoke(PluginRunnerProtocol.InvokeRequest request) throws Exception {
		long deadlineMs = Math.min(45_000L, Math.max(5_000L, request.timeoutMs() + 5_000L));
		PluginRunnerProtocol.Response response = execute(() -> {
			try (SocketChannel channel = connect();
				 DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
				 DataInputStream in = new DataInputStream(Channels.newInputStream(channel))) {
				PluginRunnerProtocol.writeInvoke(out, request);
				return PluginRunnerProtocol.readResponse(in);
			}
		}, deadlineMs);
		if (!response.ok()) {
			throw new IllegalStateException("Plugin runner rejected invocation: " + response.error());
		}
		return response.payload();
	}

	private SocketChannel connect() throws Exception {
		SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
		try {
			channel.connect(UnixDomainSocketAddress.of(socketPath));
			return channel;
		} catch (Exception e) {
			channel.close();
			throw e;
		}
	}

	private <T> T execute(Callable<T> callable, long timeoutMs) throws Exception {
		Future<T> future = ioExecutor.submit(callable);
		try {
			return future.get(Math.max(250L, timeoutMs), TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			future.cancel(true);
			throw e;
		}
	}

	@PreDestroy
	public void close() {
		ioExecutor.shutdownNow();
	}
}
