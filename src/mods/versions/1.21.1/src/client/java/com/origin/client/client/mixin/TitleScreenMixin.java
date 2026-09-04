package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.gui.OriginForeignWidgets;
import com.origin.client.client.gui.OriginModMenuScreen;
import com.origin.client.client.gui.OriginText;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.Profiles;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.shaders.IrisBridge;
import com.origin.client.client.shaders.ShaderBrowserScreen;
import com.origin.client.client.theme.OriginTheme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The Origin main menu (2026-09 redesign, Lunar-style "hero-left"):
//
//   ┌───────────────────────────────────────────────────────────────┐
//   │ [head] Player                                                 │
//   │                                                               │
//   │  ORIGIN                                  ┌─ ORIGIN CLIENT ──┐ │
//   │  MINECRAFT 1.21.1 · FABRIC · SHADERS     │ v1.0.29 · 1.21.1 │ │
//   │  [ Singleplayer ]  ← primary (accent)    │ Mods      14/26  │ │
//   │  [ Multiplayer  ]                        │ Shaders   Pack   │ │
//   │  [ Realms       ]                        │ Profiles  3      │ │
//   │  [ Options…     ]                        │ [Mods] [Shaders] │ │
//   │  [ Quit Game    ]                        └──────────────────┘ │
//   │  (lang)(access)                                               │
//   └───────────────────────────────────────────────────────────────┘
//
// The graded panorama + cursor glow + account chip stay. Vanilla's own
// widgets are only REPOSITIONED (labels, clicks and actions untouched) and
// re-skinned by OriginButtonRenderer; the side card and its two buttons are
// Origin's. Mod Menu's stray "Mods" button is hidden — the card's "Mods"
// opens Origin's own menu instead. Everything is fail-soft: a throw leaves
// vanilla's layout in place, and a missing renderer brings vanilla back.
//
// All injection targets confirmed via javap against the mapped 1.21.1 jar.
// priority 2000: Origin's re-skin wins over other title-screen mods.
@Mixin(value = TitleScreen.class, priority = 2000)
public class TitleScreenMixin {

	@Unique
	private Button originclient$modsBtn;
	@Unique
	private Button originclient$shadersBtn;

	// SETTINGS > General > Main Menu Style. "Vanilla" turns the entire re-skin
	// off — every inject below no-ops so the stock title screen shows through.
	private static boolean originclient$origin() {
		return Mods.mode(Mods.GENERAL_ID, "mainMenuStyle").equals("Origin");
	}

	// ---- shared geometry: mark, nav list, side card all derive from this ----
	// {left, stackY, navW, cardX, cardY, cardW, cardH, cardVisible(0/1)}
	// left + stackY use the SAME formulas as OriginScreenRenderer.renderTitleWordmark
	// so the wordmark and the nav list stay locked together at every window size.
	@Unique
	private static int[] originclient$geom(int sw, int sh) {
		int left = Math.max(24, (int) Math.round(sw * 0.08));
		int stackY = Math.max(sh / 2 - 8, (int) Math.round(sh * 0.40));
		int navW = Math.max(140, Math.min(180, sw / 4));
		int cardW = Math.max(170, Math.min(240, (int) Math.round(sw * 0.28)));
		int cardH = 116;
		int cardX = sw - left - cardW;
		int cardY = stackY - 6;
		boolean show = cardX >= left + navW + 24 && cardY + cardH <= sh - 36;
		return new int[]{left, stackY, navW, cardX, cardY, cardW, cardH, show ? 1 : 0};
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$background(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		OriginScreenRenderer.renderTitleBackground(guiGraphics);
		boolean hoveringClickable = false;
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof AbstractWidget widget && widget.visible && widget.isHovered()) {
				hoveringClickable = true;
				break;
			}
		}
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, hoveringClickable);
		OriginScreenRenderer.renderTitleAccountChip(guiGraphics);
		originclient$drawChrome(guiGraphics, self);
	}

	/** The status line under the wordmark + the side card (panel and text).
	 *  Drawn at render HEAD, i.e. under the widgets, so the card's two buttons
	 *  (real widgets) paint on top of the panel. */
	@Unique
	private void originclient$drawChrome(GuiGraphics g, Screen self) {
		try {
			Minecraft mc = Minecraft.getInstance();
			Font font = mc.font;
			int[] gm = originclient$geom(self.width, self.height);
			int left = gm[0], stackY = gm[1];

			// Status line: real facts about this install, in the eyebrow voice.
			String mcVer = net.minecraft.SharedConstants.getCurrentVersion().getName();
			String status = "MINECRAFT " + mcVer + "   ·   FABRIC   ·   "
					+ (IrisBridge.installed() ? "SHADERS READY" : "SHADERS OFF");
			OriginText.draw(g, font, status, left, stackY - 12, OriginTheme.MUTED, true);

			if (gm[7] == 0) {
				return;
			}
			int cx = gm[3], cy = gm[4], cw = gm[5], ch = gm[6];
			OriginUi.panel(g, cx, cy, cw, ch, OriginTheme.RADIUS_MD, OriginTheme.BOX_FILL, OriginTheme.BOX_BORDER);
			int pad = 12;
			int tx = cx + pad;
			OriginText.drawBold(g, font, "ORIGIN CLIENT", tx, cy + 10, OriginTheme.MUTED, true);

			String ver = FabricLoader.getInstance().getModContainer("originclient")
					.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
			String line2 = (ver.isEmpty() ? "" : "v" + ver + "   ·   ") + "Minecraft " + mcVer;
			OriginText.draw(g, font, line2, tx, cy + 23, OriginTheme.TEXT_DIM, true);

			int total = Mods.ALL.size(), on = 0;
			for (Mods.Mod m : Mods.ALL) {
				if (Mods.on(m.id())) {
					on++;
				}
			}
			String pack = IrisBridge.installed() ? IrisBridge.currentPack() : null;
			String shaders = !IrisBridge.installed() ? "Not installed"
					: (pack == null || pack.isEmpty()) ? "Off" : pack;
			int profiles = Profiles.names().size();
			String[][] rows = {
					{"Mods", on + " / " + total + " on"},
					{"Shaders", OriginText.ellipsize(font, shaders, cw - pad * 2 - 54)},
					{"Profiles", profiles + " saved"},
			};
			int ry = cy + 41;
			for (String[] r : rows) {
				OriginText.draw(g, font, r[0], tx, ry, OriginTheme.MUTED, true);
				int vw = OriginText.width(font, r[1]);
				OriginText.draw(g, font, r[1], cx + cw - pad - vw, ry, OriginTheme.TEXT, true);
				ry += 13;
			}
		} catch (Throwable ignored) {
		}
	}

	// Both suppressions are gated on the renderer's health: if the Origin
	// backdrop ever fails (fail-soft contract), vanilla's panorama comes back.
	@Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressPanorama(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
		if (originclient$origin() && OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (originclient$origin() && OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/LogoRenderer;renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IF)V"))
	private void originclient$logo(LogoRenderer instance, GuiGraphics guiGraphics, int screenWidth, float alpha) {
		if (!originclient$origin() || !OriginScreenRenderer.renderTitleWordmark(guiGraphics)) {
			instance.renderLogo(guiGraphics, screenWidth, alpha);
		}
	}

	// No yellow splash text (Origin style only).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/SplashRenderer;render(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/client/gui/Font;I)V"))
	private void originclient$noSplash(SplashRenderer instance, GuiGraphics guiGraphics, int screenWidth, Font font, int color) {
		if (!originclient$origin()) {
			instance.render(guiGraphics, screenWidth, font, color);
		}
	}

	// No bottom version line (the status line under the mark carries it).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"))
	private int originclient$noVersion(GuiGraphics instance, Font font, String text, int x, int y, int color) {
		return originclient$origin() ? 0 : instance.drawString(font, text, x, y, color);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$layout(CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			// Copyright line: hidden. Mod Menu's own "Mods" button: hidden — the
			// side card's Mods button opens Origin's menu, which lists everything.
			boolean hide = child instanceof PlainTextButton;
			if (child instanceof Button b && OriginWidgetOwnership.isForeign(b)
					&& "mods".equalsIgnoreCase(b.getMessage().getString())) {
				hide = true;
			}
			if (hide && child instanceof AbstractWidget widget) {
				widget.visible = false;
				widget.active = false;
			}
		}
		originclient$layoutHeroLeft(self);
		originclient$addCardButtons(self);
		OriginForeignWidgets.avoidOverlap(self);
	}

	/** Left nav list under the mark; language/accessibility icons tucked below. */
	@Unique
	private static void originclient$layoutHeroLeft(Screen self) {
		try {
			java.util.List<AbstractWidget> main = new java.util.ArrayList<>();
			java.util.List<AbstractWidget> icons = new java.util.ArrayList<>();
			for (GuiEventListener child : self.children()) {
				if (!(child instanceof AbstractWidget w) || !w.visible || OriginWidgetOwnership.isForeign(w)) {
					continue;
				}
				if (child instanceof net.minecraft.client.gui.components.SpriteIconButton) {
					icons.add(w);
				} else if (child instanceof Button) {
					main.add(w);
				}
			}
			if (main.isEmpty()) {
				return;
			}
			main.sort(java.util.Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
			int[] gm = originclient$geom(self.width, self.height);
			int x = gm[0], bw = gm[2];
			int bh = 20, step = 24;
			int total = main.size() * step - (step - bh);
			int y = gm[1] + 10;                       // just under the status line
			int iconRowY = self.height - 20 - 14;
			if (y + total > iconRowY - 10) {          // short window: keep it on-screen
				y = Math.max(40, iconRowY - 10 - total);
			}
			boolean first = true;
			for (AbstractWidget w : main) {
				w.setX(x);
				w.setY(y);
				w.setWidth(bw);
				y += step;
				if (first) {
					OriginButtonRenderer.markPrimary(w);   // Singleplayer = the main action
					first = false;
				}
			}
			int ix = x;
			for (AbstractWidget w : icons) {
				w.setX(ix);
				w.setY(iconRowY);
				ix += w.getWidth() + 6;
			}
		} catch (Throwable ignored) {
		}
	}

	/** The side card's two real buttons (Mods → Origin menu, Shaders → browser). */
	@Unique
	private void originclient$addCardButtons(Screen self) {
		try {
			int[] gm = originclient$geom(self.width, self.height);
			if (gm[7] == 0) {
				return;
			}
			int cx = gm[3], cy = gm[4], cw = gm[5], ch = gm[6];
			int pad = 10, gap = 8;
			int bw = (cw - pad * 2 - gap) / 2;
			int by = cy + ch - pad - 20;
			Minecraft mc = Minecraft.getInstance();
			originclient$modsBtn = Button.builder(Component.literal("Mods"),
					b -> mc.setScreen(new OriginModMenuScreen())).bounds(cx + pad, by, bw, 20).build();
			originclient$shadersBtn = Button.builder(Component.literal("Shaders"),
					b -> mc.setScreen(new ShaderBrowserScreen(self))).bounds(cx + pad + bw + gap, by, bw, 20).build();
			originclient$shadersBtn.active = IrisBridge.installed();
			ScreenInvoker inv = (ScreenInvoker) self;
			inv.originclient$addRenderableWidget(originclient$modsBtn);
			inv.originclient$addRenderableWidget(originclient$shadersBtn);
		} catch (Throwable ignored) {
		}
	}
}
