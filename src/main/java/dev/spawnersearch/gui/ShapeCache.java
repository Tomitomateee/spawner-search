package dev.spawnersearch.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Rasterizes glass shapes once into textures at the real screen resolution and then just blits
 * them. Drawing the shapes from thousands of 1px fills every frame was far too slow.
 */
public final class ShapeCache {
	private static final int MAX_ENTRIES = 300;
	private static final Map<String, Identifier> CACHE = new HashMap<>();
	private static int counter;

	private ShapeCache() {}

	private interface Shader {
		/** Returns ARGB for a pixel whose center lies at (x, y) in GUI units. */
		int color(float x, float y);
	}

	private static int scale() {
		return Math.max(1, (int) Math.ceil(Minecraft.getInstance().getWindow().getGuiScale()));
	}

	private static void draw(GuiGraphics g, String key, int x, int y, int w, int h, Shader shader) {
		draw(g, key, x, y, w, h, -1, shader);
	}

	/** {@code tint} is multiplied onto the cached texture, so e.g. opacity changes need no re-raster. */
	private static void draw(GuiGraphics g, String key, int x, int y, int w, int h, int tint, Shader shader) {
		if (w <= 0 || h <= 0) return;
		int s = scale();
		key = key + "@" + s;
		Identifier id = CACHE.get(key);
		if (id == null) {
			if (CACHE.size() >= MAX_ENTRIES) clear();
			NativeImage img = new NativeImage(w * s, h * s, false);
			for (int py = 0; py < h * s; py++) {
				for (int px = 0; px < w * s; px++) {
					img.setPixel(px, py, shader.color((px + 0.5f) / s, (py + 0.5f) / s));
				}
			}
			id = Identifier.fromNamespaceAndPath("spawnersearch", "shape/" + (counter++));
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, img));
			CACHE.put(key, id);
		}
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, w, h, w, h, tint);
	}

	/** Rounded rectangle whose overall alpha is applied as a tint instead of baked into the texture. */
	static void roundRectFaded(GuiGraphics g, int x, int y, int w, int h, float r, int top, int bottom, float alpha) {
		float rr = Math.min(r, Math.min(w, h) / 2f);
		int s = scale();
		int tint = (Math.round(clamp01(alpha) * 255) << 24) | 0xFFFFFF;
		draw(g, "rf" + w + "," + h + "," + rr + "," + top + "," + bottom, x, y, w, h, tint, (px, py) -> {
			float cov = clamp01(0.5f - sdf(px, py, w, h, rr) * s);
			if (cov <= 0) return 0;
			return withAlpha(Glass.lerp(top, bottom, h <= 1 ? 0 : py / h), cov);
		});
	}

	/** Gear icon; {@code step} (0–15) rotates it through one tooth period. */
	static void gear(GuiGraphics g, int x, int y, int size, int step, int color) {
		int s = scale();
		float rot = (float) (Math.floorMod(step, 16) / 16.0 * (Math.PI * 2 / 8));
		float c = size / 2f;
		draw(g, "gr" + size + "," + Math.floorMod(step, 16) + "," + color, x, y, size, size, (px, py) -> {
			float dx = px - c, dy = py - c;
			float dist = (float) Math.hypot(dx, dy);
			double ang = Math.atan2(dy, dx) + rot;
			float tooth = (float) Math.max(-1, Math.min(1, Math.cos(ang * 8) * 2.5));
			float outer = c * (0.80f + 0.18f * (tooth * 0.5f + 0.5f));
			float cov = clamp01(0.5f - (dist - outer) * s);
			float hole = clamp01(0.5f + (dist - c * 0.34f) * s);
			cov = Math.min(cov, hole);
			return cov <= 0 ? 0 : withAlpha(color, cov);
		});
	}

	/** Bakes (or returns the cached) texture at a fixed resolution, for drawing in the 3D world. */
	private static Identifier bake(String key, int w, int h, int s, Shader shader) {
		key = "world:" + key;
		Identifier id = CACHE.get(key);
		if (id != null) return id;
		if (CACHE.size() >= MAX_ENTRIES) clear();
		NativeImage img = new NativeImage(w * s, h * s, false);
		for (int py = 0; py < h * s; py++) {
			for (int px = 0; px < w * s; px++) {
				img.setPixel(px, py, shader.color((px + 0.5f) / s, (py + 0.5f) / s));
			}
		}
		id = Identifier.fromNamespaceAndPath("spawnersearch", "shape/" + (counter++));
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, img));
		CACHE.put(key, id);
		return id;
	}

	/** Frosted glass card used above player names. */
	public static Identifier cardTexture(int w, int h, Theme t) {
		float r = Math.min(8, h / 2f);
		int top = Glass.argb(0xE6, t.panelTop()), bottom = Glass.argb(0xD2, t.panelBottom());
		int blob = Glass.argb(0x55, t.blobA());
		return bake("card" + w + "x" + h + t.name(), w, h, 4, (x, y) -> {
			float d = sdf(x, y, w, h, r);
			float cov = clamp01(0.5f - d * 4);
			if (cov <= 0) return 0;
			int c = Glass.lerp(top, bottom, y / h);
			// Soft colored glow in the lower right, white sheen at the top.
			float g = clamp01(1f - (float) Math.hypot(x - w * 0.85f, y - h) / (h * 1.4f));
			c = over(withAlpha(blob, g * g), c);
			if (y < h * 0.45f) c = over(withAlpha(0xFFFFFFFF, 0.22f * (1 - y / (h * 0.45f))), c);
			float rim = clamp01(0.5f - d * 4) - clamp01(0.5f - (d + 0.75f) * 4);
			c = over(withAlpha(0xD0FFFFFF, rim), c);
			return withAlpha(c | 0xFF000000, cov * (((c >>> 24) & 0xFF) / 255f));
		});
	}

	/** Vinyl record: black disc with grooves and a soft edge; the label area is drawn separately. */
	public static Identifier vinylTexture(int size, Theme t) {
		float r = size / 2f;
		int accent = t.accentTop();
		return bake("vinyl" + size + t.name(), size, size, 4, (x, y) -> {
			float d = (float) Math.hypot(x - r, y - r);
			float cov = clamp01((r - d) * 4 + 0.5f);
			if (cov <= 0) return 0;
			float rel = d / r;
			// Fine grooves plus a few wider gaps between "tracks".
			float groove = (float) (0.5 + 0.5 * Math.sin(d * 9.0));
			int base = 14 + Math.round(groove * 10);
			if (rel > 0.42f && rel < 0.97f && Math.abs(Math.sin(rel * 26)) < 0.08) base += 18;
			if (rel > 0.97f) base = 30; // rim
			// A faint diagonal reflection that turns with the record, which makes the spin visible.
			double ang = Math.atan2(y - r, x - r);
			// The reflection (and a faint rim) glow in the theme's accent color.
			float shine = (float) Math.pow(Math.abs(Math.cos(ang - 0.8)), 18) * clamp01((rel - 0.4f) * 3);
			if (rel > 0.95f) shine = Math.max(shine, 0.35f);
			int grey = 0xFF000000 | (base << 16) | (base << 8) | base;
			return withAlpha(Glass.lerp(grey, accent | 0xFF000000, Math.min(1f, shine * 0.75f)), cov);
		});
	}

	/** The album cover cropped to a circle (the record's label); falls back to an accent gradient. */
	public static Identifier labelTexture(java.awt.image.BufferedImage cover, String key, int size, Theme t) {
		float r = size / 2f;
		int s = 4;
		return bake("label" + size + key + (cover != null) + t.name(), size, size, s, (x, y) -> {
			float d = (float) Math.hypot(x - r, y - r);
			float cov = clamp01((r - d) * s + 0.5f);
			if (cov <= 0) return 0;
			int c;
			if (cover != null) {
				int px = Math.min(cover.getWidth() - 1, (int) (x / size * cover.getWidth()));
				int py = Math.min(cover.getHeight() - 1, (int) (y / size * cover.getHeight()));
				c = cover.getRGB(px, py) | 0xFF000000;
			} else {
				c = Glass.lerp(t.secondTop(), t.accentBottom(), (x + y) / (2f * size));
			}
			return withAlpha(c, cov);
		});
	}

	/** Rounded accent square shown while a cover is loading or missing. */
	public static Identifier placeholderTexture(Theme t) {
		return bake("ph" + t.name(), 20, 20, 4, (x, y) -> {
			float cov = clamp01(0.5f - sdf(x, y, 20, 20, 4) * 4);
			return cov <= 0 ? 0 : withAlpha(Glass.lerp(t.accentTop(), t.accentBottom(), y / 20f), cov);
		});
	}

	/** Alpha-composites {@code src} over {@code dst} (both ARGB, straight alpha). */
	private static int over(int src, int dst) {
		float sa = ((src >>> 24) & 0xFF) / 255f, da = ((dst >>> 24) & 0xFF) / 255f;
		float oa = sa + da * (1 - sa);
		if (oa <= 0) return 0;
		int r = Math.round((((src >> 16) & 0xFF) * sa + ((dst >> 16) & 0xFF) * da * (1 - sa)) / oa);
		int g = Math.round((((src >> 8) & 0xFF) * sa + ((dst >> 8) & 0xFF) * da * (1 - sa)) / oa);
		int b = Math.round(((src & 0xFF) * sa + (dst & 0xFF) * da * (1 - sa)) / oa);
		return (Math.round(oa * 255) << 24) | (r << 16) | (g << 8) | b;
	}

	static void clear() {
		for (Identifier id : CACHE.values()) Minecraft.getInstance().getTextureManager().release(id);
		CACHE.clear();
	}

	// ------------------------------------------------------------ geometry

	/** Signed distance (GUI units) from a point to a rounded rectangle spanning (0,0)-(w,h). */
	private static float sdf(float x, float y, float w, float h, float r) {
		float qx = Math.abs(x - w / 2f) - (w / 2f - r);
		float qy = Math.abs(y - h / 2f) - (h / 2f - r);
		float outside = (float) Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
		return outside + Math.min(Math.max(qx, qy), 0) - r;
	}

	private static float clamp01(float v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	private static int withAlpha(int color, float coverage) {
		int a = Math.round(((color >>> 24) & 0xFF) * coverage);
		return (a << 24) | (color & 0xFFFFFF);
	}

	// ------------------------------------------------------------ shapes

	static void roundRect(GuiGraphics g, int x, int y, int w, int h, float r, int top, int bottom) {
		float rr = Math.min(r, Math.min(w, h) / 2f);
		int s = scale();
		draw(g, "rr" + w + "," + h + "," + rr + "," + top + "," + bottom, x, y, w, h, (px, py) -> {
			float cov = clamp01(0.5f - sdf(px, py, w, h, rr) * s);
			if (cov <= 0) return 0;
			return withAlpha(Glass.lerp(top, bottom, h <= 1 ? 0 : py / h), cov);
		});
	}

	static void outline(GuiGraphics g, int x, int y, int w, int h, float r, int color) {
		float rr = Math.min(r, Math.min(w, h) / 2f);
		int s = scale();
		draw(g, "ol" + w + "," + h + "," + rr + "," + color, x, y, w, h, (px, py) -> {
			float d = sdf(px, py, w, h, rr);
			// 1 GUI pixel wide band just inside the edge.
			float cov = clamp01(0.5f - d * s) - clamp01(0.5f - (d + 1f) * s);
			return cov <= 0 ? 0 : withAlpha(color, cov);
		});
	}

	/** Soft shadow that fades out over a few pixels around the shape. */
	static void shadow(GuiGraphics g, int x, int y, int w, int h, float r, int strength) {
		int pad = 6;
		float rr = Math.min(r, Math.min(w, h) / 2f);
		int tw = w + pad * 2, th = h + pad * 2;
		draw(g, "sh" + w + "," + h + "," + rr + "," + strength, x - pad, y - pad + 2, tw, th, (px, py) -> {
			float d = sdf(px - pad, py - pad, w, h, rr);
			float t = clamp01(1f - (d + 2f) / (pad + 2f));
			float a = t * t * (3 - 2 * t) * Math.min(255, strength) * 0.55f;
			return ((int) a << 24) | 0x1A1030;
		});
	}

	/** Radial gradient blob. */
	static void blob(GuiGraphics g, int cx, int cy, int radius, int color) {
		int d = radius * 2;
		draw(g, "bl" + radius + "," + color, cx - radius, cy - radius, d, d, (px, py) -> {
			float dist = (float) Math.hypot(px - radius, py - radius) / radius;
			float t = clamp01(1f - dist);
			return t <= 0 ? 0 : withAlpha(color, t * t);
		});
	}

	/** Blob whose strength changes every frame: one cached texture, the alpha comes from the tint. */
	static void glow(GuiGraphics g, int cx, int cy, int radius, int rgb, int alpha) {
		int color = 0xFF000000 | rgb;
		int d = radius * 2;
		draw(g, "bl" + radius + "," + color, cx - radius, cy - radius, d, d,
				(Math.max(0, Math.min(255, alpha)) << 24) | 0xFFFFFF, (px, py) -> {
			float dist = (float) Math.hypot(px - radius, py - radius) / radius;
			float t = clamp01(1f - dist);
			return t <= 0 ? 0 : withAlpha(color, t * t);
		});
	}
}
