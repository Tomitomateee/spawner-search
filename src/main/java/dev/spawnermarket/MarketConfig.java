package dev.spawnermarket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted settings, plus everything I published (re-sent after every reconnect). */
public class MarketConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("spawner-market.json");
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
	/** Open trade chats by trade id; a chat is deleted once its deal is closed. */
	public Map<String, Trade> trades = new LinkedHashMap<>();
	/** Ids of closed trades, so their last stored message can't bring the chat back. */
	public List<String> closedTrades = new ArrayList<>();
	/** Price alarm per spawner type key. */
	public Map<String, Alarm> alarms = new LinkedHashMap<>();
	/** My offers, deals and vouches by relay topic, so they survive a broker restart. */
	public Map<String, JsonObject> published = new LinkedHashMap<>();

	public static MarketConfig load() {
		try {
			if (Files.exists(PATH)) {
				MarketConfig cfg = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), MarketConfig.class);
				if (cfg != null) {
					if (cfg.published == null) cfg.published = new LinkedHashMap<>();
					if (cfg.trades == null) cfg.trades = new LinkedHashMap<>();
					if (cfg.closedTrades == null) cfg.closedTrades = new ArrayList<>();
					if (cfg.alarms == null) cfg.alarms = new LinkedHashMap<>();
					if (cfg.notifyTypes == null) cfg.notifyTypes = new ArrayList<>();
					cfg.bannerPos = Math.floorMod(cfg.bannerPos, 3);
					cfg.bannerSeconds = Math.max(3, Math.min(15, cfg.bannerSeconds));
					// 1.2.5: a banner whenever someone buys or sells – it was off before.
					if (cfg.settingsVersion < 1) cfg.notifyAllDeals = true;
					cfg.settingsVersion = SETTINGS_VERSION;
					return cfg;
				}
			}
		} catch (Exception e) {
			SpawnerMarketClient.LOGGER.warn("Konnte Config nicht lesen", e);
		}
		MarketConfig fresh = new MarketConfig();
		fresh.settingsVersion = SETTINGS_VERSION;
		return fresh;
	}

	public synchronized void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SpawnerMarketClient.LOGGER.warn("Konnte Config nicht speichern", e);
		}
	}

	/** A chat between an offer's owner and one interested player, about that one offer. */
	public static class Trade {
		public String id, offerId, owner, ownerName, partner, partnerName, type, server;
		public boolean sell;
		public int amount, unread;
		public long price, created;
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
