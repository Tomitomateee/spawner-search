package dev.spawnersearch.gui;

import dev.spawnersearch.MarketConfig;

/** A color preset for the GUI, selectable in the settings drawer (same presets as Liquid Player). */
public record Theme(String name,
                    int accentTop, int accentBottom,
                    int secondTop, int secondBottom,
                    int panelTop, int panelBottom,
                    int blobA, int blobB,
                    int text, int textSoft, int textFaint,
                    int pillTop, int pillBottom) {

	public static final Theme[] ALL = {
			new Theme("Lavendel", 0xFFB59CFF, 0xFF7C5CF2, 0xFF9BF0DF, 0xFF55CDB6, 0xF8F6FC, 0xECE8F3,
					0xC9A6FF, 0x9BF0DF, 0xFF1E1B2E, 0xFF6E6A80, 0xFF8A86A0, 0xF8FFFFFF, 0xE6F1EEF8),
			new Theme("Ozean", 0xFF8FC8FF, 0xFF3D7BF0, 0xFF9BE7FF, 0xFF4FB6E0, 0xEEF5FD, 0xE2ECF8,
					0x8FB8FF, 0x9BE7FF, 0xFF152036, 0xFF5E6E86, 0xFF7D8BA0, 0xF8FFFFFF, 0xE6EEF4FB),
			new Theme("Mint", 0xFF8EF0C4, 0xFF2FBF8A, 0xFFC9F59B, 0xFF8CD65A, 0xF1FBF6, 0xE3F4EC,
					0x8EF0C4, 0xC9F59B, 0xFF13261E, 0xFF5B7368, 0xFF7F9488, 0xF8FFFFFF, 0xE6EEF7F2),
			new Theme("Sunset", 0xFFFFB38A, 0xFFF2665C, 0xFFFFD88A, 0xFFF5A742, 0xFDF4EF, 0xF7E6DE,
					0xFFB38A, 0xFFD88A, 0xFF2E1A16, 0xFF80645C, 0xFF9C8078, 0xF8FFFFFF, 0xE6FAF0EB),
			new Theme("Rosé", 0xFFFFA8D0, 0xFFE5559A, 0xFFD9B3FF, 0xFFA67CF0, 0xFCF2F7, 0xF5E4EE,
					0xFFA8D0, 0xD9B3FF, 0xFF2E1424, 0xFF80627A, 0xFF9C7E94, 0xF8FFFFFF, 0xE6F8EEF4),
			new Theme("Neon", 0xFFBF40FA, 0xFF4928C2, 0xFFE3D9FC, 0xFF9F7BF5, 0xF3EEFE, 0xE3D9FC,
					0xBF40FA, 0x4928C2, 0xFF1B0F3A, 0xFF5B4A86, 0xFF7D6CA8, 0xF8FFFFFF, 0xE6EFE9FD),
			new Theme("Crimson", 0xFFE8335E, 0xFFC50337, 0xFFF08AA3, 0xFF8A1030, 0xFCEFF2, 0xF5DDE3,
					0xC50337, 0x8A1030, 0xFF2A0610, 0xFF7A4652, 0xFF9A6A75, 0xF8FFFFFF, 0xE6F9EBEF),
			new Theme("Gold", 0xFFFFD36E, 0xFFE8A317, 0xFFFFE9B0, 0xFFF2B84B, 0xFFFAF0, 0xF7EBD3,
					0xFFD36E, 0xFFB38A, 0xFF2B1E05, 0xFF806A3D, 0xFF9C8960, 0xF8FFFFFF, 0xE6FBF4E4),
			new Theme("Aqua", 0xFF7FE7F5, 0xFF1BA9C7, 0xFFB5F2D9, 0xFF4FD1A5, 0xEFFBFD, 0xDDF3F7,
					0x7FE7F5, 0xB5F2D9, 0xFF06262E, 0xFF4E7480, 0xFF7393A0, 0xF8FFFFFF, 0xE6EAF8FA),
	};

	public static Theme get(int index) {
		return ALL[Math.floorMod(index, ALL.length)];
	}

	/** The theme the user picked, in its black variant when black mode is on. */
	public static Theme current(MarketConfig cfg) {
		Theme t = get(cfg.theme);
		return cfg.blackMode ? t.black() : t;
	}

	/** Black mode: near-black glass tinted with the accent, light text, dark pills. */
	public Theme black() {
		int tint = accentBottom & 0xFFFFFF;
		return new Theme(name + " Schwarz", accentTop, accentBottom, secondTop, secondBottom,
				mix(0x0D0D12, tint, 0.12f), mix(0x040506, tint, 0.06f),
				accentTop & 0xFFFFFF, tint,
				0xFFF2F2F7, 0xFFADADBD, 0xFF7E7E8E,
				0xF0000000 | mix(0x24242C, tint, 0.14f), 0xE6000000 | mix(0x16161C, tint, 0.08f));
	}

	private static int mix(int rgb, int with, float t) {
		int r = Math.round(((rgb >> 16) & 0xFF) * (1 - t) + ((with >> 16) & 0xFF) * t);
		int g = Math.round(((rgb >> 8) & 0xFF) * (1 - t) + ((with >> 8) & 0xFF) * t);
		int b = Math.round((rgb & 0xFF) * (1 - t) + (with & 0xFF) * t);
		return (r << 16) | (g << 8) | b;
	}
}
