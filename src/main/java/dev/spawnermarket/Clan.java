package dev.spawnermarket;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A player's clan tag (e.g. "[FWM5]") in its colors, as the server shows it in "/clan info". The
 * mod reads it from that answer, shares it with the online status and puts it before names.
 */
public final class Clan {
	private Clan() {}

	/** One piece of the tag in one color; {@code c} -1 = no color of its own. */
	public static class Seg {
		public String t = "";
		public int c = -1;

		public Seg() {}

		public Seg(String t, int c) {
			this.t = t;
			this.c = c;
		}
	}

	private static final Pattern TAG_LINE = Pattern.compile("K(?:ü|ue|u)rzel\\s*:\\s*", Pattern.CASE_INSENSITIVE);
	private static final Pattern NO_CLAN = Pattern.compile("(?i)(in keinem clan|kein(en)? clan|nicht in einem clan|not in a clan)");
	private static final int MAX_LEN = 12;

	/** The tag from a "/clan info" line ("Kürzel: [FWM5]"), with its colors; null if this isn't that line. */
	public static List<Seg> parseTag(Component message) {
		StringBuilder plain = new StringBuilder();
		List<Integer> colors = new ArrayList<>();
		message.visit((Style style, String text) -> {
			TextColor tc = style.getColor();
			int c = tc == null ? -1 : tc.getValue() & 0xFFFFFF;
			for (int i = 0; i < text.length(); i++) {
				plain.append(text.charAt(i));
				colors.add(c);
			}
			return Optional.empty();
		}, Style.EMPTY);
		Matcher m = TAG_LINE.matcher(plain);
		if (!m.find()) return null;
		int start = m.end(), end = start;
		while (end < plain.length() && plain.charAt(end) != '\n') end++;
		// Only the tag itself, without trailing spaces or formatting leftovers.
		while (end > start && Character.isWhitespace(plain.charAt(end - 1))) end--;
		if (end <= start) return null;
		if (end - start > MAX_LEN) end = start + MAX_LEN;
		List<Seg> segs = new ArrayList<>();
		for (int i = start; i < end; i++) {
			char ch = plain.charAt(i);
			if (ch == '§' || Character.isISOControl(ch)) continue;
			int c = colors.get(i);
			Seg last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
			if (last != null && last.c == c) last.t += ch;
			else segs.add(new Seg(String.valueOf(ch), c));
		}
		return segs.isEmpty() ? null : segs;
	}

	/** Whether this server line says I'm in no clan. */
	public static boolean saysNoClan(Component message) {
		return NO_CLAN.matcher(message.getString()).find();
	}

	public static String text(List<Seg> tag) {
		StringBuilder b = new StringBuilder();
		if (tag != null) for (Seg s : tag) b.append(s.t);
		return b.toString();
	}

	/** The tag as text in its colors; pieces without a color get {@code defaultColor}. Null for no tag. */
	public static MutableComponent component(List<Seg> tag, int defaultColor) {
		if (tag == null || tag.isEmpty()) return null;
		MutableComponent out = Component.empty();
		for (Seg s : tag) out.append(Component.literal(s.t).withColor(s.c < 0 ? defaultColor : 0xFF000000 | s.c));
		return out;
	}

	public static JsonArray toJson(List<Seg> tag) {
		JsonArray a = new JsonArray();
		if (tag != null) for (Seg s : tag) {
			JsonObject o = new JsonObject();
			o.addProperty("t", s.t);
			o.addProperty("c", s.c);
			a.add(o);
		}
		return a;
	}

	/** A tag from another player: only a few printable characters and plain colors. */
	public static List<Seg> fromJson(JsonElement e) {
		if (e == null || !e.isJsonArray()) return null;
		List<Seg> out = new ArrayList<>();
		int len = 0;
		for (JsonElement el : e.getAsJsonArray()) {
			if (!el.isJsonObject() || out.size() >= 12) break;
			JsonObject o = el.getAsJsonObject();
			String t = o.has("t") ? o.get("t").getAsString().replaceAll("§.?", "").replaceAll("\\p{Cntrl}", "") : "";
			int c = o.has("c") ? o.get("c").getAsInt() : -1;
			if (t.isEmpty()) continue;
			if (len + t.length() > MAX_LEN) t = t.substring(0, MAX_LEN - len);
			len += t.length();
			out.add(new Seg(t, c < 0 ? -1 : c & 0xFFFFFF));
			if (len >= MAX_LEN) break;
		}
		return out.isEmpty() ? null : out;
	}
}
