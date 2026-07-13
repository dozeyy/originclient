package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginModMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

// Origin pause menu (#1). Same shape as the familiar Lunar/vanilla game menu, but
// stripped of the middle clutter (Feedback, Report Bugs, Multiplayer Options,
// Player Reporting, Friends) and given an Origin Options bar into the mod menu:
//
//     Back to Game                      (full)
//     Advancements     | Statistics
//     Host World       | Options
//     Origin Options                    (full)  -> Origin mod menu
//     Save and Quit to Title            (full)
//
// The vanilla buttons (Back to Game, Advancements, Statistics, Options, Save &
// Quit) are the real objects, only repositioned, so their actions stay intact.
// Host World is new: 26.2 removed the Open-to-LAN screen, so it's a one-click
// publish (IntegratedServer.publishServer) — disabled when not singleplayer or
// already open. Origin Options opens the mod menu. Fail-soft: if the essentials
// aren't all present or anything throws, the vanilla layout is left untouched.
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
	@Shadow private Button disconnectButton;

	protected PauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$rebuildMenu(CallbackInfo ci) {
		try {
			originclient$rebuild();
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: pause-menu rebuild failed; leaving vanilla layout", t);
		}
	}

	@Unique
	private void originclient$rebuild() {
		Button backToGame = originclient$find("menu.returnToGame");
		Button options = originclient$find("menu.options");
		Button disconnect = this.disconnectButton;
		// Only the full in-world pause menu has these; the limbo/disconnect
		// variant doesn't — leave it vanilla.
		if (backToGame == null || options == null || disconnect == null) {
			return;
		}
		Button advancements = originclient$find("gui.advancements");
		Button statistics = originclient$find("gui.stats");

		// Drop everything that isn't a keeper.
		List<Button> keep = new ArrayList<>(List.of(backToGame, options, disconnect));
		if (advancements != null) keep.add(advancements);
		if (statistics != null) keep.add(statistics);
		List<GuiEventListener> drop = new ArrayList<>();
		for (GuiEventListener c : this.children()) {
			if (c instanceof AbstractWidget w && !keep.contains(w)) {
				drop.add(c);
			}
		}
		for (GuiEventListener c : drop) {
			this.removeWidget(c);
		}

		// New buttons.
		Button hostWorld = Button.builder(Component.literal("Host World"), b -> originclient$hostWorld(b))
				.build();
		Button originOptions = Button.builder(Component.literal("Origin Options"),
						b -> Minecraft.getInstance().setScreenAndShow(new OriginModMenuScreen()))
				.build();

		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null && server.isPublished()) {
			hostWorld.setMessage(Component.literal("LAN Open :" + server.getPort()));
			hostWorld.active = false;
		} else {
			hostWorld.active = server != null; // singleplayer only
		}

		// Layout: two full-width bars top and bottom, two half-width rows between.
		int full = 204, half = 100, hgap = 4, bh = 20, vgap = 4;
		int cx = this.width / 2;
		int leftX = cx - full / 2;
		int rightX = leftX + half + hgap;
		int y = this.height / 4 + 8;

		originclient$place(backToGame, leftX, y, full, bh);
		y += bh + vgap;
		if (advancements != null && statistics != null) {
			originclient$place(advancements, leftX, y, half, bh);
			originclient$place(statistics, rightX, y, half, bh);
			y += bh + vgap;
		}
		originclient$place(hostWorld, leftX, y, half, bh);
		originclient$place(originOptions, rightX, y, half, bh);
		y += bh + vgap;
		originclient$place(options, leftX, y, full, bh);
		y += bh + vgap;
		originclient$place(disconnect, leftX, y, full, bh);

		this.addRenderableWidget(hostWorld);
		this.addRenderableWidget(originOptions);
	}

	@Unique
	private void originclient$hostWorld(Button b) {
		try {
			Minecraft mc = Minecraft.getInstance();
			IntegratedServer server = mc.getSingleplayerServer();
			if (server == null || server.isPublished()) {
				return;
			}
			boolean ok = server.publishServer(MinecraftServer.MultiplayerScope.LAN, HttpUtil.getAvailablePort());
			if (ok) {
				b.setMessage(Component.literal("LAN Open :" + server.getPort()));
				b.active = false;
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: Host World publish failed", t);
		}
	}

	@Unique
	private Button originclient$find(String translationKey) {
		Component msg = Component.translatable(translationKey);
		for (GuiEventListener c : this.children()) {
			if (c instanceof Button b && msg.equals(b.getMessage())) {
				return b;
			}
		}
		return null;
	}

	@Unique
	private static void originclient$place(AbstractWidget w, int x, int y, int width, int height) {
		w.setX(x);
		w.setY(y);
		w.setWidth(width);
		w.setHeight(height);
	}
}
