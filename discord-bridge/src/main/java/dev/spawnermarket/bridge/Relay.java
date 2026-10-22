package dev.spawnermarket.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Read-only connection to the same public MQTT broker the mod uses. Subscribes to offers, deals
 * and vouches and hands every message to the bridge; it never publishes anything.
 */
final class Relay {
	interface Handler {
		/** {@code body} null = the retained message on that topic was deleted. */
		void onMessage(String topic, JsonObject body);

		void onConnected();
	}

	private static final String HOST = "broker.emqx.io";
	private static final int PORT = 1883, KEEPALIVE_S = 60;
	/** Must match MarketRelay.ROOT in the mod. */
	static final String ROOT = "spawnermarket/" + hash("spawnermarket:v1") + "/";

	private final Handler handler;
	private volatile boolean connected;

	Relay(Handler handler) {
		this.handler = handler;
	}

	boolean connected() {
		return connected;
	}

	void start() {
		Thread th = new Thread(this::run, "relay");
		th.setDaemon(true);
		th.start();
	}

	private void run() {
		long backoff = 2000;
		while (true) {
			try (Socket s = new Socket()) {
				s.connect(new InetSocketAddress(HOST, PORT), 10_000);
				s.setSoTimeout(KEEPALIVE_S * 1000 / 2);
				s.setTcpNoDelay(true);
				DataInputStream in = new DataInputStream(s.getInputStream());
				OutputStream o = s.getOutputStream();
				o.write(connectPacket());
				o.flush();
				int[] header = readHeader(in);
				byte[] ack = in.readNBytes(header[1]);
				if ((header[0] >> 4) != 2 || ack.length < 2 || ack[1] != 0) throw new IOException("MQTT connect refused");
				o.write(subscribePacket(ROOT + "o/+/+", ROOT + "d/+/+", ROOT + "v/#"));
				o.flush();
				connected = true;
				backoff = 2000;
				Bridge.log("Mit dem Relay verbunden");
				handler.onConnected();
				int idle = 0;
				while (true) {
					int[] h;
					try {
						h = readHeader(in);
					} catch (SocketTimeoutException timeout) {
						if (++idle >= 3) throw new IOException("MQTT timeout");
						o.write(new byte[] { (byte) 0xC0, 0 }); // PINGREQ
						o.flush();
						continue;
					}
					idle = 0;
					byte[] body = in.readNBytes(h[1]);
					if (body.length < h[1]) throw new EOFException();
					if ((h[0] >> 4) == 3) onPublish(h[0], body);
				}
			} catch (Exception e) {
				Bridge.log("Relay getrennt (" + e.getMessage() + "), verbinde neu…");
			} finally {
				connected = false;
			}
			try {
				Thread.sleep(backoff);
			} catch (InterruptedException e) {
				return;
			}
			backoff = Math.min(60_000, backoff * 2);
		}
	}

	private void onPublish(int flags, byte[] body) {
		int topicLen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
		int pos = 2 + topicLen + (((flags >> 1) & 3) > 0 ? 2 : 0);
		if (pos > body.length) return;
		String topic = new String(body, 2, topicLen, StandardCharsets.UTF_8);
		if (!topic.startsWith(ROOT)) return;
		JsonObject o = null;
		if (pos < body.length) {
			try {
				o = JsonParser.parseString(new String(body, pos, body.length - pos, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (Exception e) {
				return; // not one of ours: anyone can post to a public broker
			}
		}
		handler.onMessage(topic.substring(ROOT.length()), o);
	}

	// ---------------------------------------------------------------- MQTT

	private static int[] readHeader(DataInputStream in) throws IOException {
		int first = in.read();
		if (first < 0) throw new EOFException();
		int len = 0, mul = 1, b;
		do {
			b = in.read();
			if (b < 0) throw new EOFException();
			len += (b & 0x7F) * mul;
			mul *= 128;
		} while ((b & 0x80) != 0 && mul <= 128 * 128 * 128);
		return new int[] { first, len };
	}

	private static byte[] connectPacket() throws IOException {
		ByteArrayOutputStream v = new ByteArrayOutputStream();
		writeString(v, "MQTT");
		v.write(4); // MQTT 3.1.1
		v.write(0x02); // clean session
		v.write(KEEPALIVE_S >> 8);
		v.write(KEEPALIVE_S & 0xFF);
		writeString(v, "smd-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
		return packet(0x10, v.toByteArray());
	}

	private static byte[] subscribePacket(String... topics) throws IOException {
		ByteArrayOutputStream v = new ByteArrayOutputStream();
		v.write(0);
		v.write(1); // packet id
		for (String t : topics) {
			writeString(v, t);
			v.write(0); // QoS 0
		}
		return packet(0x82, v.toByteArray());
	}

	private static byte[] packet(int type, byte[] body) {
		ByteArrayOutputStream p = new ByteArrayOutputStream();
		p.write(type);
		int len = body.length;
		do {
			int b = len % 128;
			len /= 128;
			p.write(len > 0 ? b | 0x80 : b);
		} while (len > 0);
		p.writeBytes(body);
		return p.toByteArray();
	}

	private static void writeString(ByteArrayOutputStream v, String s) throws IOException {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		v.write(b.length >> 8);
		v.write(b.length & 0xFF);
		v.write(b);
	}

	static String hash(String s) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(h, 0, 12);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
