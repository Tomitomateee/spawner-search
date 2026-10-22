package dev.spawnermarket;

import dev.spawnermarket.Market.Deal;
import dev.spawnermarket.Market.Notice;
import dev.spawnermarket.Market.Offer;
import dev.spawnermarket.MarketConfig.Trade;
import dev.spawnermarket.gui.Banner;
import dev.spawnermarket.gui.MarketScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.UUID;

/** Turns market events into banners: applies the filters from the settings, writes the text, plays the sound. */
public final class Notifications {
	private static final int GREEN = 0xFF3DDC84, RED = 0xFFF0506E;

	private Notifications() {}

	public static void handle(Market market, Notice n) {
		MarketConfig c = market.config();
		if (!c.notifications) return;
		if (!c.bannerInMarket && Minecraft.getInstance().screen instanceof MarketScreen) return;
		Banner.Note note = switch (n.kind()) {
			case OFFER -> {
				long avg = average(market, n.offer().server(), n.offer().type());
				yield wantsOffer(c, n.offer(), Market.server(), avg) ? offerNote(n.offer(), avg, !c.notifyMyServer) : null;
			}
			case DEAL -> wantsDeal(c, n.deal(), Market.server()) ? dealNote(n.deal()) : null;
			case TRADE_START -> c.notifyTrades ? tradeNote(n.trade(), "Trade", n.name() + " will handeln") : null;
			case TRADE_MESSAGE -> c.notifyMessages ? messageNote(n.from(), n.name(), n.text(), n.trade()) : null;
			case TRADE_DONE -> c.notifyTradeEnd ? tradeNote(n.trade(), "Deal", n.name() + " hat bestätigt") : null;
			case TRADE_CLOSED -> c.notifyTradeEnd ? tradeNote(n.trade(), "Trade", n.name() + " hat beendet") : null;
			case TPA -> c.notifyTpa ? tpaNote(n.from(), n.name()) : null;
			// The alarm is its own filter: it always shows.
			case ALARM -> alarmNote(n.offer());
		};
		if (note == null) return;
		boolean trade = switch (n.kind()) {
			case OFFER, DEAL, TRADE_START, TRADE_DONE, ALARM -> true;
			default -> false;
		};
		show(c, note, trade);
	}

	/** Shows a banner without any filter; {@code trade} picks the bell instead of the message sound. */
	public static void show(MarketConfig c, Banner.Note note, boolean trade) {
		Banner.show(note);
		if (c.bannerSound) {
			Minecraft.getInstance().getSoundManager().play(trade
					? SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.2f)
					: SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6f));
		}
	}

	// ================================================================ filters

	/** Whether a new offer passes the filters; {@code average} -1 = no market price yet. */
	public static boolean wantsOffer(MarketConfig c, Offer o, String myServer, long average) {
		if (!c.notifyOffers) return false;
		if (o.sell() ? !c.notifySell : !c.notifyBuy) return false;
		if (c.notifyMyServer && !o.server().equals(myServer)) return false;
		if (!c.notifyTypes.isEmpty() && !c.notifyTypes.contains(o.type())) return false;
		// Good for me: buying below the average, selling above it.
		if (c.notifyGoodPrices && average > 0 && (o.sell() ? o.price() > average : o.price() < average)) return false;
		return true;
	}

	/** Other players' new deals go through the server and spawner filters. */
	public static boolean wantsDeal(MarketConfig c, Deal d, String myServer) {
		if (!c.notifyAllDeals) return false;
		if (c.notifyMyServer && !d.server().equals(myServer)) return false;
		return c.notifyTypes.isEmpty() || c.notifyTypes.contains(d.type());
	}

	private static long average(Market market, String server, String type) {
		Market.PriceStats p = market.price(server, type);
		return p == null ? -1 : p.average();
	}

	// ================================================================ banner text

	static Banner.Note offerNote(Offer o, long average, boolean showServer) {
		String sub = o.name() + (showServer && !o.server().isEmpty() ? " · " + o.server() : "");
		String extra;
		int color;
		if (average > 0) {
			// Compared with the average price, seen from whoever answers the offer. In words:
			// Minecraft's font draws "Ø" almost like a "0".
			long pct = Math.round((o.price() - average) * 100.0 / average);
			if (pct == 0) extra = "Marktpreis";
			else if (o.sell()) extra = Math.abs(pct) + "% " + (pct < 0 ? "billiger" : "teurer");
			else extra = Math.abs(pct) + "% " + (pct > 0 ? "mehr" : "weniger");
			color = pct == 0 ? 0 : (o.sell() ? pct < 0 : pct > 0) ? GREEN : RED;
		} else {
			extra = "ges. " + Spawners.format(total(o.price(), o.amount()));
			color = 0;
		}
		return new Banner.Note(Spawners.get(o.type()), o.owner(), o.name(), amountName(o.amount(), o.type()),
				"je " + Spawners.format(o.price()), o.sell() ? "Verkauf" : "Ankauf", o.sell(), sub, extra, color);
	}

	static Banner.Note dealNote(Deal d) {
		return new Banner.Note(Spawners.get(d.type()), d.seller(), d.sellerName(), amountName(d.amount(), d.type()),
				"je " + Spawners.format(d.price()), "Verkauft", true, d.sellerName() + " → " + d.buyerName(),
				"ges. " + Spawners.format(total(d.price(), d.amount())), 0);
	}

	/** A trade chat opened or ended: what it's about, and what happened. */
	static Banner.Note tradeNote(Trade t, String tag, String sub) {
		return new Banner.Note(Spawners.get(t.type), null, "", amountName(t.amount, t.type), "je " + Spawners.format(t.price),
				tag, tag.equals("Trade"), sub, "", 0);
	}

	static Banner.Note messageNote(UUID from, String name, String text, Trade t) {
		String about = t == null ? "" : amountName(t.amount, t.type);
		return new Banner.Note(null, from, name, name, about, "Trade", true, text, "", 0);
	}

	static Banner.Note alarmNote(Offer o) {
		return new Banner.Note(Spawners.get(o.type()), o.owner(), o.name(), amountName(o.amount(), o.type()),
				"je " + Spawners.format(o.price()), "Preiswecker", o.sell(),
				o.name() + (o.sell() ? " verkauft" : " kauft") + " zu deinem Preis", "", GREEN);
	}

	static Banner.Note tpaNote(UUID from, String name) {
		return new Banner.Note(null, from, name, name, "", "TPA", true, "will sich zu dir teleportieren", "", 0);
	}

	/** Example banners for the "Test" button in the settings. */
	public static Banner.Note sample(int i) {
		long now = System.currentTimeMillis();
		String name = "Beispiel";
		Trade t = new Trade();
		t.type = "blaze";
		t.amount = 4;
		t.price = 2_000_000;
		return switch (Math.floorMod(i, 4)) {
			case 0 -> offerNote(new Offer("test", null, name, "", "skeleton", true, 16, 1_500_000, "", now), 1_620_000, false);
			case 1 -> offerNote(new Offer("test", null, name, "", "creeper", false, 8, 900_000, "", now), -1, false);
			case 2 -> messageNote(null, name, "Hast du noch Lohe-Spawner?", t);
			default -> tradeNote(t, "Trade", name + " will handeln");
		};
	}

	/** Example banner for the price alarm's "Test" buttons. */
	public static Banner.Note sampleAlarm() {
		return alarmNote(new Offer("test", null, "Beispiel", "", "iron_golem", true, 6, 3_200_000, "", System.currentTimeMillis()));
	}

	private static String amountName(int amount, String type) {
		Spawners.Type t = Spawners.get(type);
		return amount + "× " + (t == null ? type : t.name());
	}

	/** Price × amount without overflowing (up to 10^15 × 10^5). */
	private static long total(long price, int amount) {
		return price > Long.MAX_VALUE / amount ? Long.MAX_VALUE : price * amount;
	}
}
