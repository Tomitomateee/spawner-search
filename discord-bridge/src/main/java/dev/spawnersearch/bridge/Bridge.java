package dev.spawnersearch.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mirrors the Spawner Search into Discord: reads offers, deals and vouches from the mod's relay
 * and keeps four channels up to date through webhooks. Verkauf, Ankauf and Preise are one live
 * message each that gets edited; every new vouch is posted as its own message.
 *
 * <p>Run it anywhere with Java 21+ (your PC, a server): {@code java -jar spawner-search-discord.jar}.
 * {@code --dry-run} prints what would be posted instead of sending it.
 */
public final class Bridge implements Relay.Handler {
	// ================================================================ config / state files

	static final class Config {
		String server = "";
		Map<String, String> webhooks = new LinkedHashMap<>();
		int maxPerSpawner = 10;
		/** Every live message is refreshed this often, even when nothing changed (changes show at once). */
		int refreshMinutes = 3;
		boolean postExistingVouches = false;
	}

	static final class State {
		/** "channel|webhookId" → id of the live message. */
		Map<String, String> messages = new LinkedHashMap<>();
		/** Vouches already posted (topic keys). */
		Set<String> vouches = new LinkedHashSet<>();
		boolean vouchesInitialized;
		/** Sender name the live messages were posted with; editing a message can't change it. */
		String author;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String AUTHOR = "Spawner-Search";
	/**
	 * All files live next to the jar, so it works no matter where it's started from (autostart,
	 * a shortcut, javaw without a console).
	 */
	private static final Path HOME = home();
	private static final Path CONFIG = HOME.resolve("bridge.json"), STATE = HOME.resolve("bridge-state.json");
	private static final Path STOP = HOME.resolve("stop.flag"), LOCK = HOME.resolve("bridge.lock");

	private static Path home() {
		try {
			Path jar = Path.of(Bridge.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			return Files.isRegularFile(jar) ? jar.getParent() : Path.of("").toAbsolutePath();
		} catch (Exception e) {
			return Path.of("").toAbsolutePath();
		}
	}

	// ================================================================ market data (same rules as the mod)

	record Offer(String owner, String name, String server, String type, boolean sell, int amount, long price, String note, long ts) {}

	record Deal(String trade, String seller, String sellerName, String buyer, String buyerName, String server,
	            String type, int amount, long price, long ts) {}

	record Vouch(String key, String target, String targetName, String from, String fromName, int rating, String trade, String text, long ts) {}

	private static final long OFFER_MAX_AGE = 7 * 86_400_000L, DEAL_MAX_AGE = 30 * 86_400_000L;
	private static final long MAX_PRICE = 1_000_000_000_000_000L;
	/** Spawners: key, name, emoji, embed color. Same keys as the mod. */
	private static final String[][] SPAWNERS = {
			{ "cow", "Kuh", "🐮", "8B5A2B" },
			{ "iron_golem", "Golem", "🗿", "B8B8C8" },
			{ "spider", "Spinne", "🕷️", "5A3E3E" },
			{ "zombified_piglin", "Piglin", "🐷", "E39C9C" },
			{ "skeleton", "Skelett", "💀", "D8D8D8" },
			{ "creeper", "Creeper", "🟩", "3DBE4A" },
			{ "blaze", "Lohe", "🔥", "F2A33A" },
	};

	private final Config config;
	private final State state;
	private final boolean dryRun;
	private final Relay relay = new Relay(this);
	private final Map<String, Offer> offers = new HashMap<>();
	private final Map<String, Deal> dealRecords = new HashMap<>();
	private final Map<String, Vouch> vouches = new HashMap<>();
	/** Last content sent per board, so unchanged boards aren't edited again. */
	private final Map<String, String> sent = new HashMap<>();
	/** When each live message was last sent. */
	private final Map<String, Long> sentAt = new HashMap<>();
	private volatile long connectedAt;

	private Bridge(Config config, State state, boolean dryRun) {
		this.config = config;
		this.state = state;
		this.dryRun = dryRun;
	}

	public static void main(String[] args) throws Exception {
		// Umlauts and emoji in the console (start.bat switches it to UTF-8).
		System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8));
		boolean dryRun = List.of(args).contains("--dry-run");
		// One-off: post every vouch again (after the old posts were deleted), then end.
		boolean repostVouches = List.of(args).contains("--vouches-neu-posten");
		if (!dryRun) {
			// Only one at a time: a second start (e.g. autostart plus a double click) just ends.
			java.nio.channels.FileChannel lock = java.nio.channels.FileChannel.open(LOCK,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
			if (lock.tryLock() == null) {
				log("Läuft schon – dieser zweite Start beendet sich.");
				return;
			}
			Files.deleteIfExists(STOP);
		}
		if (!Files.exists(CONFIG)) {
			Config example = new Config();
			for (String c : new String[] { "verkauf", "ankauf", "preise", "vouches" }) example.webhooks.put(c, "");
			Files.writeString(CONFIG, GSON.toJson(example), StandardCharsets.UTF_8);
			log("bridge.json angelegt – trag dort die vier Webhook-URLs ein und starte neu.");
			if (!dryRun) return;
		}
		Config config = GSON.fromJson(Files.readString(CONFIG, StandardCharsets.UTF_8), Config.class);
		if (config.webhooks == null) config.webhooks = new LinkedHashMap<>();
		config.server = normalizeServer(config.server == null ? "" : config.server);
		config.maxPerSpawner = Math.max(1, Math.min(25, config.maxPerSpawner));
		config.refreshMinutes = Math.max(1, Math.min(60, config.refreshMinutes <= 0 ? 3 : config.refreshMinutes));
		if (!dryRun) {
			for (String c : new String[] { "verkauf", "ankauf", "preise", "vouches" }) {
				String url = config.webhooks.get(c);
				if (url == null || url.isBlank()) log("Kein Webhook für #" + c + " – der Kanal wird übersprungen.");
				else if (!Discord.validWebhook(url)) {
					log("Der Webhook für #" + c + " ist keine Discord-Webhook-URL – bitte prüfen.");
					config.webhooks.remove(c);
				}
			}
		}
		State state = Files.exists(STATE) ? GSON.fromJson(Files.readString(STATE, StandardCharsets.UTF_8), State.class) : new State();
		if (state.messages == null) state.messages = new LinkedHashMap<>();
		if (state.vouches == null) state.vouches = new LinkedHashSet<>();
		Bridge bridge = new Bridge(config, state, dryRun);
		if (repostVouches) bridge.repostVouches();
		else bridge.run();
	}

	private void run() throws InterruptedException {
		log("Spawner Search → Discord" + (config.server.isEmpty() ? " (alle Server)" : " für " + config.server)
				+ (dryRun ? " – Probelauf, es wird nichts gesendet" : ""));
		renewAfterRename();
		relay.start();
		while (true) {
			Thread.sleep(5000);
			// stop.bat leaves this file: end cleanly.
			if (!dryRun && Files.exists(STOP)) {
				try {
					Files.deleteIfExists(STOP);
				} catch (Exception ignored) {
					// ending anyway
				}
				log("Beendet (stop.bat)");
				System.exit(0);
			}
			// Give the broker a few seconds to hand out everything stored before the first update.
			if (!relay.connected() || System.currentTimeMillis() - connectedAt < 8000) continue;
			try {
				update();
			} catch (Exception e) {
				log("Fehler beim Aktualisieren: " + e);
			}
			if (dryRun) return;
		}
	}

	private void repostVouches() throws InterruptedException {
		log("Vouches neu posten" + (dryRun ? " – Probelauf" : ""));
		relay.start();
		for (int i = 0; i < 60 && (!relay.connected() || System.currentTimeMillis() - connectedAt < 8000); i++) Thread.sleep(1000);
		if (!relay.connected()) {
			log("Relay nicht erreichbar – nichts gepostet");
			System.exit(1);
		}
		synchronized (this) {
			List<Vouch> list = new ArrayList<>(countedVouches(confirmedDeals()));
			list.addAll(extraVouches());
			list.sort(Comparator.comparingLong(Vouch::ts));
			String url = config.webhooks.get("vouches");
			int posted = 0;
			for (Vouch v : list) {
				JsonObject message = vouchMessage(v);
				if (dryRun) System.out.println("\n===== #vouches (neu) =====\n" + preview(message));
				else if (url == null || url.isBlank() || Discord.post(url, message) == null) break;
				state.vouches.add(v.key);
				posted++;
			}
			state.vouchesInitialized = true;
			if (!dryRun) saveState();
			log("#vouches: " + posted + " von " + list.size() + " Vouches neu gepostet");
		}
		System.exit(0);
	}

	/**
	 * Vouches that are only left in Discord, not on the relay any more: {@code vouches-nachtragen.json},
	 * a list of {von, an, bewertung (1/0/-1), text, zeit (ISO-8601)}.
	 */
	private static List<Vouch> extraVouches() {
		List<Vouch> out = new ArrayList<>();
		Path file = HOME.resolve("vouches-nachtragen.json");
		if (!Files.exists(file)) return out;
		try {
			for (JsonElement el : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
				JsonObject o = el.getAsJsonObject();
				long ts = Instant.parse(o.get("zeit").getAsString()).toEpochMilli();
				out.add(new Vouch("extra/" + ts, "", o.get("an").getAsString(), "", o.get("von").getAsString(),
						o.get("bewertung").getAsInt(), "", o.has("text") ? o.get("text").getAsString() : "", ts));
			}
		} catch (Exception e) {
			log("vouches-nachtragen.json nicht lesbar: " + e);
		}
		return out;
	}

	@Override
	public void onConnected() {
		connectedAt = System.currentTimeMillis();
	}

	// ================================================================ relay messages

	@Override
	public synchronized void onMessage(String topic, JsonObject o) {
		String[] part = topic.split("/");
		try {
			switch (part[0]) {
				case "o" -> onOffer(part, o);
				case "d" -> onDeal(part, o);
				case "v" -> onVouch(part, o);
				default -> { }
			}
		} catch (RuntimeException ignored) {
			// malformed: anyone can post to a public broker
		}
	}

	private void onOffer(String[] part, JsonObject o) {
		if (part.length != 3) return;
		String key = part[1] + "/" + part[2];
		if (o == null) {
			offers.remove(key);
			return;
		}
		if (!str(o, "u").equals(part[1]) || !str(o, "id").equals(part[2])) return;
		String type = str(o, "ty");
		int n = o.get("n").getAsInt();
		long p = o.get("p").getAsLong();
		if (spawner(type) == null || n < 1 || n > 100_000 || p < 1 || p > MAX_PRICE) return;
		offers.put(key, new Offer(part[1], playerName(str(o, "nm")), normalizeServer(clean(str(o, "sv"), 64)), type,
				o.get("s").getAsBoolean(), n, p, clean(str(o, "no"), 60), Math.min(now(), o.get("ts").getAsLong())));
	}

	private void onDeal(String[] part, JsonObject o) {
		if (part.length != 3) return;
		String key = part[2] + "/" + part[1];
		if (o == null) {
			dealRecords.remove(key);
			return;
		}
		String u = str(o, "u"), seller = str(o, "so"), buyer = str(o, "bu");
		String sn = playerName(str(o, "sn")), bn = playerName(str(o, "bn"));
		if (!u.equals(part[1]) || !str(o, "tr").equals(part[2]) || seller.equals(buyer) || sn.isEmpty() || bn.isEmpty()) return;
		if (!u.equals(seller) && !u.equals(buyer)) return;
		if (!playerName(str(o, "nm")).equals(u.equals(seller) ? sn : bn)) return;
		String type = str(o, "ty");
		int n = o.get("n").getAsInt();
		long p = o.get("p").getAsLong();
		if (spawner(type) == null || n < 1 || n > 100_000 || p < 1 || p > MAX_PRICE) return;
		dealRecords.put(key, new Deal(part[2], seller, sn, buyer, bn, normalizeServer(clean(str(o, "sv"), 64)), type, n, p,
				Math.min(now(), o.get("ts").getAsLong())));
	}

	private void onVouch(String[] part, JsonObject o) {
		if (part.length != 3 && part.length != 4) return;
		String key = part[1] + "/" + part[2] + (part.length == 4 ? "/" + part[3] : "");
		if (o == null) {
			vouches.remove(key);
			return;
		}
		if (!str(o, "u").equals(part[2]) || !str(o, "to").equals(part[1]) || part[1].equals(part[2])) return;
		if (part.length == 4 && !str(o, "tr").equals(part[3])) return;
		int rating = o.has("r") ? Integer.signum(o.get("r").getAsInt()) : o.get("pos").getAsBoolean() ? 1 : -1;
		vouches.put(key, new Vouch(key, part[1], playerName(str(o, "tn")), part[2], playerName(str(o, "nm")), rating,
				clean(str(o, "tr"), 10), clean(str(o, "tx"), 120), Math.min(now(), o.get("ts").getAsLong())));
	}

	// ================================================================ what the market shows

	private boolean onMyServer(String server) {
		return config.server.isEmpty() || config.server.equals(server);
	}

	/** Deals both seller and buyer confirmed (any age). */
	private synchronized Map<String, Deal> confirmedDeals() {
		Map<String, Deal> bySeller = new HashMap<>(), byBuyer = new HashMap<>();
		for (Map.Entry<String, Deal> e : dealRecords.entrySet()) {
			Deal d = e.getValue();
			String publisher = e.getKey().substring(e.getKey().indexOf('/') + 1);
			if (publisher.equals(d.seller)) bySeller.put(d.trade, d);
			else if (publisher.equals(d.buyer)) byBuyer.put(d.trade, d);
		}
		Map<String, Deal> out = new HashMap<>();
		for (Deal d : bySeller.values()) {
			Deal b = byBuyer.get(d.trade);
			if (b != null && b.seller.equals(d.seller) && b.buyer.equals(d.buyer) && b.type.equals(d.type)) out.put(d.trade, d);
		}
		return out;
	}

	private synchronized List<Offer> offers(boolean sell) {
		List<Offer> list = new ArrayList<>();
		for (Offer o : offers.values()) {
			if (o.sell == sell && onMyServer(o.server) && now() - o.ts <= OFFER_MAX_AGE) list.add(o);
		}
		// Like the mod: Verkauf cheapest first, Ankauf highest first.
		Comparator<Offer> byPrice = Comparator.comparingLong(Offer::price);
		list.sort((sell ? byPrice : byPrice.reversed()).thenComparing(Comparator.comparingLong(Offer::ts).reversed()));
		return list;
	}

	/** Vouches that count, in the order they came: one per person and trade, positive only with a deal. */
	private synchronized List<Vouch> countedVouches(Map<String, Deal> deals) {
		Map<String, Vouch> first = new HashMap<>();
		List<Vouch> list = new ArrayList<>();
		for (Vouch v : vouches.values()) {
			if (v.trade.isEmpty()) list.add(v);
			else first.merge(v.trade + "/" + v.from, v, (a, b) -> a.ts <= b.ts ? a : b);
		}
		list.addAll(first.values());
		list.removeIf(v -> v.rating > 0 && !deals.containsKey(v.trade));
		list.sort(Comparator.comparingLong(Vouch::ts));
		return list;
	}

	// ================================================================ updating Discord

	private void update() {
		postBoard("verkauf", offerBoard(true));
		postBoard("ankauf", offerBoard(false));
		Map<String, Deal> deals = confirmedDeals();
		postBoard("preise", priceBoard(deals));
		// #vouches: a live overview on top, below it every vouch as its own message.
		postBoard("vouches", vouchBoard(deals));
		postVouches(deals);
	}

	/**
	 * The live messages still show the sender name they were posted with (Discord keeps it on
	 * edits). After a rename they're deleted, so the next round posts them again under the new name.
	 */
	private void renewAfterRename() {
		if (dryRun || AUTHOR.equals(state.author)) return;
		for (Map.Entry<String, String> e : state.messages.entrySet()) {
			String url = config.webhooks.get(e.getKey().substring(0, e.getKey().indexOf('|')));
			if (url != null && Discord.delete(url, e.getValue())) log("#" + e.getKey().substring(0, e.getKey().indexOf('|')) + ": alte Live-Nachricht gelöscht");
		}
		state.messages.clear();
		state.author = AUTHOR;
		saveState();
	}

	/** Edits the channel's live message (posts it the first time, or again if it was deleted). */
	private void postBoard(String channel, JsonObject message) {
		String url = config.webhooks.get(channel);
		String signature = message.toString();
		// Changed: right away. Unchanged: refreshed every few minutes ("Stand" shows it's alive).
		boolean due = now() - sentAt.getOrDefault(channel, 0L) >= config.refreshMinutes * 60_000L;
		if (signature.equals(sent.get(channel)) && !due) return;
		message.addProperty("content", message.get("content").getAsString()
				+ "\n-# Live aus der Spawner-Search-Mod · Stand <t:" + now() / 1000 + ":R>");
		if (dryRun) {
			System.out.println("\n===== #" + channel + " =====\n" + preview(message));
			sent.put(channel, signature);
			sentAt.put(channel, now());
			return;
		}
		if (url == null || url.isBlank()) return;
		String slot = channel + "|" + webhookId(url);
		String id = state.messages.get(slot);
		int result = id == null ? 1 : Discord.edit(url, id, message);
		if (result == 1) {
			String posted = Discord.post(url, message);
			if (posted == null) return;
			state.messages.put(slot, posted);
			saveState();
			log("#" + channel + ": Live-Nachricht angelegt");
		} else if (result < 0) {
			return; // try again next round
		} else {
			log("#" + channel + (signature.equals(sent.get(channel)) ? ": aufgefrischt" : ": aktualisiert"));
		}
		sent.put(channel, signature);
		sentAt.put(channel, now());
	}

	private void postVouches(Map<String, Deal> deals) {
		List<Vouch> list = countedVouches(deals);
		if (!state.vouchesInitialized) {
			// First start: don't flood the channel with every old vouch.
			state.vouchesInitialized = true;
			if (!config.postExistingVouches) {
				for (Vouch v : list) state.vouches.add(v.key);
				saveState();
				log("#vouches: " + list.size() + " bestehende Vouches übersprungen, ab jetzt kommt jeder neue");
				return;
			}
		}
		String url = config.webhooks.get("vouches");
		for (Vouch v : list) {
			if (state.vouches.contains(v.key)) continue;
			JsonObject message = vouchMessage(v);
			if (dryRun) {
				System.out.println("\n===== #vouches (neu) =====\n" + preview(message));
			} else {
				if (url == null || url.isBlank()) return;
				if (Discord.post(url, message) == null) return;
			}
			state.vouches.add(v.key);
			saveState();
		}
	}

	// ================================================================ message content

	private JsonObject offerBoard(boolean sell) {
		List<Offer> all = offers(sell);
		JsonObject msg = baseMessage((sell ? "## 💰 Verkauf\nSpieler, die Spawner **verkaufen** – günstigster Preis zuerst."
				: "## 🔎 Ankauf\nSpieler, die Spawner **kaufen** wollen – höchstes Gebot zuerst.") + serverLine());
		JsonArray embeds = new JsonArray();
		List<String> empty = new ArrayList<>();
		int budget = 5200;
		for (String[] s : SPAWNERS) {
			List<Offer> list = all.stream().filter(o -> o.type.equals(s[0])).toList();
			if (list.isEmpty()) {
				empty.add(s[1]);
				continue;
			}
			StringBuilder d = new StringBuilder();
			int shown = 0;
			for (Offer o : list) {
				if (shown >= config.maxPerSpawner) break;
				String line = "`" + o.amount + "×` **" + format(o.price) + "** je Stück · ges. " + format(total(o.price, o.amount))
						+ " · **" + md(o.name) + "** · <t:" + o.ts / 1000 + ":R>"
						+ (o.note.isEmpty() ? "" : "\n> " + md(o.note))
						+ (config.server.isEmpty() && !o.server.isEmpty() ? " · " + md(o.server) : "") + "\n";
				if (line.length() > budget) break;
				budget -= line.length();
				d.append(line);
				shown++;
			}
			if (shown < list.size()) d.append("-# … und ").append(list.size() - shown).append(" weitere in der Mod");
			embeds.add(embed(s[2] + " " + s[1] + "-Spawner · " + list.size() + (list.size() == 1 ? " Angebot" : " Angebote"),
					d.toString(), s[3]));
		}
		if (embeds.isEmpty()) {
			embeds.add(embed(sell ? "Gerade verkauft niemand Spawner" : "Gerade will niemand Spawner kaufen",
					"Neue Angebote erscheinen hier automatisch, sobald jemand sie in der Mod erstellt.", "7C5CF2"));
		} else if (!empty.isEmpty()) {
			msg.addProperty("content", msg.get("content").getAsString() + "\n-# Keine Angebote: " + String.join(", ", empty));
		}
		msg.add("embeds", embeds);
		return msg;
	}

	private JsonObject priceBoard(Map<String, Deal> confirmed) {
		JsonObject msg = baseMessage("## 📊 Preise\nPro Spawner der höchste Verkaufs- und Ankaufspreis und der "
				+ "Durchschnitt aus den Deals der letzten 30 Tage." + serverLine());
		List<Deal> deals = new ArrayList<>();
		for (Deal d : confirmed.values()) if (onMyServer(d.server) && now() - d.ts <= DEAL_MAX_AGE) deals.add(d);
		deals.sort(Comparator.comparingLong(Deal::ts).reversed());
		List<Offer> sells = offers(true), buys = offers(false);

		JsonObject prices = embed("Spawner-Preise", "", "7C5CF2");
		JsonArray fields = new JsonArray();
		for (String[] s : SPAWNERS) {
			long highSell = -1, lowSell = -1, highBuy = -1, sum = 0;
			int pieces = 0, count = 0;
			for (Offer o : sells) if (o.type.equals(s[0])) {
				highSell = Math.max(highSell, o.price);
				lowSell = lowSell < 0 ? o.price : Math.min(lowSell, o.price);
			}
			for (Offer o : buys) if (o.type.equals(s[0])) highBuy = Math.max(highBuy, o.price);
			for (Deal d : deals) if (d.type.equals(s[0])) {
				sum += d.price * (long) d.amount;
				pieces += d.amount;
				count++;
			}
			String value = "Verkauf: **" + (highSell > 0 ? format(highSell) : "–") + "**"
					+ (lowSell > 0 && lowSell != highSell ? " (ab " + format(lowSell) + ")" : "")
					+ "\nAnkauf: **" + (highBuy > 0 ? format(highBuy) : "–") + "**"
					+ "\nØ Deal: **" + (pieces > 0 ? format(sum / pieces) : "–") + "**" + (count > 0 ? " (" + count + ")" : "");
			JsonObject f = new JsonObject();
			f.addProperty("name", s[2] + " " + s[1]);
			f.addProperty("value", value);
			f.addProperty("inline", true);
			fields.add(f);
		}
		prices.add("fields", fields);
		JsonArray embeds = new JsonArray();
		embeds.add(prices);

		StringBuilder last = new StringBuilder();
		for (int i = 0; i < Math.min(8, deals.size()); i++) {
			Deal d = deals.get(i);
			last.append("`").append(d.amount).append("×` ").append(name(d.type)).append(" je **").append(format(d.price))
					.append("** · ").append(md(d.sellerName)).append(" → ").append(md(d.buyerName))
					.append(" · <t:").append(d.ts / 1000).append(":R>\n");
		}
		embeds.add(embed("Letzte Deals", last.isEmpty() ? "Noch keine Deals – ein Deal zählt, sobald beide Händler positiv gevoucht haben."
				: last.toString(), "55CDB6"));
		msg.add("embeds", embeds);
		return msg;
	}

	/** Top traders by vouches (each counted vouch once), plus how vouching works. */
	private JsonObject vouchBoard(Map<String, Deal> deals) {
		JsonObject msg = baseMessage("## ⭐ Vouches\nWer zuverlässig handelt – nach jedem Trade vouchen beide Händler füreinander. "
				+ "Jeder neue Vouch erscheint unten als eigene Nachricht.");
		Map<String, int[]> score = new HashMap<>();
		Map<String, String> names = new HashMap<>();
		for (Vouch v : countedVouches(deals)) {
			int[] s = score.computeIfAbsent(v.target, k -> new int[3]);
			s[v.rating > 0 ? 0 : v.rating == 0 ? 1 : 2]++;
			if (!v.targetName.isEmpty()) names.put(v.target, v.targetName);
		}
		List<String> ranked = new ArrayList<>(score.keySet());
		ranked.sort(Comparator.comparingInt((String u) -> score.get(u)[0] - score.get(u)[2])
				.thenComparingInt(u -> score.get(u)[0]).reversed());
		StringBuilder top = new StringBuilder();
		for (int i = 0; i < Math.min(15, ranked.size()); i++) {
			String u = ranked.get(i);
			int[] s = score.get(u);
			top.append("**").append(i + 1).append(".** ").append(md(names.getOrDefault(u, "?")))
					.append(" · ✅ ").append(s[0]).append(" · ⚪ ").append(s[1]).append(" · ❌ ").append(s[2]).append('\n');
		}
		JsonArray embeds = new JsonArray();
		embeds.add(embed("🏆 Top-Händler", top.isEmpty() ? "Noch keine Vouches." : top.toString(), "F2B84B"));
		embeds.add(embed("So zählen Vouches",
				"✅ **Positiv** – der Deal hat geklappt. Zählt erst, wenn beide Händler positiv gevoucht haben.\n"
						+ "⚪ **Neutral** – der Trade kam nicht zustande.\n"
						+ "❌ **Negativ** – der Trade lief schlecht.\n"
						+ "Vouchen geht nur in der Mod, nach einem Trade-Chat mit mindestens 2 Nachrichten.", "7C5CF2"));
		msg.add("embeds", embeds);
		return msg;
	}

	private JsonObject vouchMessage(Vouch v) {
		String title = v.rating > 0 ? "✅ Positiver Vouch" : v.rating == 0 ? "⚪ Neutraler Vouch" : "❌ Negativer Vouch";
		String color = v.rating > 0 ? "3DDC84" : v.rating == 0 ? "9A98AC" : "F0506E";
		String target = v.targetName.isEmpty() ? "?" : v.targetName;
		JsonObject e = embed(title, "**" + md(v.fromName) + "** → **" + md(target) + "**" + (v.text.isEmpty() ? "" : "\n> " + md(v.text))
				+ (v.rating > 0 ? "\n-# Deal von beiden bestätigt" : v.rating == 0 ? "\n-# Trade kam nicht zustande" : ""), color);
		e.addProperty("timestamp", Instant.ofEpochMilli(v.ts).toString());
		JsonObject msg = baseMessage("");
		msg.remove("content");
		JsonArray embeds = new JsonArray();
		embeds.add(e);
		msg.add("embeds", embeds);
		return msg;
	}

	private String serverLine() {
		return config.server.isEmpty() ? "" : "\nServer: **" + md(config.server) + "**";
	}

	private static JsonObject baseMessage(String content) {
		JsonObject msg = new JsonObject();
		msg.addProperty("username", AUTHOR);
		msg.addProperty("content", content);
		// Player names and texts come from anyone: never ping.
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray());
		msg.add("allowed_mentions", mentions);
		return msg;
	}

	private static JsonObject embed(String title, String description, String color) {
		JsonObject e = new JsonObject();
		e.addProperty("title", title);
		if (!description.isEmpty()) e.addProperty("description", description);
		e.addProperty("color", Integer.parseInt(color, 16));
		return e;
	}

	/** Console view of a message for --dry-run. */
	private static String preview(JsonObject m) {
		StringBuilder b = new StringBuilder();
		if (m.has("content")) b.append(m.get("content").getAsString()).append('\n');
		if (m.has("embeds")) for (var el : m.getAsJsonArray("embeds")) {
			JsonObject e = el.getAsJsonObject();
			b.append("--- ").append(e.get("title").getAsString()).append('\n');
			if (e.has("description")) b.append(e.get("description").getAsString()).append('\n');
			if (e.has("fields")) for (var f : e.getAsJsonArray("fields")) {
				b.append("  [").append(f.getAsJsonObject().get("name").getAsString()).append("] ")
						.append(f.getAsJsonObject().get("value").getAsString().replace("\n", " | ")).append('\n');
			}
		}
		return b.toString();
	}

	// ================================================================ helpers

	private void saveState() {
		try {
			Files.writeString(STATE, GSON.toJson(state), StandardCharsets.UTF_8);
		} catch (Exception e) {
			log("Konnte bridge-state.json nicht speichern: " + e.getMessage());
		}
	}

	private static String webhookId(String url) {
		String[] p = url.split("/");
		return p.length >= 2 ? p[p.length - 2] : url;
	}

	private static String[] spawner(String key) {
		for (String[] s : SPAWNERS) if (s[0].equals(key)) return s;
		return null;
	}

	private static String name(String key) {
		String[] s = spawner(key);
		return s == null ? key : s[1];
	}

	static String normalizeServer(String ip) {
		ip = ip.toLowerCase(Locale.ROOT).trim();
		if (ip.endsWith(":25565")) ip = ip.substring(0, ip.length() - 6);
		if (ip.endsWith(".")) ip = ip.substring(0, ip.length() - 1);
		return ip;
	}

	/** 950 · 12.5k · 250k · 1.5M · 2B (same as the mod). */
	static String format(long v) {
		if (v < 1000) return Long.toString(v);
		String[] units = { "k", "M", "B", "T", "Q" };
		double d = v;
		int u = -1;
		while (d >= 1000 && u < units.length - 1) {
			d /= 1000;
			u++;
		}
		String num = d >= 100 ? String.format(Locale.ROOT, "%.0f", d)
				: d >= 10 ? String.format(Locale.ROOT, "%.1f", d) : String.format(Locale.ROOT, "%.2f", d);
		if (num.contains(".")) num = num.replaceAll("0+$", "").replaceAll("\\.$", "");
		return num + units[u];
	}

	private static long total(long price, int amount) {
		return price > Long.MAX_VALUE / amount ? Long.MAX_VALUE : price * amount;
	}

	/** Escapes Discord markdown in text from players. */
	private static String md(String s) {
		return s.replaceAll("([\\\\*_~`|>#\\[\\]()])", "\\\\$1");
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static String playerName(String s) {
		return s.matches("[A-Za-z0-9_]{1,16}") ? s : "";
	}

	private static String clean(String s, int max) {
		String out = s.replaceAll("§.?", "").replaceAll("\\p{Cntrl}", "").trim();
		return out.length() > max ? out.substring(0, max) : out;
	}

	private static long now() {
		return System.currentTimeMillis();
	}

	private static final Path LOG = HOME.resolve("bridge.log");

	/** Console and bridge.log (kept small: started over beyond 1 MB). */
	static synchronized void log(String msg) {
		String line = "[" + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss")) + "] " + msg;
		System.out.println(line);
		try {
			if (Files.exists(LOG) && Files.size(LOG) > 1_000_000) Files.delete(LOG);
			Files.writeString(LOG, line + System.lineSeparator(), StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (Exception ignored) {
			// logging must never stop the bridge
		}
	}
}
