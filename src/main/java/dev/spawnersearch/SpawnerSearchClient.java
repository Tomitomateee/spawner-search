package dev.spawnersearch;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spawnersearch.gui.Banner;
import dev.spawnersearch.gui.MarketScreen;
import dev.spawnersearch.gui.WelcomeScreen;
import dev.spawnersearch.selftest.SelfTest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpawnerSearchClient implements ClientModInitializer {
	public static final String MOD_ID = "spawnersearch";
	public static final Logger LOGGER = LoggerFactory.getLogger("Spawner Search");

	private static Market market;

	public static Market market() {
		return market;
	}

	@Override
	public void onInitializeClient() {
		market = new Market(MarketConfig.load());
		market.setNotifier(n -> Notifications.handle(market, n));

		// Banners: in game as part of the HUD, in other menus on top of the menu.
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "banner"), (g, delta) -> {
			if (Minecraft.getInstance().screen == null) Banner.render(g, market.config());
		});
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof MarketScreen) return;
			ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
				if (!market.config().bannerInMenus) return;
				g.nextStratum();
				Banner.render(g, market.config());
			});
		});

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "market"));
		KeyMapping open = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key." + MOD_ID + ".open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, category));

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			// The self-test stays off the public relay and feeds the market itself.
			if (SelfTest.MODE.isEmpty()) market.start();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> market.goodbye());
		SelfTest.register(market);

		// "/clan info" – typed or sent by the mod – and its answer give my clan tag.
		ClientSendMessageEvents.COMMAND.register(command -> {
			if (command.trim().equalsIgnoreCase("clan info")) market.expectClanInfo();
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) market.onServerMessage(message);
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			market.tick();
			while (open.consumeClick()) {
				if (client.screen != null) continue;
				// Each time I join hugoSMP: the welcome window (clan, Discord) before the market.
				if (market.welcomePending()) client.setScreen(new WelcomeScreen(market));
				else client.setScreen(new MarketScreen(market));
			}
		});
	}
}
