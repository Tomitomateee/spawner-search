package dev.spawnersearch.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Drawing primitives for the liquid glass look: anti-aliased rounded rectangles built from
 * horizontal scanlines, soft shadows, glossy highlights and simple vector icons.
 */
public final class Glass {
	private Glass() {}

	// ------------------------------------------------------------ colors

	public static int argb(int a, int rgb) {
		return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF);
	}

	public static int alpha(int color, float factor) {
		return argb(Math.round(((color >>> 24) & 0xFF) * factor), color);
	}

	public static int lerp(int c1, int c2, float t) {
		t = Math.max(0, Math.min(1, t));
		int a = Math.round(((c1 >>> 24) & 0xFF) + (((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t);
		int r = Math.round(((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
		int g = Math.round(((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
		int b = Math.round((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	// ------------------------------------------------------------ shapes

	/** Horizontal inset of a rounded rectangle's edge at the given row. */
	private static float inset(int row, int h, float r) {
		float d;
		if (row < r) d = r - row - 0.5f;
		else if (row >= h - r) d = row - (h - r) + 0.5f;
		else return 0;
		return r - (float) Math.sqrt(Math.max(0, r * r - d * d));
	}

	public static void roundRect(GuiGraphics g, int x, int y, int w, int h, float r, int color) {
		roundRect(g, x, y, w, h, r, color, color);
	}

	/** Rounded rectangle with a vertical gradient from {@code top} to {@code bottom}. */
	public static void roundRect(GuiGraphics g, int x, int y, int w, int h, float r, int top, int bottom) {
		if (w <= 0 || h <= 0) return;
		if (w > 6 && h > 6) {
			ShapeCache.roundRect(g, x, y, w, h, r, top, bottom);
			return;
		}
		r = Math.min(r, Math.min(w, h) / 2f);
		int ri = (int) Math.ceil(r);
		boolean flat = top == bottom;
		for (int row = 0; row < h; row++) {
			int color = flat ? top : lerp(top, bottom, h <= 1 ? 0 : row / (float) (h - 1));
			if (flat && row >= ri && row < h - ri) {
				// Middle block in one call.
				g.fill(x, y + row, x + w, y + h - ri, color);
				row = h - ri - 1;
				continue;
			}
			float in = inset(row, h, r);
			int full = (int) Math.ceil(in);
			if (x + full < x + w - full) g.fill(x + full, y + row, x + w - full, y + row + 1, color);
			float cover = full - in;
			if (full > 0 && cover > 0.03f) {
				int edge = alpha(color, cover);
				g.fill(x + full - 1, y + row, x + full, y + row + 1, edge);
				g.fill(x + w - full, y + row, x + w - full + 1, y + row + 1, edge);
			}
		}
	}

	/** 1px outline following the rounded shape. */
	public static void roundOutline(GuiGraphics g, int x, int y, int w, int h, float r, int color) {
		if (w <= 1 || h <= 1) return;
		if (w > 6 && h > 6) {
			ShapeCache.outline(g, x, y, w, h, r, color);
			return;
		}
		r = Math.min(r, Math.min(w, h) / 2f);
		for (int row = 0; row < h; row++) {
			int in = Math.round(inset(row, h, r));
			if (row == 0 || row == h - 1) {
				g.fill(x + in, y + row, x + w - in, y + row + 1, color);
				continue;
			}
			int neighbor = Math.round(inset(row < h / 2 ? row - 1 : row + 1, h, r));
			int len = Math.max(1, neighbor - in);
			g.fill(x + in, y + row, x + in + len, y + row + 1, color);
			g.fill(x + w - in - len, y + row, x + w - in, y + row + 1, color);
		}
	}

	public static void circle(GuiGraphics g, int x, int y, int d, int color) {
		roundRect(g, x, y, d, d, d / 2f, color);
	}

	/** Soft drop shadow: a few stacked, slightly offset translucent shapes. */
	public static void shadow(GuiGraphics g, int x, int y, int w, int h, float r, int strength) {
		if (w <= 0 || h <= 0) return;
		ShapeCache.shadow(g, x, y, w, h, r, strength);
	}

	/** A frosted glass surface with highlight rim, as used for cards and the main panel. */
	public static void glass(GuiGraphics g, int x, int y, int w, int h, float r, int tintTop, int tintBottom) {
		shadow(g, x, y, w, h, r, 60);
		roundRect(g, x, y, w, h, r, tintTop, tintBottom);
		// Inner sheen across the upper half.
		roundRect(g, x + 2, y + 1, w - 4, Math.max(2, h / 2), r - 1, 0x30FFFFFF, 0x00FFFFFF);
		roundOutline(g, x, y, w, h, r, 0x90FFFFFF);
	}

	/** A glossy, colored pill button like the purple and teal ones in the reference design. */
	public static void pill(GuiGraphics g, int x, int y, int w, int h, int top, int bottom, boolean hovered) {
		float r = h / 2f;
		shadow(g, x, y + 1, w, h, r, 90);
		// Colored glow under the pill.
		roundRect(g, x + 4, y + 3, w - 8, h, r, alpha(bottom, 0.25f));
		if (hovered) {
			top = lerp(top, 0xFFFFFFFF, 0.12f);
			bottom = lerp(bottom, 0xFFFFFFFF, 0.12f);
		}
		roundRect(g, x, y, w, h, r, top, bottom);
		roundRect(g, x + 3, y + 1, w - 6, h / 2, r - 1, 0x55FFFFFF, 0x08FFFFFF);
		roundOutline(g, x, y, w, h, r, 0x80FFFFFF);
	}

	/** Radial blob made of stacked translucent circles, used for the pastel gradients. */
	public static void blob(GuiGraphics g, int cx, int cy, int radius, int color) {
		ShapeCache.blob(g, cx, cy, radius, color);
	}

	// ------------------------------------------------------------ icons (drawn centered in a box)

	public static void playIcon(GuiGraphics g, int cx, int cy, int size, int color) {
		int h = size;
		int top = cy - h / 2;
		for (int row = 0; row < h; row++) {
			float t = 1f - Math.abs(row + 0.5f - h / 2f) / (h / 2f);
			int len = Math.max(1, Math.round(t * h * 0.85f));
			g.fill(cx - h / 3, top + row, cx - h / 3 + len, top + row + 1, color);
		}
	}

	public static void pauseIcon(GuiGraphics g, int cx, int cy, int size, int color) {
		int bw = Math.max(2, size / 3);
		g.fill(cx - bw - 1, cy - size / 2, cx - 1, cy + size / 2, color);
		g.fill(cx + 1, cy - size / 2, cx + bw + 1, cy + size / 2, color);
	}

	public static void skipIcon(GuiGraphics g, int cx, int cy, int size, int color, boolean forward) {
		int h = size;
		int top = cy - h / 2;
		for (int row = 0; row < h; row++) {
			float t = 1f - Math.abs(row + 0.5f - h / 2f) / (h / 2f);
			int len = Math.max(1, Math.round(t * h * 0.7f));
			if (forward) g.fill(cx - h / 2, top + row, cx - h / 2 + len, top + row + 1, color);
			else g.fill(cx + h / 2 - len, top + row, cx + h / 2, top + row + 1, color);
		}
		if (forward) g.fill(cx + h / 2 - 2, top, cx + h / 2, top + h, color);
		else g.fill(cx - h / 2, top, cx - h / 2 + 2, top + h, color);
	}

	public static void plusIcon(GuiGraphics g, int cx, int cy, int size, int color) {
		g.fill(cx - size / 2, cy - 1, cx + size / 2, cy + 1, color);
		g.fill(cx - 1, cy - size / 2, cx + 1, cy + size / 2, color);
	}

	public static void minusIcon(GuiGraphics g, int cx, int cy, int size, int color) {
		g.fill(cx - size / 2, cy, cx + size / 2, cy + 1, color);
	}

	public static void checkIcon(GuiGraphics g, int cx, int cy, int color) {
		int[][] pts = {{-3, 0}, {-2, 1}, {-1, 2}, {0, 1}, {1, 0}, {2, -1}, {3, -2}};
		for (int[] p : pts) g.fill(cx + p[0], cy + p[1], cx + p[0] + 1, cy + p[1] + 2, color);
	}

	public static void chevronIcon(GuiGraphics g, int cx, int cy, int color) {
		for (int i = -3; i <= 3; i++) {
			int x = cx - 1 + (3 - Math.abs(i));
			g.fill(x - 1, cy + i, x + 1, cy + i + 1, color);
		}
	}

	public static void searchIcon(GuiGraphics g, int cx, int cy, int color) {
		roundOutline(g, cx - 4, cy - 4, 7, 7, 3.5f, color);
		for (int i = 0; i < 3; i++) g.fill(cx + 2 + i, cy + 2 + i, cx + 4 + i, cy + 3 + i, color);
	}

	/** Speaker with 0–2 sound waves for {@code level} (0..1); a cross when muted. */
	public static void speakerIcon(GuiGraphics g, int cx, int cy, float level, int color) {
		g.fill(cx - 5, cy - 2, cx - 3, cy + 2, color);
		for (int i = 0; i < 3; i++) g.fill(cx - 3 + i, cy - 2 - i, cx - 2 + i, cy + 2 + i, color);
		if (level <= 0.001f) {
			for (int i = 0; i < 5; i++) {
				g.fill(cx + 1 + i, cy - 2 + i, cx + 2 + i, cy - 1 + i, color);
				g.fill(cx + 5 - i, cy - 2 + i, cx + 6 - i, cy - 1 + i, color);
			}
			return;
		}
		g.fill(cx + 1, cy - 1, cx + 2, cy + 1, color);
		g.fill(cx + 2, cy - 2, cx + 3, cy - 1, color);
		g.fill(cx + 2, cy + 1, cx + 3, cy + 2, color);
		if (level > 0.5f) {
			g.fill(cx + 4, cy - 2, cx + 5, cy + 2, color);
			g.fill(cx + 3, cy - 4, cx + 4, cy - 2, color);
			g.fill(cx + 3, cy + 2, cx + 4, cy + 4, color);
		}
	}

	public static void noteIcon(GuiGraphics g, int cx, int cy, int color) {
		g.fill(cx, cy - 5, cx + 1, cy + 3, color);
		g.fill(cx, cy - 5, cx + 4, cy - 4, color);
		g.fill(cx + 3, cy - 5, cx + 4, cy - 2, color);
		circle(g, cx - 3, cy + 1, 4, color);
	}
}
