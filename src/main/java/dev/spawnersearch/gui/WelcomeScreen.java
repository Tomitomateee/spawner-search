package dev.spawnersearch.gui;

import dev.spawnersearch.Clan;
import dev.spawnersearch.Market;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Shown the first time the market is opened after joining hugoSMP (on every join): greets the
 * player, asks for "/clan info" (its answer gives the clan tag shown before their name) and links
 * the Discord. "Weiter" leads to the market.
 */
public class WelcomeScreen extends Screen {
	public static final String DISCORD = "https://discord.gg/KZMCU84vAa";
	private static final int W = 280, H = 186, WHITE = 0xFFFFFFFF;

	private final Market market;
	private int x, y;

	public WelcomeScreen(Market market) {
		super(Component.literal("Spawner Search"));
		this.market = market;
	}

	/** On hugoSMP right now (any address of it). */
	public static boolean isHugoSmp() {
		return Market.server().contains("hugosmp");
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		extractBlurredBackground(g);
		g.fillGradient(0, 0, width, height, 0x60000000, 0x80000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
		Theme t = Theme.current(market.config());
		int text = t.text(), soft = t.textSoft();
		x = (width - W) / 2;
		y = (height - H) / 2;
		Glass.shadow(g, x, y, W, H, 16, 110);
		ShapeCache.roundRectFaded(g, x, y, W, H, 16, Glass.argb(0xFF, t.panelTop()), Glass.argb(0xD4, t.panelBottom()),
				Math.max(20, Math.min(100, market.config().opacity)) / 100f);
		Glass.roundRect(g, x + 3, y + 1, W - 6, 24, 15, 0x50FFFFFF, 0x00FFFFFF);
		Glass.roundOutline(g, x, y, W, H, 16, 0xC0FFFFFF);

		// Name of the mod, big.
		float s = 1.6f;
		String title = "Spawner Search";
		g.pose().pushMatrix();
		g.pose().translate(x + W / 2f - font.width(title) * s / 2f, y + 16);
		g.pose().scale(s, s);
		g.text(font, title, 0, 0, t.accentTop() | 0xFF000000, false);
		g.pose().popMatrix();

		String name = Market.myName();
		centered(g, "Hallo " + name + "!", y + 44, text);
		centered(g, "Mach /clan info, um fortzufahren.", y + 60, soft);
		centered(g, "Solltest du in keinem Clan sein, bitte trotzdem ausführen.", y + 72, soft);

		// /clan info right from here (the answer comes in the chat and is read by the mod).
		String cmd = "/clan info ausführen";
		int cw = font.width(cmd) + 16, cx = x + (W - cw) / 2, cy = y + 88;
		Glass.pill(g, cx, cy, cw, 16, t.pillTop(), t.pillBottom(), hover(mx, my, cx, cy, cw, 16));
		centered(g, cmd, cy + 4, text);

		// Just the name until "/clan info" was answered; then the clan tag in front (if any).
		MutableComponent tag = market.clanFound() ? Clan.component(market.myClanTag(), WHITE) : null;
		MutableComponent line = Component.literal(tag != null ? "Dein Clan: " : "Dein Name: ").withColor(soft);
		if (tag != null) line.append(tag).append(" ");
		line.append(Component.literal(name).withColor(WHITE));
		g.text(font, line, x + (W - font.width(line)) / 2, y + 114, WHITE, false);
		if (market.clanInfoWaiting()) centered(g, "Warte auf die Antwort vom Server …", y + 126, t.textFaint());
		String msg = market.message();
		if (msg != null && !market.clanInfoWaiting()) centered(g, msg, y + 126, t.accentTop() | 0xFF000000);

		// Weiter (left) and Discord (right).
		int bw = (W - 36) / 2, by = y + H - 32;
		// Weiter unlocks once "/clan info" was answered.
		if (market.clanInfoDone()) {
			Glass.pill(g, x + 12, by, bw, 20, t.accentTop(), t.accentBottom(), hover(mx, my, x + 12, by, bw, 20));
			centered(g, "Weiter", by + 6, WHITE, x + 12 + bw / 2);
		} else {
			Glass.pill(g, x + 12, by, bw, 20, 0xFF6E6E78, 0xFF55555E, false);
			centered(g, "Weiter", by + 6, 0xFFB8B8C0, x + 12 + bw / 2);
		}
		Glass.pill(g, x + 24 + bw, by, bw, 20, 0xFF7289DA, 0xFF5865F2, hover(mx, my, x + 24 + bw, by, bw, 20));
		centered(g, "Discord", by + 6, WHITE, x + 24 + bw + bw / 2);
		super.extractRenderState(g, mx, my, pt);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		int mx = (int) e.x(), my = (int) e.y();
		int bw = (W - 36) / 2, by = y + H - 32;
		String cmd = "/clan info ausführen";
		int cw = font.width(cmd) + 16, cx = x + (W - cw) / 2, cy = y + 88;
		if (hover(mx, my, cx, cy, cw, 16)) {
			market.requestClanInfo();
			return true;
		}
		if (hover(mx, my, x + 12, by, bw, 20)) {
			if (!market.clanInfoDone()) {
				market.say("Führ zuerst /clan info aus");
				return true;
			}
			market.welcomeDone();
			minecraft.gui.setScreen(new MarketScreen(market));
			return true;
		}
		if (hover(mx, my, x + 24 + bw, by, bw, 20)) {
			com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(DISCORD));
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	private void centered(GuiGraphicsExtractor g, String s, int y, int color) {
		g.text(font, s, x + (W - font.width(s)) / 2, y, color, false);
	}

	private void centered(GuiGraphicsExtractor g, String s, int y, int color, int cx) {
		g.text(font, s, cx - font.width(s) / 2, y, color, false);
	}

	private static boolean hover(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}
}
