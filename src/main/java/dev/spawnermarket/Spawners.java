package dev.spawnermarket;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/** Spawner types that can be traded, with their German name and an icon, plus price helpers. */
public final class Spawners {
	private Spawners() {}

	public record Type(String key, String name, Item icon) {
		public ItemStack stack() {
			return new ItemStack(icon);
		}
	}

	public static final Type[] ALL = {
			new Type("zombie", "Zombie", Items.ZOMBIE_SPAWN_EGG),
			new Type("skeleton", "Skelett", Items.SKELETON_SPAWN_EGG),
			new Type("spider", "Spinne", Items.SPIDER_SPAWN_EGG),
			new Type("cave_spider", "Höhlenspinne", Items.CAVE_SPIDER_SPAWN_EGG),
			new Type("creeper", "Creeper", Items.CREEPER_SPAWN_EGG),
			new Type("blaze", "Blaze", Items.BLAZE_SPAWN_EGG),
			new Type("iron_golem", "Eisengolem", Items.IRON_GOLEM_SPAWN_EGG),
			new Type("pig", "Schwein", Items.PIG_SPAWN_EGG),
			new Type("cow", "Kuh", Items.COW_SPAWN_EGG),
			new Type("chicken", "Huhn", Items.CHICKEN_SPAWN_EGG),
			new Type("sheep", "Schaf", Items.SHEEP_SPAWN_EGG),
			new Type("slime", "Schleim", Items.SLIME_SPAWN_EGG),
			new Type("magma_cube", "Magmawürfel", Items.MAGMA_CUBE_SPAWN_EGG),
			new Type("enderman", "Enderman", Items.ENDERMAN_SPAWN_EGG),
			new Type("witch", "Hexe", Items.WITCH_SPAWN_EGG),
			new Type("zombified_piglin", "Zombie-Piglin", Items.ZOMBIFIED_PIGLIN_SPAWN_EGG),
			new Type("silverfish", "Silberfisch", Items.SILVERFISH_SPAWN_EGG),
			new Type("other", "Sonstige", Items.SPAWNER),
	};

	/** The type with this key, or null for unknown keys (they come from other players). */
	public static Type get(String key) {
		for (Type t : ALL) if (t.key.equals(key)) return t;
		return null;
	}

	public static int index(String key) {
		for (int i = 0; i < ALL.length; i++) if (ALL[i].key.equals(key)) return i;
		return -1;
	}

	// ---------------------------------------------------------------- prices

	public static final long MAX_PRICE = 1_000_000_000_000_000L;

	/** 950 · 12.5k · 250k · 1.5M · 2B */
	public static String format(long v) {
		if (v < 1000) return Long.toString(v);
		String[] units = {"k", "M", "B", "T", "Q"};
		double d = v;
		int u = -1;
		while (d >= 1000 && u < units.length - 1) {
			d /= 1000;
			u++;
		}
		String num = d >= 100 ? String.format(Locale.ROOT, "%.0f", d)
				: d >= 10 ? String.format(Locale.ROOT, "%.1f", d) : String.format(Locale.ROOT, "%.2f", d);
		if (num.contains(".")) num = num.replaceAll("0+$", "").replaceAll("\\.$", "");
		return num + units[u];
	}

	/** Parses "250k", "1,5m", "2.000.000", "3b"; returns -1 if it isn't a price. */
	public static long parse(String s) {
		if (s == null) return -1;
		s = s.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("$", "");
		if (s.isEmpty()) return -1;
		long mul = 1;
		char last = s.charAt(s.length() - 1);
		switch (last) {
			case 'k' -> mul = 1_000L;
			case 'm' -> mul = 1_000_000L;
			case 'b' -> mul = 1_000_000_000L;
			case 't' -> mul = 1_000_000_000_000L;
			case 'q' -> mul = 1_000_000_000_000_000L;
			default -> { }
		}
		if (mul > 1) s = s.substring(0, s.length() - 1);
		// "2.000.000" / "2,000,000" are thousands separators, "1,5" / "1.5" a decimal point.
		if (s.matches("\\d{1,3}([.,]\\d{3})+")) s = s.replaceAll("[.,]", "");
		else s = s.replace(',', '.');
		try {
			double d = Double.parseDouble(s) * mul;
			if (Double.isNaN(d) || d < 1 || d > MAX_PRICE) return -1;
			return Math.round(d);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** "vor 5 Min." style age. */
	public static String ago(long ts) {
		long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
		if (s < 60) return "gerade";
		if (s < 3600) return "vor " + s / 60 + " Min.";
		if (s < 86400) return "vor " + s / 3600 + " Std.";
		return "vor " + s / 86400 + (s / 86400 == 1 ? " Tag" : " Tagen");
	}
}
