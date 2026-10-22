package dev.spawnermarket.selftest;

import com.google.gson.JsonObject;
import dev.spawnermarket.Market;
import dev.spawnermarket.Market.Deal;
import dev.spawnermarket.Market.Notice;
import dev.spawnermarket.Market.Offer;
import dev.spawnermarket.MarketConfig;
import dev.spawnermarket.MarketConfig.Msg;
import dev.spawnermarket.MarketConfig.Trade;
import dev.spawnermarket.Notifications;
import dev.spawnermarket.SpawnerMarketClient;
import dev.spawnermarket.Spawners;
import dev.spawnermarket.gui.Banner;
import dev.spawnermarket.gui.MarketScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.tutorial.TutorialSteps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Self-test in the real game – never active unless the client is started with
 * {@code -Dspawnermarket.selftest=full}:
 *
 * <pre>
 * ./gradlew prodClient -Pselftest -Pworld="New World"
 * </pre>
 *
 * <p>Stays off the public relay and feeds the market made-up offers, deals and trade chats.
 * Checks which of them get a banner, the filters, the order of a trade chat whose partner's
 * clock is behind, and that the chat survives saving and loading. Then photographs the banners
 * (center, right, left, over the inventory) and clicks through the settings in the real menu.
 * Screenshots land in {@code screenshots/}, the result in {@code spawnermarket-selftest-full.txt}.
 * Delete {@code config/spawner-market.json} before a run: trades from the last run would stay open.
 */
public final class SelfTest {
	public static final String MODE = System.getProperty("spawnermarket.selftest", "");

	private static final int PAUSE = 10;
	private static final int PW = 230, H = 250, GAP = 10, SIDE_MAX = 170;
	private static final UUID KING = UUID.nameUUIDFromBytes("Spawnerking".getBytes(StandardCharsets.UTF_8));
	private static final UUID BLAZE = UUID.nameUUIDFromBytes("Blazefarm99".getBytes(StandardCharsets.UTF_8));
	/** Trade chats: Spawnerking (order test), Blazefarm99 (banner). */
	private static final String KING_TRADE = "a1b2c3d4e5", BLAZE_TRADE = "b1b2c3d4e5";

	@FunctionalInterface
	private interface Step {
		/** @return {@code true} when done */
		boolean run(Minecraft mc);
	}

	private static final List<Step> SCRIPT = new ArrayList<>();
	private static final List<String> FAILURES = new ArrayList<>();
	private static final List<String> NOTES = new ArrayList<>();
	private static final List<Notice> NOTICES = new ArrayList<>();

	private static Market market;
	private static int ticks, pause, step, stepTicks;
	private static boolean started;

	private SelfTest() {}

	public static void register(Market m) {
		if (MODE.isEmpty()) return;
		market = m;
		m.setNotifier(n -> {
			NOTICES.add(n);
			Notifications.handle(m, n);
		});
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
		SpawnerMarketClient.LOGGER.info("[SELFTEST] Modus {} - warte auf die Welt", MODE);
	}

	private static void tick(Minecraft mc) {
		ticks++;
		if (mc.options != null) mc.options.pauseOnLostFocus = false;
		if (ticks == 1) mc.getTutorial().setStep(TutorialSteps.NONE);
		if (!started) {
			if (mc.player != null && mc.level != null && mc.screen == null && mc.getOverlay() == null && ticks > 100) {
				started = true;
				build();
			} else if (ticks > 20 * 240) {
				started = true;
				FAILURES.add("Keine Welt innerhalb von 4 Minuten geladen - nichts getestet");
				SCRIPT.add(SelfTest::finish);
			}
			return;
		}
		if (pause > 0) {
			pause--;
			return;
		}
		if (step >= SCRIPT.size()) return;
		stepTicks++;
		boolean done;
		try {
			done = SCRIPT.get(step).run(mc);
		} catch (RuntimeException e) {
			SpawnerMarketClient.LOGGER.error("[SELFTEST] Schritt {} abgestürzt", step, e);
			FAILURES.add("Schritt " + step + " warf " + e);
			done = true;
		}
		if (done) {
			step++;
			stepTicks = 0;
			pause = PAUSE;
		}
	}

	private static void build() {
		MarketConfig c = market.config();
		SCRIPT.add(mc -> {
			c.bannerSeconds = 15;
			c.bannerPos = 1;
			checkNews();
			checkFilters();
			checkChatOrder();
			return true;
		});

		// Banners in game: center, right, left, then over the inventory.
		SCRIPT.add(mc -> {
			feedBanners();
			return true;
		});
		SCRIPT.add(mc -> stepTicks > 25);
		SCRIPT.add(mc -> {
			check(Banner.visible() == 3, "Statt 3 Bannern sind " + Banner.visible() + " zu sehen");
			return shot(mc, "01-banner-mitte");
		});
		SCRIPT.add(mc -> {
			c.bannerPos = 2;
			return true;
		});
		SCRIPT.add(mc -> shot(mc, "02-banner-rechts"));
		SCRIPT.add(mc -> {
			c.bannerPos = 0;
			return true;
		});
		SCRIPT.add(mc -> shot(mc, "03-banner-links"));
		SCRIPT.add(mc -> {
			c.bannerPos = 1;
			mc.setScreen(new InventoryScreen(mc.player));
			return true;
		});
		SCRIPT.add(mc -> stepTicks > 5);
		SCRIPT.add(mc -> shot(mc, "04-ueber-inventar"));

		// The settings, clicked like a player would.
		SCRIPT.add(mc -> {
			Banner.clear();
			mc.setScreen(new MarketScreen(market));
			return true;
		});
		SCRIPT.add(mc -> stepTicks > 5);
		SCRIPT.add(mc -> click(mc, px(mc) + 18, py(mc) + 15));
		SCRIPT.add(mc -> stepTicks > 10);
		SCRIPT.add(mc -> shot(mc, "05-zahnrad"));
		SCRIPT.add(mc -> click(mc, px(mc) + 72, py(mc) + 26 + 86));
		SCRIPT.add(mc -> {
			// Ankauf off and on again, then pick Skelett and Lohe.
			int ankaufX = px(mc) + 12 + mc.font.width("Verkauf") + 12 + 4 + (mc.font.width("Ankauf") + 12) / 2;
			click(mc, ankaufX, py(mc) + 81);
			check(!c.notifyBuy, "Klick auf „Ankauf“ hat den Filter nicht ausgeschaltet");
			click(mc, ankaufX, py(mc) + 81);
			check(c.notifyBuy, "Zweiter Klick auf „Ankauf“ hat den Filter nicht wieder eingeschaltet");
			click(mc, cellX(mc, "skeleton"), py(mc) + 138 + 20);
			click(mc, cellX(mc, "blaze"), py(mc) + 138 + 20);
			check(c.notifyTypes.contains("skeleton") && c.notifyTypes.contains("blaze") && c.notifyTypes.size() == 2,
					"Spawner-Auswahl per Klick ergab " + c.notifyTypes);
			return true;
		});
		SCRIPT.add(mc -> shot(mc, "06-einstellungen-angebote"));
		SCRIPT.add(mc -> click(mc, tabX(mc, 1), py(mc) + 36));
		SCRIPT.add(mc -> shot(mc, "07-einstellungen-trades"));
		SCRIPT.add(mc -> click(mc, tabX(mc, 2), py(mc) + 36));
		SCRIPT.add(mc -> click(mc, px(mc) + 52, py(mc) + H - 18));
		SCRIPT.add(mc -> stepTicks > 8);
		SCRIPT.add(mc -> {
			check(Banner.visible() == 1, "„Test“ zeigt " + Banner.visible() + " statt 1 Banner");
			return shot(mc, "08-einstellungen-anzeige");
		});
		SCRIPT.add(mc -> {
			c.notifyTypes.clear();
			c.save();
			// "Fertig" goes back to the market.
			return click(mc, px(mc) + 12 + 88 + 50, py(mc) + H - 18);
		});
		SCRIPT.add(mc -> shot(mc, "09-markt"));

		// The trade chat with Spawnerking: newest, so the first row on the left.
		SCRIPT.add(mc -> click(mc, px(mc) - GAP - sw(mc) + 40, py(mc) + 30 + 13));
		SCRIPT.add(mc -> stepTicks > 5);
		SCRIPT.add(mc -> shot(mc, "10-trade-chat"));
		SCRIPT.add(mc -> {
			mc.setScreen(null);
			return true;
		});
		SCRIPT.add(SelfTest::finish);
	}

	// ================================================================ checks

	/** Which offers and deals count as news. */
	private static void checkNews() {
		long now = System.currentTimeMillis();
		int n = NOTICES.size();
		deliver("o/" + KING + "/st1", offer(KING, "Spawnerking", "st1", "cow", true, 5, 300_000, now - 3_600_000), true);
		check(NOTICES.size() == n, "Gespeichertes Angebot beim Verbinden hat ein Banner ausgelöst");
		deliver("o/" + KING + "/new1", offer(KING, "Spawnerking", "new1", "skeleton", true, 16, 1_500_000, now), false);
		check(NOTICES.size() == n + 1 && NOTICES.get(n).kind() == Notice.Kind.OFFER, "Neues Angebot hat kein Banner ausgelöst");
		deliver("o/" + KING + "/new1", offer(KING, "Spawnerking", "new1", "skeleton", true, 10, 1_500_000, now), false);
		check(NOTICES.size() == n + 1, "Geändertes Angebot hat ein Banner ausgelöst");
		deliver("o/" + KING + "/old1", offer(KING, "Spawnerking", "old1", "spider", false, 2, 50_000, now - 2 * 3_600_000), false);
		check(NOTICES.size() == n + 1, "Altes Angebot (nach Reconnect neu gesendet) hat ein Banner ausgelöst");
		deliver("o/" + Market.me() + "/mine1", offer(Market.me(), Market.myName(), "mine1", "creeper", true, 1, 10_000, now), false);
		check(NOTICES.size() == n + 1, "Eigenes Angebot hat ein Banner ausgelöst");
		// A deal counts once both sides published it.
		deliver("d/" + KING + "/c1c2c3c4c5", deal(KING, "Spawnerking", "c1c2c3c4c5", "zombified_piglin", 3, 700_000, now), false);
		check(NOTICES.size() == n + 1, "Halber Deal (nur eine Seite) hat ein Banner ausgelöst");
		deliver("d/" + BLAZE + "/c1c2c3c4c5", deal(BLAZE, "Blazefarm99", "c1c2c3c4c5", "zombified_piglin", 3, 700_000, now), false);
		check(NOTICES.size() == n + 2 && NOTICES.get(n + 1).kind() == Notice.Kind.DEAL, "Bestätigter Deal hat kein Ereignis ausgelöst");
		NOTES.add("Neu-Erkennung: " + (NOTICES.size() - n) + " von 7 Ereignissen gemeldet (erwartet 2)");
	}

	private static void checkFilters() {
		long now = System.currentTimeMillis();
		Offer sell = new Offer("a", KING, "Spawnerking", "hugo.net", "skeleton", true, 1, 90, "", now);
		Offer buy = new Offer("b", KING, "Spawnerking", "hugo.net", "blaze", false, 1, 90, "", now);
		MarketConfig c = new MarketConfig();
		check(Notifications.wantsOffer(c, sell, "hugo.net", -1), "Standard: Angebot auf meinem Server abgelehnt");
		check(!Notifications.wantsOffer(c, sell, "anderer.net", -1), "„Nur mein Server“: fremder Server durchgelassen");
		check(!Notifications.wantsOffer(c, sell, "", -1), "„Nur mein Server“ im Hauptmenü: Angebot durchgelassen");
		c.notifyMyServer = false;
		check(Notifications.wantsOffer(c, sell, "anderer.net", -1), "„Alle Server“: fremder Server abgelehnt");
		c.notifySell = false;
		check(!Notifications.wantsOffer(c, sell, "hugo.net", -1) && Notifications.wantsOffer(c, buy, "hugo.net", -1), "Verkauf aus: falsch gefiltert");
		c.notifySell = true;
		c.notifyTypes.add("blaze");
		check(!Notifications.wantsOffer(c, sell, "hugo.net", -1) && Notifications.wantsOffer(c, buy, "hugo.net", -1), "Spawner-Filter: falsch gefiltert");
		c.notifyTypes.clear();
		c.notifyGoodPrices = true;
		check(Notifications.wantsOffer(c, sell, "hugo.net", 100) && !Notifications.wantsOffer(c, sell, "hugo.net", 80), "Gute Preise (Verkauf): falsch");
		check(!Notifications.wantsOffer(c, buy, "hugo.net", 100) && Notifications.wantsOffer(c, buy, "hugo.net", 80), "Gute Preise (Ankauf): falsch");
		check(Notifications.wantsOffer(c, sell, "hugo.net", -1), "Gute Preise ohne Schnittpreis: abgelehnt");
		c.notifyOffers = false;
		check(!Notifications.wantsOffer(c, sell, "hugo.net", -1), "Angebote aus: trotzdem durchgelassen");

		MarketConfig d = new MarketConfig();
		Deal deal = new Deal("x", KING, "Spawnerking", BLAZE, "Blazefarm99", "hugo.net", "cow", 1, 5, now);
		check(!Notifications.wantsDeal(d, deal, "hugo.net"), "Fremder Deal ohne „Alle neuen Deals“ durchgelassen");
		d.notifyAllDeals = true;
		check(Notifications.wantsDeal(d, deal, "hugo.net") && !Notifications.wantsDeal(d, deal, "x.net"), "„Alle neuen Deals“: Server-Filter falsch");
		NOTES.add("Filter geprüft");
	}

	/** A partner whose clock is 8 s behind, a re-sent message, an old 1.1.0 chat, then save and load. */
	private static void checkChatOrder() {
		long now = System.currentTimeMillis();
		int n = NOTICES.size();
		trade(KING, "Spawnerking", KING_TRADE, "start", "", now, false);
		Trade t = market.trade(KING_TRADE);
		check(t != null && NOTICES.size() == n + 1 && NOTICES.get(n).kind() == Notice.Kind.TRADE_START, "Neuer Trade: kein Chat oder kein Banner");
		if (t == null) return;
		trade(KING, "Spawnerking", KING_TRADE, "msg", "Hey, hast du noch Skelett-Spawner?", now, false);
		market.addSent(t, "Ja, 16 Stück, je 1,5M", now + 1);
		// Their clock is behind: by send time this would land above my message.
		trade(KING, "Spawnerking", KING_TRADE, "msg", "Deal! Ich schick dir TPA", now - 8_000, false);
		List<String> want = List.of("Hey, hast du noch Skelett-Spawner?", "Ja, 16 Stück, je 1,5M", "Deal! Ich schick dir TPA");
		check(texts().equals(want), "Trade-Chat falsch sortiert: " + texts());
		check(NOTICES.size() == n + 3, "Trade-Nachrichten: " + (NOTICES.size() - n - 1) + " statt 2 Banner");
		int unread = t.unread;
		// The broker hands out the newest message again after a reconnect.
		trade(KING, "Spawnerking", KING_TRADE, "msg", "Deal! Ich schick dir TPA", now - 8_000, true);
		check(texts().equals(want), "Erneut zugestellte Nachricht doppelt: " + texts());
		check(t.unread == unread, "Erneut zugestellte Nachricht als ungelesen gezählt");
		// A chat saved by 1.1.0: no send time, ts = send time.
		t.messages.add(new Msg(KING.toString(), "Spawnerking", "alt", now - 5_000, 0));
		trade(KING, "Spawnerking", KING_TRADE, "msg", "alt", now - 5_000, true);
		check(t.messages.stream().filter(m -> m.text.equals("alt")).count() == 1, "Nachricht aus einem 1.1.0-Chat kam doppelt");
		t.messages.removeIf(m -> m.text.equals("alt"));

		market.config().save();
		Trade loaded = MarketConfig.load().trades.get(KING_TRADE);
		List<String> loadedTexts = loaded == null ? List.of() : loaded.messages.stream().filter(m -> !m.from.isEmpty()).map(m -> m.text).toList();
		check(loadedTexts.equals(want), "Nach Speichern und Laden: " + loadedTexts);
		check(loaded != null && loaded.messages.stream().allMatch(m -> m.from.isEmpty() || m.sent > 0), "Sendezeit wurde nicht gespeichert");
		NOTES.add("Trade-Chat-Reihenfolge: " + texts());
	}

	private static void feedBanners() {
		Banner.clear();
		long now = System.currentTimeMillis();
		// Price history (stored, no banner), so the offer can be compared with the average.
		deliver("d/" + BLAZE + "/d1d2d3d4d5", deal(BLAZE, "Blazefarm99", "d1d2d3d4d5", "skeleton", 10, 1_700_000, now - 86_400_000), true);
		deliver("d/" + KING + "/d1d2d3d4d5", deal(KING, "Spawnerking", "d1d2d3d4d5", "skeleton", 10, 1_700_000, now - 86_400_000), true);
		deliver("o/" + KING + "/v1", offer(KING, "Spawnerking", "v1", "skeleton", true, 16, 1_450_000, now), false);
		trade(BLAZE, "Blazefarm99", BLAZE_TRADE, "start", "", now, false);
		// Last, so this chat is the newest one in the menu later.
		trade(KING, "Spawnerking", KING_TRADE, "msg", "Hast du auch Lohe-Spawner? Zahle 2M", now, false);
	}

	// ================================================================ helpers

	private static List<String> texts() {
		Trade t = market.trade(KING_TRADE);
		return t == null ? List.of() : t.messages.stream().filter(m -> !m.from.isEmpty()).map(m -> m.text).toList();
	}

	private static void deliver(String topic, JsonObject o, boolean stored) {
		market.onMessage(topic, o, stored);
	}

	private static JsonObject offer(UUID u, String name, String id, String type, boolean sell, int n, long price, long ts) {
		JsonObject o = new JsonObject();
		o.addProperty("u", u.toString());
		o.addProperty("nm", name);
		// "" = singleplayer, so they pass "only my server".
		o.addProperty("sv", "");
		o.addProperty("id", id);
		o.addProperty("ty", type);
		o.addProperty("s", sell);
		o.addProperty("n", n);
		o.addProperty("p", price);
		o.addProperty("no", "");
		o.addProperty("ts", ts);
		return o;
	}

	/** One side's record of a deal between Spawnerking (seller) and Blazefarm99 (buyer). */
	private static JsonObject deal(UUID u, String name, String trade, String type, int n, long price, long ts) {
		JsonObject d = new JsonObject();
		d.addProperty("u", u.toString());
		d.addProperty("nm", name);
		d.addProperty("tr", trade);
		d.addProperty("so", KING.toString());
		d.addProperty("sn", "Spawnerking");
		d.addProperty("bu", BLAZE.toString());
		d.addProperty("bn", "Blazefarm99");
		d.addProperty("sv", "");
		d.addProperty("ty", type);
		d.addProperty("n", n);
		d.addProperty("p", price);
		d.addProperty("ts", ts);
		return d;
	}

	/** A trade chat message to me about one of my offers. */
	private static void trade(UUID from, String name, String id, String kind, String text, long ts, boolean stored) {
		boolean king = from.equals(KING);
		JsonObject o = new JsonObject();
		o.addProperty("u", from.toString());
		o.addProperty("nm", name);
		o.addProperty("to", Market.me().toString());
		o.addProperty("k", kind);
		o.addProperty("tr", id);
		o.addProperty("tx", text);
		o.addProperty("deal", false);
		o.addProperty("oid", king ? "my1" : "my2");
		o.addProperty("ow", Market.me().toString());
		o.addProperty("on", Market.myName());
		o.addProperty("ty", king ? "skeleton" : "creeper");
		o.addProperty("s", true);
		o.addProperty("n", king ? 16 : 8);
		o.addProperty("p", king ? 1_500_000 : 900_000);
		o.addProperty("sv", "");
		o.addProperty("ts", ts);
		deliver("m/" + Market.me() + "/" + from, o, stored);
	}

	private static int px(Minecraft mc) {
		return (mc.screen.width - PW) / 2;
	}

	private static int py(Minecraft mc) {
		return (mc.screen.height - H) / 2;
	}

	private static int sw(Minecraft mc) {
		return Math.max(110, Math.min(SIDE_MAX, (mc.screen.width - 12 - PW - 2 * GAP) / 2));
	}

	/** Center of tab {@code i} of the three tabs at the top of the middle panel. */
	private static int tabX(Minecraft mc, int i) {
		int segW = (PW - 24 - 2) / 3;
		return px(mc) + 13 + i * segW + segW / 2;
	}

	/** Center of a spawner's cell on the settings page. */
	private static int cellX(Minecraft mc, String key) {
		int n = Spawners.ALL.length, cell = (PW - 20) / n;
		return px(mc) + (PW - n * cell) / 2 + Spawners.index(key) * cell + cell / 2;
	}

	private static boolean click(Minecraft mc, int x, int y) {
		mc.screen.mouseClicked(new MouseButtonEvent(x + 0.5, y + 0.5, new MouseButtonInfo(0, 0)), false);
		return true;
	}

	private static void check(boolean ok, String failure) {
		if (!ok) FAILURES.add(failure);
	}

	private static boolean shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "spawnermarket-" + name + ".png", mc.getMainRenderTarget(), 1, message -> { });
		NOTES.add("Screenshot " + name);
		return true;
	}

	private static boolean finish(Minecraft mc) {
		StringBuilder out = new StringBuilder();
		out.append("Spawner-Market-Selbsttest (").append(MODE).append(")\n");
		out.append(FAILURES.isEmpty() ? "ERGEBNIS: BESTANDEN\n" : "ERGEBNIS: FEHLGESCHLAGEN (" + FAILURES.size() + ")\n");
		FAILURES.forEach(f -> out.append("  ").append(f).append('\n'));
		out.append("\nNotizen:\n");
		NOTES.forEach(note -> out.append("  ").append(note).append('\n'));
		try {
			Files.writeString(mc.gameDirectory.toPath().resolve("spawnermarket-selftest-" + MODE + ".txt"), out.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SpawnerMarketClient.LOGGER.error("[SELFTEST] Ergebnis konnte nicht geschrieben werden", e);
		}
		SpawnerMarketClient.LOGGER.info("[SELFTEST] fertig:\n{}", out);
		mc.stop();
		return true;
	}
}
