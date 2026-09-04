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
import com.origin.client.client.render.TitleLayout;
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

// The Origin main menu (2026-09 redesign v4, Lunar-style "hero-left"):
//
//   ┌──────────────────────────────────────────────────────────────────┐
//   │ [head] Player                                                    │
//   │                                                                  │
//   │  ORIGIN  (letters reveal in)                 ┌─ ORIGIN CLIENT ─┐ │
//   │  [ Singleplayer ]  ← primary (accent)        │ v0.4.2 · 1.21.1 │ │
//   │  [ Multiplayer  ]     (nav slides in,        │ Mods     14/26  │ │
//   │  [ Realms       ]      staggered)            │ Profiles 3      │ │
//   │  [ Mods         ]  ← Origin's own            │ [   Edit HUD  ] │ │
//   │                                              ├─ CONTINUE ──────┤ │
//   │  [ Options…     ]                            │ My World  2h ago│ │
//   │  [ Quit Game    ]                            │          [Play] │ │
//   │  (lang)(access)                              │ Hypixel   [Join]│ │
//   └──────────────────────────────────────────────┴─────────────────┘ │
//
// GEOMETRY LIVES IN ONE PLACE: TitleLayout. The wordmark (drawn by
// OriginScreenRenderer) and everything placed here read the same computed
// block, so they can never collide again on a short window.
//
// Vanilla's own widgets are only REPOSITIONED (labels, clicks, actions
// untouched) and re-skinned by OriginButtonRenderer. The cards and their
// buttons are Origin's, added as real widgets through ScreenInvoker. CONTINUE
// is real quick-play: the most recently played world (LevelStorageSource
// summaries, loaded async) and the top saved server (ServerList), opened with
// vanilla's own WorldOpenFlows / ConnectScreen. Mod Menu's own "Mods" button
// is excluded from the layout AND hidden every frame (it is added by Mod
// Menu's screen event after our init hook). Everything is fail-soft.
//
// All injection targets and quick-play signatures confirmed via javap against
// the mapped 1.21.1 jar. priority 2000: Origin's re-skin wins over other mods.
@Mixin(value = TitleScreen.class, priority = 2000)
public class TitleScreenMixin {

	@Unique
	private Button originclient$editHudBtn, originclient$playBtn, originclient$joinBtn, originclient$modsNav;
	@Unique
	private long originclient$openedAt = 0;
	@Unique
	private final List<AbstractWidget> originclient$nav = new ArrayList<>();
	@Unique
	private final List<int[]> originclient$navPos = new ArrayList<>();
	@Unique
	private final List<AbstractWidget> originclient$icons = new ArrayList<>();
	@Unique
	private final List<int[]> originclient$iconPos = new ArrayList<>();

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

	@Unique
	private static double originclient$ease(double raw) {
		return OriginTheme.easeOut(Math.max(0.0, Math.min(1.0, raw)));
	}

	/** Mod Menu's title button (any "Mods" button that isn't Origin's own). */
	@Unique
	private boolean originclient$isForeignMods(GuiEventListener child) {
		return child instanceof Button b && b != originclient$modsNav
				&& "mods".equalsIgnoreCase(b.getMessage().getString());
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
			// AFTER our init hook, so hide it here, every frame.
			if (originclient$isForeignMods(child) && child instanceof AbstractWidget w && w.visible) {
				w.visible = false;
				w.active = false;
				continue;
			}
			if (child instanceof AbstractWidget widget && widget.visible && widget.isHovered()) {
				hoveringClickable = true;
				break;
			}
		}
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, hoveringClickable);
		OriginScreenRenderer.renderTitleAccountChip(guiGraphics);
		originclient$applyPositions();
		originclient$drawChrome(guiGraphics, self);
	}

	/** Re-applies Origin's positions to the nav + icon widgets EVERY FRAME, with
	 *  the entrance slide on X. Every frame because Mod Menu's screen event fires
	 *  after our init and "bumps" every button below Realms down by 24 to make
	 *  room for its own (hidden) button — which left a phantom gap in the list
	 *  until this. Six setX/setY calls a frame is nothing. */
	@Unique
	private void originclient$applyPositions() {
		long el = System.currentTimeMillis() - originclient$openedAt;
		for (int i = 0; i < originclient$nav.size(); i++) {
			int[] t = originclient$navPos.get(i);
			double e = el > 1200 ? 1.0 : originclient$ease((el - 120 - i * 55) / 380.0);
			AbstractWidget w = originclient$nav.get(i);
			w.setX(t[0] - (int) Math.round((1.0 - e) * 26));
			w.setY(t[1]);
		}
		for (int i = 0; i < originclient$icons.size(); i++) {
			int[] t = originclient$iconPos.get(i);
			originclient$icons.get(i).setX(t[0]);
			originclient$icons.get(i).setY(t[1]);
		}
	}

	/** The side cards (panels and text). Drawn at render HEAD, i.e. under the
	 *  widgets, so the cards' buttons (real widgets) paint on top. They fade and
	 *  slide in after the wordmark has revealed. */
	@Unique
	private void originclient$drawChrome(GuiGraphics g, Screen self) {
		try {
			Minecraft mc = Minecraft.getInstance();
			Font font = mc.font;
			boolean haveWorld = originclient$worldId != null, haveServer = originclient$server != null;
			TitleLayout.contEntries = (haveWorld ? 1 : 0) + (haveServer ? 1 : 0);
			TitleLayout L = TitleLayout.of(self.width, self.height);
			long el = System.currentTimeMillis() - originclient$openedAt;
			float ca = (float) originclient$ease((el - 260) / 420.0);   // chrome alpha
			int slide = (int) Math.round((1.0 - ca) * 14);

			boolean btns = L.card && ca > 0.6f;
			if (originclient$editHudBtn != null) {
				originclient$editHudBtn.visible = btns;
			}
			if (originclient$playBtn != null) {
				originclient$playBtn.visible = btns && L.cont && haveWorld;
				originclient$joinBtn.visible = btns && L.cont && haveServer;
			}
			if (!L.card || ca <= 0.01f) {
				return;
			}
			int cx = L.cardX + slide, cy = L.cardY, cw = L.cardW, ch = L.cardH;
			int pad = 12, tx = cx + pad;
			OriginUi.panel(g, cx, cy, cw, ch, OriginTheme.RADIUS_MD,
					originclient$a(OriginTheme.BOX_FILL, ca), originclient$a(OriginTheme.BOX_BORDER, ca));
			OriginText.drawBold(g, font, "ORIGIN CLIENT", tx, cy + 10, originclient$a(OriginTheme.MUTED, ca), true);

			String mcVer = net.minecraft.SharedConstants.getCurrentVersion().getName();
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
			int profiles = Profiles.names().size();
			String[][] rows = {
					{"Mods", on + " / " + total + " on"},
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
			if (!L.cont || (!haveWorld && !haveServer)) {
				return;
			}
			int cy2 = L.contY, ch2 = L.contH;
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
				OriginText.draw(g, font, OriginText.ellipsize(font, originclient$ago(originclient$worldPlayed), textW - 16), tx + 16, rowY + 12,
						originclient$a(OriginTheme.MUTED, ca), true);
				rowY += 30;
			}
			if (haveServer) {
				ServerData sd = originclient$server;
				OriginUi.icon(g, "serveraddress", tx, rowY + 1, 12, originclient$a(OriginTheme.ACCENT_2, ca));
				OriginText.drawBold(g, font, OriginText.ellipsize(font, sd.name == null ? sd.ip : sd.name, textW - 16), tx + 16, rowY,
						originclient$a(OriginTheme.TEXT, ca), true);
				OriginText.draw(g, font, OriginText.ellipsize(font, sd.ip, textW - 16), tx + 16, rowY + 12,
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

	// No bottom version line (the card carries the version).
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
			// Copyright line + any Mod Menu "Mods" button already present: hidden.
			if ((child instanceof PlainTextButton || originclient$isForeignMods(child))
					&& child instanceof AbstractWidget widget) {
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

	/** Left nav list under the mark; language/accessibility icons tucked below.
	 *  Publishes the nav count to TitleLayout FIRST so the wordmark is sized for
	 *  the same block this method places the buttons in. */
	@Unique
	private void originclient$layoutHeroLeft(Screen self) {
		try {
			originclient$nav.clear();
			originclient$navPos.clear();
			originclient$icons.clear();
			originclient$iconPos.clear();
			List<AbstractWidget> main = new ArrayList<>();
			List<AbstractWidget> icons = new ArrayList<>();
			for (GuiEventListener child : self.children()) {
				if (!(child instanceof AbstractWidget w) || !w.visible || OriginWidgetOwnership.isForeign(w)
						|| originclient$isForeignMods(child)) {
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
			TitleLayout.navCount = main.size();
			TitleLayout L = TitleLayout.of(self.width, self.height);
			int x = L.left, bw = L.navW;
			int y = L.navTop;
			for (int i = 0; i < main.size(); i++) {
				AbstractWidget w = main.get(i);
				if (i == main.size() - 2 && main.size() >= 4) {
					y += L.navGroupGap;                    // Options / Quit form their own group
				}
				w.setX(x);
				w.setY(y);
				w.setWidth(bw);
				originclient$nav.add(w);
				originclient$navPos.add(new int[]{x, y});
				y += L.navStep;
				if (i == 0) {
					OriginButtonRenderer.markPrimary(w);   // Singleplayer = the main action
				}
			}
			// Language / accessibility icons: bottom-right corner, right-aligned.
			int totalW = 0;
			for (AbstractWidget w : icons) {
				totalW += w.getWidth() + 6;
			}
			int ix = self.width - L.left - Math.max(0, totalW - 6);
			for (AbstractWidget w : icons) {
				w.setX(ix);
				w.setY(L.iconRowY);
				originclient$icons.add(w);
				originclient$iconPos.add(new int[]{ix, L.iconRowY});
				ix += w.getWidth() + 6;
			}
			StringBuilder dbg = new StringBuilder();
			for (AbstractWidget w : main) {
				dbg.append(w.getClass().getSimpleName()).append(':').append(w.getMessage().getString())
						.append('@').append(w.getY()).append(' ');
			}
			com.origin.client.OriginClient.LOGGER.debug("[title-layout] nav = {}", dbg);
		} catch (Throwable ignored) {
		}
	}

	/** The cards' real buttons: Edit HUD (stats card), Play / Join (continue). */
	@Unique
	private void originclient$addCardButtons(Screen self) {
		try {
			TitleLayout L = TitleLayout.of(self.width, self.height);
			if (!L.card) {
				return;
			}
			int cx = L.cardX, cy = L.cardY, cw = L.cardW, ch = L.cardH;
			int pad = 12;
			Minecraft mc = Minecraft.getInstance();
			ScreenInvoker inv = (ScreenInvoker) self;
			originclient$editHudBtn = Button.builder(Component.literal("Edit HUD"),
					b -> mc.setScreen(new HudEditorScreen())).bounds(cx + pad, cy + ch - pad - 20, cw - pad * 2, 20).build();
			originclient$editHudBtn.visible = false;
			inv.originclient$addRenderableWidget(originclient$editHudBtn);

			// CONTINUE: Play (most recent world) / Join (top saved server). Hidden
			// until drawChrome confirms there is data + room.
			int bx = cx + cw - pad - 44;
			originclient$playBtn = Button.builder(Component.literal("Play"), b -> {
				String id = originclient$worldId;
				if (id != null) {
					mc.createWorldOpenFlows().openWorld(id, () -> mc.setScreen(self));
				}
			}).bounds(bx, L.contY + 24, 44, 20).build();
			originclient$joinBtn = Button.builder(Component.literal("Join"), b -> {
				ServerData sd = originclient$server;
				if (sd != null) {
					ConnectScreen.startConnecting(self, mc, ServerAddress.parseString(sd.ip), sd, false, null);
				}
			}).bounds(bx, L.contY + 24 + (originclient$worldId != null ? 30 : 0), 44, 20).build();
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
					Button j = originclient$joinBtn, p = originclient$playBtn;
					if (j != null && p != null) {
						j.setY(p.getY() + 30);        // Join sits under Play once we know there is a world
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
