package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.gui.OriginForeignWidgets;
import com.origin.client.client.gui.OriginModMenuScreen;
import com.origin.client.client.gui.OriginText;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.hud.HudEditorScreen;
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
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

// The Origin main menu (2026-09 redesign v3, Lunar-style "hero-left"):
//
//   ┌──────────────────────────────────────────────────────────────────┐
//   │ [head] Player                                                    │
//   │                                                                  │
//   │  ORIGIN  (letters reveal in)                 ┌─ ORIGIN CLIENT ─┐ │
//   │  [ Singleplayer ]  ← primary (accent)        │ v0.4.2 · 1.21.1 │ │
//   │  [ Multiplayer  ]     (nav slides in,        │ Mods     14/26  │ │
//   │  [ Realms       ]      staggered)            │ Shaders  Pack   │ │
//   │  [ Mods         ]  ← Origin's own            │ Profiles 3      │ │
//   │  [ Options…     ]                            │[Shaders][EditHUD]│ │
//   │  [ Quit Game    ]                            ├─ CONTINUE ──────┤ │
//   │                                              │ My World  2h ago│ │
//   │                                              │          [Play] │ │
//   │  (lang)(access)                              │ Hypixel   [Join]│ │
//   └──────────────────────────────────────────────┴─────────────────┘ │
//
// Vanilla's own widgets are only REPOSITIONED (labels, clicks, actions
// untouched) and re-skinned by OriginButtonRenderer. The two cards and their
// buttons are Origin's, added as real widgets through ScreenInvoker. CONTINUE
// is real quick-play: the most recently played world (LevelStorageSource
// summaries, loaded async) and the top saved server (ServerList), opened with
// vanilla's own WorldOpenFlows / ConnectScreen. Mod Menu's stray "Mods" button
// is hidden. Everything is fail-soft: a throw leaves vanilla's layout in place.
//
// All injection targets and quick-play signatures confirmed via javap against
// the mapped 1.21.1 jar. priority 2000: Origin's re-skin wins over other mods.
@Mixin(value = TitleScreen.class, priority = 2000)
public class TitleScreenMixin {

	@Unique
	private Button originclient$modsBtn, originclient$shadersBtn, originclient$playBtn, originclient$joinBtn;
	@Unique
	private Button originclient$modsNav;
	@Unique
	private long originclient$openedAt = 0;
	@Unique
	private final List<AbstractWidget> originclient$nav = new ArrayList<>();
	@Unique
	private int originclient$navX = 0;

	// Quick-play data (static: survives the screen being rebuilt on resize, and
	// the world summaries arrive async). Refreshed on every title init.
	@Unique
	private static volatile String originclient$worldId, originclient$worldName;
	@Unique
	private static volatile long originclient$worldPlayed;
	@Unique
	private static volatile ServerData originclient$server;

	// SETTINGS > General > Main Menu Style. "Vanilla" turns the entire re-skin
	// off — every inject below no-ops so the stock title screen shows through.
	private static boolean originclient$origin() {
		return Mods.mode(Mods.GENERAL_ID, "mainMenuStyle").equals("Origin");
	}

	// ---- shared geometry ----
	// {left, stackY, navW, cardX, cardY, cardW, cardH, cardVisible, contY, contH, contVisible}
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
		int contY = cardY + cardH + 8, contH = 86;
		boolean showCont = show && contY + contH <= sh - 36;
		return new int[]{left, stackY, navW, cardX, cardY, cardW, cardH, show ? 1 : 0, contY, contH, showCont ? 1 : 0};
	}

	@Unique
	private static double originclient$ease(double raw) {
		return OriginTheme.easeOut(Math.max(0.0, Math.min(1.0, raw)));
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
			// Mod Menu adds its "Mods" button through a screen event that fires
			// AFTER our init hook, so hide it here, every frame. Origin's own Mods
			// button (originclient$modsNav) lives in the nav list instead.
			if (child instanceof Button b && b != originclient$modsNav && b.visible
					&& "mods".equalsIgnoreCase(b.getMessage().getString())) {
				b.visible = false;
				b.active = false;
				continue;
			}
			if (child instanceof AbstractWidget widget && widget.visible && widget.isHovered()) {
				hoveringClickable = true;
				break;
			}
		}
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, hoveringClickable);
		OriginScreenRenderer.renderTitleAccountChip(guiGraphics);
		originclient$animateNav();
		originclient$drawChrome(guiGraphics, self);
	}

	/** Entrance choreography for the nav list: each button slides in from the
	 *  left a beat after the previous one. Positions are re-set every frame
	 *  while the animation runs (cheap), then settle exactly on the target. */
	@Unique
	private void originclient$animateNav() {
		long el = System.currentTimeMillis() - originclient$openedAt;
		if (el > 1200 || originclient$nav.isEmpty()) {
			return;
		}
		for (int i = 0; i < originclient$nav.size(); i++) {
			double e = originclient$ease((el - 120 - i * 55) / 380.0);
			originclient$nav.get(i).setX(originclient$navX - (int) Math.round((1.0 - e) * 26));
		}
	}

	/** Status line under the wordmark + the two side cards (panels and text).
	 *  Drawn at render HEAD, i.e. under the widgets, so the cards' buttons
	 *  (real widgets) paint on top. Fades/slides in after the wordmark. */
	@Unique
	private void originclient$drawChrome(GuiGraphics g, Screen self) {
		try {
			Minecraft mc = Minecraft.getInstance();
			Font font = mc.font;
			int[] gm = originclient$geom(self.width, self.height);
			long el = System.currentTimeMillis() - originclient$openedAt;
			float ca = (float) originclient$ease((el - 260) / 420.0);   // chrome alpha
			int slide = (int) Math.round((1.0 - ca) * 14);

			String mcVer = net.minecraft.SharedConstants.getCurrentVersion().getName();

			boolean show = gm[7] == 1, showCont = gm[10] == 1;
			boolean btns = show && ca > 0.6f;
			if (originclient$modsBtn != null) {
				originclient$modsBtn.visible = btns;
				originclient$shadersBtn.visible = btns;
			}
			boolean haveWorld = originclient$worldId != null, haveServer = originclient$server != null;
			if (originclient$playBtn != null) {
				originclient$playBtn.visible = btns && showCont && haveWorld;
				originclient$joinBtn.visible = btns && showCont && haveServer;
			}
			if (!show || ca <= 0.01f) {
				return;
			}
			int cx = gm[3] + slide, cy = gm[4], cw = gm[5], ch = gm[6];
			int pad = 12, tx = cx + pad;
			OriginUi.panel(g, cx, cy, cw, ch, OriginTheme.RADIUS_MD,
					originclient$a(OriginTheme.BOX_FILL, ca), originclient$a(OriginTheme.BOX_BORDER, ca));
			OriginText.drawBold(g, font, "ORIGIN CLIENT", tx, cy + 10, originclient$a(OriginTheme.MUTED, ca), true);

			String ver = FabricLoader.getInstance().getModContainer("originclient")
					.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
			String line2 = (ver.isEmpty() ? "" : "v" + ver + "   ·   ") + "Minecraft " + mcVer;
			OriginText.draw(g, font, line2, tx, cy + 23, originclient$a(OriginTheme.TEXT_DIM, ca), true);

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
				OriginText.draw(g, font, r[0], tx, ry, originclient$a(OriginTheme.MUTED, ca), true);
				int vw = OriginText.width(font, r[1]);
				OriginText.draw(g, font, r[1], cx + cw - pad - vw, ry, originclient$a(OriginTheme.TEXT, ca), true);
				ry += 13;
			}

			// CONTINUE card — quick-play. Only when there is something to continue.
			if (!showCont || (!haveWorld && !haveServer)) {
				return;
			}
			int cy2 = gm[8], ch2 = gm[9];
			OriginUi.panel(g, cx, cy2, cw, ch2, OriginTheme.RADIUS_MD,
					originclient$a(OriginTheme.BOX_FILL, ca), originclient$a(OriginTheme.BOX_BORDER, ca));
			OriginText.drawBold(g, font, "CONTINUE", tx, cy2 + 10, originclient$a(OriginTheme.MUTED, ca), true);
			int btnW = 44;
			int textW = cw - pad * 2 - btnW - 8;
			int rowY = cy2 + 24;
			if (haveWorld) {
				OriginUi.icon(g, "blockoverlay", tx, rowY + 1, 12, originclient$a(OriginTheme.ACCENT_2, ca));
				OriginText.drawBold(g, font, OriginText.ellipsize(font, originclient$worldName, textW - 16), tx + 16, rowY,
						originclient$a(OriginTheme.TEXT, ca), true);
				OriginText.draw(g, font, "Singleplayer  ·  " + originclient$ago(originclient$worldPlayed), tx + 16, rowY + 12,
						originclient$a(OriginTheme.MUTED, ca), true);
				rowY += 30;
			}
			if (haveServer) {
				ServerData sd = originclient$server;
				OriginUi.icon(g, "serveraddress", tx, rowY + 1, 12, originclient$a(OriginTheme.ACCENT_2, ca));
				OriginText.drawBold(g, font, OriginText.ellipsize(font, sd.name == null ? sd.ip : sd.name, textW - 16), tx + 16, rowY,
						originclient$a(OriginTheme.TEXT, ca), true);
				OriginText.draw(g, font, OriginText.ellipsize(font, "Multiplayer  ·  " + sd.ip, textW - 16), tx + 16, rowY + 12,
						originclient$a(OriginTheme.MUTED, ca), true);
			}
		} catch (Throwable ignored) {
		}
	}

	@Unique
	private static int originclient$a(int argb, float mul) {
		int a = (int) Math.round(((argb >>> 24) & 0xFF) * mul);
		return (a << 24) | (argb & 0xFFFFFF);
	}

	@Unique
	private static String originclient$ago(long epochMs) {
		long d = Math.max(0, System.currentTimeMillis() - epochMs);
		long min = d / 60_000, hr = min / 60, day = hr / 24;
		if (day >= 1) {
			return day == 1 ? "yesterday" : day + " days ago";
		}
		if (hr >= 1) {
			return hr + (hr == 1 ? " hour ago" : " hours ago");
		}
		if (min >= 1) {
			return min + (min == 1 ? " minute ago" : " minutes ago");
		}
		return "just now";
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
		if (originclient$openedAt == 0) {           // first init only — a resize re-init doesn't replay
			originclient$openedAt = System.currentTimeMillis();
			OriginScreenRenderer.setTitleOpenedAt(originclient$openedAt);
		}
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
		try {
			Minecraft mc = Minecraft.getInstance();
			originclient$modsNav = Button.builder(Component.literal("Mods"),
					b -> mc.setScreen(new OriginModMenuScreen())).bounds(0, 0, 150, 20).build();
			((ScreenInvoker) self).originclient$addRenderableWidget(originclient$modsNav);
		} catch (Throwable ignored) {
			originclient$modsNav = null;
		}
		originclient$layoutHeroLeft(self);
		originclient$addCardButtons(self);
		originclient$loadQuickPlay(self);
		OriginForeignWidgets.avoidOverlap(self);
	}

	/** Left nav list under the mark; language/accessibility icons tucked below. */
	@Unique
	private void originclient$layoutHeroLeft(Screen self) {
		try {
			originclient$nav.clear();
			List<AbstractWidget> main = new ArrayList<>();
			List<AbstractWidget> icons = new ArrayList<>();
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
			main.remove(originclient$modsNav);
			main.sort(java.util.Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
			if (originclient$modsNav != null) {
				main.add(Math.min(3, main.size()), originclient$modsNav);   // after Realms, before Options
			}
			int[] gm = originclient$geom(self.width, self.height);
			int x = gm[0], bw = gm[2];
			int bh = 20, step = 24;
			int total = main.size() * step - (step - bh);
			int y = gm[1];                            // right under the mark
			int iconRowY = self.height - 20 - 14;
			if (y + total > iconRowY - 10) {          // short window: keep it on-screen
				y = Math.max(40, iconRowY - 10 - total);
			}
			originclient$navX = x;
			boolean first = true;
			for (AbstractWidget w : main) {
				w.setX(x);
				w.setY(y);
				w.setWidth(bw);
				y += step;
				originclient$nav.add(w);
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

	/** The cards' real buttons: Mods / Shaders (stats card), Play / Join (continue). */
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
			ScreenInvoker inv = (ScreenInvoker) self;
			originclient$modsBtn = Button.builder(Component.literal("Shaders"),
					b -> mc.setScreen(new ShaderBrowserScreen(self))).bounds(cx + pad, by, bw, 20).build();
			originclient$modsBtn.active = IrisBridge.installed();
			originclient$shadersBtn = Button.builder(Component.literal("Edit HUD"),
					b -> mc.setScreen(new HudEditorScreen())).bounds(cx + pad + bw + gap, by, bw, 20).build();
			originclient$modsBtn.visible = false;
			originclient$shadersBtn.visible = false;
			inv.originclient$addRenderableWidget(originclient$modsBtn);
			inv.originclient$addRenderableWidget(originclient$shadersBtn);

			// CONTINUE: Play (most recent world) / Join (top saved server). Hidden
			// until drawChrome confirms there is data + room.
			int cy2 = gm[8];
			int bx = cx + cw - 12 - 44;
			originclient$playBtn = Button.builder(Component.literal("Play"), b -> {
				String id = originclient$worldId;
				if (id != null) {
					mc.createWorldOpenFlows().openWorld(id, () -> mc.setScreen(self));
				}
			}).bounds(bx, cy2 + 24, 44, 20).build();
			originclient$joinBtn = Button.builder(Component.literal("Join"), b -> {
				ServerData sd = originclient$server;
				if (sd != null) {
					ConnectScreen.startConnecting(self, mc, ServerAddress.parseString(sd.ip), sd, false, null);
				}
			}).bounds(bx, cy2 + 24 + (originclient$worldId != null ? 30 : 0), 44, 20).build();
			originclient$playBtn.visible = false;
			originclient$joinBtn.visible = false;
			inv.originclient$addRenderableWidget(originclient$playBtn);
			inv.originclient$addRenderableWidget(originclient$joinBtn);
		} catch (Throwable ignored) {
		}
	}

	/** Refresh quick-play data: the most recently played world (async — vanilla's
	 *  own summary loader, off-thread) and the top entry of the saved server list. */
	@Unique
	private void originclient$loadQuickPlay(Screen self) {
		Minecraft mc = Minecraft.getInstance();
		try {
			LevelStorageSource src = mc.getLevelSource();
			src.loadLevelSummaries(src.findLevelCandidates()).thenAccept(list -> {
				LevelSummary best = null;
				for (LevelSummary s : list) {
					if (best == null || s.getLastPlayed() > best.getLastPlayed()) {
						best = s;
					}
				}
				if (best != null) {
					originclient$worldId = best.getLevelId();
					originclient$worldName = best.getLevelName();
					originclient$worldPlayed = best.getLastPlayed();
					// The Join button sits under Play; re-place it now that we know.
					Button j = originclient$joinBtn;
					if (j != null) {
						j.setY(originclient$playBtn.getY() + 30);
					}
				}
			});
		} catch (Throwable ignored) {
		}
		try {
			ServerList sl = new ServerList(mc);
			sl.load();
			originclient$server = sl.size() > 0 ? sl.get(0) : null;
		} catch (Throwable ignored) {
			originclient$server = null;
		}
	}
}
