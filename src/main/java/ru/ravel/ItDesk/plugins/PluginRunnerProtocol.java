package ru.ravel.ItDesk.plugins;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Internal, length-prefixed protocol between webapp and the isolated runner. */
public final class PluginRunnerProtocol {

	public static final int MAGIC = 0x554C4450; // ULDP
	public static final short VERSION = 1;
	public static final byte CMD_PING = 1;
	public static final byte CMD_INVOKE = 2;
	public static final byte STATUS_OK = 0;
	public static final byte STATUS_ERROR = 1;
	public static final int MAX_STRING_BYTES = 4096;
	public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

	private PluginRunnerProtocol() {
	}

	public record InvokeRequest(
			String pluginKey,
			String pluginDirectory,
			String entrypoint,
			String handler,
			long timeoutMs,
			int memoryMb,
			String brokerToken,
			byte[] contextJson
	) {
	}

	public record Response(boolean ok, byte[] payload, String error) {
		public static Response ok(byte[] payload) {
			return new Response(true, payload == null ? new byte[0] : payload, null);
		}
		public static Response error(String error) {
			return new Response(false, new byte[0], error == null ? "RUNNER_ERROR" : error);
		}
	}

	public static void writeHeader(DataOutputStream out, byte command) throws IOException {
		out.writeInt(MAGIC);
		out.writeShort(VERSION);
		out.writeByte(command);
	}

	public static byte readHeader(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC) {
			throw new IOException("Invalid plugin runner protocol magic");
		}
		if (in.readShort() != VERSION) {
			throw new IOException("Unsupported plugin runner protocol version");
		}
		return in.readByte();
	}

	public static void writeInvoke(DataOutputStream out, InvokeRequest request) throws IOException {
		writeHeader(out, CMD_INVOKE);
		writeString(out, request.pluginKey());
		writeString(out, request.pluginDirectory());
		writeString(out, request.entrypoint());
		writeString(out, request.handler());
		out.writeLong(request.timeoutMs());
		out.writeInt(request.memoryMb());
		writeString(out, request.brokerToken());
		writeBytes(out, request.contextJson(), MAX_PAYLOAD_BYTES);
		out.flush();
	}

	public static InvokeRequest readInvokeBody(DataInputStream in) throws IOException {
		return new InvokeRequest(
				readString(in), readString(in), readString(in), readString(in),
				in.readLong(), in.readInt(), readString(in), readBytes(in, MAX_PAYLOAD_BYTES)
		);
	}

	public static void writePing(DataOutputStream out) throws IOException {
		writeHeader(out, CMD_PING);
		out.flush();
	}

	public static void writeResponse(DataOutputStream out, Response response) throws IOException {
		out.writeByte(response.ok() ? STATUS_OK : STATUS_ERROR);
		if (response.ok()) {
			writeBytes(out, response.payload(), MAX_PAYLOAD_BYTES);
		} else {
			writeString(out, response.error());
		}
		out.flush();
	}

	public static Response readResponse(DataInputStream in) throws IOException {
		int status = in.read();
		if (status < 0) {
			throw new EOFException("Plugin runner closed the connection");
		}
		if (status == STATUS_OK) {
			return Response.ok(readBytes(in, MAX_PAYLOAD_BYTES));
		}
		if (status == STATUS_ERROR) {
			return Response.error(readString(in));
		}
		throw new IOException("Invalid plugin runner response status");
	}

	private static void writeString(DataOutputStream out, String value) throws IOException {
		byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
		writeBytes(out, bytes, MAX_STRING_BYTES);
	}

	private static String readString(DataInputStream in) throws IOException {
		return new String(readBytes(in, MAX_STRING_BYTES), StandardCharsets.UTF_8);
	}

	private static void writeBytes(DataOutputStream out, byte[] bytes, int max) throws IOException {
		byte[] safe = bytes == null ? new byte[0] : bytes;
		if (safe.length > max) {
			throw new IOException("Plugin runner protocol field is too large");
		}
		out.writeInt(safe.length);
		out.write(safe);
	}

	private static byte[] readBytes(DataInputStream in, int max) throws IOException {
		int size = in.readInt();
		if (size < 0 || size > max) {
			throw new IOException("Plugin runner protocol field has invalid length");
		}
		byte[] bytes = new byte[size];
		in.readFully(bytes);
		return bytes;
	}
}
