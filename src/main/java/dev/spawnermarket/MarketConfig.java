package dev.spawnermarket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Settings in the game's config folder, and the data of my Minecraft account – clan, trade chats,
 * price alarms, what I published – in a file of its own per account in my user folder. That way
 * a modpack (which ships the config folder) never carries anyone's trades or clan along, and two
 * accounts on one PC each keep their own.
 */
public class MarketConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("spawner-market.json");
	/** Outside the game folder on purpose: {@code <user>/.spawner-market/accounts/<uuid>.json}. */
	private static final Path ACCOUNTS = Path.of(System.getProperty("user.home"), ".spawner-market", "accounts");
	private static final int SETTINGS_VERSION = 1;

	/** Index into {@code Theme.ALL}. */
	public int theme = 0;
	public boolean blackMode = true;
	/** Glass panel opacity in percent (20–100). */
	public int opacity = 85;
	/** Server commands used by the TPA buttons (without the slash). */
	public String tpaCommand = "tpa";
	public String tpaAcceptCommand = "tpaccept";
	/** Master switch for the banners at the top of the screen (and their sound). */
	public boolean notifications = true;

	// Which events get a banner.
	public boolean notifyOffers = true;
	public boolean notifySell = true;
	public boolean notifyBuy = true;
	/** Only offers on the server I'm on right now. */
	public boolean notifyMyServer = true;
	/** Only sell offers at or below the average price, buy offers at or above it; no average yet counts as good. */
	public boolean notifyGoodPrices = false;
	/** Spawner keys to watch ({@code Spawners.Type.key}); empty = all. */
	public List<String> notifyTypes = new ArrayList<>();
	/** Someone opens a trade chat with me. */
	public boolean notifyTrades = true;
	public boolean notifyMessages = true;
	/** A trade of mine ends: deal confirmed or ended without one. */
	public boolean notifyTradeEnd = true;
	public boolean notifyTpa = true;
	/** Every new deal on the market (confirmed by both sides): someone bought or sold spawners. */
	public boolean notifyAllDeals = true;

	// How the banners look.
	/** 0 = top left, 1 = top center, 2 = top right. */
	public int bannerPos = 1;
	/** How long one banner stays, in seconds (3–15). */
	public int bannerSeconds = 6;
	public boolean bannerSound = true;
	/** Also on top of other menus (inventory, chests, pause), not only in game. */
	public boolean bannerInMenus = true;
	/** Also on top of the Spawnerhandel menu itself. */
	public boolean bannerInMarket = true;
	/** Bumped when a new default has to reach existing configs as well (see load). */
	public int settingsVersion;

	// Per account (transient: never written into the config folder, see Account).
	/** My clan tag from "/clan info" (null = none / not read yet), shown before my name. */
	public transient List<Clan.Seg> clanTag;
	/** Open trade chats by trade id; a chat is deleted once its deal is closed. */
	public transient Map<String, Trade> trades = new LinkedHashMap<>();
	/** Ids of closed trades, so their last stored message can't bring the chat back. */
	public transient List<String> closedTrades = new ArrayList<>();
	/** Price alarm per spawner type key. */
	public transient Map<String, Alarm> alarms = new LinkedHashMap<>();
	/** My offers, deals and vouches by relay topic, so they survive a broker restart. */
	public transient Map<String, JsonObject> published = new LinkedHashMap<>();
	/** Whose data the fields above are. */
	private transient UUID account;

	/** The per-account file's content. */
	private static class Account {
		List<Clan.Seg> clanTag;
		Map<String, Trade> trades = new LinkedHashMap<>();
		List<String> closedTrades = new ArrayList<>();
		Map<String, Alarm> alarms = new LinkedHashMap<>();
		Map<String, JsonObject> published = new LinkedHashMap<>();
	}

	public static MarketConfig load() {
		MarketConfig cfg = null;
		JsonObject old = null;
		try {
			if (Files.exists(PATH)) {
				String json = Files.readString(PATH, StandardCharsets.UTF_8);
				cfg = GSON.fromJson(json, MarketConfig.class);
				JsonElement el = JsonParser.parseString(json);
				if (el.isJsonObject()) old = el.getAsJsonObject();
			}
		} catch (Exception e) {
			SpawnerMarketClient.LOGGER.warn("Konnte Config nicht lesen", e);
		}
		if (cfg == null) cfg = new MarketConfig();
		if (cfg.notifyTypes == null) cfg.notifyTypes = new ArrayList<>();
		cfg.bannerPos = Math.floorMod(cfg.bannerPos, 3);
		cfg.bannerSeconds = Math.max(3, Math.min(15, cfg.bannerSeconds));
		// 1.2.5: a banner whenever someone buys or sells – it was off before.
		if (cfg.settingsVersion < 1) cfg.notifyAllDeals = true;
		cfg.settingsVersion = SETTINGS_VERSION;
		cfg.loadAccount(Minecraft.getInstance().getUser().getProfileId(), old);
		return cfg;
	}

	private Path accountFile() {
		return ACCOUNTS.resolve(account + ".json");
	}

	/**
	 * Reads my account's file. Before 1.4.1 this data sat in the config folder: if there's no
	 * account file yet, what belongs to this account is taken over from there once (and the
	 * config file is written again without it).
	 */
	private void loadAccount(UUID uuid, JsonObject old) {
		account = uuid;
		Account a = null;
		boolean migrate = false;
		try {
			if (Files.exists(accountFile())) a = GSON.fromJson(Files.readString(accountFile(), StandardCharsets.UTF_8), Account.class);
		} catch (Exception e) {
			SpawnerMarketClient.LOGGER.warn("Konnte Account-Daten nicht lesen", e);
		}
		if (a == null) {
			a = new Account();
			if (old != null && (old.has("trades") || old.has("published") || old.has("clanTag") || old.has("alarms"))) {
				migrate = true;
				String me = uuid.toString();
				try {
					if (old.has("trades")) {
						Map<String, Trade> all = GSON.fromJson(old.get("trades"), new TypeToken<Map<String, Trade>>() {}.getType());
						if (all != null) for (Map.Entry<String, Trade> e : all.entrySet()) if (me.equals(e.getValue().owner) || me.equals(e.getValue().partner)) a.trades.put(e.getKey(), e.getValue());
					}
					if (old.has("published")) {
						Map<String, JsonObject> all = GSON.fromJson(old.get("published"), new TypeToken<Map<String, JsonObject>>() {}.getType());
						if (all != null) for (Map.Entry<String, JsonObject> e : all.entrySet()) if (e.getKey().contains(me)) a.published.put(e.getKey(), e.getValue());
					}
					if (old.has("closedTrades")) a.closedTrades = GSON.fromJson(old.get("closedTrades"), new TypeToken<List<String>>() {}.getType());
					if (old.has("alarms")) a.alarms = GSON.fromJson(old.get("alarms"), new TypeToken<Map<String, Alarm>>() {}.getType());
					if (old.has("clanTag")) a.clanTag = GSON.fromJson(old.get("clanTag"), new TypeToken<List<Clan.Seg>>() {}.getType());
				} catch (Exception e) {
					SpawnerMarketClient.LOGGER.warn("Konnte alte Daten nicht übernehmen", e);
				}
			}
		}
		clanTag = a.clanTag;
		trades = a.trades == null ? new LinkedHashMap<>() : a.trades;
		closedTrades = a.closedTrades == null ? new ArrayList<>() : a.closedTrades;
		alarms = a.alarms == null ? new LinkedHashMap<>() : a.alarms;
		published = a.published == null ? new LinkedHashMap<>() : a.published;
		// Old data leaves the config folder right away.
		if (migrate || (old != null && old.has("trades"))) save();
	}

	public synchronized void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SpawnerMarketClient.LOGGER.warn("Konnte Config nicht speichern", e);
		}
		if (account == null) return;
		try {
			Account a = new Account();
			a.clanTag = clanTag;
			a.trades = trades;
			a.closedTrades = closedTrades;
			a.alarms = alarms;
			a.published = published;
			Files.createDirectories(ACCOUNTS);
			Files.writeString(accountFile(), GSON.toJson(a), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SpawnerMarketClient.LOGGER.warn("Konnte Account-Daten nicht speichern", e);
		}
	}

	/** A chat between an offer's owner and one interested player, about that one offer. */
	public static class Trade {
		public String id, offerId, owner, ownerName, partner, partnerName, type, server;
		public boolean sell;
		public int amount, unread;
		public long price, created;
		/** The other side runs a mod version from before both-sided vouching (warned once). */
		public boolean oldPeer;
		public List<Msg> messages = new ArrayList<>();

		/** The other player in this trade, seen from {@code me}. */
		public String other(String me) {
			return me.equals(owner) ? partner : owner;
		}

		public String otherName(String me) {
			return me.equals(owner) ? partnerName : ownerName;
		}

		public long lastActivity() {
			return messages.isEmpty() ? created : messages.get(messages.size() - 1).ts;
		}
	}

	/**
	 * Notifies me when a spawner reaches my price: {@code buy} = a sell offer at or below
	 * {@code price}, otherwise a buy offer at or above it.
	 */
	public static class Alarm {
		public long price;
		public boolean buy = true;
	}

	/**
	 * One chat line; {@code from} empty = a note from the mod itself. {@code ts} is my clock and
	 * only orders the chat; {@code sent} is the sender's clock and recognizes the message when the
	 * broker hands it out again (0 in chats from 1.1.0).
	 */
	public static class Msg {
		public String from = "", name = "", text = "";
		public long ts, sent;

		public Msg() {}

		public Msg(String from, String name, String text, long ts) {
			this(from, name, text, ts, ts);
		}

		public Msg(String from, String name, String text, long ts, long sent) {
			this.from = from;
			this.name = name;
			this.text = text;
			this.ts = ts;
			this.sent = sent;
		}
	}
}
