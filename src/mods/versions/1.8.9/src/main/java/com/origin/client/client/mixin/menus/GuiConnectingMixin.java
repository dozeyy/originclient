package com.origin.client.client.mixin.menus;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.resources.I18n;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// "Connecting to the server..." draws 1.21.1's one loading picture (LoadingLook:
// the real logo at 44 % of the height, the plain progress bar sweeping, the
// connection status as its caption), the same picture as every other loading
// screen, then the screen's own Cancel button on top. The backdrop already comes
// from MenuHooks' DrawScreenEvent.Pre (no world while connecting).
//
// Target (javap 1.8.9, MCP): GuiConnecting.drawScreen(IIF)V is drawDefaultBackground,
// then drawCenteredString(I18n "connect.connecting" if networkManager == null, else
// "connect.authorizing") at height / 2 - 50, then GuiScreen.drawScreen (buttonList,
// then labelList: GuiConnecting adds no labels). HEAD + cancel draws the same parts
// in Origin's layout; GuiConnecting is not one of the classes OptiFine replaces.
// If this stops applying, the vanilla connect screen shows over the menu backdrop
// (nothing breaks). Any failure here leaves vanilla's draw for that frame.
@Mixin(GuiConnecting.class)
public abstract class GuiConnectingMixin {
	@Shadow
	private NetworkManager networkManager;

	// The caption and what it was built from (no string built per frame).
	@Unique
	private String originclient$status;
	@Unique
	private boolean originclient$authorizing;
	@Unique
	private String originclient$lang;

	// Vanilla anchors Cancel at height/4 + 132 (javap 1.8.9), which on a tall GUI
	// climbs into the middle of the screen (the overlap Will hit joining a world,
	// 2026-10-20 playtest). With the Origin picture up, Cancel sits at vanilla's
	// spot when that is clear of the picture, else 12 GUI px under its caption
	// (OriginScreenRenderer.connectCancelTop); in vanilla mode, 36 px under the
	// vanilla status line's top. Kept on screen. A miss leaves vanilla's layout.
	@Inject(method = "initGui", at = @At("TAIL"))
	private void originclient$clearCancel(CallbackInfo ci) {
		try {
			Object self = this;
			if (!(self instanceof GuiScreenAccessor)) {
				return;
			}
			int h = ((net.minecraft.client.gui.GuiScreen) self).height;
			boolean origin = !OriginStyle.vanillaMenusOn(this) && OriginScreenRenderer.isActive();
			for (GuiButton b : ((GuiScreenAccessor) self).originclient$buttonList()) {
				if (b.id == 0) {
					b.yPosition = origin
							? OriginScreenRenderer.connectCancelTop(b.yPosition, h, b.height)
							: Math.min(Math.max(b.yPosition, h / 2 - 50 + 36), h - 24);
				}
			}
		} catch (Throwable t) {
			// vanilla's layout
		}
	}

	@Inject(method = "drawScreen", at = @At("HEAD"), cancellable = true)
	private void originclient$originPicture(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		try {
			if (OriginStyle.vanillaMenusOn(this)) {
				return;   // vanilla mode: the stock connect screen
			}
			Object self = this;
			if (!(self instanceof GuiScreenAccessor)) {
				return;
			}
			Minecraft mc = Minecraft.getMinecraft();
			boolean authorizing = networkManager != null;
			String lang = mc.gameSettings == null ? null : mc.gameSettings.language;
			if (originclient$status == null || authorizing != originclient$authorizing
					|| !java.util.Objects.equals(lang, originclient$lang)) {
				originclient$authorizing = authorizing;
				originclient$lang = lang;
				originclient$status = I18n.format(authorizing ? "connect.authorizing" : "connect.connecting");
			}
			if (!OriginScreenRenderer.renderConnecting(new Gfx(), originclient$status)) {
				return;   // Origin rendering is off: vanilla draws this frame
			}
			ci.cancel();
			// GuiScreen.drawScreen's own button pass (index loop: no iterator per frame).
			List<GuiButton> buttons = ((GuiScreenAccessor) self).originclient$buttonList();
			for (int i = 0; i < buttons.size(); i++) {
				buttons.get(i).drawButton(mc, mouseX, mouseY);
			}
		} catch (Throwable t) {
			// vanilla's screen (if the picture already drew, the frame just lacks its button)
		}
	}
}
