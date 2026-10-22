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

	/** Index into {@code Theme.ALL}. */
	public int theme = 0;
	public boolean blackMode = true;
	/** Glass panel opacity in percent (20–100). */
	public int opacity = 85;
	/** Server commands used by the TPA buttons (without the slash). */
	public String tpaCommand = "tpa";
	public String tpaAcceptCommand = "tpaccept";
	/** Toast + sound for private messages and TPA requests while the menu is closed. */
	public boolean notifications = true;
	/** Open trade chats by trade id; a chat is deleted once its deal is closed. */
	public Map<String, Trade> trades = new LinkedHashMap<>();
	/** Ids of closed trades, so their last stored message can't bring the chat back. */
	public List<String> closedTrades = new ArrayList<>();
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
					return cfg;
				}
			}
		} catch (Exception e) {
			SpawnerMarketClient.LOGGER.warn("Konnte Config nicht lesen", e);
		}
		return new MarketConfig();
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

	/** One chat line; {@code from} empty = a note from the mod itself. */
	public static class Msg {
		public String from = "", name = "", text = "";
		public long ts;

		public Msg() {}

		public Msg(String from, String name, String text, long ts) {
			this.from = from;
			this.name = name;
			this.text = text;
			this.ts = ts;
		}
	}
}
