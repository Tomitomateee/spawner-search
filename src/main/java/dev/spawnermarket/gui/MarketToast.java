package dev.spawnermarket.gui;

import dev.spawnermarket.Market;
import dev.spawnermarket.SpawnerMarketClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.UUID;

/** Glass notification at the top right for private messages and TPA requests. */
public class MarketToast implements Toast {
	private static final int W = 170, H = 34;
	private static final long SHOW_MS = 6000;

	private final String title;
	private final String message;
	private final UUID player;
	private Visibility visibility = Visibility.SHOW;

	public MarketToast(String title, String message, UUID player) {
		this.title = title;
		this.message = message;
		this.player = player;
	}

	public static void show(String title, String message, UUID player) {
		Minecraft.getInstance().getToastManager().addToast(new MarketToast(title, message, player));
	}

	@Override
	public Visibility getWantedVisibility() {
		return visibility;
	}

	@Override
	public void update(ToastManager manager, long visibleMs) {
		visibility = visibleMs >= SHOW_MS ? Visibility.HIDE : Visibility.SHOW;
	}

	@Override
	public int width() {
		return W;
	}

	@Override
	public int height() {
		return H;
	}

	@Override
	public void render(GuiGraphics g, Font font, long visibleMs) {
		Theme t = Theme.current(SpawnerMarketClient.market().config());
		Glass.shadow(g, 0, 0, W, H, 12, 100);
		ShapeCache.roundRectFaded(g, 0, 0, W, H, 12, t.accentTop() | 0xFF000000, t.accentBottom() | 0xFF000000, 0.92f);
		Glass.blob(g, W - 20, H, 30, Glass.argb(0x50, t.secondTop()));
		Glass.roundRect(g, 3, 1, W - 6, 14, 11, 0x60FFFFFF, 0x00FFFFFF);
		Glass.roundOutline(g, 0, 0, W, H, 12, 0xD0FFFFFF);

		int tx = 10;
		PlayerInfo info = player == null ? null : Market.tabInfo(player);
		if (info != null) {
			Glass.shadow(g, 8, 8, 18, 18, 4, 80);
			PlayerFaceRenderer.draw(g, info.getSkin(), 8, 8, 18);
			tx = 32;
		}
		g.drawString(font, clip(font, title, W - tx - 8), tx, 7, 0xFFFFFFFF, true);
		g.drawString(font, clip(font, message, W - tx - 8), tx, 19, 0xFFF0F0F8, false);
	}

	private static String clip(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
	}
}
