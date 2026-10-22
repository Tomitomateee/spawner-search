package dev.spawnermarket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Connection to a free public MQTT broker (no login, no server plugin needed). Offers, deals,
 * vouches and the last chat message of every player are "retained", so they arrive the moment
 * someone connects; DMs and TPA requests go straight to the recipient's topic. Messages are
 * handed to {@link Market} on the game thread.
 */
public final class MarketRelay {
	public interface Handler {
		/** {@code body} null = the retained message on that topic was deleted. */
		void onMessage(String topic, JsonObject body, boolean stored);

		void onConnected();
	}

	private static final String HOST = "broker.emqx.io";
	private static final int PORT = 1883, KEEPALIVE_S = 60;
	public static final String ROOT = "spawnermarket/" + hash("spawnermarket:v1") + "/";

	private final Handler handler;
	private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "spawnermarket-send");
		t.setDaemon(true);
		return t;
	});
	private volatile boolean on;
	private volatile boolean connected;
	private volatile Socket socket;
	private volatile OutputStream out;

	public MarketRelay(Handler handler) {
		this.handler = handler;
	}

	public synchronized void start() {
		if (on) return;
		on = true;
		startReader();
	}

	public boolean connected() {
		return connected;
	}

	/** Publishes {@code body} (null = delete the retained message) on a topic below the root. */
	public void publish(String topic, JsonObject body, boolean retain) {
		byte[] payload = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
		sender.execute(() -> {
			OutputStream o = out;
			if (o == null) return;
			try {
				byte[] packet = publishPacket(ROOT + topic, payload, retain);
				synchronized (this) {
					o.write(packet);
					o.flush();
				}
			} catch (IOException e) {
				SpawnerMarketClient.LOGGER.debug("Relay: Senden fehlgeschlagen", e);
				closeSocket();
			}
		});
	}

	/** Waits (briefly) until everything queued has been sent; used while the game quits. */
	public void flush() {
		try {
			sender.submit(() -> { }).get(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (Exception ignored) {
			// quitting anyway
		}
	}

	private void startReader() {
		Thread th = new Thread(() -> {
			long backoff = 2000;
			while (on) {
				try (Socket s = new Socket()) {
					UUID id = Minecraft.getInstance().getUser().getProfileId();
					s.connect(new InetSocketAddress(HOST, PORT), 10_000);
					s.setSoTimeout(KEEPALIVE_S * 1000 / 2);
					s.setTcpNoDelay(true);
					DataInputStream in = new DataInputStream(s.getInputStream());
					OutputStream o = s.getOutputStream();
					o.write(connectPacket(id));
					o.flush();
					int[] header = readHeader(in);
					byte[] ack = in.readNBytes(header[1]);
					if ((header[0] >> 4) != 2 || ack.length < 2 || ack[1] != 0) throw new IOException("MQTT connect refused");
					socket = s;
					o.write(subscribePacket(ROOT + "o/+/+", ROOT + "d/+/+", ROOT + "v/+/+", ROOT + "p/+",
							ROOT + "g/#", ROOT + "m/" + id + "/+", ROOT + "t/" + id));
					o.flush();
					out = o;
					connected = true;
					backoff = 2000;
					Minecraft.getInstance().execute(handler::onConnected);
					int idle = 0;
					while (on) {
						int[] h;
						try {
							h = readHeader(in);
						} catch (SocketTimeoutException timeout) {
							if (++idle >= 3) throw new IOException("MQTT timeout");
							synchronized (this) {
								o.write(new byte[] { (byte) 0xC0, 0 }); // PINGREQ
								o.flush();
							}
							continue;
						}
						idle = 0;
						byte[] body = in.readNBytes(h[1]);
						if (body.length < h[1]) throw new EOFException();
						if ((h[0] >> 4) == 3) onPublish(h[0], body);
					}
				} catch (Exception e) {
					if (on) SpawnerMarketClient.LOGGER.debug("Relay getrennt, verbinde neu", e);
				} finally {
					connected = false;
					socket = null;
					out = null;
				}
				if (!on) break;
				try {
					Thread.sleep(backoff);
				} catch (InterruptedException e) {
					break;
				}
				backoff = Math.min(60_000, backoff * 2);
			}
		}, "spawnermarket-relay");
		th.setDaemon(true);
		th.start();
	}

	private void onPublish(int flags, byte[] body) {
		int topicLen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
		int pos = 2 + topicLen + (((flags >> 1) & 3) > 0 ? 2 : 0);
		if (pos > body.length) return;
		String topic = new String(body, 2, topicLen, StandardCharsets.UTF_8);
		if (!topic.startsWith(ROOT)) return;
		String rel = topic.substring(ROOT.length());
		boolean stored = (flags & 1) != 0;
		JsonObject o = null;
		if (pos < body.length) {
			try {
				o = JsonParser.parseString(new String(body, pos, body.length - pos, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (Exception e) {
				return; // not one of ours: anyone can post to a public broker
			}
		}
		JsonObject msg = o;
		Minecraft.getInstance().execute(() -> handler.onMessage(rel, msg, stored));
	}

	// ---------------------------------------------------------------- MQTT

	/** Reads a fixed header: {type+flags, remaining length}. */
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

	private static byte[] connectPacket(UUID id) throws IOException {
		ByteArrayOutputStream v = new ByteArrayOutputStream();
		writeString(v, "MQTT");
		v.write(4); // MQTT 3.1.1
		v.write(0x02 | 0x04 | 0x20); // clean session, will, will retained
		v.write(KEEPALIVE_S >> 8);
		v.write(KEEPALIVE_S & 0xFF);
		writeString(v, "sm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
		// Last will: the broker marks me offline if the connection drops.
		writeString(v, ROOT + "p/" + id);
		JsonObject bye = new JsonObject();
		bye.addProperty("u", id.toString());
		bye.addProperty("nm", Minecraft.getInstance().getUser().getName());
		bye.addProperty("off", true);
		byte[] willBody = bye.toString().getBytes(StandardCharsets.UTF_8);
		v.write(willBody.length >> 8);
		v.write(willBody.length & 0xFF);
		v.write(willBody);
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

	private static byte[] publishPacket(String topic, byte[] payload, boolean retain) throws IOException {
		ByteArrayOutputStream v = new ByteArrayOutputStream();
		writeString(v, topic);
		v.write(payload);
		return packet(0x30 | (retain ? 1 : 0), v.toByteArray());
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

	private void closeSocket() {
		Socket s = socket;
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
				// already closed
			}
		}
	}

	public static String hash(String s) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(h, 0, 12);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
