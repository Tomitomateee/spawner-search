package dev.spawnermarket.gui;

import dev.spawnermarket.Market;
import dev.spawnermarket.Market.ChatMsg;
import dev.spawnermarket.Market.Deal;
import dev.spawnermarket.Market.Offer;
import dev.spawnermarket.Market.PriceStats;
import dev.spawnermarket.Market.TpaRequest;
import dev.spawnermarket.Market.Vouch;
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
 * The liquid glass market: chats on the left, spawner trades (offers, deals, prices) in the
 * middle, vouches of the selected player on the right.
 */
public class MarketScreen extends Screen {
	private static final int PW = 230, SIDE_MAX = 170, GAP = 10, H = 250;
	private static final int ROW_H = 26, ROW_STEP = 29;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREEN = 0xFF3DDC84, ORANGE = 0xFFE0A030, RED = 0xFFF0506E;
	private static final int DRAWER_H = 104;

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

	private enum Mode { MARKET, CREATE, DEAL }

	private final Market market;
	private final List<Hit> hits = new ArrayList<>();
	/** Chat panel x, market panel x, vouch panel x, shared y, side panel width. */
	private int lx, px, vx, py, sw = SIDE_MAX;

	// Market
	private Mode mode = Mode.MARKET;
	private int tab;
	private int sideFilter;
	private String typeFilter;
	private boolean allServers;
	private int listScroll;
	private int createType;
	private boolean createSell = true;
	private Offer dealOffer;
	private boolean drawerOpen, draggingOpacity;
	private float drawerAnim;
	private long lastFrame;

	// Chat
	private UUID dmWith;
	private int chatScroll;
	private String chatCacheKey;
	private List<ChatLine> chatLines = List.of();

	private record ChatLine(FormattedCharSequence text, UUID from, String name, int nameWidth, boolean first) {}

	// Vouches
	private UUID target;
	private String targetName = "";
	private int vouchScroll;

	private EditBox chatBox, searchBox, vouchBox, amountBox, priceBox, noteBox, partnerBox;

	public MarketScreen(Market market) {
		super(Component.literal("Spawner Market"));
		this.market = market;
		applyTheme(Theme.current(market.config()), market.config().opacity);
		allServers = Market.server().isEmpty();
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
		partnerBox = box(partnerBox, 16, "Name");
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
		for (EditBox b : List.of(chatBox, searchBox, vouchBox, amountBox, priceBox, noteBox, partnerBox)) b.visible = false;

		panel(g, lx, py, sw, H);
		renderChat(g, mx, my);
		panel(g, px, py, PW, H);
		renderMarket(g, mx, my);
		panel(g, vx, py, sw, H);
		renderVouches(g, mx, my);
		renderDrawer(g, mx, my);
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

	private String scopeServer() {
		return allServers ? "" : Market.server();
	}

	private void renderMarket(GuiGraphics g, int mx, int my) {
		// Header: settings gear, title, relay status, close.
		boolean hg = hover(mx, my, px + 10, py + 7, 16, 16);
		int gearStep = Math.round(drawerAnim * 16) + (hg && drawerAnim == 0 ? 2 : 0);
		ShapeCache.gear(g, px + 11, py + 8, 14, gearStep, hg || drawerOpen ? ACCENT : TEXT_FAINT);
		hit(px + 10, py + 7, 16, 16, () -> drawerOpen = !drawerOpen);
		g.drawString(font, "Spawnerhandel", px + 30, py + 12, TEXT_FAINT, false);
		Glass.circle(g, px + 30 + font.width("Spawnerhandel") + 4, py + 13, 4, market.connected() ? GREEN : ORANGE);
		boolean hc = hover(mx, my, px + PW - 28, py + 9, 16, 14);
		Glass.roundRect(g, px + PW - 27, py + 15, 12, 2, 1, hc ? ACCENT : TEXT_FAINT);
		hit(px + PW - 28, py + 9, 16, 14, this::onClose);

		switch (mode) {
			case CREATE -> renderCreate(g, mx, my);
			case DEAL -> renderDealForm(g, mx, my);
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

		// Filter row: buy/sell (market only), server scope on the right.
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
		String scope = allServers ? "Alle Server" : "Mein Server";
		int scw = font.width(scope) + 12;
		chip(g, mx, my, px + PW - 12 - scw, fy, scope, !allServers, () -> {
			if (Market.server().isEmpty()) {
				market.say("Du bist auf keinem Server");
				return;
			}
			allServers = !allServers;
			listScroll = 0;
		});

		int top = py + 68;
		if (tab == 0 && typeFilter != null) {
			Spawners.Type t = Spawners.get(typeFilter);
			String label = "Nur " + (t == null ? typeFilter : t.name()) + "  ✕";
			boolean hov = hover(mx, my, px + 12, top - 2, font.width(label) + 4, 12);
			g.drawString(font, label, px + 14, top, hov ? ACCENT : TEXT_SOFT, false);
			hit(px + 12, top - 2, font.width(label) + 4, 12, () -> typeFilter = null);
			top += 12;
		}
		int bottom = py + H - 34;
		switch (tab) {
			case 1 -> renderDeals(g, mx, my, top, bottom, market.deals(sv));
			case 2 -> renderPrices(g, mx, my, top, bottom, market.prices(sv));
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
			centered(g, tab == 1 ? "Deals trägt der Anbieter mit ✓ ein" : "Ø aus Deals der letzten 30 Tage",
					px + PW / 2, by + 6, TEXT_FAINT);
		}
	}

	private List<Offer> filteredOffers(String sv) {
		List<Offer> list = new ArrayList<>();
		for (Offer o : market.offers(sv)) {
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
			centered(g, typeFilter != null || sideFilter != 0 ? "Nichts gefunden" : "Noch keine Angebote", px + PW / 2, (top + bottom) / 2 - 10, TEXT_SOFT);
			centered(g, allServers ? "Sei der Erste!" : "auf diesem Server", px + PW / 2, (top + bottom) / 2, TEXT_SOFT);
		}
		UUID me = Market.me();
		g.enableScissor(px + 4, top, px + PW - 4, bottom);
		for (int i = 0; i < visible + 1 && listScroll + i < list.size(); i++) {
			Offer o = list.get(listScroll + i);
			int x = px + 8, w = PW - 18, y = top + i * ROW_STEP;
			boolean hov = hover(mx, my, x, y, w, ROW_H) && my < bottom;
			Glass.glass(g, x, y, w, ROW_H, 8, hov ? 0x90FFFFFF : 0x58FFFFFF, hov ? 0x70FFFFFF : 0x38FFFFFF);
			Spawners.Type t = Spawners.get(o.type());
			if (t != null) g.renderItem(t.stack(), x + 5, y + 5);
			String name = t == null ? o.type() : t.name();
			g.drawString(font, clip(o.amount() + "× " + name, w - 96), x + 26, y + 4, TEXT, false);
			boolean online = market.online(o.owner());
			Glass.circle(g, x + 26, y + 17, 4, online ? GREEN : 0x80A09CB8);
			String sub = o.name() + " · " + (o.note().isEmpty() ? (allServers ? o.server() : Spawners.ago(o.ts())) : o.note());
			g.drawString(font, clip(sub, w - 100), x + 32, y + 15, TEXT_SOFT, false);
			// Click on the row: show the trader's vouches on the right.
			hitClipped(x, y, w, ROW_H, top, bottom, () -> selectTarget(o.owner(), o.name()));

			int rx = x + w - 6;
			if (hov) {
				// Actions: mine → deal / delete, others → chat / TPA.
				if (o.owner().equals(me)) {
					rx -= smallButton(g, mx, my, rx, y + 6, "✕", RED, top, bottom, () -> market.removeOffer(o)) + 3;
					smallButton(g, mx, my, rx, y + 6, "✓ Deal", ACCENT, top, bottom, () -> openDeal(o));
				} else {
					rx -= smallButton(g, mx, my, rx, y + 6, "TPA", ACCENT, top, bottom, () -> market.tpa(o.owner(), o.name())) + 3;
					smallButton(g, mx, my, rx, y + 6, "Chat", ACCENT, top, bottom, () -> openDm(o.owner(), o.name()));
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
			if (t != null) g.renderItem(t.stack(), x + 5, y + 5);
			g.drawString(font, clip(d.amount() + "× " + (t == null ? d.type() : t.name()), w - 80), x + 26, y + 4, TEXT, false);
			g.drawString(font, clip(d.seller() + " → " + d.buyer(), w - 80), x + 26, y + 15, TEXT_SOFT, false);
			String price = "je " + Spawners.format(d.price());
			g.drawString(font, price, x + w - 6 - font.width(price), y + 4, ACCENT, false);
			String ago = Spawners.ago(d.ts());
			g.drawString(font, ago, x + w - 6 - font.width(ago), y + 15, TEXT_FAINT, false);
			hitClipped(x, y, w, ROW_H, top, bottom, () -> selectTarget(d.owner(), d.name()));
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
			g.renderItem(p.type().stack(), x + 5, y + 5);
			g.drawString(font, p.type().name() + "-Spawner", x + 26, y + 4, TEXT, false);
			g.drawString(font, p.dealCount() + (p.dealCount() == 1 ? " Deal" : " Deals") + " · "
					+ p.offerCount() + (p.offerCount() == 1 ? " Angebot" : " Angebote"), x + 26, y + 15, TEXT_SOFT, false);
			String avg = p.average() > 0 ? "Ø " + Spawners.format(p.average()) : "Ø –";
			g.drawString(font, avg, x + w - 6 - font.width(avg), y + 4, ACCENT, false);
			String low = p.lowestSell() > 0 ? "ab " + Spawners.format(p.lowestSell())
					: p.highestBuy() > 0 ? "kauft " + Spawners.format(p.highestBuy()) : "";
			g.drawString(font, low, x + w - 6 - font.width(low), y + 15, TEAL_BOTTOM, false);
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
		int cols = 9, cell = 22, gx = px + (PW - cols * cell) / 2, gy = py + 66;
		for (int i = 0; i < Spawners.ALL.length; i++) {
			int cx = gx + (i % cols) * cell, cy = gy + (i / cols) * cell;
			boolean sel = i == createType, hov = hover(mx, my, cx, cy, cell - 2, cell - 2);
			if (sel) Glass.roundRect(g, cx, cy, cell - 2, cell - 2, 6, ACCENT_TOP, ACCENT_BOTTOM);
			else Glass.roundRect(g, cx, cy, cell - 2, cell - 2, 6, hov ? 0x80FFFFFF : 0x40FFFFFF, hov ? 0x60FFFFFF : 0x20FFFFFF);
			Glass.roundOutline(g, cx, cy, cell - 2, cell - 2, 6, 0x90FFFFFF);
			g.renderItem(Spawners.ALL[i].stack(), cx + 2, cy + 2);
			int idx = i;
			hit(cx, cy, cell - 2, cell - 2, () -> createType = idx);
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
				+ (ps.lowestSell() > 0 ? " · ab " + Spawners.format(ps.lowestSell()) : "");
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
		allServers = false;
		listScroll = 0;
	}

	// ---------------------------------------------------------------- deal form

	private void openDeal(Offer o) {
		dealOffer = o;
		mode = Mode.DEAL;
		partnerBox.setValue("");
		amountBox.setValue(Integer.toString(o.amount()));
		priceBox.setValue(Spawners.format(o.price()));
		setFocused(partnerBox);
	}

	private void renderDealForm(GuiGraphics g, int mx, int my) {
		Offer o = dealOffer;
		Spawners.Type t = Spawners.get(o.type());
		g.drawString(font, "Deal eintragen", px + 14, py + 32, TEXT, false);
		Glass.glass(g, px + 12, py + 44, PW - 24, ROW_H, 8, 0x58FFFFFF, 0x38FFFFFF);
		if (t != null) g.renderItem(t.stack(), px + 17, py + 49);
		g.drawString(font, o.amount() + "× " + (t == null ? o.type() : t.name()) + " · je " + Spawners.format(o.price()), px + 38, py + 48, TEXT, false);
		g.drawString(font, o.sell() ? "Du verkaufst" : "Du kaufst", px + 38, py + 59, TEXT_SOFT, false);

		g.drawString(font, o.sell() ? "Käufer (Name)" : "Verkäufer (Name)", px + 14, py + 80, TEXT_SOFT, false);
		field(g, partnerBox, px + 12, py + 90, PW - 24);
		int half = (PW - 24 - 8) / 2;
		g.drawString(font, "Anzahl", px + 14, py + 114, TEXT_SOFT, false);
		g.drawString(font, "Preis pro Stück", px + 12 + half + 10, py + 114, TEXT_SOFT, false);
		field(g, amountBox, px + 12, py + 124, half);
		field(g, priceBox, px + 12 + half + 8, py + 124, half);
		g.drawString(font, "Der Deal zählt für die Spawner-Preise.", px + 14, py + 152, TEXT_FAINT, false);
		g.drawString(font, "Der Rest des Angebots bleibt online.", px + 14, py + 163, TEXT_FAINT, false);
		g.drawString(font, "Danach kannst du rechts vouchen.", px + 14, py + 174, TEXT_FAINT, false);
		formButtons(g, mx, my, "Eintragen", this::submitDeal);
	}

	private void submitDeal() {
		String partner = partnerBox.getValue().trim();
		String err = market.recordDeal(dealOffer, partner, parseInt(amountBox.getValue()), Spawners.parse(priceBox.getValue()));
		if (err != null) {
			market.say(err);
			return;
		}
		market.say("Deal eingetragen");
		UUID u = market.uuidByName(partner);
		if (u != null) selectTarget(u, partner);
		amountBox.setValue("");
		priceBox.setValue("");
		backToMarket();
		tab = 1;
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
		mode = Mode.MARKET;
		dealOffer = null;
		setFocused(null);
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
		g.drawString(font, "Deckkraft", dx + 12, dy + 85, TEXT, false);
		int op = market.config().opacity;
		int ox = dx + 70, ow = dw - 82, oy = dy + 88;
		Glass.roundRect(g, ox, oy, ow, 4, 2, 0x50A09CB8);
		int of = Math.round(ow * (op - 20) / 80f);
		Glass.roundRect(g, ox, oy, Math.max(4, of), 4, 2, ACCENT_TOP, ACCENT_BOTTOM);
		Glass.circle(g, ox + of - 5, oy - 3, 10, WHITE);
		Glass.roundOutline(g, ox + of - 5, oy - 3, 10, 10, 5, 0x40000000);
		hit(ox - 6, oy - 6, ow + 12, 16, () -> {
			draggingOpacity = true;
			setOpacity(lastClickX);
		});
		g.disableScissor();
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

	// ================================================================ left: chats

	private String chatKey() {
		return switch (market.config().chatTab) {
			case 1 -> Market.serverChannel(Market.server());
			case 2 -> dmWith == null ? "" : "dm:" + dmWith;
			default -> Market.GLOBAL;
		};
	}

	private void renderChat(GuiGraphics g, int mx, int my) {
		int chatTab = market.config().chatTab;
		market.setViewing(chatKey());
		g.drawString(font, "Chats", lx + 14, py + 12, TEXT, false);
		String sv = Market.server();
		if (!sv.isEmpty() && chatTab == 1) {
			String s = clip(sv, sw - 60);
			g.drawString(font, s, lx + sw - 14 - font.width(s), py + 12, TEXT_FAINT, false);
		}
		int ug = market.unread(Market.GLOBAL), us = sv.isEmpty() ? 0 : market.unread(Market.serverChannel(sv)), ud = market.unreadDms();
		segmented(g, mx, my, lx + 12, py + 28, sw - 24, new String[] {
				ug > 0 ? "Global·" + ug : "Global", us > 0 ? "Server·" + us : "Server", ud > 0 ? "Privat·" + ud : "Privat" },
				chatTab, i -> {
					market.config().chatTab = i;
					market.config().save();
					chatScroll = 0;
				});

		int top = py + 50, bottom = py + H - 34;
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

		if (chatTab == 2 && dmWith == null) {
			renderConversations(g, mx, my, top, py + H - 8);
			return;
		}
		if (chatTab == 2) {
			// Conversation header: back, head, name, TPA.
			UUID with = dmWith;
			String name = market.name(with);
			boolean hb = hover(mx, my, lx + 8, top + 1, 14, 16);
			g.drawString(font, "‹", lx + 12, top + 5, hb ? ACCENT : TEXT_SOFT, false);
			hit(lx + 8, top + 1, 14, 16, () -> {
				dmWith = null;
				chatScroll = 0;
			});
			head(g, with, name, lx + 22, top + 3, 12);
			Glass.circle(g, lx + 37, top + 7, 4, market.online(with) ? GREEN : 0x80A09CB8);
			g.drawString(font, clip(name, sw - 90), lx + 44, top + 5, TEXT, false);
			hit(lx + 22, top + 1, sw - 80, 16, () -> selectTarget(with, name));
			smallButton(g, mx, my, lx + sw - 10, top + 2, "TPA", ACCENT, 0, height, () -> market.tpa(with, name));
			top += 20;
		}

		List<ChatMsg> msgs = chatTab == 2 ? market.dm(dmWith) : chatTab == 1 ? market.channel(Market.serverChannel(sv)) : market.channel(Market.GLOBAL);
		int textW = sw - 24;
		buildChatLines(chatKey(), msgs, textW);
		int lineH = 10, visible = Math.max(1, (bottom - top) / lineH);
		int total = chatLines.size();
		chatScroll = Math.max(0, Math.min(chatScroll, Math.max(0, total - visible)));
		if (msgs.isEmpty()) {
			String l1, l2;
			if (!market.connected()) { l1 = "Verbinde mit dem"; l2 = "Relay…"; }
			else if (chatTab == 1 && sv.isEmpty()) { l1 = "Du bist auf keinem"; l2 = "Server"; }
			else if (chatTab == 2) { l1 = "Noch keine Nachrichten"; l2 = "Schreib was!"; }
			else { l1 = "Noch ist es still hier"; l2 = "Sag Hallo!"; }
			centered(g, l1, lx + sw / 2, (top + bottom) / 2 - 10, TEXT_SOFT);
			centered(g, l2, lx + sw / 2, (top + bottom) / 2, TEXT_SOFT);
		}
		g.enableScissor(lx + 4, top, lx + sw - 4, bottom);
		int first = Math.max(0, total - visible - chatScroll);
		int last = total - chatScroll;
		int y = bottom - (last - first) * lineH;
		for (int i = first; i < last; i++, y += lineH) {
			ChatLine l = chatLines.get(i);
			g.drawString(font, l.text, lx + 12, y + 1, TEXT, false);
			if (l.first && l.from != null && !l.from.equals(Market.me())) {
				ChatLine line = l;
				// Name: open a private chat and show their vouches.
				hitClipped(lx + 12, y, l.nameWidth, lineH, top, bottom, () -> openDm(line.from, line.name));
			}
		}
		g.disableScissor();

		// Input.
		int iy = py + H - 28;
		Glass.shadow(g, lx + 10, iy, sw - 20, 18, 9, 60);
		Glass.roundRect(g, lx + 10, iy, sw - 20, 18, 9, PILL_TOP, PILL_BOTTOM);
		Glass.roundOutline(g, lx + 10, iy, sw - 20, 18, 9, chatBox.isFocused() ? ACCENT_TOP : 0xC0FFFFFF);
		place(chatBox, lx + 10, iy, sw - 20);
		chatBox.setHint(Component.literal(chatTab == 2 ? "An " + market.name(dmWith) + "…" : chatTab == 1 ? "An den Server…" : "An alle…").withColor(0xFF8E8AA0));
		hit(lx + 10, iy, sw - 20, 18, () -> setFocused(chatBox));
	}

	private void buildChatLines(String key, List<ChatMsg> msgs, int width) {
		String cacheKey = key + "|" + msgs.size() + "|" + (msgs.isEmpty() ? 0 : msgs.get(msgs.size() - 1).ts()) + "|" + width + "|" + ACCENT;
		if (cacheKey.equals(chatCacheKey)) return;
		chatCacheKey = cacheKey;
		List<ChatLine> lines = new ArrayList<>();
		for (ChatMsg m : msgs) {
			int nameColor = m.mine() ? TEAL_BOTTOM : ACCENT_TOP;
			Component c = Component.literal(m.name()).withColor(nameColor)
					.append(Component.literal(": " + m.text()).withColor(TEXT));
			List<FormattedCharSequence> split = font.split(c, width);
			for (int i = 0; i < split.size(); i++) {
				lines.add(new ChatLine(split.get(i), m.from(), m.name(), font.width(m.name()), i == 0));
			}
		}
		chatLines = lines;
	}

	private void renderConversations(GuiGraphics g, int mx, int my, int top, int bottom) {
		List<UUID> list = market.conversations();
		if (list.isEmpty()) {
			centered(g, "Keine Privatchats", lx + sw / 2, (top + bottom) / 2 - 10, TEXT_SOFT);
			centered(g, "Klick auf einen Namen", lx + sw / 2, (top + bottom) / 2, TEXT_SOFT);
			centered(g, "oder „Chat“ im Markt", lx + sw / 2, (top + bottom) / 2 + 10, TEXT_SOFT);
			return;
		}
		int step = 30;
		int visible = Math.max(1, (bottom - top) / step);
		chatScroll = Math.max(0, Math.min(chatScroll, Math.max(0, list.size() - visible)));
		g.enableScissor(lx + 4, top, lx + sw - 4, bottom);
		for (int i = 0; i < visible + 1 && chatScroll + i < list.size(); i++) {
			UUID u = list.get(chatScroll + i);
			String name = market.name(u);
			int x = lx + 8, w = sw - 16, y = top + 2 + i * step;
			boolean hov = hover(mx, my, x, y, w, 26);
			Glass.glass(g, x, y, w, 26, 9, hov ? 0x90FFFFFF : 0x58FFFFFF, hov ? 0x70FFFFFF : 0x38FFFFFF);
			head(g, u, name, x + 5, y + 7, 12);
			g.drawString(font, clip(name, w - 40), x + 22, y + 4, TEXT, false);
			List<ChatMsg> msgs = market.dm(u);
			String lastMsg = msgs.isEmpty() ? "" : (msgs.get(msgs.size() - 1).mine() ? "Du: " : "") + msgs.get(msgs.size() - 1).text();
			g.drawString(font, clip(lastMsg, w - 30), x + 22, y + 15, TEXT_SOFT, false);
			int unread = market.unread("dm:" + u);
			if (unread > 0) {
				Glass.circle(g, x + w - 14, y + 4, 10, ACCENT);
				centered(g, unread > 9 ? "+" : Integer.toString(unread), x + w - 9, y + 5, WHITE);
			} else {
				Glass.circle(g, x + w - 10, y + 6, 4, market.online(u) ? GREEN : 0x80A09CB8);
			}
			hitClipped(x, y, w, 26, top, bottom, () -> openDm(u, name));
		}
		g.disableScissor();
	}

	private void openDm(UUID with, String name) {
		if (with.equals(Market.me())) return;
		market.openDm(with);
		market.config().chatTab = 2;
		market.config().save();
		dmWith = with;
		chatScroll = 0;
		selectTarget(with, name);
		setFocused(chatBox);
	}

	private void sendChat() {
		String text = chatBox.getValue();
		if (text.isBlank()) return;
		int chatTab = market.config().chatTab;
		String err;
		if (chatTab == 2) err = dmWith == null ? "Wähle zuerst einen Chat" : market.sendDm(dmWith, text);
		else err = market.sendChat(chatTab == 1 ? Market.serverChannel(Market.server()) : Market.GLOBAL, text);
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
		String pos = "+" + score[0], neg = "−" + score[1];
		g.drawString(font, pos, vx + 47, cy + 18, GREEN, false);
		g.drawString(font, neg, vx + 47 + font.width(pos) + 6, cy + 18, RED, false);
		boolean online = market.online(target);
		String where = !online ? "offline" : market.sameServer(target) ? "hier" : market.serverOf(target);
		String whereC = clip(where, sw - 80 - font.width(pos + neg));
		g.drawString(font, whereC, vx + sw - 14 - font.width(whereC), cy + 18, online ? GREEN : TEXT_FAINT, false);

		// The list.
		boolean canVouch = !target.equals(me);
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
			Glass.roundRect(g, x + 5, y + 4, 9, 9, 4.5f, v.positive() ? GREEN : RED);
			centered(g, v.positive() ? "+" : "−", x + 10, y + 5, WHITE);
			g.drawString(font, clip(v.fromName(), w - 70), x + 18, y + 5, TEXT, false);
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
			centered(g, "Das sind deine Vouches", vx + sw / 2, py + H - 16, TEXT_FAINT);
			return;
		}
		// Write a vouch: text, then + or −.
		int iy = py + H - 50;
		Glass.shadow(g, vx + 10, iy, sw - 20, 18, 9, 60);
		Glass.roundRect(g, vx + 10, iy, sw - 20, 18, 9, PILL_TOP, PILL_BOTTOM);
		Glass.roundOutline(g, vx + 10, iy, sw - 20, 18, 9, vouchBox.isFocused() ? ACCENT_TOP : 0xC0FFFFFF);
		place(vouchBox, vx + 10, iy, sw - 20);
		hit(vx + 10, iy, sw - 20, 18, () -> setFocused(vouchBox));
		Vouch mine = market.myVouchFor(target);
		int bw = (sw - 24 - 6) / 2, by = py + H - 28;
		boolean hp = hover(mx, my, vx + 12, by, bw, 20), hn = hover(mx, my, vx + 18 + bw, by, bw, 20);
		Glass.pill(g, vx + 12, by, bw, 20, 0xFF5BE39A, 0xFF2FBF6A, hp || (mine != null && mine.positive()));
		centered(g, "+ Positiv", vx + 12 + bw / 2, by + 6, WHITE);
		Glass.pill(g, vx + 18 + bw, by, bw, 20, 0xFFF27A8E, 0xFFD43A57, hn || (mine != null && !mine.positive()));
		centered(g, "− Negativ", vx + 18 + bw + bw / 2, by + 6, WHITE);
		hit(vx + 12, by, bw, 20, () -> submitVouch(true));
		hit(vx + 18 + bw, by, bw, 20, () -> submitVouch(false));
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
			String pos = "+" + s[0], neg = "−" + s[1];
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

	private void submitVouch(boolean positive) {
		if (target == null) return;
		String err = market.vouch(target, targetName, positive, vouchBox.getValue());
		if (err != null) {
			market.say(err);
			return;
		}
		market.say("Vouch für " + targetName + " gespeichert");
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
			chatScroll += market.config().chatTab == 2 && dmWith == null ? -step : step * 3;
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
			if (getFocused() == partnerBox || (mode == Mode.DEAL && getFocused() instanceof EditBox)) { submitDeal(); return true; }
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

	private String clip(String text, int maxWidth) {
		if (text == null) return "";
		if (font.width(text) <= maxWidth) return text;
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
	}
}
