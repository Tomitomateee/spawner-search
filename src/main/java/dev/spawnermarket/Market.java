package dev.spawnermarket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.spawnermarket.MarketConfig.Msg;
import dev.spawnermarket.MarketConfig.Trade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the market knows: offers, deals, vouches, trade chats, TPA requests and who is
 * online where. Fed by {@link MarketRelay}; only touched on the game thread.
 *
 * <p>Deals can't be typed in: a trade chat is opened from an offer, and the deal is closed by
 * vouching the other player (vouching is only possible in a trade). Both clients then publish the deal under their own identity, and
 * only deals confirmed by seller and buyer count — so nobody can put someone else's name on one.
 */
public final class Market implements MarketRelay.Handler {
	public record Offer(String id, UUID owner, String name, String server, String type, boolean sell,
	                    int amount, long price, String note, long ts) {}

	/** A finished trade, confirmed by both sides. */
	public record Deal(String trade, UUID seller, String sellerName, UUID buyer, String buyerName, String server,
	                   String type, int amount, long price, long ts) {}

	/** {@code rating}: 1 positive, 0 neutral (trade didn't happen), -1 negative. */
	public record Vouch(UUID target, String targetName, UUID from, String fromName, int rating, String trade, String text, long ts) {
		public boolean positive() { return rating > 0; }
	}

	public record TpaRequest(UUID from, String name, long at) {}

	private record Presence(String server, boolean online, long seen) {}

	private static final long OFFER_MAX_AGE = 7 * 86_400_000L, DEAL_MAX_AGE = 30 * 86_400_000L;
	private static final long ONLINE_MS = 5 * 60_000L, TPA_MS = 120_000L;
	private static final long HEARTBEAT_MS = 120_000L;
	/** Offers and deals older than this are re-sends (after a reconnect), not news. */
	private static final long FRESH_MS = 15 * 60_000L;
	/** How many of my earlier messages travel along with each new one. */
	private static final int HISTORY = 5;
	private static final int CHAT_KEEP = 150, CLOSED_KEEP = 300;

	private final MarketConfig config;
	private final MarketRelay relay;
	private final Map<String, Offer> offers = new HashMap<>();
	/** "trade/publisher" → that side's record of the deal. */
	private final Map<String, Deal> dealRecords = new HashMap<>();
	/** "target/from/trade" → vouch: one per trade, so vouches from the same player stack. */
	private final Map<String, Vouch> vouches = new HashMap<>();
	private final Map<UUID, Presence> presence = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final List<TpaRequest> tpas = new ArrayList<>();
	/** Other players' clan tags, from their online status. */
	private final Map<UUID, List<Clan.Seg>> clans = new HashMap<>();
	/**
	 * "Name belongs to clan", read from "/clan info": kept only in memory, so it lasts until the
	 * game is restarted and is asked again after that (never saved to a file).
	 */
	private static final Map<String, List<Clan.Seg>> CLAN_BY_NAME = new HashMap<>();
	/** Names for which "/clan info" said "no clan" since the game started. */
	private static final Set<String> NO_CLAN_NAMES = new HashSet<>();
	/**
	 * My tag as the relay still has it from before the restart: the others keep seeing it until I
	 * run /clan info again (which then decides). Only shared, never shown as "my clan" here.
	 */
	private List<Clan.Seg> carriedClan;
	/** Until when an answer to "/clan info" is expected (I sent or typed the command). */
	private long clanInfoUntil;
	/** Since joining hugoSMP: "/clan info" was answered (clan or "no clan"), and when it was sent. */
	private boolean clanAnswered;
	private long clanAskedAt;
	/** Offers I was already alarmed about this session. */
	private final Set<String> alarmed = new HashSet<>();
	/** Which trade chat the GUI is showing right now (no unread count for it). */
	private String viewing = "";
	private String message;
	private long messageAt, lastChatAt, lastHeartbeat;
	private String lastServer = "";
	/** Just joined hugoSMP: the next H shows the welcome window (clan, Discord) first. */
	private boolean welcomePending;
	/** Goes up with every chat message, so the GUI knows when to re-wrap the lines. */
	private int chatVersion;
	/** Called for new offers and deals, trade chats and TPA requests. */
	private Notifier notifier = n -> { };

	/**
	 * Something worth a banner. {@code offer}, {@code deal} and {@code trade} are only set for the
	 * kinds they belong to.
	 */
	public record Notice(Kind kind, UUID from, String name, String text, Offer offer, Deal deal, Trade trade) {
		public enum Kind {
			/** Someone put up a new offer. */
			OFFER,
			/** Seller and buyer both confirmed a deal (one I'm not part of). */
			DEAL,
			/** Someone opened a trade chat with me. */
			TRADE_START,
			TRADE_MESSAGE,
			/** The other side vouched me positively: the deal is done. */
			TRADE_DONE,
			/** The other side ended the trade without a deal. */
			TRADE_CLOSED,
			TPA,
			/** An offer reached one of my price alarms. */
			ALARM
		}
	}

	public interface Notifier {
		void notify(Notice n);
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

	/** Best known name of a player. */
	public String name(UUID uuid) {
		if (uuid.equals(me())) return myName();
		PlayerInfo info = tabInfo(uuid);
		if (info != null) return info.getProfile().name();
		String known = names.get(uuid);
		if (known != null) return known;
		// Not seen online yet: any vouch, offer, deal or trade chat with them names them too.
		for (Vouch v : vouches.values()) {
			if (v.target.equals(uuid) && !v.targetName.isEmpty()) return remember(uuid, v.targetName);
			if (v.from.equals(uuid) && !v.fromName.isEmpty()) return remember(uuid, v.fromName);
		}
		for (Offer o : offers.values()) if (o.owner.equals(uuid) && !o.name.isEmpty()) return remember(uuid, o.name);
		for (Deal d : dealRecords.values()) {
			if (d.seller.equals(uuid)) return remember(uuid, d.sellerName);
			if (d.buyer.equals(uuid)) return remember(uuid, d.buyerName);
		}
		String id = uuid.toString();
		for (Trade t : config.trades.values()) {
			if (id.equals(t.owner) && t.ownerName != null && !t.ownerName.isEmpty()) return remember(uuid, t.ownerName);
			if (id.equals(t.partner) && t.partnerName != null && !t.partnerName.isEmpty()) return remember(uuid, t.partnerName);
		}
		return "?";
	}

	private String remember(UUID uuid, String name) {
		names.put(uuid, name);
		return name;
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
		// Highest price first, newest first among equal prices.
		list.sort(Comparator.comparingLong(Offer::price).thenComparingLong(Offer::ts).reversed());
		return list;
	}

	/** All my own offers, on every server. */
	public List<Offer> myOffers() {
		UUID me = me();
		List<Offer> list = new ArrayList<>();
		for (Offer o : offers("")) if (o.owner.equals(me)) list.add(o);
		return list;
	}

	/** Ids of trades whose deal both sides confirmed (no age limit; used to check vouches). */
	private Set<String> confirmedTrades() {
		Map<String, Deal> bySeller = new HashMap<>();
		Set<String> out = new HashSet<>();
		for (Map.Entry<String, Deal> e : dealRecords.entrySet()) {
			Deal d = e.getValue();
			if (e.getKey().endsWith("/" + d.seller)) bySeller.put(d.trade, d);
		}
		for (Map.Entry<String, Deal> e : dealRecords.entrySet()) {
			Deal d = e.getValue(), s = bySeller.get(d.trade);
			if (e.getKey().endsWith("/" + d.buyer) && s != null && s.buyer.equals(d.buyer) && s.seller.equals(d.seller)) out.add(d.trade);
		}
		return out;
	}

	/** A positive vouch only counts when its trade's deal is confirmed by both sides. */
	public boolean counts(Vouch v) {
		return !v.positive() || confirmedTrades().contains(v.trade);
	}

	/** Deals confirmed by both seller and buyer, newest first. */
	public List<Deal> deals(String server) {
		long now = System.currentTimeMillis();
		Map<String, Deal> bySeller = new HashMap<>(), byBuyer = new HashMap<>();
		for (Map.Entry<String, Deal> e : dealRecords.entrySet()) {
			Deal d = e.getValue();
			String publisher = e.getKey().substring(e.getKey().indexOf('/') + 1);
			if (publisher.equals(d.seller.toString())) bySeller.put(d.trade, d);
			else if (publisher.equals(d.buyer.toString())) byBuyer.put(d.trade, d);
		}
		List<Deal> list = new ArrayList<>();
		for (Deal d : bySeller.values()) {
			Deal b = byBuyer.get(d.trade);
			// Both sides must name the same two players and the same trade.
			if (b == null || !b.seller.equals(d.seller) || !b.buyer.equals(d.buyer) || !b.type.equals(d.type)) continue;
			if (now - d.ts > DEAL_MAX_AGE) continue;
			if (!server.isEmpty() && !server.equals(d.server)) continue;
			list.add(d);
		}
		list.sort(Comparator.comparingLong(Deal::ts).reversed());
		return list;
	}

	/** Price overview for one spawner type. -1 = unknown. */
	public record PriceStats(Spawners.Type type, long average, int dealCount, long highestSell, long highestBuy, int offerCount) {}

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
			long sell = -1, buy = -1;
			int offerCount = 0;
			for (Offer o : os) {
				if (!o.type.equals(t.key())) continue;
				offerCount++;
				if (o.sell && o.price > sell) sell = o.price;
				if (!o.sell && o.price > buy) buy = o.price;
			}
			if (count == 0 && offerCount == 0) continue;
			out.add(new PriceStats(t, pieces > 0 ? sum / pieces : -1, count, sell, buy, offerCount));
		}
		// Most traded first.
		out.sort(Comparator.comparingInt((PriceStats p) -> p.dealCount + p.offerCount).reversed());
		return out;
	}

	public PriceStats price(String server, String type) {
		for (PriceStats p : prices(server)) if (p.type.key().equals(type)) return p;
		return null;
	}

	/**
	 * Every vouch that counts: one per person and trade (both sides vouch each other, so a trade
	 * has two). Vouches from 1.2.0 have no trade and all count.
	 */
	private List<Vouch> validVouches() {
		Map<String, Vouch> first = new HashMap<>();
		List<Vouch> list = new ArrayList<>();
		for (Vouch v : vouches.values()) {
			if (v.trade.isEmpty()) list.add(v);
			else first.merge(v.trade + "/" + v.from, v, (a, b) -> a.ts <= b.ts ? a : b);
		}
		list.addAll(first.values());
		return list;
	}

	public List<Vouch> vouchesFor(UUID target) {
		List<Vouch> list = new ArrayList<>();
		for (Vouch v : validVouches()) if (v.target.equals(target)) list.add(v);
		list.sort(Comparator.comparingLong(Vouch::ts).reversed());
		return list;
	}

	/** {positive, neutral, negative}; every trade's vouch counts, positives only with a confirmed deal. */
	public int[] score(UUID target) {
		Set<String> confirmed = confirmedTrades();
		int pos = 0, neu = 0, neg = 0;
		for (Vouch v : validVouches()) {
			if (!v.target.equals(target)) continue;
			if (v.rating > 0) { if (confirmed.contains(v.trade)) pos++; }
			else if (v.rating == 0) neu++;
			else neg++;
		}
		return new int[] { pos, neu, neg };
	}

	/** Players with the most positive vouches, for the right panel when nobody is selected. */
	public List<UUID> topVouched(int max) {
		Set<String> confirmed = confirmedTrades();
		Map<UUID, Integer> net = new HashMap<>();
		for (Vouch v : validVouches()) {
			if (v.rating > 0 && !confirmed.contains(v.trade)) continue;
			net.merge(v.target, v.rating, Integer::sum);
		}
		List<UUID> list = new ArrayList<>(net.keySet());
		list.sort(Comparator.comparingInt((UUID u) -> net.get(u)).reversed());
		return list.size() > max ? list.subList(0, max) : list;
	}

	/** Open trade chats, most recent first. */
	public List<Trade> trades() {
		List<Trade> list = new ArrayList<>(config.trades.values());
		list.sort(Comparator.comparingLong(Trade::lastActivity).reversed());
		return list;
	}

	public Trade trade(String id) {
		return id == null ? null : config.trades.get(id);
	}

	/** My open trade about this offer (I'm the one who asked), or null. */
	public Trade tradeFor(Offer o) {
		String me = me().toString();
		for (Trade t : config.trades.values()) if (o.id().equals(t.offerId) && me.equals(t.partner)) return t;
		return null;
	}

	/** The newest open trade with this player, or null. */
	public Trade tradeWith(UUID other) {
		String me = me().toString(), o = other.toString();
		for (Trade t : trades()) if (o.equals(t.other(me))) return t;
		return null;
	}

	public void setViewing(String tradeId) {
		viewing = tradeId == null ? "" : tradeId;
		Trade t = config.trades.get(viewing);
		if (t != null && t.unread > 0) {
			t.unread = 0;
			config.save();
		}
	}

	public int unreadTrades() {
		int n = 0;
		for (Trade t : config.trades.values()) n += t.unread;
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

	/**
	 * A deal on one of my offers: what's left stays up (10 offered, 2 bought → 8 left), a sold-out
	 * offer is deleted.
	 */
	private void reduceMyOffer(String offerId, int traded) {
		String topic = "o/" + me() + "/" + offerId;
		JsonObject cur = config.published.get(topic);
		if (cur == null) return;
		int left = cur.get("n").getAsInt() - traded;
		if (left <= 0) {
			deleteMine(topic);
			return;
		}
		JsonObject rest = cur.deepCopy();
		rest.addProperty("n", left);
		publishMine(topic, rest);
	}

	/**
	 * Opens a trade chat about someone else's offer (or returns the one already open), and tells
	 * the owner so the chat shows up for them too.
	 */
	public Trade startTrade(Offer o, int amount) {
		String me = me().toString();
		if (o.owner.equals(me())) return null;
		amount = Math.max(1, Math.min(o.amount, amount));
		for (Trade t : config.trades.values()) if (o.id.equals(t.offerId) && me.equals(t.partner)) return t;
		Trade t = new Trade();
		t.id = newId();
		t.offerId = o.id;
		t.owner = o.owner.toString();
		t.ownerName = o.name;
		t.partner = me;
		t.partnerName = myName();
		t.type = o.type;
		t.server = o.server;
		t.sell = o.sell;
		// Only the amount I picked: the rest of the offer stays for others.
		t.amount = amount;
		t.price = o.price;
		t.created = System.currentTimeMillis();
		t.messages.add(new Msg("", "", "Trade gestartet. Ein Vouch schließt den Deal ab.", t.created));
		config.trades.put(t.id, t);
		config.save();
		sendTrade(t, "start", "", false);
		return t;
	}

	public String sendTradeMessage(Trade t, String text) {
		text = clean(text, 200);
		if (text.isEmpty()) return null;
		if (!relay.connected()) return "Keine Verbindung zum Relay";
		long now = System.currentTimeMillis();
		if (now - lastChatAt < 500) return "Nicht so schnell";
		lastChatAt = now;
		// Same time in the packet and in my chat, so the copy in the next packet's history matches.
		sendTrade(t, "msg", text, false, now);
		addSent(t, text, now);
		return null;
	}

	/** Puts a message I sent at the end of the trade chat. */
	public void addSent(Trade t, String text, long sent) {
		addMessage(t, new Msg(me().toString(), myName(), text, arrivalTime(t), sent));
		config.save();
	}

	public int chatVersion() {
		return chatVersion;
	}

	private void sendTrade(Trade t, String kind, String text, boolean deal) {
		sendTrade(t, kind, text, deal, System.currentTimeMillis());
	}

	/** Every trade message carries the offer, so the first one that arrives can open the chat. */
	private void sendTrade(Trade t, String kind, String text, boolean deal, long ts) {
		String me = me().toString();
		String to = t.other(me);
		JsonObject o = new JsonObject();
		o.addProperty("u", me().toString());
		o.addProperty("nm", myName());
		o.addProperty("to", to);
		o.addProperty("k", kind);
		o.addProperty("tr", t.id);
		o.addProperty("tx", text);
		o.addProperty("deal", deal);
		o.addProperty("oid", t.offerId);
		o.addProperty("ow", t.owner);
		o.addProperty("on", t.ownerName);
		o.addProperty("ty", t.type);
		o.addProperty("s", t.sell);
		o.addProperty("n", t.amount);
		o.addProperty("p", t.price);
		o.addProperty("sv", t.server);
		o.addProperty("ts", ts);
		// 2 = both sides vouch before the trade closes (1.3.1+).
		o.addProperty("ver", TRADE_PROTOCOL);
		if (kind.equals("msg")) {
			// My last messages ride along (oldest first): the broker only keeps the newest one,
			// so whatever they missed – offline, a dropped connection – arrives with the next.
			List<Msg> mine = new ArrayList<>();
			for (Msg m : t.messages) if (me.equals(m.from) && m.sent > 0 && m.sent != ts) mine.add(m);
			JsonArray h = new JsonArray();
			for (Msg m : mine.subList(Math.max(0, mine.size() - HISTORY), mine.size())) {
				JsonObject e = new JsonObject();
				e.addProperty("tx", m.text);
				e.addProperty("ts", m.sent);
				h.add(e);
			}
			o.add("h", h);
		}
		// Retained: the newest message also reaches them if they're offline right now.
		relay.publish("m/" + to + "/" + me, o, true);
	}

	/** Publishes my side of the deal; it counts once the other side has published theirs. */
	private void publishDeal(Trade t) {
		boolean iOwn = me().toString().equals(t.owner);
		UUID seller = UUID.fromString(t.sell ? t.owner : t.partner), buyer = UUID.fromString(t.sell ? t.partner : t.owner);
		String sellerName = t.sell ? t.ownerName : t.partnerName, buyerName = t.sell ? t.partnerName : t.ownerName;
		JsonObject d = new JsonObject();
		d.addProperty("u", me().toString());
		d.addProperty("nm", myName());
		d.addProperty("tr", t.id);
		d.addProperty("so", seller.toString());
		d.addProperty("sn", sellerName);
		d.addProperty("bu", buyer.toString());
		d.addProperty("bn", buyerName);
		d.addProperty("sv", t.server);
		d.addProperty("ty", t.type);
		d.addProperty("n", t.amount);
		d.addProperty("p", t.price);
		d.addProperty("ts", System.currentTimeMillis());
		publishMine("d/" + me() + "/" + t.id, d);
		if (iOwn) reduceMyOffer(t.offerId, t.amount);
	}

	/** Deletes the chat; a new deal with the same player gets a new chat. */
	private void forgetTrade(Trade t) {
		config.trades.remove(t.id);
		config.closedTrades.add(t.id);
		while (config.closedTrades.size() > CLOSED_KEEP) config.closedTrades.remove(0);
		config.save();
	}

	/** Ends a trade without a deal (both chats are deleted). */
	public void cancelTrade(Trade t) {
		sendTrade(t, "close", "", false);
		forgetTrade(t);
	}

	/**
	 * Vouches the other player of an open trade: only in a trade, after at least two chat
	 * messages (one from each side), and once per trade. The trade closes when both have vouched
	 * (see {@link #checkTradeDone}). Vouches from the same player still stack across trades. Positive: the deal went through, it is
	 * recorded for both and the chat is deleted. Neutral: the trade didn't happen, the chat is
	 * deleted but the offer stays. Negative: no deal, the chat is deleted.
	 */
	/** Messages both sides wrote in a trade chat (the mod's own notes don't count). */
	public static final int VOUCH_MIN_MESSAGES = 2;
	private static final int TRADE_PROTOCOL = 2;

	/** How many real messages the trade chat has, and whether both players wrote something. */
	public static int chatMessages(Trade t) {
		int n = 0;
		for (Msg m : t.messages) if (!m.from.isEmpty()) n++;
		return n;
	}

	public static boolean bothWrote(Trade t) {
		return t.messages.stream().anyMatch(m -> m.from.equals(t.owner))
				&& t.messages.stream().anyMatch(m -> m.from.equals(t.partner));
	}

	/** Why I can't vouch in this trade yet, or null when I can. */
	public String vouchBlocker(Trade t) {
		String me = me().toString();
		if (vouchIn(t, me()) != null) return "Warte auf den Vouch von " + t.otherName(me);
		// The partner already vouched, so the trade happened: answering it must never be blocked,
		// not even when some of their messages didn't reach me.
		if (vouchIn(t, UUID.fromString(t.other(me))) != null) return null;
		if (chatMessages(t) < VOUCH_MIN_MESSAGES || !bothWrote(t)) return "Erst mind. " + VOUCH_MIN_MESSAGES + " Nachrichten im Chat";
		return null;
	}

	/** {@code from}'s vouch in this trade, or null. */
	public Vouch vouchIn(Trade t, UUID from) {
		UUID other = from.equals(me()) ? UUID.fromString(t.other(me().toString())) : me();
		for (Vouch v : vouches.values()) if (t.id.equals(v.trade) && v.from.equals(from) && v.target.equals(other)) return v;
		return null;
	}

	/**
	 * A trade is done once both players vouched each other. Both positive = a deal (each side
	 * publishes its record, the offer goes); otherwise no deal. Either way the chat is deleted.
	 */
	private void checkTradeDone(Trade t) {
		UUID other = UUID.fromString(t.other(me().toString()));
		Vouch mine = vouchIn(t, me()), theirs = vouchIn(t, other);
		if (mine == null || theirs == null || !config.trades.containsKey(t.id)) return;
		boolean deal = mine.positive() && theirs.positive();
		if (deal) publishDeal(t);
		forgetTrade(t);
		String name = t.otherName(me().toString());
		notifier.notify(new Notice(deal ? Notice.Kind.TRADE_DONE : Notice.Kind.TRADE_CLOSED, other, name, "", null, null, t));
		say(deal ? "Deal mit " + name + " abgeschlossen – ihr habt beide gevoucht"
				: "Trade mit " + name + " abgeschlossen (kein Deal)");
	}

	public String vouch(UUID target, String targetName, int rating, String text, Trade trade) {
		if (target.equals(me())) return "Du kannst dich nicht selbst vouchen";
		if (trade == null || !target.toString().equals(trade.other(me().toString()))) trade = tradeWith(target);
		if (trade == null) return "Vouchen geht nur nach einem Trade";
		String blocked = vouchBlocker(trade);
		if (blocked != null) return blocked;
		rating = Integer.signum(rating);
		// Neutral = the trade didn't happen: nothing to describe, so no text.
		text = rating == 0 ? "" : clean(text, 120);
		if (rating != 0 && text.length() < 3) return "Schreib kurz, wie der Handel lief";
		JsonObject v = new JsonObject();
		v.addProperty("u", me().toString());
		v.addProperty("nm", myName());
		v.addProperty("to", target.toString());
		v.addProperty("tn", targetName);
		v.addProperty("r", rating);
		v.addProperty("pos", rating > 0);
		v.addProperty("tr", trade.id);
		v.addProperty("tx", text);
		v.addProperty("ts", System.currentTimeMillis());
		publishMine("v/" + target + "/" + me() + "/" + trade.id, v);
		if (config.trades.containsKey(trade.id)) {
			// Still open: the partner hasn't vouched yet.
			String label = rating > 0 ? "positiv" : rating == 0 ? "neutral" : "negativ";
			addMessage(trade, new Msg("", "", "Du hast " + label + " gevoucht. Der Trade ist abgeschlossen, sobald "
					+ targetName + " auch für dich vouched.", System.currentTimeMillis()));
			config.save();
			say("Vouch gespeichert – warte auf " + targetName);
		}
		return null;
	}

	public void removeVouch(Vouch v) {
		if (!v.from.equals(me())) return;
		// While the trade is open, the vouch is part of closing it: it stays.
		if (config.trades.containsKey(v.trade)) {
			say("Der Vouch gehört zu einem offenen Trade");
			return;
		}
		// Vouches from 1.2.x have no trade in their topic.
		String topic = "v/" + v.target + "/" + me() + (v.trade.isEmpty() ? "" : "/" + v.trade);
		if (!config.published.containsKey(topic)) topic = "v/" + v.target + "/" + me();
		deleteMine(topic);
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
			if (!sv.equals(lastServer)) {
				welcomePending = sv.contains("hugosmp");
				// Every join asks again.
				clanAnswered = false;
				clanAskedAt = 0;
			}
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
		// "cl": my tag; an empty list = I'm in no clan; missing = not asked yet since the game
		// started – then the others keep showing the tag they know.
		List<Clan.Seg> mine = myClanTag();
		if (mine != null) o.add("cl", Clan.toJson(mine));
		else if (NO_CLAN_NAMES.contains(myName().toLowerCase(Locale.ROOT))) o.add("cl", new JsonArray());
		else if (carriedClan != null) o.add("cl", Clan.toJson(carriedClan));
		relay.publish("p/" + me(), o, true);
	}

	// ================================================================ clan

	/** Whether the welcome window should come before the market (once per join of hugoSMP). */
	public boolean welcomePending() {
		return welcomePending && server().contains("hugosmp");
	}

	public void welcomeDone() {
		welcomePending = false;
	}

	/** A player's clan tag, or null. */
	public List<Clan.Seg> clanTag(UUID uuid) {
		return uuid.equals(me()) ? myClanTag() : clans.get(uuid);
	}

	/** My clan tag for the name I play with right now, or null. */
	public List<Clan.Seg> myClanTag() {
		return CLAN_BY_NAME.get(myName().toLowerCase(Locale.ROOT));
	}

	/** Sends "/clan info"; the answer in chat sets my clan tag. */
	public boolean requestClanInfo() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || server().isEmpty()) {
			say("Geht nur auf einem Server");
			return false;
		}
		mc.player.connection.sendCommand("clan info");
		expectClanInfo();
		say("/clan info gesendet …");
		return true;
	}

	/** "/clan info" was sent (by me or typed in chat): read the answer of the next seconds. */
	public void expectClanInfo() {
		clanAskedAt = System.currentTimeMillis();
		clanInfoUntil = clanAskedAt + 8000;
	}

	/**
	 * "/clan info" was answered since joining: the server named my clan or said I have none. If
	 * the answer can't be read (8 s after sending), it counts as done too, so nobody gets stuck.
	 */
	public boolean clanInfoDone() {
		return clanAnswered || (clanAskedAt > 0 && System.currentTimeMillis() - clanAskedAt > 8000);
	}

	/** Sent, answer not there yet. */
	public boolean clanInfoWaiting() {
		return !clanAnswered && clanAskedAt > 0 && System.currentTimeMillis() - clanAskedAt <= 8000;
	}

	/** The server answered "/clan info" with a clan (not with "no clan"). */
	public boolean clanFound() {
		return clanAnswered && myClanTag() != null;
	}

	/** Every chat line from the server: picks out the tag while an answer to "/clan info" is due. */
	public void onServerMessage(net.minecraft.network.chat.Component message) {
		if (System.currentTimeMillis() > clanInfoUntil) return;
		List<Clan.Seg> tag = Clan.parseTag(message);
		if (tag != null) {
			CLAN_BY_NAME.put(myName().toLowerCase(Locale.ROOT), tag);
			NO_CLAN_NAMES.remove(myName().toLowerCase(Locale.ROOT));
			clanInfoUntil = 0;
			clanAnswered = true;
			lastHeartbeat = 0; // share it right away
			say("Clan erkannt: " + Clan.text(tag));
		} else if (Clan.saysNoClan(message)) {
			CLAN_BY_NAME.remove(myName().toLowerCase(Locale.ROOT));
			NO_CLAN_NAMES.add(myName().toLowerCase(Locale.ROOT));
			clanInfoUntil = 0;
			clanAnswered = true;
			lastHeartbeat = 0;
			say("Du bist in keinem Clan");
		}
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
		resendTrades();
		// First status a few seconds later: by then my stored status (with my clan) has arrived,
		// so it isn't overwritten by one without it.
		lastHeartbeat = System.currentTimeMillis() - HEARTBEAT_MS + 4000;
	}

	/**
	 * For every open trade, sends my newest message again (with the ones before it), or the
	 * start if I haven't written yet. The other side recognizes what it already has, so this
	 * only fills gaps – e.g. a chat started while the other player was offline for hours.
	 */
	private void resendTrades() {
		String me = me().toString();
		for (Trade t : config.trades.values()) {
			Msg last = null;
			for (Msg m : t.messages) if (me.equals(m.from) && m.sent > 0) last = m;
			if (last != null) sendTrade(t, "msg", last.text, false, last.sent);
			else if (me.equals(t.partner)) sendTrade(t, "start", "", false, t.created);
		}
	}

	@Override
	public void onMessage(String topic, JsonObject o, boolean stored) {
		String[] part = topic.split("/");
		try {
			switch (part[0]) {
				case "o" -> onOffer(part, o, stored);
				case "d" -> onDeal(part, o, stored);
				case "v" -> onVouch(part, o);
				case "p" -> onPresence(part, o, stored);
				case "m" -> onTradeMessage(part, o, stored);
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

	private void onOffer(String[] part, JsonObject o, boolean stored) {
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
		long ts = o.get("ts").getAsLong();
		Offer offer = new Offer(part[2], u, playerName(str(o, "nm")), clean(str(o, "sv"), 64), type.key(),
				o.get("s").getAsBoolean(), n, p, clean(str(o, "no"), 60), Math.min(System.currentTimeMillis(), ts));
		Offer before = offers.put(key, offer);
		if (before == null && isNews(stored, u, ts)) notifier.notify(new Notice(Notice.Kind.OFFER, u, offer.name, "", offer, null, null));
		checkAlarm(offer);
	}

	/** Checks every offer against the price alarms again (after they were changed). */
	public void checkAlarms() {
		alarmed.clear();
		for (Offer o : offers("")) checkAlarm(o);
	}

	private void checkAlarm(Offer o) {
		MarketConfig.Alarm a = config.alarms.get(o.type);
		if (a == null || a.price <= 0 || o.owner.equals(me())) return;
		if (System.currentTimeMillis() - o.ts > OFFER_MAX_AGE) return;
		// Prices are in each server's own money: only offers on my server.
		String sv = server();
		if (sv.isEmpty() || !sv.equals(o.server)) return;
		boolean hit = a.buy ? o.sell && o.price <= a.price : !o.sell && o.price >= a.price;
		if (!hit || !alarmed.add(o.owner + "/" + o.id + "/" + o.price)) return;
		Spawners.Type t = Spawners.get(o.type);
		String what = o.amount + "× " + (t == null ? o.type : t.name()) + " je " + Spawners.format(o.price);
		notifier.notify(new Notice(Notice.Kind.ALARM, o.owner, o.name, "", o, null, null));
		say("Preiswecker: " + what + " (" + o.name + ")");
	}

	/**
	 * Whether something live from someone else was just created. The broker hands out the stored
	 * messages on connect and everyone re-sends theirs after a reconnect – that isn't news.
	 */
	private static boolean isNews(boolean stored, UUID author, long ts) {
		return !stored && !author.equals(me()) && Math.abs(System.currentTimeMillis() - ts) < FRESH_MS;
	}

	private void onDeal(String[] part, JsonObject o, boolean stored) {
		if (part.length != 3) return;
		String key = part[2] + "/" + part[1];
		if (o == null) {
			dealRecords.remove(key);
			return;
		}
		UUID u = author(o, part[1]);
		if (!str(o, "tr").equals(part[2])) return;
		UUID seller = UUID.fromString(str(o, "so")), buyer = UUID.fromString(str(o, "bu"));
		String sn = playerName(str(o, "sn")), bn = playerName(str(o, "bn"));
		// The publisher must be one of the two, under their own name.
		if (seller.equals(buyer) || sn.isEmpty() || bn.isEmpty()) return;
		if (!u.equals(seller) && !u.equals(buyer)) return;
		if (!playerName(str(o, "nm")).equals(u.equals(seller) ? sn : bn)) return;
		Spawners.Type type = Spawners.get(str(o, "ty"));
		int n = o.get("n").getAsInt();
		long p = o.get("p").getAsLong();
		if (type == null || n < 1 || n > 100_000 || p < 1 || p > Spawners.MAX_PRICE) return;
		long ts = o.get("ts").getAsLong();
		Deal d = new Deal(part[2], seller, sn, buyer, bn, clean(str(o, "sv"), 64), type.key(), n, p,
				Math.min(System.currentTimeMillis(), ts));
		Deal before = dealRecords.put(key, d);
		// News once the second side confirms. Deals I'm part of come as TRADE_DONE instead.
		Deal other = dealRecords.get(part[2] + "/" + (u.equals(seller) ? buyer : seller));
		boolean confirmed = other != null && other.seller.equals(seller) && other.buyer.equals(buyer) && other.type.equals(d.type);
		if (before == null && confirmed && isNews(stored, u, ts) && !seller.equals(me()) && !buyer.equals(me()))
			notifier.notify(new Notice(Notice.Kind.DEAL, u, playerName(str(o, "nm")), "", null, d, null));
	}

	private void onVouch(String[] part, JsonObject o) {
		// v/<target>/<from>/<trade>; v/<target>/<from> from 1.2.x (one per pair).
		if (part.length != 3 && part.length != 4) return;
		String key = part[1] + "/" + part[2] + (part.length == 4 ? "/" + part[3] : "");
		if (o == null) {
			vouches.remove(key);
			return;
		}
		UUID from = author(o, part[2]);
		UUID target = UUID.fromString(part[1]);
		if (!str(o, "to").equals(part[1]) || target.equals(from)) return;
		if (part.length == 4 && !str(o, "tr").equals(part[3])) return;
		String tn = playerName(str(o, "tn"));
		if (!tn.isEmpty() && !target.equals(me())) names.putIfAbsent(target, tn);
		int rating = o.has("r") ? Integer.signum(o.get("r").getAsInt()) : o.get("pos").getAsBoolean() ? 1 : -1;
		Vouch v = new Vouch(target, tn, from, playerName(str(o, "nm")), rating, clean(str(o, "tr"), 10),
				clean(str(o, "tx"), 120), Math.min(System.currentTimeMillis(), o.get("ts").getAsLong()));
		Vouch before = vouches.put(key, v);
		Trade t = config.trades.get(v.trade);
		if (t == null) return;
		String me = me().toString();
		if (!from.toString().equals(t.other(me)) || !target.toString().equals(me)) {
			if (from.equals(me())) checkTradeDone(t);
			return;
		}
		if (before == null && vouchIn(t, me()) == null) {
			// They vouched first: tell me it's my turn.
			String label = v.rating > 0 ? "positiv" : v.rating == 0 ? "neutral" : "negativ";
			addMessage(t, new Msg("", "", v.fromName + " hat " + label + " für dich gevoucht. Jetzt bist du dran – "
					+ "erst wenn ihr beide gevoucht habt, ist der Trade abgeschlossen.", System.currentTimeMillis()));
			if (!t.id.equals(viewing)) t.unread++;
			config.save();
			notifier.notify(new Notice(Notice.Kind.TRADE_MESSAGE, from, v.fromName, "hat für dich gevoucht – jetzt bist du dran", null, null, t));
		}
		checkTradeDone(t);
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
		// Only an empty tag list means "no clan". Without one they just haven't run /clan info since
		// their game started: keep the tag we know, it's still their clan.
		if (u.equals(me()) && stored && carriedClan == null) carriedClan = Clan.fromJson(o.get("cl"));
		if (o.has("cl") && !u.equals(me())) {
			List<Clan.Seg> tag = Clan.fromJson(o.get("cl"));
			if (tag == null) clans.remove(u);
			else clans.put(u, tag);
		}
	}

	private void onTradeMessage(String[] part, JsonObject o, boolean stored) {
		if (part.length != 3 || o == null) return;
		String me = me().toString();
		if (!part[1].equals(me)) return;
		UUID u = author(o, part[2]);
		if (u.equals(me())) return;
		String id = str(o, "tr");
		if (!id.matches("[0-9a-f]{10}") || config.closedTrades.contains(id)) return;
		String name = playerName(str(o, "nm"));
		String kind = str(o, "k");
		Trade t = config.trades.get(id);

		if (t != null && u.toString().equals(t.other(me)) && (!o.has("ver") || o.get("ver").getAsInt() < TRADE_PROTOCOL) && !t.oldPeer) {
			// Before 1.3.1 a single vouch closed the trade: say why it may not work for both.
			t.oldPeer = true;
			addMessage(t, new Msg("", "", name + " nutzt eine ältere Version der Mod. Damit ihr beide vouchen könnt, "
					+ "braucht ihr beide mindestens 1.3.2.", arrivalTime(t)));
			config.save();
		}

		if (kind.equals("close")) {
			if (t == null || !u.toString().equals(t.other(me))) return;
			// An older client closes the trade right after its own vouch. Then the vouch is
			// already here: keep the chat open, so I can still vouch back.
			if (vouchIn(t, u) != null && vouchIn(t, me()) == null) {
				config.save();
				return;
			}
			// They cancelled with ✕ (deals only come from both vouches).
			forgetTrade(t);
			if (!stored) notifier.notify(new Notice(Notice.Kind.TRADE_CLOSED, u, name, "", null, null, t));
			say(name + " hat den Trade abgebrochen");
			return;
		}

		if (t == null) {
			// First message of a trade: the chat opens. The sender or I must own the offer.
			String owner = str(o, "ow");
			if (!owner.equals(me) && !owner.equals(u.toString())) return;
			Spawners.Type type = Spawners.get(str(o, "ty"));
			int n = o.get("n").getAsInt();
			long p = o.get("p").getAsLong();
			if (type == null || n < 1 || n > 100_000 || p < 1 || p > Spawners.MAX_PRICE || name.isEmpty()) return;
			t = new Trade();
			t.id = id;
			t.offerId = clean(str(o, "oid"), 10);
			t.owner = owner;
			t.ownerName = owner.equals(me) ? myName() : name;
			t.partner = owner.equals(me) ? u.toString() : me;
			t.partnerName = owner.equals(me) ? name : myName();
			t.type = type.key();
			t.server = clean(str(o, "sv"), 64);
			t.sell = o.get("s").getAsBoolean();
			t.amount = n;
			t.price = p;
			t.created = Math.min(System.currentTimeMillis(), o.get("ts").getAsLong());
			t.messages.add(new Msg("", "", name + " möchte handeln. Ein Vouch schließt den Deal ab.", t.created));
			config.trades.put(id, t);
			if (!id.equals(viewing)) t.unread++;
			if (kind.equals("start")) notifier.notify(new Notice(Notice.Kind.TRADE_START, u, name, "", null, null, t));
		} else if (!u.toString().equals(t.other(me))) {
			return;
		}

		if (kind.equals("msg")) {
			// First the messages of theirs I may have missed, then the new one.
			int added = 0;
			if (o.has("h") && o.get("h").isJsonArray()) {
				JsonArray h = o.getAsJsonArray("h");
				for (int i = Math.max(0, h.size() - HISTORY); i < h.size(); i++) {
					// A broken entry must not cost the new message.
					if (!h.get(i).isJsonObject() || !h.get(i).getAsJsonObject().has("ts")) continue;
					JsonObject e = h.get(i).getAsJsonObject();
					if (receive(t, u, name, clean(str(e, "tx"), 200), e.get("ts").getAsLong(), stored, true)) added++;
				}
			}
			String text = clean(str(o, "tx"), 200);
			boolean newOne = receive(t, u, name, text, o.get("ts").getAsLong(), stored, false);
			if (newOne) added++;
			// Written while I was offline (the broker hands it out on connect) or missed in
			// between: still tell me, once, instead of leaving it to the unread count in the menu.
			if (added > 0 && (stored || !newOne) && !t.id.equals(viewing)) {
				String note = added == 1 ? (newOne ? text : "hat dir geschrieben") : added + " neue Nachrichten";
				notifier.notify(new Notice(Notice.Kind.TRADE_MESSAGE, u, name, note, null, null, t));
				say(name + " hat dir im Trade-Chat geschrieben");
			}
		}
		config.save();
	}

	/**
	 * Adds one of their messages unless I have it already. A new one goes at the end: the sender's
	 * clock can be seconds off, and sorting by it put replies above the message they answer. One
	 * I missed ({@code late}, from the history of a later message) goes right before the next of
	 * their messages I do have – their own messages can be compared by their own clock.
	 */
	/** Returns whether the message was new. */
	private boolean receive(Trade t, UUID u, String name, String text, long sent, boolean stored, boolean late) {
		String from = u.toString();
		// Recognized by the sender's clock; chats from 1.1.0 only have min(arrival, sent) in ts.
		boolean known = t.messages.stream().anyMatch(m -> from.equals(m.from) && (m.sent == sent || (m.sent == 0 && m.ts == sent)));
		if (text.isEmpty() || known) return false;
		int at = t.messages.size();
		for (int i = 0; late && i < t.messages.size(); i++) {
			Msg m = t.messages.get(i);
			if (from.equals(m.from) && m.sent > sent) {
				at = i;
				break;
			}
		}
		if (at == t.messages.size()) addMessage(t, new Msg(from, name, text, arrivalTime(t), sent));
		else insertMessage(t, at, new Msg(from, name, text, t.messages.get(at).ts, sent));
		if (!t.id.equals(viewing)) {
			t.unread++;
			// One banner per arrival, for the new message.
			if (!stored && !late) notifier.notify(new Notice(Notice.Kind.TRADE_MESSAGE, u, name, text, null, null, t));
		}
		return true;
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
		notifier.notify(new Notice(Notice.Kind.TPA, u, name, "", null, null, null));
	}

	/** Order time that puts a message at the end of the chat (see onTradeMessage). */
	private static long arrivalTime(Trade t) {
		long last = t.messages.isEmpty() ? 0 : t.messages.get(t.messages.size() - 1).ts;
		return Math.max(System.currentTimeMillis(), last + 1);
	}

	private void insertMessage(Trade t, int index, Msg m) {
		chatVersion++;
		t.messages.add(index, m);
		while (t.messages.size() > CHAT_KEEP) t.messages.remove(0);
	}

	private void addMessage(Trade t, Msg m) {
		chatVersion++;
		// Sorted by order time (ts); new messages get one at the end, see arrivalTime.
		List<Msg> list = t.messages;
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
