package dev.spawnermarket;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the market knows: offers, deals, vouches, chats, TPA requests and who is online
 * where. Fed by {@link MarketRelay}; only touched on the game thread.
 */
public final class Market implements MarketRelay.Handler {
	public record Offer(String id, UUID owner, String name, String server, String type, boolean sell,
	                    int amount, long price, String note, long ts) {}

	/** A finished trade, entered by {@code owner}; {@code sold}: the owner was the seller. */
	public record Deal(String id, UUID owner, String name, String partner, String server, String type, boolean sold,
	                   int amount, long price, long ts) {
		public String seller() { return sold ? name : partner; }

		public String buyer() { return sold ? partner : name; }
	}

	public record Vouch(UUID target, String targetName, UUID from, String fromName, boolean positive, String text, long ts) {}

	public record ChatMsg(UUID from, String name, String text, long ts, boolean mine) {}

	public record TpaRequest(UUID from, String name, long at) {}

	private record Presence(String server, boolean online, long seen) {}

	public static final String GLOBAL = "global";
	private static final long OFFER_MAX_AGE = 7 * 86_400_000L, DEAL_MAX_AGE = 30 * 86_400_000L;
	private static final long CHAT_MAX_AGE = 6 * 3_600_000L, ONLINE_MS = 5 * 60_000L, TPA_MS = 120_000L;
	private static final long HEARTBEAT_MS = 120_000L;
	private static final int CHAT_KEEP = 150;

	private final MarketConfig config;
	private final MarketRelay relay;
	private final Map<String, Offer> offers = new HashMap<>();
	private final Map<String, Deal> deals = new HashMap<>();
	/** "target/from" → vouch: one vouch per pair, the newest counts. */
	private final Map<String, Vouch> vouches = new HashMap<>();
	private final Map<UUID, Presence> presence = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<String, List<ChatMsg>> channels = new HashMap<>();
	private final Map<UUID, List<ChatMsg>> dms = new LinkedHashMap<>();
	private final Set<String> seen = new HashSet<>();
	private final Map<String, Integer> unread = new HashMap<>();
	private final List<TpaRequest> tpas = new ArrayList<>();
	/** Which chat the GUI is showing right now (no unread count for it). */
	private String viewing = "";
	private String message;
	private long messageAt, lastChatAt, lastHeartbeat;
	private String lastServer = "";
	/** Called for private messages and TPA requests: (title, text, from). */
	private Notifier notifier = (t, m, u) -> { };

	public interface Notifier {
		void notify(String title, String text, UUID from);
	}

	public Market(MarketConfig config) {
		this.config = config;
		this.relay = new MarketRelay(this);
	}

	public void start() {
		relay.start();
	}

	public void setNotifier(Notifier n) {
		notifier = n;
	}

	public MarketConfig config() {
		return config;
	}

	public boolean connected() {
		return relay.connected();
	}

	// ================================================================ identity

	public static UUID me() {
		return Minecraft.getInstance().getUser().getProfileId();
	}

	public static String myName() {
		return Minecraft.getInstance().getUser().getName();
	}

	/** The current server's address, normalized ("" in singleplayer / menus). */
	public static String server() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getCurrentServer() == null || mc.isLocalServer()) return "";
		String ip = mc.getCurrentServer().ip.toLowerCase(Locale.ROOT).trim();
		if (ip.endsWith(":25565")) ip = ip.substring(0, ip.length() - 6);
		if (ip.endsWith(".")) ip = ip.substring(0, ip.length() - 1);
		return ip.length() > 64 ? ip.substring(0, 64) : ip;
	}

	public static String serverChannel(String server) {
		return server.isEmpty() ? "" : "s-" + MarketRelay.hash("server:" + server);
	}

	/** Best known name of a player. */
	public String name(UUID uuid) {
		if (uuid.equals(me())) return myName();
		PlayerInfo info = tabInfo(uuid);
		if (info != null) return info.getProfile().name();
		return names.getOrDefault(uuid, "?");
	}

	/** UUID for a name: tab list first, then everyone seen on the relay. */
	public UUID uuidByName(String name) {
		if (name == null || name.isBlank()) return null;
		if (name.equalsIgnoreCase(myName())) return me();
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null) {
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				if (info.getProfile().name().equalsIgnoreCase(name)) return info.getProfile().id();
			}
		}
		for (Map.Entry<UUID, String> e : names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
		return null;
	}

	public static PlayerInfo tabInfo(UUID uuid) {
		Minecraft mc = Minecraft.getInstance();
		return mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(uuid);
	}

	public boolean online(UUID uuid) {
		if (uuid.equals(me())) return true;
		Presence p = presence.get(uuid);
		return (p != null && p.online && System.currentTimeMillis() - p.seen < ONLINE_MS) || tabInfo(uuid) != null;
	}

	/** Whether the player is on my server right now (in my tab list, or says so on the relay). */
	public boolean sameServer(UUID uuid) {
		if (tabInfo(uuid) != null) return true;
		String sv = server();
		Presence p = presence.get(uuid);
		return !sv.isEmpty() && p != null && online(uuid) && sv.equals(p.server);
	}

	public String serverOf(UUID uuid) {
		Presence p = presence.get(uuid);
		return p == null || !online(uuid) ? "" : p.server;
	}

	// ================================================================ status line

	public void say(String msg) {
		message = msg;
		messageAt = System.currentTimeMillis();
	}

	/** Short feedback for the GUI, shown for a few seconds. */
	public String message() {
		return message != null && System.currentTimeMillis() - messageAt < 3500 ? message : null;
	}

	// ================================================================ queries

	/** Offers, newest first; {@code server} "" = all servers. */
	public List<Offer> offers(String server) {
		long now = System.currentTimeMillis();
		List<Offer> list = new ArrayList<>();
		for (Offer o : offers.values()) {
			if (now - o.ts > OFFER_MAX_AGE) continue;
			if (!server.isEmpty() && !server.equals(o.server)) continue;
			list.add(o);
		}
		list.sort(Comparator.comparingLong(Offer::ts).reversed());
		return list;
	}

	public List<Deal> deals(String server) {
		long now = System.currentTimeMillis();
		List<Deal> list = new ArrayList<>();
		for (Deal d : deals.values()) {
			if (now - d.ts > DEAL_MAX_AGE) continue;
			if (!server.isEmpty() && !server.equals(d.server)) continue;
			list.add(d);
		}
		list.sort(Comparator.comparingLong(Deal::ts).reversed());
		return list;
	}

	/** Price overview for one spawner type. -1 = unknown. */
	public record PriceStats(Spawners.Type type, long average, int dealCount, long lowestSell, long highestBuy, int offerCount) {}

	public List<PriceStats> prices(String server) {
		List<Deal> ds = deals(server);
		List<Offer> os = offers(server);
		List<PriceStats> out = new ArrayList<>();
		for (Spawners.Type t : Spawners.ALL) {
			long sum = 0;
			int pieces = 0, count = 0;
			for (Deal d : ds) {
				if (!d.type.equals(t.key())) continue;
				// Weighted by pieces, so one big deal counts more than a single spawner.
				sum += d.price * d.amount;
				pieces += d.amount;
				count++;
			}
			long low = -1, high = -1;
			int offerCount = 0;
			for (Offer o : os) {
				if (!o.type.equals(t.key())) continue;
				offerCount++;
				if (o.sell && (low < 0 || o.price < low)) low = o.price;
				if (!o.sell && o.price > high) high = o.price;
			}
			if (count == 0 && offerCount == 0) continue;
			out.add(new PriceStats(t, pieces > 0 ? sum / pieces : -1, count, low, high, offerCount));
		}
		// Most traded first.
		out.sort(Comparator.comparingInt((PriceStats p) -> p.dealCount + p.offerCount).reversed());
		return out;
	}

	public PriceStats price(String server, String type) {
		for (PriceStats p : prices(server)) if (p.type.key().equals(type)) return p;
		return null;
	}

	public List<Vouch> vouchesFor(UUID target) {
		List<Vouch> list = new ArrayList<>();
		for (Vouch v : vouches.values()) if (v.target.equals(target)) list.add(v);
		list.sort(Comparator.comparingLong(Vouch::ts).reversed());
		return list;
	}

	/** {positive, negative} */
	public int[] score(UUID target) {
		int pos = 0, neg = 0;
		for (Vouch v : vouches.values()) {
			if (!v.target.equals(target)) continue;
			if (v.positive) pos++;
			else neg++;
		}
		return new int[] { pos, neg };
	}

	public Vouch myVouchFor(UUID target) {
		return vouches.get(target + "/" + me());
	}

	/** Players with the most positive vouches, for the right panel when nobody is selected. */
	public List<UUID> topVouched(int max) {
		Map<UUID, Integer> net = new HashMap<>();
		for (Vouch v : vouches.values()) net.merge(v.target, v.positive ? 1 : -1, Integer::sum);
		List<UUID> list = new ArrayList<>(net.keySet());
		list.sort(Comparator.comparingInt((UUID u) -> net.get(u)).reversed());
		return list.size() > max ? list.subList(0, max) : list;
	}

	public List<ChatMsg> channel(String ch) {
		return channels.getOrDefault(ch, List.of());
	}

	public List<ChatMsg> dm(UUID with) {
		return dms.getOrDefault(with, List.of());
	}

	/** Conversations, most recent first. */
	public List<UUID> conversations() {
		List<UUID> list = new ArrayList<>(dms.keySet());
		list.sort(Comparator.comparingLong((UUID u) -> {
			List<ChatMsg> l = dms.get(u);
			return l.isEmpty() ? 0 : l.get(l.size() - 1).ts;
		}).reversed());
		return list;
	}

	public void openDm(UUID with) {
		dms.computeIfAbsent(with, k -> new ArrayList<>());
	}

	public void setViewing(String key) {
		viewing = key;
		unread.remove(key);
	}

	public int unread(String key) {
		return unread.getOrDefault(key, 0);
	}

	public int unreadDms() {
		int n = 0;
		for (Map.Entry<String, Integer> e : unread.entrySet()) if (e.getKey().startsWith("dm:")) n += e.getValue();
		return n;
	}

	public List<TpaRequest> tpaRequests() {
		long now = System.currentTimeMillis();
		tpas.removeIf(t -> now - t.at > TPA_MS);
		return tpas;
	}

	// ================================================================ actions

	private static String newId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	private void publishMine(String topic, JsonObject body) {
		config.published.put(topic, body);
		config.save();
		relay.publish(topic, body, true);
		// Show it right away, also while offline.
		onMessage(topic, body, false);
	}

	private void deleteMine(String topic) {
		config.published.remove(topic);
		config.save();
		relay.publish(topic, null, true);
		onMessage(topic, null, false);
	}

	/** Returns an error text, or null when the offer was published. */
	public String publishOffer(Spawners.Type type, boolean sell, int amount, long price, String note) {
		String sv = server();
		if (sv.isEmpty()) return "Tritt erst einem Server bei";
		if (amount < 1 || amount > 100_000) return "Anzahl 1–100000";
		if (price < 1 || price > Spawners.MAX_PRICE) return "Ungültiger Preis";
		String id = newId();
		JsonObject o = new JsonObject();
		o.addProperty("u", me().toString());
		o.addProperty("nm", myName());
		o.addProperty("sv", sv);
		o.addProperty("id", id);
		o.addProperty("ty", type.key());
		o.addProperty("s", sell);
		o.addProperty("n", amount);
		o.addProperty("p", price);
		o.addProperty("no", clean(note, 60));
		o.addProperty("ts", System.currentTimeMillis());
		publishMine("o/" + me() + "/" + id, o);
		return null;
	}

	public void removeOffer(Offer offer) {
		if (offer.owner.equals(me())) deleteMine("o/" + me() + "/" + offer.id);
	}

	/** Enters a finished trade from one of my offers; the rest of the offer stays up. */
	public String recordDeal(Offer offer, String partner, int amount, long price) {
		if (!offer.owner.equals(me())) return "Nicht dein Angebot";
		if (!partner.matches("[A-Za-z0-9_]{2,16}")) return "Name des Handelspartners fehlt";
		if (partner.equalsIgnoreCase(myName())) return "Du kannst nicht mit dir selbst handeln";
		if (amount < 1 || amount > offer.amount) return "Anzahl 1–" + offer.amount;
		if (price < 1 || price > Spawners.MAX_PRICE) return "Ungültiger Preis";
		String id = newId();
		JsonObject d = new JsonObject();
		d.addProperty("u", me().toString());
		d.addProperty("nm", myName());
		d.addProperty("pn", partner);
		d.addProperty("sv", offer.server);
		d.addProperty("id", id);
		d.addProperty("ty", offer.type);
		d.addProperty("s", offer.sell);
		d.addProperty("n", amount);
		d.addProperty("p", price);
		d.addProperty("ts", System.currentTimeMillis());
		publishMine("d/" + me() + "/" + id, d);
		if (amount >= offer.amount) {
			removeOffer(offer);
		} else {
			JsonObject rest = config.published.get("o/" + me() + "/" + offer.id);
			if (rest != null) {
				rest = rest.deepCopy();
				rest.addProperty("n", offer.amount - amount);
				publishMine("o/" + me() + "/" + offer.id, rest);
			}
		}
		return null;
	}

	public String vouch(UUID target, String targetName, boolean positive, String text) {
		if (target.equals(me())) return "Du kannst dich nicht selbst vouchen";
		text = clean(text, 120);
		if (text.length() < 3) return "Schreib kurz, wie der Handel lief";
		JsonObject v = new JsonObject();
		v.addProperty("u", me().toString());
		v.addProperty("nm", myName());
		v.addProperty("to", target.toString());
		v.addProperty("tn", targetName);
		v.addProperty("pos", positive);
		v.addProperty("tx", text);
		v.addProperty("ts", System.currentTimeMillis());
		publishMine("v/" + target + "/" + me(), v);
		return null;
	}

	public void removeVouch(UUID target) {
		deleteMine("v/" + target + "/" + me());
	}

	/** Chat in a channel ({@link #GLOBAL} or a server channel). */
	public String sendChat(String channel, String text) {
		text = clean(text, 200);
		if (text.isEmpty()) return null;
		if (channel.isEmpty()) return "Tritt erst einem Server bei";
		if (!relay.connected()) return "Keine Verbindung zum Relay";
		long now = System.currentTimeMillis();
		if (now - lastChatAt < 900) return "Nicht so schnell";
		lastChatAt = now;
		JsonObject o = new JsonObject();
		o.addProperty("u", me().toString());
		o.addProperty("nm", myName());
		o.addProperty("sv", server());
		o.addProperty("tx", text);
		o.addProperty("ts", now);
		relay.publish("g/" + channel + "/" + me(), o, true);
		return null;
	}

	public String sendDm(UUID to, String text) {
		text = clean(text, 200);
		if (text.isEmpty()) return null;
		if (to.equals(me())) return "Das bist du";
		if (!relay.connected()) return "Keine Verbindung zum Relay";
		long now = System.currentTimeMillis();
		if (now - lastChatAt < 500) return "Nicht so schnell";
		lastChatAt = now;
		JsonObject o = new JsonObject();
		o.addProperty("u", me().toString());
		o.addProperty("nm", myName());
		o.addProperty("to", to.toString());
		o.addProperty("tx", text);
		o.addProperty("ts", now);
		// Retained: the newest message also reaches them if they're offline right now.
		relay.publish("m/" + to + "/" + me(), o, true);
		addMessage(dms.computeIfAbsent(to, k -> new ArrayList<>()), new ChatMsg(me(), myName(), text, now, true));
		return null;
	}

	/** Sends the server's TPA command and tells the player on the relay so they can accept in the menu. */
	public void tpa(UUID to, String name) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		if (to.equals(me())) return;
		if (!sameServer(to)) {
			String sv = serverOf(to);
			say(sv.isEmpty() ? name + " ist gerade nicht online" : name + " ist auf " + sv);
			return;
		}
		mc.player.connection.sendCommand(config.tpaCommand + " " + name);
		JsonObject o = new JsonObject();
		o.addProperty("u", me().toString());
		o.addProperty("nm", myName());
		o.addProperty("sv", server());
		o.addProperty("ts", System.currentTimeMillis());
		relay.publish("t/" + to, o, false);
		say("TPA an " + name + " gesendet");
	}

	public void acceptTpa(TpaRequest r) {
		Minecraft mc = Minecraft.getInstance();
		tpas.remove(r);
		if (mc.player == null) return;
		mc.player.connection.sendCommand(config.tpaAcceptCommand + " " + r.name);
		say("TPA von " + r.name + " angenommen");
	}

	public void declineTpa(TpaRequest r) {
		tpas.remove(r);
	}

	// ================================================================ relay

	public void tick() {
		String sv = server();
		long now = System.currentTimeMillis();
		if (!sv.equals(lastServer) || now - lastHeartbeat > HEARTBEAT_MS) {
			lastServer = sv;
			lastHeartbeat = now;
			heartbeat();
		}
	}

	private void heartbeat() {
		if (!relay.connected()) return;
		JsonObject o = new JsonObject();
		o.addProperty("u", me().toString());
		o.addProperty("nm", myName());
		o.addProperty("sv", server());
		o.addProperty("ts", System.currentTimeMillis());
		relay.publish("p/" + me(), o, true);
	}

	/** Quitting: mark me offline right away instead of waiting for the broker. */
	public void goodbye() {
		JsonObject o = new JsonObject();
		o.addProperty("u", me().toString());
		o.addProperty("nm", myName());
		o.addProperty("off", true);
		relay.publish("p/" + me(), o, true);
		relay.flush();
	}

	@Override
	public void onConnected() {
		// The broker may have lost my retained messages: send them again.
		for (Map.Entry<String, JsonObject> e : config.published.entrySet()) relay.publish(e.getKey(), e.getValue(), true);
		lastHeartbeat = 0;
	}

	@Override
	public void onMessage(String topic, JsonObject o, boolean stored) {
		String[] part = topic.split("/");
		try {
			switch (part[0]) {
				case "o" -> onOffer(part, o);
				case "d" -> onDeal(part, o);
				case "v" -> onVouch(part, o);
				case "p" -> onPresence(part, o, stored);
				case "g" -> onChat(part, o, stored);
				case "m" -> onDm(part, o, stored);
				case "t" -> onTpa(part, o, stored);
				default -> { }
			}
		} catch (RuntimeException ignored) {
			// malformed: anyone can post to a public broker
		}
	}

	/** Checks that the message's author is the one the topic belongs to. */
	private UUID author(JsonObject o, String topicOwner) {
		UUID u = UUID.fromString(str(o, "u"));
		if (!u.toString().equals(topicOwner)) throw new IllegalArgumentException("wrong owner");
		String nm = playerName(str(o, "nm"));
		if (!nm.isEmpty() && !u.equals(me())) names.put(u, nm);
		return u;
	}

	private void onOffer(String[] part, JsonObject o) {
		if (part.length != 3) return;
		String key = part[1] + "/" + part[2];
		if (o == null) {
			offers.remove(key);
			return;
		}
		UUID u = author(o, part[1]);
		Spawners.Type type = Spawners.get(str(o, "ty"));
		int n = o.get("n").getAsInt();
		long p = o.get("p").getAsLong();
		if (type == null || n < 1 || n > 100_000 || p < 1 || p > Spawners.MAX_PRICE || !str(o, "id").equals(part[2])) return;
		offers.put(key, new Offer(part[2], u, playerName(str(o, "nm")), clean(str(o, "sv"), 64), type.key(),
				o.get("s").getAsBoolean(), n, p, clean(str(o, "no"), 60), Math.min(System.currentTimeMillis(), o.get("ts").getAsLong())));
	}

	private void onDeal(String[] part, JsonObject o) {
		if (part.length != 3) return;
		String key = part[1] + "/" + part[2];
		if (o == null) {
			deals.remove(key);
			return;
		}
		UUID u = author(o, part[1]);
		Spawners.Type type = Spawners.get(str(o, "ty"));
		int n = o.get("n").getAsInt();
		long p = o.get("p").getAsLong();
		String partner = playerName(str(o, "pn"));
		if (type == null || n < 1 || n > 100_000 || p < 1 || p > Spawners.MAX_PRICE || partner.isEmpty()) return;
		deals.put(key, new Deal(part[2], u, playerName(str(o, "nm")), partner, clean(str(o, "sv"), 64), type.key(),
				o.get("s").getAsBoolean(), n, p, Math.min(System.currentTimeMillis(), o.get("ts").getAsLong())));
	}

	private void onVouch(String[] part, JsonObject o) {
		if (part.length != 3) return;
		String key = part[1] + "/" + part[2];
		if (o == null) {
			vouches.remove(key);
			return;
		}
		UUID from = author(o, part[2]);
		UUID target = UUID.fromString(part[1]);
		if (!str(o, "to").equals(part[1]) || target.equals(from)) return;
		String tn = playerName(str(o, "tn"));
		if (!tn.isEmpty() && !target.equals(me())) names.putIfAbsent(target, tn);
		vouches.put(key, new Vouch(target, tn, from, playerName(str(o, "nm")), o.get("pos").getAsBoolean(),
				clean(str(o, "tx"), 120), Math.min(System.currentTimeMillis(), o.get("ts").getAsLong())));
	}

	private void onPresence(String[] part, JsonObject o, boolean stored) {
		if (part.length != 2 || o == null) return;
		UUID u = author(o, part[1]);
		if (o.has("off") && o.get("off").getAsBoolean()) {
			presence.put(u, new Presence("", false, System.currentTimeMillis()));
			return;
		}
		long now = System.currentTimeMillis();
		long seen = stored && o.has("ts") ? Math.min(now, o.get("ts").getAsLong()) : now;
		presence.put(u, new Presence(clean(str(o, "sv"), 64), true, seen));
	}

	private void onChat(String[] part, JsonObject o, boolean stored) {
		if (part.length != 3 || o == null) return;
		String ch = part[1];
		// Only the global chat and the chat of the server I'm on.
		if (!ch.equals(GLOBAL) && !ch.equals(serverChannel(server()))) return;
		UUID u = author(o, part[2]);
		long now = System.currentTimeMillis();
		long ts = o.get("ts").getAsLong();
		if (stored && now - ts > CHAT_MAX_AGE) return;
		String text = clean(str(o, "tx"), 200);
		if (text.isEmpty() || !seen.add(u + ":" + ts)) return;
		List<ChatMsg> list = channels.computeIfAbsent(ch, k -> new ArrayList<>());
		addMessage(list, new ChatMsg(u, playerName(str(o, "nm")), text, Math.min(now, ts), u.equals(me())));
		if (!stored && !u.equals(me()) && !viewing.equals(ch)) unread.merge(ch, 1, Integer::sum);
	}

	private void onDm(String[] part, JsonObject o, boolean stored) {
		if (part.length != 3 || o == null) return;
		if (!part[1].equals(me().toString())) return;
		UUID u = author(o, part[2]);
		if (u.equals(me())) return;
		long ts = o.get("ts").getAsLong();
		String text = clean(str(o, "tx"), 200);
		if (text.isEmpty() || !seen.add(u + ":" + ts)) return;
		String name = playerName(str(o, "nm"));
		addMessage(dms.computeIfAbsent(u, k -> new ArrayList<>()), new ChatMsg(u, name, text, Math.min(System.currentTimeMillis(), ts), false));
		String key = "dm:" + u;
		if (!viewing.equals(key)) {
			unread.merge(key, 1, Integer::sum);
			if (!stored) notifier.notify("Nachricht von " + name, text, u);
		}
	}

	private void onTpa(String[] part, JsonObject o, boolean stored) {
		if (stored || part.length != 2 || o == null) return;
		UUID u = UUID.fromString(str(o, "u"));
		if (u.equals(me())) return;
		String name = playerName(str(o, "nm"));
		if (name.isEmpty()) return;
		names.put(u, name);
		tpas.removeIf(t -> t.from.equals(u));
		tpas.add(new TpaRequest(u, name, System.currentTimeMillis()));
		notifier.notify("TPA-Anfrage", name + " möchte sich zu dir teleportieren", u);
	}

	private static void addMessage(List<ChatMsg> list, ChatMsg m) {
		// Keep sorted by time: stored messages can arrive out of order.
		int i = list.size();
		while (i > 0 && list.get(i - 1).ts > m.ts) i--;
		list.add(i, m);
		while (list.size() > CHAT_KEEP) list.remove(0);
	}

	// ================================================================ untrusted text

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	/** Minecraft names only; anything else becomes "". */
	private static String playerName(String s) {
		return s.matches("[A-Za-z0-9_]{1,16}") ? s : "";
	}

	/** Strips formatting codes and control characters, limits length. */
	public static String clean(String s, int max) {
		if (s == null) return "";
		String out = s.replaceAll("§.?", "").replaceAll("\\p{Cntrl}", "").trim();
		return out.length() > max ? out.substring(0, max) : out;
	}
}
