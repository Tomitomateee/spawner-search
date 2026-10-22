package dev.spawnersearch.gui;

import dev.spawnersearch.Market;
import dev.spawnersearch.MarketConfig;
import dev.spawnersearch.Spawners;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Small glass banners at the top of the screen for new offers and deals, trade chats and TPA
 * requests. Up to three at once, stacked downwards; more wait their turn.
 */
public final class Banner {
	public static final int W = 216, H = 30;
	private static final int GAP = 4, MARGIN = 4, MAX = 3;
	private static final long FADE_MS = 200;

	/**
	 * One banner. Left: the {@code spawner} model, else the head of {@code player}. First line:
	 * {@code title} and {@code right} (accent color). Second line: a {@code tag} pill (accent, or the
	 * second color with {@code tagSecond}), {@code sub} and {@code subRight} in {@code subRightColor}.
	 */
	public record Note(Spawners.Type spawner, UUID player, String playerName, String title, String right,
	                   String tag, boolean tagSecond, String sub, String subRight, int subRightColor) {}

	private static final class Shown {
		final Note note;
		final long at;
		float y = Float.NaN;

		Shown(Note note, long at) {
			this.note = note;
			this.at = at;
		}
	}

	private static final List<Shown> shown = new ArrayList<>();
	private static final ArrayDeque<Note> waiting = new ArrayDeque<>();
	private static long lastFrame;

	private Banner() {}

	public static void show(Note n) {
		waiting.add(n);
	}

	public static void clear() {
		shown.clear();
		waiting.clear();
	}

	/** Banners on screen right now (for the self-test). */
	public static int visible() {
		return shown.size();
	}

	public static void render(GuiGraphics g, MarketConfig cfg) {
		long now = System.currentTimeMillis();
		long life = cfg.bannerSeconds * 1000L;
		shown.removeIf(s -> now - s.at > life);
		while (shown.size() < MAX && !waiting.isEmpty()) shown.add(new Shown(waiting.poll(), now));
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		if (shown.isEmpty()) return;

		Font font = Minecraft.getInstance().font;
		Theme t = Theme.current(cfg);
		int sw = g.guiWidth();
		int x = switch (cfg.bannerPos) {
			case 0 -> MARGIN;
			case 2 -> sw - W - MARGIN;
			default -> (sw - W) / 2;
		};
		int target = MARGIN;
		for (Shown s : shown) {
			// Slides up smoothly when the one above it goes.
			if (Float.isNaN(s.y)) s.y = target;
			s.y += (target - s.y) * Math.min(1, dt * 12);
			long age = now - s.at;
			float a = Math.min(1, Math.min(age, life - age) / (float) FADE_MS);
			// In steps, so the cached glass shapes aren't rebuilt for every frame of a fade.
			a = Math.round(Math.max(0, a) * 10) / 10f;
			draw(g, font, t, s.note, x, Math.round(s.y - (1 - a) * 6), a, age / (float) life);
			target += H + GAP;
		}
	}

	private static void draw(GuiGraphics g, Font font, Theme t, Note n, int x, int y, float a, float progress) {
		if (a <= 0) return;
		Glass.shadow(g, x, y, W, H, 10, Math.round(110 * a));
		ShapeCache.roundRectFaded(g, x, y, W, H, 10, t.pillTop() | 0xFF000000, t.pillBottom() | 0xFF000000, 0.96f * a);
		Glass.roundRect(g, x + 3, y + 1, W - 6, 12, 9, Glass.argb(Math.round(0x40 * a), 0xFFFFFF), 0x00FFFFFF);
		Glass.roundOutline(g, x, y, W, H, 10, Glass.argb(Math.round(0x90 * a), 0xFFFFFF));
		// Accent stripe on the left.
		Glass.roundRect(g, x + 3, y + 7, 3, H - 14, 1.5f, Glass.alpha(t.accentTop(), a), Glass.alpha(t.accentBottom(), a));
		if (a < 0.2f) return;

		// Spawner models and heads can't fade; they appear with the text.
		if (a >= 0.5f) {
			if (n.spawner() != null) n.spawner().draw(g, x + 8, y + 5, 20);
			else head(g, font, t, n.player(), n.playerName(), x + 10, y + 7, 16);
		}
		int text = Glass.alpha(t.text(), a), soft = Glass.alpha(t.textSoft(), a), accent = Glass.alpha(t.accentBottom(), a);
		int tx = x + 32, right = x + W - 8;

		// Line 1: what (and for how much).
		int rw = n.right().isEmpty() ? 0 : font.width(n.right()) + 6;
		g.drawString(font, clip(font, n.title(), right - tx - rw), tx, y + 6, text, false);
		if (rw > 0) g.drawString(font, n.right(), right - rw + 6, y + 6, accent, false);

		// Line 2: tag, who, extra info on the right if there's room.
		int ty = y + 17;
		if (!n.tag().isEmpty()) {
			int tw = font.width(n.tag()) + 8;
			// The darker end of the color, so the white text stays readable on light themes.
			int fill = Glass.alpha(n.tagSecond() ? t.secondBottom() : t.accentBottom(), a);
			Glass.roundRect(g, tx, ty - 1, tw, 10, 5, fill);
			g.drawString(font, n.tag(), tx + 4, ty, Glass.argb(Math.round(255 * a), 0xFFFFFF), true);
			tx += tw + 4;
		}
		String sub = clip(font, n.sub(), right - tx);
		g.drawString(font, sub, tx, ty, soft, false);
		if (!n.subRight().isEmpty()) {
			int srw = font.width(n.subRight());
			if (tx + font.width(sub) + 6 + srw <= right) {
				int color = n.subRightColor() == 0 ? soft : Glass.alpha(n.subRightColor(), a);
				g.drawString(font, n.subRight(), right - srw, ty, color, false);
			}
		}

		// Time left.
		int len = Math.round((W - 24) * Math.max(0, 1 - progress));
		if (len > 0) g.fill(x + 12, y + H - 2, x + 12 + len, y + H - 1, Glass.argb(Math.round(0x90 * a), t.accentBottom()));
	}

	/** Player head from the tab list, else a colored letter (same as in the menu). */
	private static void head(GuiGraphics g, Font font, Theme t, UUID uuid, String name, int x, int y, int size) {
		PlayerInfo info = uuid == null ? null : Market.tabInfo(uuid);
		if (info != null) {
			PlayerFaceRenderer.draw(g, info.getSkin(), x, y, size);
			return;
		}
		Glass.roundRect(g, x, y, size, size, size / 4f, t.accentTop(), t.accentBottom());
		String letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
		g.drawString(font, letter, x + (size - font.width(letter)) / 2 + 1, y + (size - 8) / 2 + 1, 0xFFFFFFFF, false);
	}

	private static String clip(Font font, String text, int maxWidth) {
		if (text == null) return "";
		if (font.width(text) <= maxWidth) return text;
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
	}
}
