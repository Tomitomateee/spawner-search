package dev.spawnermarket.gui;

import dev.spawnermarket.Market;
import dev.spawnermarket.MarketConfig;
import dev.spawnermarket.MarketConfig.Msg;
import dev.spawnermarket.MarketConfig.Trade;
import dev.spawnermarket.Market.Deal;
import dev.spawnermarket.Market.Offer;
import dev.spawnermarket.Market.PriceStats;
import dev.spawnermarket.Market.TpaRequest;
import dev.spawnermarket.Market.Vouch;
import dev.spawnermarket.MarketConfig;
import dev.spawnermarket.Notifications;
import dev.spawnermarket.Spawners;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The liquid glass market: trade chats on the left, spawner trades (offers, deals, prices) in the
 * middle, vouches of the selected player on the right.
 */
public class MarketScreen extends Screen {
	private static final int PW = 230, SIDE_MAX = 170, GAP = 10, H = 250;
	private static final int ROW_H = 26, ROW_STEP = 29;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREEN = 0xFF3DDC84, ORANGE = 0xFFE0A030, RED = 0xFFF0506E;
	private static final int DRAWER_H = 146;

	// Theme colors; set from the selected Theme at the start of every frame.
	private static int TEXT, TEXT_SOFT, TEXT_FAINT, ICON;
	private static int ACCENT, ACCENT_TOP, ACCENT_BOTTOM, TEAL_TOP, TEAL_BOTTOM;
	private static int PILL_TOP, PILL_BOTTOM, PANEL_TOP, PANEL_BOTTOM, BLOB_A, BLOB_B;
	private static float PANEL_ALPHA;

	private static void applyTheme(Theme t, int opacity) {
		TEXT = t.text();
		TEXT_SOFT = t.textSoft();
		TEXT_FAINT = t.textFaint();
		ICON = t.textSoft();
		ACCENT = t.accentBottom();
		ACCENT_TOP = t.accentTop();
		ACCENT_BOTTOM = t.accentBottom();
		TEAL_TOP = t.secondTop();
		TEAL_BOTTOM = t.secondBottom();
		PILL_TOP = t.pillTop();
		PILL_BOTTOM = t.pillBottom();
		PANEL_ALPHA = Math.max(20, Math.min(100, opacity)) / 100f;
		PANEL_TOP = Glass.argb(0xFF, t.panelTop());
		PANEL_BOTTOM = Glass.argb(0xD4, t.panelBottom());
		BLOB_A = Glass.argb(0x40, t.blobA());
		BLOB_B = Glass.argb(0x30, t.blobB());
	}

	/** A clickable area recorded while drawing; clicks are matched against the last frame's list. */
	private record Hit(int x, int y, int w, int h, Runnable action) {}

	private enum Mode { MARKET, CREATE, NOTIFY, ALARMS }

	private final Market market;
	private final List<Hit> hits = new ArrayList<>();
	/** Chat panel x, market panel x, vouch panel x, shared y, side panel width. */
	private int lx, px, vx, py, sw = SIDE_MAX;

	// Market
	private Mode mode = Mode.MARKET;
	private int tab;
	private int sideFilter;
	private String typeFilter;
	/** "Meine Gebote": only my own offers, on every server. */
	private boolean myOnly;
	private int listScroll;
	private int createType;
	private boolean createSell = true;
	private boolean drawerOpen, draggingOpacity;
	private float drawerAnim;
	private long lastFrame;
	// Notification settings page
	private int notifyTab, testBanner;

	// Trade chats
	private String openTrade;
	private int chatScroll;
	private String chatCacheKey;
	private List<ChatLine> chatLines = List.of();

	private record ChatLine(FormattedCharSequence text) {}

	// Vouches
	private UUID target;
	private String targetName = "";
	private int vouchScroll;

	private EditBox chatBox, searchBox, vouchBox, amountBox, priceBox, noteBox;
	private final EditBox[] alarmBoxes = new EditBox[Spawners.ALL.length];

	public MarketScreen(Market market) {
		super(Component.literal("Spawner Market"));
		this.market = market;
		applyTheme(Theme.current(market.config()), market.config().opacity);
	}

	private void layout() {
		sw = Math.max(110, Math.min(SIDE_MAX, (width - 12 - PW - 2 * GAP) / 2));
		px = (width - PW) / 2;
		lx = px - GAP - sw;
		vx = px + PW + GAP;
		py = (height - H) / 2;
	}

	// ================================================================ lifecycle

	@Override
	protected void init() {
		layout();
		chatBox = box(chatBox, 200, "Nachricht…");
		searchBox = box(searchBox, 16, "Spieler suchen…");
		vouchBox = box(vouchBox, 120, "Wie lief der Handel?");
		amountBox = box(amountBox, 6, "z.B. 16");
		priceBox = box(priceBox, 14, "z.B. 250k");
		noteBox = box(noteBox, 60, "z.B. nur heute, Ort…");
		for (int i = 0; i < alarmBoxes.length; i++) alarmBoxes[i] = box(alarmBoxes[i], 14, "aus");
	}

	private EditBox box(EditBox old, int maxLength, String hint) {
		EditBox b = new EditBox(font, 0, 0, 100, 10, Component.literal(hint));
		b.setBordered(false);
		b.setTextShadow(false);
		b.setMaxLength(maxLength);
		b.setHint(Component.literal(hint).withColor(0xFF8E8AA0));
		if (old != null) b.setValue(old.getValue());
		b.visible = false;
		addRenderableWidget(b);
		return b;
	}

	/** Places a text field inside a glass pill drawn at (x, y, w, 18). */
	private void place(EditBox b, int x, int y, int w) {
		b.setX(x + 8);
		b.setY(y + 5);
		b.setWidth(w - 16);
		b.setTextColor(TEXT);
		b.visible = true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		market.setViewing("");
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		renderBlurredBackground(g);
		if (market.config().blackMode) g.fillGradient(0, 0, width, height, 0x60000000, 0x80000000);
		else g.fillGradient(0, 0, width, height, 0x40F3EEF9, 0x50D9D2E6);
	}

	// ================================================================ rendering

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		applyTheme(Theme.current(market.config()), market.config().opacity);
		long now = System.currentTimeMillis();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		drawerAnim += ((drawerOpen ? 1 : 0) - drawerAnim) * Math.min(1, dt * 12);
		if (Math.abs(drawerAnim - (drawerOpen ? 1 : 0)) < 0.002f) drawerAnim = drawerOpen ? 1 : 0;
		layout();
		hits.clear();
		for (EditBox b : List.of(chatBox, searchBox, vouchBox, amountBox, priceBox, noteBox)) b.visible = false;
		for (EditBox b : alarmBoxes) b.visible = false;

		panel(g, lx, py, sw, H);
		renderChat(g, mx, my);
		panel(g, px, py, PW, H);
		renderMarket(g, mx, my);
		panel(g, vx, py, sw, H);
		renderVouches(g, mx, my);
		renderDrawer(g, mx, my);
		if (mode == Mode.NOTIFY) {
			// Banners are hidden while this menu is open – except here, as a preview.
			g.nextStratum();
			Banner.render(g, market.config());
		}
		String msg = market.message();
		if (msg != null) {
			g.nextStratum();
			renderToast(g, px + PW / 2, Math.max(4, py - 22), PW + 2 * sw, msg);
		}
		super.render(g, mx, my, pt);
	}

	private void panel(GuiGraphics g, int x, int y, int w, int h) {
		Glass.shadow(g, x, y, w, h, 16, 110);
		ShapeCache.roundRectFaded(g, x, y, w, h, 16, PANEL_TOP, PANEL_BOTTOM, PANEL_ALPHA);
		g.enableScissor(x + 2, y + 2, x + w - 2, y + h - 2);
		Glass.blob(g, x + w - 30, y + h - 40, 60, BLOB_A);
		Glass.blob(g, x + 30, y + 40, 50, 0x30FFFFFF);
		Glass.blob(g, x + 20, y + h - 20, 40, BLOB_B);
		g.disableScissor();
		Glass.roundRect(g, x + 3, y + 1, w - 6, 24, 15, 0x50FFFFFF, 0x00FFFFFF);
		Glass.roundOutline(g, x, y, w, h, 16, 0xC0FFFFFF);
	}

	// ================================================================ middle: market

	/** The market shows my current server ("" = not on a server: every server). */
	private String scopeServer() {
		return Market.server();
	}

	private void renderMarket(GuiGraphics g, int mx, int my) {
		// Header: settings gear, title, relay status, close.
		boolean hg = hover(mx, my, px + 10, py + 7, 16, 16);
		int gearStep = Math.round(drawerAnim * 16) + (hg && drawerAnim == 0 ? 2 : 0);
		ShapeCache.gear(g, px + 11, py + 8, 14, gearStep, hg || drawerOpen ? ACCENT : TEXT_FAINT);
		hit(px + 10, py + 7, 16, 16, () -> drawerOpen = !drawerOpen);
		String title = mode == Mode.NOTIFY ? "Benachrichtigungen" : "Spawnerhandel";
		g.drawString(font, title, px + 30, py + 12, TEXT_FAINT, false);
		Glass.circle(g, px + 30 + font.width(title) + 4, py + 13, 4, market.connected() ? GREEN : ORANGE);
		boolean hc = hover(mx, my, px + PW - 28, py + 9, 16, 14);
		Glass.roundRect(g, px + PW - 27, py + 15, 12, 2, 1, hc ? ACCENT : TEXT_FAINT);
		hit(px + PW - 28, py + 9, 16, 14, this::onClose);

		switch (mode) {
			case CREATE -> renderCreate(g, mx, my);
			case NOTIFY -> renderNotify(g, mx, my);
			case ALARMS -> renderAlarms(g, mx, my);
			default -> renderMarketTabs(g, mx, my);
		}
	}

	private void renderMarketTabs(GuiGraphics g, int mx, int my) {
		String sv = scopeServer();
		List<Offer> offers = filteredOffers(sv);
		segmented(g, mx, my, px + 12, py + 28, PW - 24,
				new String[] { "Markt · " + offers.size(), "Deals", "Preise" }, tab, i -> {
					tab = i;
					listScroll = 0;
				});

		// Filter row: buy/sell and "my offers" (market only).
		int fy = py + 50;
		if (tab == 0) {
			String[] sides = { "Alle", "Verkauf", "Ankauf" };
			int cx = px + 12;
			for (int i = 0; i < sides.length; i++) {
				int idx = i;
				cx += chip(g, mx, my, cx, fy, sides[i], sideFilter == i, () -> {
					sideFilter = idx;
					listScroll = 0;
				}) + 4;
			}
		}
		if (tab == 0) {
			String mine = "Meine Gebote";
			int mw = font.width(mine) + 12;
			chip(g, mx, my, px + PW - 12 - mw, fy, mine, myOnly, () -> {
				myOnly = !myOnly;
				listScroll = 0;
			});
		} else {
			g.drawString(font, tab == 1 ? "Abgeschlossene Deals" : "Höchste Preise", px + 14, fy + 3, TEXT_SOFT, false);
		}

		// Spawner filter: click a model to show only that spawner, click again for all.
		int n = Spawners.ALL.length, cell = (PW - 24) / n, gx = px + 12 + (PW - 24 - n * cell) / 2, gy = py + 68;
		for (int i = 0; i < n; i++) {
			Spawners.Type t = Spawners.ALL[i];
			int cx = gx + i * cell, cw = cell - 3;
			boolean sel = t.key().equals(typeFilter), hov = hover(mx, my, cx, gy, cw, 20);
			if (sel) Glass.roundRect(g, cx, gy, cw, 20, 6, ACCENT_TOP, ACCENT_BOTTOM);
			else Glass.roundRect(g, cx, gy, cw, 20, 6, hov ? 0x70FFFFFF : 0x30FFFFFF, hov ? 0x50FFFFFF : 0x18FFFFFF);
			Glass.roundOutline(g, cx, gy, cw, 20, 6, sel ? 0xD0FFFFFF : 0x70FFFFFF);
			t.draw(g, cx + (cw - 16) / 2, gy + 2, 16);
			hit(cx, gy, cw, 20, () -> {
				typeFilter = t.key().equals(typeFilter) ? null : t.key();
				listScroll = 0;
			});
		}

		int top = py + 94;
		int bottom = py + H - 34;
		switch (tab) {
			case 1 -> renderDeals(g, mx, my, top, bottom, filterDeals(market.deals(sv)));
			case 2 -> renderPrices(g, mx, my, top, bottom, filterPrices(market.prices(sv)));
			default -> renderOffers(g, mx, my, top, bottom, offers);
		}

		// Bottom: create an offer / explanation.
		int by = py + H - 28;
		if (tab == 0) {
			boolean hb = hover(mx, my, px + 12, by, PW - 24, 20);
			Glass.pill(g, px + 12, by, PW - 24, 20, ACCENT_TOP, ACCENT_BOTTOM, hb);
			centered(g, "+ Angebot erstellen", px + PW / 2, by + 6, WHITE);
			hit(px + 12, by, PW - 24, 20, this::openCreate);
		} else {
			centered(g, tab == 1 ? "Deals entstehen durch einen Vouch" : "Höchster Ankauf / Verkauf · Ø aus Deals",
					px + PW / 2, by + 6, TEXT_FAINT);
		}
	}

	private List<Deal> filterDeals(List<Deal> deals) {
		if (typeFilter == null) return deals;
		List<Deal> list = new ArrayList<>();
		for (Deal d : deals) if (typeFilter.equals(d.type())) list.add(d);
		return list;
	}

	private List<PriceStats> filterPrices(List<PriceStats> prices) {
		if (typeFilter == null) return prices;
		List<PriceStats> list = new ArrayList<>();
		for (PriceStats p : prices) if (typeFilter.equals(p.type().key())) list.add(p);
		return list;
	}

	private List<Offer> filteredOffers(String sv) {
		List<Offer> list = new ArrayList<>();
		for (Offer o : myOnly ? market.myOffers() : market.offers(sv)) {
			if (sideFilter == 1 && !o.sell()) continue;
			if (sideFilter == 2 && o.sell()) continue;
			if (typeFilter != null && !typeFilter.equals(o.type())) continue;
			list.add(o);
		}
		return list;
	}

	private int visibleRows(int top, int bottom) {
		return Math.max(1, (bottom - top + 3) / ROW_STEP);
	}

	private void scrollbar(GuiGraphics g, int top, int bottom, int total, int visible) {
		if (total <= visible) return;
		int trackH = bottom - top - 3;
		int thumbH = Math.max(10, trackH * visible / total);
		int thumbY = top + (trackH - thumbH) * listScroll / Math.max(1, total - visible);
		Glass.roundRect(g, px + PW - 6, thumbY, 3, thumbH, 1.5f, Glass.argb(0x80, ACCENT));
	}

	private void renderOffers(GuiGraphics g, int mx, int my, int top, int bottom, List<Offer> list) {
		int visible = visibleRows(top, bottom);
		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, list.size() - visible)));
		if (list.isEmpty()) {
			if (myOnly) {
				centered(g, "Du hast keine Gebote", px + PW / 2, (top + bottom) / 2 - 10, TEXT_SOFT);
				centered(g, "„+ Angebot erstellen“", px + PW / 2, (top + bottom) / 2, TEXT_SOFT);
			} else {
				centered(g, typeFilter != null || sideFilter != 0 ? "Nichts gefunden" : "Noch keine Angebote", px + PW / 2, (top + bottom) / 2 - 10, TEXT_SOFT);
				centered(g, Market.server().isEmpty() ? "Sei der Erste!" : "auf diesem Server", px + PW / 2, (top + bottom) / 2, TEXT_SOFT);
			}
		}
		UUID me = Market.me();
		g.enableScissor(px + 4, top, px + PW - 4, bottom);
		for (int i = 0; i < visible + 1 && listScroll + i < list.size(); i++) {
			Offer o = list.get(listScroll + i);
			int x = px + 8, w = PW - 18, y = top + i * ROW_STEP;
			boolean hov = hover(mx, my, x, y, w, ROW_H) && my < bottom;
			Glass.glass(g, x, y, w, ROW_H, 8, hov ? 0x90FFFFFF : 0x58FFFFFF, hov ? 0x70FFFFFF : 0x38FFFFFF);
			Spawners.Type t = Spawners.get(o.type());
			if (t != null) t.draw(g, x + 3, y + 3, 20);
			String name = t == null ? o.type() : t.name();
			g.drawString(font, clip(o.amount() + "× " + name, w - 96), x + 26, y + 4, TEXT, false);
			boolean online = market.online(o.owner());
			Glass.circle(g, x + 26, y + 17, 4, online ? GREEN : 0x80A09CB8);
			String sub = o.name() + " · " + (o.note().isEmpty() ? (myOnly || Market.server().isEmpty() ? o.server() : Spawners.ago(o.ts())) : o.note());
			g.drawString(font, clip(sub, w - 100), x + 32, y + 15, TEXT_SOFT, false);
			// Click on the row: show the trader's vouches on the right.
			hitClipped(x, y, w, ROW_H, top, bottom, () -> selectTarget(o.owner(), o.name()));

			int rx = x + w - 6;
			if (hov) {
				// Actions: mine → delete, others → trade chat / TPA.
				if (o.owner().equals(me)) {
					smallButton(g, mx, my, rx, y + 6, "✕", RED, top, bottom, () -> market.removeOffer(o));
				} else {
					rx -= smallButton(g, mx, my, rx, y + 6, "TPA", ACCENT, top, bottom, () -> market.tpa(o.owner(), o.name())) + 3;
					smallButton(g, mx, my, rx, y + 6, "Handeln", ACCENT, top, bottom, () -> startTrade(o));
				}
			} else {
				String price = "je " + Spawners.format(o.price());
				g.drawString(font, price, rx - font.width(price), y + 4, ACCENT, false);
				String side = o.sell() ? "Verkauf" : "Ankauf";
				g.drawString(font, side, rx - font.width(side), y + 15, o.sell() ? TEAL_BOTTOM : ACCENT_TOP, false);
			}
		}
		g.disableScissor();
		scrollbar(g, top, bottom, list.size(), visible);
	}

	private void renderDeals(GuiGraphics g, int mx, int my, int top, int bottom, List<Deal> list) {
		int visible = visibleRows(top, bottom);
		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, list.size() - visible)));
		if (list.isEmpty()) {
			centered(g, "Noch keine Deals", px + PW / 2, (top + bottom) / 2 - 10, TEXT_SOFT);
			centered(g, "in den letzten 30 Tagen", px + PW / 2, (top + bottom) / 2, TEXT_SOFT);
		}
		g.enableScissor(px + 4, top, px + PW - 4, bottom);
		for (int i = 0; i < visible + 1 && listScroll + i < list.size(); i++) {
			Deal d = list.get(listScroll + i);
			int x = px + 8, w = PW - 18, y = top + i * ROW_STEP;
			boolean hov = hover(mx, my, x, y, w, ROW_H) && my < bottom;
			Glass.glass(g, x, y, w, ROW_H, 8, hov ? 0x90FFFFFF : 0x58FFFFFF, hov ? 0x70FFFFFF : 0x38FFFFFF);
			Spawners.Type t = Spawners.get(d.type());
			if (t != null) t.draw(g, x + 3, y + 3, 20);
			g.drawString(font, clip(d.amount() + "× " + (t == null ? d.type() : t.name()), w - 80), x + 26, y + 4, TEXT, false);
			g.drawString(font, clip(d.sellerName() + " → " + d.buyerName(), w - 80), x + 26, y + 15, TEXT_SOFT, false);
			String price = "je " + Spawners.format(d.price());
			g.drawString(font, price, x + w - 6 - font.width(price), y + 4, ACCENT, false);
			String ago = Spawners.ago(d.ts());
			g.drawString(font, ago, x + w - 6 - font.width(ago), y + 15, TEXT_FAINT, false);
			hitClipped(x, y, w, ROW_H, top, bottom, () -> selectTarget(d.seller(), d.sellerName()));
		}
		g.disableScissor();
		scrollbar(g, top, bottom, list.size(), visible);
	}

	private void renderPrices(GuiGraphics g, int mx, int my, int top, int bottom, List<PriceStats> list) {
		int visible = visibleRows(top, bottom);
		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, list.size() - visible)));
		if (list.isEmpty()) {
			centered(g, "Noch keine Preise", px + PW / 2, (top + bottom) / 2 - 10, TEXT_SOFT);
			centered(g, "– es gibt noch keine Deals", px + PW / 2, (top + bottom) / 2, TEXT_SOFT);
		}
		g.enableScissor(px + 4, top, px + PW - 4, bottom);
		for (int i = 0; i < visible + 1 && listScroll + i < list.size(); i++) {
			PriceStats p = list.get(listScroll + i);
			int x = px + 8, w = PW - 18, y = top + i * ROW_STEP;
			boolean hov = hover(mx, my, x, y, w, ROW_H) && my < bottom;
			Glass.glass(g, x, y, w, ROW_H, 8, hov ? 0x90FFFFFF : 0x58FFFFFF, hov ? 0x70FFFFFF : 0x38FFFFFF);
			p.type().draw(g, x + 3, y + 3, 20);
			g.drawString(font, p.type().name() + "-Spawner", x + 26, y + 4, TEXT, false);
			String avg = (p.average() > 0 ? "Ø " + Spawners.format(p.average()) : "Ø –") + " · "
					+ p.dealCount() + (p.dealCount() == 1 ? " Deal" : " Deals");
			g.drawString(font, clip(avg, w - 110), x + 26, y + 15, TEXT_SOFT, false);
			// Highest prices, like in the market list.
			String buy = "Ankauf " + (p.highestBuy() > 0 ? Spawners.format(p.highestBuy()) : "–");
			String sell = "Verkauf " + (p.highestSell() > 0 ? Spawners.format(p.highestSell()) : "–");
			g.drawString(font, buy, x + w - 6 - font.width(buy), y + 4, ACCENT_TOP, false);
			g.drawString(font, sell, x + w - 6 - font.width(sell), y + 15, TEAL_BOTTOM, false);
			// Click: show the offers of this type.
			hitClipped(x, y, w, ROW_H, top, bottom, () -> {
				typeFilter = p.type().key();
				tab = 0;
				listScroll = 0;
			});
		}
		g.disableScissor();
		scrollbar(g, top, bottom, list.size(), visible);
	}

	// ---------------------------------------------------------------- create offer

	private void openCreate() {
		if (Market.server().isEmpty()) {
			market.say("Tritt erst einem Server bei");
			return;
		}
		mode = Mode.CREATE;
		if (typeFilter != null && Spawners.index(typeFilter) >= 0) createType = Spawners.index(typeFilter);
		setFocused(amountBox);
	}

	private void renderCreate(GuiGraphics g, int mx, int my) {
		segmented(g, mx, my, px + 12, py + 28, PW - 24, new String[] { "Ich verkaufe", "Ich kaufe" },
				createSell ? 0 : 1, i -> createSell = i == 0);

		Spawners.Type type = Spawners.ALL[createType];
		g.drawString(font, "Spawner: ", px + 14, py + 54, TEXT_SOFT, false);
		g.drawString(font, type.name(), px + 14 + font.width("Spawner: "), py + 54, TEXT, false);
		// One cell per spawner model, the name below it.
		int n = Spawners.ALL.length, cell = (PW - 20) / n, gx = px + (PW - n * cell) / 2, gy = py + 66, ch = 42;
		for (int i = 0; i < n; i++) {
			int cx = gx + i * cell, cw = cell - 2;
			boolean sel = i == createType, hov = hover(mx, my, cx, gy, cw, ch);
			if (sel) Glass.roundRect(g, cx, gy, cw, ch, 6, ACCENT_TOP, ACCENT_BOTTOM);
			else Glass.roundRect(g, cx, gy, cw, ch, 6, hov ? 0x80FFFFFF : 0x40FFFFFF, hov ? 0x60FFFFFF : 0x20FFFFFF);
			Glass.roundOutline(g, cx, gy, cw, ch, 6, 0x90FFFFFF);
			int is = Math.min(24, cw - 4);
			Spawners.ALL[i].draw(g, cx + (cw - is) / 2, gy + 3, is);
			smallCentered(g, Spawners.ALL[i].name(), cx + cw / 2, gy + 31, sel ? WHITE : TEXT_SOFT, 0.7f);
			int idx = i;
			hit(cx, gy, cw, ch, () -> createType = idx);
		}

		int half = (PW - 24 - 8) / 2;
		g.drawString(font, "Anzahl", px + 14, py + 116, TEXT_SOFT, false);
		g.drawString(font, "Preis pro Stück", px + 12 + half + 10, py + 116, TEXT_SOFT, false);
		field(g, amountBox, px + 12, py + 126, half);
		field(g, priceBox, px + 12 + half + 8, py + 126, half);
		g.drawString(font, "Notiz (optional)", px + 14, py + 150, TEXT_SOFT, false);
		field(g, noteBox, px + 12, py + 160, PW - 24);

		// Market price for orientation and the total.
		PriceStats ps = market.price(Market.server(), type.key());
		String hint = ps == null ? "Noch kein Marktpreis für " + type.name()
				: "Markt: " + (ps.average() > 0 ? "Ø " + Spawners.format(ps.average()) : "Ø –")
				+ (ps.highestBuy() > 0 ? " · AK " + Spawners.format(ps.highestBuy()) : "")
				+ (ps.highestSell() > 0 ? " · VK " + Spawners.format(ps.highestSell()) : "");
		g.drawString(font, clip(hint, PW - 28), px + 14, py + 186, TEXT_FAINT, false);
		long price = Spawners.parse(priceBox.getValue());
		int amount = parseInt(amountBox.getValue());
		if (price > 0 && amount > 0) {
			String total = "Gesamt: " + Spawners.format(price * amount);
			g.drawString(font, total, px + 14, py + 198, ACCENT, false);
		}
		formButtons(g, mx, my, "Veröffentlichen", this::submitCreate);
	}

	private void submitCreate() {
		int amount = parseInt(amountBox.getValue());
		long price = Spawners.parse(priceBox.getValue());
		if (amount <= 0) { market.say("Gib eine Anzahl ein"); return; }
		if (price <= 0) { market.say("Gib einen Preis ein, z.B. 250k"); return; }
		String err = market.publishOffer(Spawners.ALL[createType], createSell, amount, price, noteBox.getValue());
		if (err != null) {
			market.say(err);
			return;
		}
		market.say("Angebot ist online");
		amountBox.setValue("");
		priceBox.setValue("");
		noteBox.setValue("");
		backToMarket();
		tab = 0;
		sideFilter = 0;
		typeFilter = null;
		myOnly = true;
		listScroll = 0;
	}

	private void formButtons(GuiGraphics g, int mx, int my, String confirm, Runnable action) {
		int by = py + H - 28, cw = 80;
		boolean hc = hover(mx, my, px + 12, by, cw, 20);
		Glass.pill(g, px + 12, by, cw, 20, PILL_TOP, PILL_BOTTOM, hc);
		centered(g, "Abbrechen", px + 12 + cw / 2, by + 6, TEXT);
		hit(px + 12, by, cw, 20, this::backToMarket);
		int ox = px + 12 + cw + 8, ow = PW - 24 - cw - 8;
		boolean ho = hover(mx, my, ox, by, ow, 20);
		Glass.pill(g, ox, by, ow, 20, ACCENT_TOP, ACCENT_BOTTOM, ho);
		centered(g, confirm, ox + ow / 2, by + 6, WHITE);
		hit(ox, by, ow, 20, action);
	}

	private void backToMarket() {
		if (mode == Mode.ALARMS) saveAlarms();
		mode = Mode.MARKET;
		setFocused(null);
	}

	// ---------------------------------------------------------------- notification settings

	private void openNotify() {
		mode = Mode.NOTIFY;
		drawerOpen = false;
		setFocused(null);
	}

	private void renderNotify(GuiGraphics g, int mx, int my) {
		MarketConfig c = market.config();
		segmented(g, mx, my, px + 12, py + 28, PW - 24, new String[] { "Angebote", "Trades", "Anzeige" }, notifyTab, i -> notifyTab = i);
		switch (notifyTab) {
			case 1 -> {
				setting(g, py + 54, "Neue Trades", c.notifyTrades, () -> c.notifyTrades = !c.notifyTrades);
				hint(g, py + 69, "Jemand will über dein Angebot handeln");
				setting(g, py + 86, "Trade-Nachrichten", c.notifyMessages, () -> c.notifyMessages = !c.notifyMessages);
				setting(g, py + 106, "Deal / Trade beendet", c.notifyTradeEnd, () -> c.notifyTradeEnd = !c.notifyTradeEnd);
				setting(g, py + 126, "TPA-Anfragen", c.notifyTpa, () -> c.notifyTpa = !c.notifyTpa);
				setting(g, py + 146, "Alle neuen Deals", c.notifyAllDeals, () -> c.notifyAllDeals = !c.notifyAllDeals);
				hint(g, py + 161, "Mit Server- und Spawner-Filter");
			}
			case 2 -> {
				g.drawString(font, "Position oben", px + 14, py + 56, TEXT, false);
				segmented(g, mx, my, px + 12, py + 68, PW - 24, new String[] { "Links", "Mitte", "Rechts" }, c.bannerPos, i -> {
					c.bannerPos = i;
					c.save();
				});
				g.drawString(font, "Anzeigedauer", px + 14, py + 97, TEXT, false);
				int rx = px + PW - 12;
				rx -= smallButton(g, mx, my, rx, py + 94, "+", ACCENT, 0, height, () -> setBannerSeconds(c.bannerSeconds + 1)) + 4;
				String secs = c.bannerSeconds + " s";
				g.drawString(font, secs, rx - font.width(secs), py + 97, TEXT_SOFT, false);
				rx -= font.width(secs) + 4;
				smallButton(g, mx, my, rx, py + 94, "−", ACCENT, 0, height, () -> setBannerSeconds(c.bannerSeconds - 1));
				setting(g, py + 116, "Ton", c.bannerSound, () -> c.bannerSound = !c.bannerSound);
				setting(g, py + 136, "Auch über Menüs", c.bannerInMenus, () -> c.bannerInMenus = !c.bannerInMenus);
				hint(g, py + 151, "Inventar, Kisten, Pause-Menü…");
				hint(g, py + 172, "Kein Banner, solange dieses Menü");
				hint(g, py + 182, "offen ist – es zeigt alles selbst.");
			}
			default -> renderNotifyOffers(g, mx, my, c);
		}

		// Bottom: try it out, back.
		int by = py + H - 28, cw = 80;
		boolean ht = hover(mx, my, px + 12, by, cw, 20);
		Glass.pill(g, px + 12, by, cw, 20, PILL_TOP, PILL_BOTTOM, ht);
		centered(g, "Test", px + 12 + cw / 2, by + 6, TEXT);
		hit(px + 12, by, cw, 20, () -> {
			// Takes turns: two offers, a trade message, a new trade.
			int i = testBanner++ % 4;
			Notifications.show(c, Notifications.sample(i), i != 2);
		});
		int ox = px + 12 + cw + 8, ow = PW - 24 - cw - 8;
		boolean ho = hover(mx, my, ox, by, ow, 20);
		Glass.pill(g, ox, by, ow, 20, ACCENT_TOP, ACCENT_BOTTOM, ho);
		centered(g, "Fertig", ox + ow / 2, by + 6, WHITE);
		hit(ox, by, ow, 20, this::backToMarket);
	}

	private void renderNotifyOffers(GuiGraphics g, int mx, int my, MarketConfig c) {
		setting(g, py + 54, "Neue Angebote", c.notifyOffers, () -> c.notifyOffers = !c.notifyOffers);
		int cx = px + 12, fy = py + 74;
		cx += chip(g, mx, my, cx, fy, "Verkauf", c.notifySell, () -> {
			c.notifySell = !c.notifySell;
			c.save();
		}) + 4;
		chip(g, mx, my, cx, fy, "Ankauf", c.notifyBuy, () -> {
			c.notifyBuy = !c.notifyBuy;
			c.save();
		});
		String scope = c.notifyMyServer ? "Nur mein Server" : "Alle Server";
		int scw = font.width(scope) + 12;
		chip(g, mx, my, px + PW - 12 - scw, fy, scope, c.notifyMyServer, () -> {
			c.notifyMyServer = !c.notifyMyServer;
			c.save();
		});
		setting(g, py + 94, "Nur gute Preise", c.notifyGoodPrices, () -> c.notifyGoodPrices = !c.notifyGoodPrices);
		hint(g, py + 109, "Nur besser als der Schnittpreis");

		// Which spawners: none picked = all. Same cells as in "Angebot erstellen".
		g.drawString(font, "Spawner", px + 14, py + 126, TEXT, false);
		if (c.notifyTypes.isEmpty()) {
			g.drawString(font, "alle", px + PW - 14 - font.width("alle"), py + 126, TEXT_SOFT, false);
		} else {
			String reset = c.notifyTypes.size() + " gewählt  ✕";
			boolean hr = hover(mx, my, px + PW - 16 - font.width(reset), py + 124, font.width(reset) + 4, 12);
			g.drawString(font, reset, px + PW - 14 - font.width(reset), py + 126, hr ? ACCENT : TEXT_SOFT, false);
			hit(px + PW - 16 - font.width(reset), py + 124, font.width(reset) + 4, 12, () -> {
				c.notifyTypes.clear();
				c.save();
			});
		}
		int n = Spawners.ALL.length, cell = (PW - 20) / n, gx = px + (PW - n * cell) / 2, gy = py + 138, ch = 42;
		for (int i = 0; i < n; i++) {
			Spawners.Type type = Spawners.ALL[i];
			int x = gx + i * cell, cw = cell - 2;
			boolean sel = c.notifyTypes.contains(type.key()), hov = hover(mx, my, x, gy, cw, ch);
			if (sel) Glass.roundRect(g, x, gy, cw, ch, 6, ACCENT_TOP, ACCENT_BOTTOM);
			else Glass.roundRect(g, x, gy, cw, ch, 6, hov ? 0x80FFFFFF : 0x40FFFFFF, hov ? 0x60FFFFFF : 0x20FFFFFF);
			Glass.roundOutline(g, x, gy, cw, ch, 6, 0x90FFFFFF);
			int is = Math.min(24, cw - 4);
			type.draw(g, x + (cw - is) / 2, gy + 3, is);
			smallCentered(g, type.name(), x + cw / 2, gy + 31, sel ? WHITE : TEXT_SOFT, 0.7f);
			hit(x, gy, cw, ch, () -> {
				if (!c.notifyTypes.remove(type.key())) c.notifyTypes.add(type.key());
				c.save();
			});
		}
		hint(g, gy + ch + 6, "Nichts gewählt = alle Spawner");
	}

	/** On/off row on the settings page; saves right away. */
	private void setting(GuiGraphics g, int y, String label, boolean on, Runnable flip) {
		toggleRow(g, px, PW, y, label, on, () -> {
			flip.run();
			market.config().save();
		});
	}

	private void hint(GuiGraphics g, int y, String text) {
		g.drawString(font, clip(text, PW - 28), px + 14, y, TEXT_FAINT, false);
	}

	private void setBannerSeconds(int s) {
		market.config().bannerSeconds = Math.max(3, Math.min(15, s));
		market.config().save();
	}

	// ---------------------------------------------------------------- settings drawer

	private void renderDrawer(GuiGraphics g, int mx, int my) {
		if (drawerAnim <= 0) return;
		float e = 1 - (1 - drawerAnim) * (1 - drawerAnim) * (1 - drawerAnim);
		int dw = PW - 24, dx = px + 12, top = py + 26;
		int dy = top - Math.round((1 - e) * (DRAWER_H + 4));
		g.nextStratum();
		g.enableScissor(px, top, px + PW, top + DRAWER_H + 12);
		Glass.shadow(g, dx, dy, dw, DRAWER_H, 12, 120);
		Glass.roundRect(g, dx, dy, dw, DRAWER_H, 12, Glass.argb(0xF4, PILL_TOP), Glass.argb(0xF0, PILL_BOTTOM));
		Glass.roundOutline(g, dx, dy, dw, DRAWER_H, 12, 0xC0FFFFFF);
		// Clicks inside the drawer never reach what's under it.
		hit(dx, dy, dw, DRAWER_H, () -> { });

		g.drawString(font, "Farbe", dx + 12, dy + 9, TEXT, false);
		String name = Theme.get(market.config().theme).name();
		g.drawString(font, name, dx + dw - 12 - font.width(name), dy + 9, TEXT_SOFT, false);
		for (int i = 0; i < Theme.ALL.length; i++) {
			Theme t = Theme.ALL[i];
			int sx0 = dx + 12 + i * 20, sy0 = dy + 22;
			boolean sel = i == market.config().theme;
			Glass.roundRect(g, sx0, sy0, 16, 16, 8, t.accentTop(), t.accentBottom());
			Glass.roundRect(g, sx0 + 8, sy0 + 8, 7, 7, 3.5f, t.secondTop(), t.secondBottom());
			Glass.roundOutline(g, sx0, sy0, 16, 16, 8, 0xB0FFFFFF);
			if (sel) Glass.roundOutline(g, sx0 - 2, sy0 - 2, 20, 20, 10, TEXT);
			int idx = i;
			hit(sx0, sy0, 16, 16, () -> {
				market.config().theme = idx;
				market.config().save();
				ShapeCache.clear();
			});
		}
		toggleRow(g, dx, dw, dy + 44, "Black Mode", market.config().blackMode, () -> {
			market.config().blackMode = !market.config().blackMode;
			market.config().save();
			ShapeCache.clear();
		});
		toggleRow(g, dx, dw, dy + 62, "Benachrichtigungen", market.config().notifications, () -> {
			market.config().notifications = !market.config().notifications;
			market.config().save();
		});
		boolean hn = hover(mx, my, dx + 8, dy + 78, dw - 16, 18);
		g.drawString(font, "Filter & Anzeige", dx + 20, dy + 83, hn ? ACCENT : TEXT_SOFT, false);
		g.drawString(font, "›", dx + dw - 18, dy + 83, hn ? ACCENT : TEXT_SOFT, false);
		hit(dx + 8, dy + 78, dw - 16, 18, this::openNotify);
		g.drawString(font, "Deckkraft", dx + 12, dy + 103, TEXT, false);
		int op = market.config().opacity;
		int ox = dx + 70, ow = dw - 82, oy = dy + 106;
		Glass.roundRect(g, ox, oy, ow, 4, 2, 0x50A09CB8);
		int of = Math.round(ow * (op - 20) / 80f);
		Glass.roundRect(g, ox, oy, Math.max(4, of), 4, 2, ACCENT_TOP, ACCENT_BOTTOM);
		Glass.circle(g, ox + of - 5, oy - 3, 10, WHITE);
		Glass.roundOutline(g, ox + of - 5, oy - 3, 10, 10, 5, 0x40000000);
		hit(ox - 6, oy - 6, ow + 12, 16, () -> {
			draggingOpacity = true;
			setOpacity(lastClickX);
		});
		int ay = dy + 122;
		boolean ha = hover(mx, my, dx + 12, ay, dw - 24, 18);
		Glass.pill(g, dx + 12, ay, dw - 24, 18, TEAL_TOP, TEAL_BOTTOM, ha);
		int active = 0;
		for (MarketConfig.Alarm a : market.config().alarms.values()) if (a.price > 0) active++;
		centered(g, "Preiswecker" + (active > 0 ? " · " + active + " aktiv" : "") + "  ›", dx + dw / 2, ay + 5, WHITE);
		hit(dx + 12, ay, dw - 24, 18, this::openAlarms);
		g.disableScissor();
	}

	// ---------------------------------------------------------------- price alarms

	private void openAlarms() {
		drawerOpen = false;
		drawerAnim = 0;
		mode = Mode.ALARMS;
		for (int i = 0; i < alarmBoxes.length; i++) {
			MarketConfig.Alarm a = market.config().alarms.get(Spawners.ALL[i].key());
			alarmBoxes[i].setValue(a == null || a.price <= 0 ? "" : Spawners.format(a.price));
		}
		setFocused(null);
	}

	/** One row per spawner: model, name, buy/sell switch and the price to wait for. */
	private void renderAlarms(GuiGraphics g, int mx, int my) {
		g.drawString(font, "Preiswecker", px + 14, py + 32, TEXT, false);
		String sv = Market.server();
		g.drawString(font, clip(sv.isEmpty() ? "gilt für deinen Server" : "für " + sv, PW - 110), px + 90, py + 32, TEXT_FAINT, false);
		for (int i = 0; i < Spawners.ALL.length; i++) {
			Spawners.Type t = Spawners.ALL[i];
			MarketConfig.Alarm a = market.config().alarms.computeIfAbsent(t.key(), k -> new MarketConfig.Alarm());
			int y = py + 44 + i * 22, x = px + 8, w = PW - 16;
			Glass.glass(g, x, y, w, 20, 8, 0x58FFFFFF, 0x38FFFFFF);
			t.draw(g, x + 3, y + 2, 16);
			g.drawString(font, t.name(), x + 24, y + 6, TEXT, false);
			// Kaufen: tell me when someone sells at or below; Verkaufen: when someone buys at or above.
			String mode = a.buy ? "Kaufen ≤" : "Verkaufen ≥";
			int cw = font.width("Verkaufen ≥") + 12, cx = x + 88;
			chip(g, mx, my, cx, y + 3, mode, a.buy, () -> a.buy = !a.buy);
			field(g, alarmBoxes[i], cx + cw + 4, y + 1, x + w - 4 - (cx + cw + 4));
		}
		centered(g, "Leer = aus · Meldung bei deinem Preis", px + PW / 2, py + H - 44, TEXT_FAINT);
		formButtons(g, mx, my, "Speichern", this::backToMarket);
	}

	private void saveAlarms() {
		int active = 0;
		for (int i = 0; i < alarmBoxes.length; i++) {
			MarketConfig.Alarm a = market.config().alarms.computeIfAbsent(Spawners.ALL[i].key(), k -> new MarketConfig.Alarm());
			long p = Spawners.parse(alarmBoxes[i].getValue());
			a.price = Math.max(0, p);
			if (a.price > 0) active++;
		}
		market.config().save();
		market.checkAlarms();
		market.say(active == 0 ? "Preiswecker aus" : active + (active == 1 ? " Preiswecker aktiv" : " Preiswecker aktiv"));
	}

	private void setOpacity(int mx) {
		int ox = px + 12 + 70, ow = PW - 24 - 82;
		market.config().opacity = Math.max(20, Math.min(100, 20 + Math.round((mx - ox) * 80f / ow)));
	}

	private void toggleRow(GuiGraphics g, int dx, int dw, int y, String label, boolean on, Runnable action) {
		g.drawString(font, label, dx + 12, y + 3, TEXT, false);
		int tgx = dx + dw - 40;
		Glass.roundRect(g, tgx, y, 28, 14, 7, on ? ACCENT_TOP : 0x60A09CB8, on ? ACCENT_BOTTOM : 0x60A09CB8);
		Glass.roundOutline(g, tgx, y, 28, 14, 7, 0x90FFFFFF);
		Glass.circle(g, on ? tgx + 15 : tgx + 1, y + 1, 12, WHITE);
		hit(dx + 8, y - 2, dw - 16, 18, action);
	}

	// ================================================================ left: trade chats

	private Trade currentTrade() {
		Trade t = market.trade(openTrade);
		if (t == null) openTrade = null;
		return t;
	}

	private void renderChat(GuiGraphics g, int mx, int my) {
		Trade trade = currentTrade();
		market.setViewing(openTrade);
		g.drawString(font, "Trade-Chats", lx + 14, py + 12, TEXT, false);
		int unread = market.unreadTrades();
		if (unread > 0) {
			String u = unread > 99 ? "99+" : Integer.toString(unread);
			int w = Math.max(12, font.width(u) + 6);
			Glass.roundRect(g, lx + sw - 14 - w, py + 10, w, 12, 6, ACCENT_TOP, ACCENT_BOTTOM);
			centered(g, u, lx + sw - 14 - w / 2, py + 12, WHITE);
		}

		int top = py + 28, bottom = py + H - 34;
		// Incoming TPA requests on top.
		for (TpaRequest r : List.copyOf(market.tpaRequests())) {
			int x = lx + 8, w = sw - 16;
			Glass.glass(g, x, top + 2, w, 22, 9, Glass.argb(0xC0, TEAL_TOP), Glass.argb(0xA0, TEAL_BOTTOM));
			g.drawString(font, clip(r.name() + " will TPA", w - 64), x + 8, top + 9, WHITE, false);
			boolean ha = hover(mx, my, x + w - 58, top + 5, 38, 16), hx = hover(mx, my, x + w - 18, top + 5, 16, 16);
			Glass.roundRect(g, x + w - 58, top + 5, 38, 16, 8, ha ? WHITE : 0x60FFFFFF, ha ? WHITE : 0x40FFFFFF);
			centered(g, "Ja", x + w - 39, top + 9, ha ? TEXT : WHITE);
			centered(g, "✕", x + w - 10, top + 9, hx ? TEXT : WHITE);
			hit(x + w - 58, top + 5, 38, 16, () -> market.acceptTpa(r));
			hit(x + w - 18, top + 5, 16, 16, () -> market.declineTpa(r));
			top += 26;
		}

		if (trade == null) {
			renderTradeList(g, mx, my, top, py + H - 8);
			return;
		}

		// Header: back, partner, TPA, end; below it what the trade is about.
		String me = Market.me().toString();
		UUID with = UUID.fromString(trade.other(me));
		String name = trade.otherName(me);
		boolean hb = hover(mx, my, lx + 8, top + 1, 14, 16);
		g.drawString(font, "‹", lx + 12, top + 5, hb ? ACCENT : TEXT_SOFT, false);
		hit(lx + 8, top + 1, 14, 16, () -> openTrade = null);
		head(g, with, name, lx + 22, top + 3, 12);
		Glass.circle(g, lx + 37, top + 7, 4, market.online(with) ? GREEN : 0x80A09CB8);
		g.drawString(font, clip(name, sw - 112), lx + 44, top + 5, TEXT, false);
		hit(lx + 22, top + 1, sw - 112, 16, () -> selectTarget(with, name));
		int rx = lx + sw - 10;
		rx -= smallButton(g, mx, my, rx, top + 2, "✕", RED, 0, height, () -> {
			market.cancelTrade(trade);
			openTrade = null;
			market.say("Trade beendet");
		}) + 3;
		smallButton(g, mx, my, rx, top + 2, "TPA", ACCENT, 0, height, () -> market.tpa(with, name));
		top += 19;
		Spawners.Type type = Spawners.get(trade.type);
		Glass.glass(g, lx + 8, top, sw - 16, 22, 8, 0x58FFFFFF, 0x38FFFFFF);
		if (type != null) type.draw(g, lx + 11, top + 2, 18);
		String what = trade.amount + "× " + (type == null ? trade.type : type.name()) + " · je " + Spawners.format(trade.price);
		g.drawString(font, clip(what, sw - 42), lx + 32, top + 3, TEXT, false);
		String role = me.equals(trade.owner) == trade.sell ? "Du verkaufst" : "Du kaufst";
		g.drawString(font, role, lx + 32, top + 12, TEXT_SOFT, false);
		top += 26;

		List<Msg> msgs = trade.messages;
		buildChatLines(trade.id, msgs, sw - 24);
		// The first message sits at the top; once the chat is full it follows the newest one.
		int lineH = 10, visible = Math.max(1, (bottom - top) / lineH);
		int total = chatLines.size();
		chatScroll = Math.max(0, Math.min(chatScroll, Math.max(0, total - visible)));
		g.enableScissor(lx + 4, top, lx + sw - 4, bottom);
		int first = Math.max(0, total - visible - chatScroll);
		int last = Math.min(total, first + visible);
		int y = top;
		for (int i = first; i < last; i++, y += lineH) g.drawString(font, chatLines.get(i).text, lx + 12, y + 1, TEXT, false);
		g.disableScissor();

		// Input.
		int iy = py + H - 28;
		Glass.shadow(g, lx + 10, iy, sw - 20, 18, 9, 60);
		Glass.roundRect(g, lx + 10, iy, sw - 20, 18, 9, PILL_TOP, PILL_BOTTOM);
		Glass.roundOutline(g, lx + 10, iy, sw - 20, 18, 9, chatBox.isFocused() ? ACCENT_TOP : 0xC0FFFFFF);
		place(chatBox, lx + 10, iy, sw - 20);
		chatBox.setHint(Component.literal("An " + name + "…").withColor(0xFF8E8AA0));
		hit(lx + 10, iy, sw - 20, 18, () -> setFocused(chatBox));
	}

	private void buildChatLines(String key, List<Msg> msgs, int width) {
		// The version changes with every message, also when the chat is full and the oldest drops out.
		String cacheKey = key + "|" + market.chatVersion() + "|" + width + "|" + ACCENT;
		if (cacheKey.equals(chatCacheKey)) return;
		chatCacheKey = cacheKey;
		String me = Market.me().toString();
		List<ChatLine> lines = new ArrayList<>();
		for (Msg m : msgs) {
			Component c;
			if (m.from.isEmpty()) {
				// A note from the mod: faint, without a name.
				c = Component.literal(m.text).withColor(TEXT_FAINT);
			} else {
				int nameColor = me.equals(m.from) ? TEAL_BOTTOM : ACCENT_TOP;
				c = Component.literal(m.name).withColor(nameColor).append(Component.literal(": " + m.text).withColor(TEXT));
			}
			for (FormattedCharSequence line : font.split(c, width)) lines.add(new ChatLine(line));
		}
		chatLines = lines;
	}

	private void renderTradeList(GuiGraphics g, int mx, int my, int top, int bottom) {
		List<Trade> list = market.trades();
		if (list.isEmpty()) {
			centered(g, "Keine offenen Trades", lx + sw / 2, (top + bottom) / 2 - 10, TEXT_SOFT);
			centered(g, "„Handeln“ an einem", lx + sw / 2, (top + bottom) / 2, TEXT_SOFT);
			centered(g, "Angebot startet einen", lx + sw / 2, (top + bottom) / 2 + 10, TEXT_SOFT);
			return;
		}
		String me = Market.me().toString();
		int step = 30;
		int visible = Math.max(1, (bottom - top) / step);
		chatScroll = Math.max(0, Math.min(chatScroll, Math.max(0, list.size() - visible)));
		g.enableScissor(lx + 4, top, lx + sw - 4, bottom);
		for (int i = 0; i < visible + 1 && chatScroll + i < list.size(); i++) {
			Trade t = list.get(chatScroll + i);
			UUID u = UUID.fromString(t.other(me));
			String name = t.otherName(me);
			int x = lx + 8, w = sw - 16, y = top + 2 + i * step;
			boolean hov = hover(mx, my, x, y, w, 26);
			Glass.glass(g, x, y, w, 26, 9, hov ? 0x90FFFFFF : 0x58FFFFFF, hov ? 0x70FFFFFF : 0x38FFFFFF);
			Spawners.Type type = Spawners.get(t.type);
			if (type != null) type.draw(g, x + 3, y + 3, 20);
			g.drawString(font, clip(name, w - 44), x + 26, y + 4, TEXT, false);
			Msg lastMsg = t.messages.isEmpty() ? null : t.messages.get(t.messages.size() - 1);
			String sub = lastMsg == null || lastMsg.from.isEmpty()
					? t.amount + "× " + (type == null ? t.type : type.name()) + " · je " + Spawners.format(t.price)
					: (me.equals(lastMsg.from) ? "Du: " : "") + lastMsg.text;
			g.drawString(font, clip(sub, w - 32), x + 26, y + 15, TEXT_SOFT, false);
			if (t.unread > 0) {
				Glass.circle(g, x + w - 14, y + 4, 10, ACCENT);
				centered(g, t.unread > 9 ? "+" : Integer.toString(t.unread), x + w - 9, y + 5, WHITE);
			} else {
				Glass.circle(g, x + w - 10, y + 6, 4, market.online(u) ? GREEN : 0x80A09CB8);
			}
			hitClipped(x, y, w, 26, top, bottom, () -> openTrade(t));
		}
		g.disableScissor();
	}

	private void startTrade(Offer o) {
		Trade t = market.startTrade(o);
		if (t != null) openTrade(t);
	}

	private void openTrade(Trade t) {
		openTrade = t.id;
		chatScroll = 0;
		String me = Market.me().toString();
		selectTarget(UUID.fromString(t.other(me)), t.otherName(me));
		setFocused(chatBox);
	}

	private void sendChat() {
		Trade t = currentTrade();
		if (t == null || chatBox.getValue().isBlank()) return;
		String err = market.sendTradeMessage(t, chatBox.getValue());
		if (err != null) market.say(err);
		else {
			chatBox.setValue("");
			chatScroll = 0;
		}
	}

	// ================================================================ right: vouches

	private void selectTarget(UUID uuid, String name) {
		target = uuid;
		targetName = name == null || name.isEmpty() || name.equals("?") ? market.name(uuid) : name;
		vouchScroll = 0;
	}

	private void renderVouches(GuiGraphics g, int mx, int my) {
		g.drawString(font, "Vouches", vx + 14, py + 12, TEXT, false);
		UUID me = Market.me();
		boolean hm = hover(mx, my, vx + sw - 30, py + 7, 16, 16);
		// My own vouches: my head, top right.
		head(g, me, Market.myName(), vx + sw - 29, py + 8, 14);
		Glass.roundOutline(g, vx + sw - 30, py + 7, 16, 16, 4, hm ? ACCENT : 0xB0FFFFFF);
		hit(vx + sw - 30, py + 7, 16, 16, () -> selectTarget(me, Market.myName()));

		// Search by name.
		int sy = py + 28;
		Glass.shadow(g, vx + 10, sy, sw - 20, 18, 9, 60);
		Glass.roundRect(g, vx + 10, sy, sw - 20, 18, 9, PILL_TOP, PILL_BOTTOM);
		Glass.roundOutline(g, vx + 10, sy, sw - 20, 18, 9, searchBox.isFocused() ? ACCENT_TOP : 0xC0FFFFFF);
		place(searchBox, vx + 10, sy, sw - 34);
		Glass.searchIcon(g, vx + sw - 22, sy + 9, ICON);
		hit(vx + sw - 30, sy, 20, 18, this::searchPlayer);
		hit(vx + 10, sy, sw - 20, 18, () -> setFocused(searchBox));

		if (target == null) {
			renderTopTraders(g, mx, my, py + 52, py + H - 8);
			return;
		}

		// Selected player: head, name, score, where they are.
		int cy = py + 52;
		Glass.glass(g, vx + 8, cy, sw - 16, 32, 10, 0x58FFFFFF, 0x38FFFFFF);
		boolean hb = hover(mx, my, vx + 10, cy + 8, 12, 16);
		g.drawString(font, "‹", vx + 13, cy + 12, hb ? ACCENT : TEXT_SOFT, false);
		hit(vx + 10, cy + 8, 12, 16, () -> target = null);
		head(g, target, targetName, vx + 22, cy + 6, 20);
		g.drawString(font, clip(targetName, sw - 70), vx + 47, cy + 6, TEXT, false);
		int[] score = market.score(target);
		String pos = "+" + score[0], neu = "○" + score[1], neg = "−" + score[2];
		int sx0 = vx + 47;
		g.drawString(font, pos, sx0, cy + 18, GREEN, false);
		sx0 += font.width(pos) + 5;
		g.drawString(font, neu, sx0, cy + 18, TEXT_SOFT, false);
		sx0 += font.width(neu) + 5;
		g.drawString(font, neg, sx0, cy + 18, RED, false);
		boolean online = market.online(target);
		String where = !online ? "offline" : market.sameServer(target) ? "hier" : market.serverOf(target);
		String whereC = clip(where, vx + sw - 18 - (sx0 + font.width(neg)));
		g.drawString(font, whereC, vx + sw - 14 - font.width(whereC), cy + 18, online ? GREEN : TEXT_FAINT, false);

		// The list.
		// Vouching only works for the partner of an open trade.
		Trade trade = target.equals(me) ? null : market.tradeWith(target);
		boolean canVouch = trade != null;
		int top = cy + 38, bottom = canVouch ? py + H - 54 : py + H - 22;
		List<Vouch> list = market.vouchesFor(target);
		vouchScroll = Math.max(0, Math.min(vouchScroll, Math.max(0, list.size() - 1)));
		if (list.isEmpty()) {
			centered(g, "Noch keine Vouches", vx + sw / 2, (top + bottom) / 2 - 5, TEXT_SOFT);
		}
		g.enableScissor(vx + 4, top, vx + sw - 4, bottom);
		int y = top;
		for (int i = vouchScroll; i < list.size() && y < bottom; i++) {
			Vouch v = list.get(i);
			List<FormattedCharSequence> lines = font.split(Component.literal(v.text()), sw - 30);
			if (lines.size() > 3) lines = lines.subList(0, 3);
			int h = 16 + lines.size() * 10;
			int x = vx + 8, w = sw - 16;
			boolean hov = hover(mx, my, x, y, w, h) && my < bottom;
			Glass.glass(g, x, y, w, h, 8, hov ? 0x90FFFFFF : 0x58FFFFFF, hov ? 0x70FFFFFF : 0x38FFFFFF);
			boolean counts = market.counts(v);
			int badge = v.rating() > 0 ? (counts ? GREEN : Glass.argb(0x80, GREEN)) : v.rating() == 0 ? 0xFF9A98AC : RED;
			Glass.roundRect(g, x + 5, y + 4, 9, 9, 4.5f, badge);
			centered(g, v.rating() > 0 ? "+" : v.rating() == 0 ? "○" : "−", x + 10, y + 5, WHITE);
			// A positive vouch counts once both sides confirmed the deal.
			String from = counts ? v.fromName() : v.fromName() + " (unbestätigt)";
			g.drawString(font, clip(from, w - 70), x + 18, y + 5, counts ? TEXT : TEXT_FAINT, false);
			String ago = Spawners.ago(v.ts());
			g.drawString(font, ago, x + w - 6 - font.width(ago), y + 5, TEXT_FAINT, false);
			for (int j = 0; j < lines.size(); j++) g.drawString(font, lines.get(j), x + 8, y + 16 + j * 10, TEXT_SOFT, false);
			if (v.from().equals(me) && hov) {
				smallButton(g, mx, my, x + w - 4, y + h - 16, "✕", RED, top, bottom, () -> market.removeVouch(v.target()));
			} else {
				hitClipped(x, y, w, 14, top, bottom, () -> selectTarget(v.from(), v.fromName()));
			}
			y += h + 3;
		}
		g.disableScissor();

		if (!canVouch) {
			centered(g, target.equals(me) ? "Das sind deine Vouches" : "Vouchen geht nur nach einem Trade",
					vx + sw / 2, py + H - 16, TEXT_FAINT);
			return;
		}
		// Write a vouch: text, then +, neutral or −.
		int iy = py + H - 50;
		Glass.shadow(g, vx + 10, iy, sw - 20, 18, 9, 60);
		Glass.roundRect(g, vx + 10, iy, sw - 20, 18, 9, PILL_TOP, PILL_BOTTOM);
		Glass.roundOutline(g, vx + 10, iy, sw - 20, 18, 9, vouchBox.isFocused() ? ACCENT_TOP : 0xC0FFFFFF);
		place(vouchBox, vx + 10, iy, sw - 20);
		vouchBox.setHint(Component.literal("Wie lief der Trade?").withColor(0xFF8E8AA0));
		hit(vx + 10, iy, sw - 20, 18, () -> setFocused(vouchBox));
		// + closes the deal, ○ = trade didn't happen (offer stays), − = no deal; the chat closes on each.
		int bw = (sw - 24 - 8) / 3, by = py + H - 28;
		int[] ratings = { 1, 0, -1 };
		String[] labels = { "+ Deal", "○ Neutral", "− Neg." };
		int[][] colors = { { 0xFF5BE39A, 0xFF2FBF6A }, { 0xFFB8B6C8, 0xFF8A88A0 }, { 0xFFF27A8E, 0xFFD43A57 } };
		for (int i = 0; i < 3; i++) {
			int bx = vx + 12 + i * (bw + 4), rating = ratings[i];
			Glass.pill(g, bx, by, bw, 20, colors[i][0], colors[i][1], hover(mx, my, bx, by, bw, 20));
			centered(g, clip(labels[i], bw - 4), bx + bw / 2, by + 6, WHITE);
			hit(bx, by, bw, 20, () -> submitVouch(rating));
		}
	}

	private void renderTopTraders(GuiGraphics g, int mx, int my, int top, int bottom) {
		g.drawString(font, "Top-Händler", vx + 14, top + 2, TEXT_SOFT, false);
		top += 14;
		List<UUID> list = market.topVouched(30);
		if (list.isEmpty()) {
			centered(g, "Noch keine Vouches", vx + sw / 2, (top + bottom) / 2 - 10, TEXT_SOFT);
			centered(g, "Klick auf einen Händler", vx + sw / 2, (top + bottom) / 2, TEXT_SOFT);
			return;
		}
		int step = 22, visible = Math.max(1, (bottom - top) / step);
		vouchScroll = Math.max(0, Math.min(vouchScroll, Math.max(0, list.size() - visible)));
		g.enableScissor(vx + 4, top, vx + sw - 4, bottom);
		for (int i = 0; i < visible + 1 && vouchScroll + i < list.size(); i++) {
			UUID u = list.get(vouchScroll + i);
			String name = market.name(u);
			int x = vx + 8, w = sw - 16, y = top + i * step;
			boolean hov = hover(mx, my, x, y, w, 19);
			Glass.glass(g, x, y, w, 19, 8, hov ? 0x90FFFFFF : 0x58FFFFFF, hov ? 0x70FFFFFF : 0x38FFFFFF);
			centered(g, Integer.toString(vouchScroll + i + 1), x + 9, y + 6, TEXT_FAINT);
			head(g, u, name, x + 17, y + 4, 11);
			g.drawString(font, clip(name, w - 70), x + 32, y + 6, TEXT, false);
			int[] s = market.score(u);
			String pos = "+" + s[0], neg = "−" + s[2];
			g.drawString(font, neg, x + w - 6 - font.width(neg), y + 6, RED, false);
			g.drawString(font, pos, x + w - 10 - font.width(neg) - font.width(pos), y + 6, GREEN, false);
			hitClipped(x, y, w, 19, top, bottom, () -> selectTarget(u, name));
		}
		g.disableScissor();
	}

	private void searchPlayer() {
		String name = searchBox.getValue().trim();
		if (name.isEmpty()) return;
		UUID u = market.uuidByName(name);
		if (u == null) {
			market.say(name + " ist unbekannt (braucht die Mod oder muss online sein)");
			return;
		}
		selectTarget(u, name);
		searchBox.setValue("");
		setFocused(null);
	}

	private void submitVouch(int rating) {
		if (target == null) return;
		Trade trade = currentTrade();

		String err = market.vouch(target, targetName, rating, vouchBox.getValue(), trade);
		if (err != null) {
			market.say(err);
			return;
		}
		if (trade != null && market.trade(trade.id) == null) openTrade = null;
		vouchBox.setValue("");
		setFocused(null);
	}

	// ================================================================ widgets

	/** Segmented tab control; returns nothing, registers its own hits. */
	private void segmented(GuiGraphics g, int mx, int my, int x, int y, int w, String[] labels, int active,
	                       java.util.function.IntConsumer onSelect) {
		Glass.glass(g, x, y, w, 18, 9, 0x50FFFFFF, 0x30FFFFFF);
		int segW = (w - 2) / labels.length;
		for (int i = 0; i < labels.length; i++) {
			int sx0 = x + 1 + i * segW;
			boolean act = i == active, hov = hover(mx, my, sx0, y + 1, segW, 16);
			if (act) Glass.roundRect(g, sx0, y + 1, segW, 16, 8, ACCENT_TOP, ACCENT_BOTTOM);
			else if (hov) Glass.roundRect(g, sx0, y + 1, segW, 16, 8, 0x40FFFFFF, 0x20FFFFFF);
			centered(g, clip(labels[i], segW - 4), sx0 + segW / 2, y + 5, act ? WHITE : TEXT_SOFT);
			int idx = i;
			hit(sx0, y + 1, segW, 16, () -> onSelect.accept(idx));
		}
	}

	/** Small rounded filter chip; returns its width. */
	private int chip(GuiGraphics g, int mx, int my, int x, int y, String label, boolean active, Runnable action) {
		int w = font.width(label) + 12;
		boolean hov = hover(mx, my, x, y, w, 14);
		if (active) Glass.roundRect(g, x, y, w, 14, 7, Glass.argb(0xD0, ACCENT_TOP), Glass.argb(0xD0, ACCENT_BOTTOM));
		else Glass.roundRect(g, x, y, w, 14, 7, hov ? 0x60FFFFFF : 0x30FFFFFF, hov ? 0x40FFFFFF : 0x18FFFFFF);
		Glass.roundOutline(g, x, y, w, 14, 7, 0x90FFFFFF);
		g.drawString(font, label, x + 6, y + 3, active ? WHITE : TEXT_SOFT, false);
		hit(x, y, w, 14, action);
		return w;
	}

	/** Small pill button whose RIGHT edge is at {@code right}; returns its width. */
	private int smallButton(GuiGraphics g, int mx, int my, int right, int y, String label, int color,
	                        int clipTop, int clipBottom, Runnable action) {
		int w = font.width(label) + 10, x = right - w;
		boolean hov = hover(mx, my, x, y, w, 14);
		Glass.roundRect(g, x, y, w, 14, 7, hov ? color : Glass.argb(0x30, color), hov ? color : Glass.argb(0x20, color));
		Glass.roundOutline(g, x, y, w, 14, 7, hov ? 0xC0FFFFFF : Glass.argb(0xC0, color));
		g.drawString(font, label, x + 5, y + 3, hov ? WHITE : color, false);
		// Registered after the row, so it wins over the row's own click.
		hitClipped(x, y, w, 14, clipTop, clipBottom, action);
		return w;
	}

	private void field(GuiGraphics g, EditBox b, int x, int y, int w) {
		Glass.shadow(g, x, y, w, 18, 9, 60);
		Glass.roundRect(g, x, y, w, 18, 9, PILL_TOP, PILL_BOTTOM);
		Glass.roundOutline(g, x, y, w, 18, 9, b.isFocused() ? ACCENT_TOP : 0xC0FFFFFF);
		place(b, x, y, w);
		hit(x, y, w, 18, () -> setFocused(b));
	}

	/** Player head from the tab list, else a colored letter. */
	private void head(GuiGraphics g, UUID uuid, String name, int x, int y, int size) {
		PlayerInfo info = Market.tabInfo(uuid);
		if (info != null) {
			PlayerFaceRenderer.draw(g, info.getSkin(), x, y, size);
			return;
		}
		Glass.roundRect(g, x, y, size, size, Math.max(2, size / 4f), ACCENT_TOP, ACCENT_BOTTOM);
		String letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
		centered(g, letter, x + size / 2 + 1, y + (size - 8) / 2 + 1, WHITE);
	}

	private void renderToast(GuiGraphics g, int cx, int y, int maxW, String msg) {
		msg = clip(msg, maxW - 20);
		int tw = font.width(msg) + 20;
		int tx = cx - tw / 2;
		Glass.glass(g, tx, y, tw, 16, 8, Glass.argb(0xF0, PILL_TOP), Glass.argb(0xE8, PILL_BOTTOM));
		g.drawString(font, msg, tx + 10, y + 4, TEXT, false);
	}

	// ================================================================ input

	private int lastClickX;

	private void hit(int x, int y, int w, int h, Runnable action) {
		hits.add(new Hit(x, y, w, h, action));
	}

	/** A hit area limited to a scrolling list's visible rows. */
	private void hitClipped(int x, int y, int w, int h, int top, int bottom, Runnable action) {
		int y0 = Math.max(y, top), y1 = Math.min(y + h, bottom);
		if (y1 > y0) hits.add(new Hit(x, y0, w, y1 - y0, action));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() != 0) return super.mouseClicked(e, doubleClick);
		int mx = (int) e.x(), my = (int) e.y();
		lastClickX = mx;
		if (drawerOpen && !hover(mx, my, px + 12, py + 26, PW - 24, DRAWER_H) && !hover(mx, my, px + 10, py + 7, 16, 16)) {
			drawerOpen = false;
			return true;
		}
		// Topmost (drawn last) first.
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (hover(mx, my, h.x, h.y, h.w, h.h)) {
				EditBox before = getFocused() instanceof EditBox b ? b : null;
				h.action.run();
				// Clicking anywhere but a text field takes the focus away from it.
				if (before != null && getFocused() == before && !isFieldHit(before, mx, my)) setFocused(null);
				if (getFocused() instanceof EditBox b && isFieldHit(b, mx, my)) super.mouseClicked(e, doubleClick);
				return true;
			}
		}
		setFocused(null);
		return super.mouseClicked(e, doubleClick);
	}

	private static boolean isFieldHit(EditBox b, int mx, int my) {
		return b.visible && mx >= b.getX() - 8 && mx < b.getX() + b.getWidth() + 8 && my >= b.getY() - 5 && my < b.getY() + 13;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (draggingOpacity) {
			setOpacity((int) e.x());
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (draggingOpacity) {
			draggingOpacity = false;
			market.config().save();
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sxAmount, double syAmount) {
		int step = (int) Math.signum(syAmount);
		if (hover((int) mx, (int) my, lx, py, sw, H)) {
			// Chat scrolls in lines (up = older), the conversation list in rows.
			chatScroll += currentTrade() == null ? -step : step * 3;
			return true;
		}
		if (hover((int) mx, (int) my, px, py, PW, H)) {
			listScroll -= step;
			return true;
		}
		if (hover((int) mx, (int) my, vx, py, sw, H)) {
			vouchScroll -= step;
			return true;
		}
		return super.mouseScrolled(mx, my, sxAmount, syAmount);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.isEscape()) {
			if (drawerOpen) { drawerOpen = false; return true; }
			if (mode != Mode.MARKET) { backToMarket(); return true; }
			if (getFocused() instanceof EditBox) { setFocused(null); return true; }
			onClose();
			return true;
		}
		if (e.isConfirmation()) {
			if (getFocused() == chatBox) { sendChat(); return true; }
			if (getFocused() == searchBox) { searchPlayer(); return true; }
			if (mode == Mode.CREATE && getFocused() instanceof EditBox) { submitCreate(); return true; }
		}
		return super.keyPressed(e);
	}

	// ================================================================ helpers

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static boolean hover(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	private void centered(GuiGraphics g, String text, int cx, int y, int color) {
		g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
	}

	private void smallCentered(GuiGraphics g, String text, int cx, int y, int color, float scale) {
		g.pose().pushMatrix();
		g.pose().translate(cx - font.width(text) * scale / 2f, y);
		g.pose().scale(scale, scale);
		g.drawString(font, text, 0, 0, color, false);
		g.pose().popMatrix();
	}

	private String clip(String text, int maxWidth) {
		if (text == null) return "";
		if (font.width(text) <= maxWidth) return text;
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
	}
}
