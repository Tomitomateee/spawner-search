package dev.spawnermarket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
	/** Left panel tab: 0 = global, 1 = server, 2 = private. */
	public int chatTab = 0;
	/** My offers, deals and vouches by relay topic, so they survive a broker restart. */
	public Map<String, JsonObject> published = new LinkedHashMap<>();

	public static MarketConfig load() {
		try {
			if (Files.exists(PATH)) {
				MarketConfig cfg = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), MarketConfig.class);
				if (cfg != null) {
					if (cfg.published == null) cfg.published = new LinkedHashMap<>();
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
}
