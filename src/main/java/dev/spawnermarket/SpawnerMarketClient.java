package dev.spawnermarket;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spawnermarket.gui.MarketScreen;
import dev.spawnermarket.gui.MarketToast;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpawnerMarketClient implements ClientModInitializer {
	public static final String MOD_ID = "spawnermarket";
	public static final Logger LOGGER = LoggerFactory.getLogger("Spawner Market");

	private static Market market;

	public static Market market() {
		return market;
	}

	@Override
	public void onInitializeClient() {
		market = new Market(MarketConfig.load());
		market.setNotifier((title, text, from) -> {
			Minecraft mc = Minecraft.getInstance();
			// While the menu is open, it shows these itself.
			if (!market.config().notifications || mc.screen instanceof MarketScreen) return;
			MarketToast.show(title, text, from);
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6f));
		});

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "market"));
		KeyMapping open = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key." + MOD_ID + ".open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, category));

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> market.start());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> market.goodbye());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			market.tick();
			while (open.consumeClick()) {
				if (client.screen == null) client.setScreen(new MarketScreen(market));
			}
		});
	}
}
