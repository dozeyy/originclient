package com.origin.client.client.gui;

import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.theme.OriginTheme;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// The read-only mods list behind the title screen's "Mods" button: every mod
// in the instance, split into "Origin" (the client + the launcher-managed
// stack, shown so players can see exactly what Origin ships) and "Your Mods"
// (anything the player added themselves). Display only, on purpose — mod
// SETTINGS stay in-game (Right Shift -> HUD editor -> mod menu), so this
// screen never mutates anything.
//
// This file lives in shared/ and must compile UNCHANGED on every module from
// 1.20 to 1.21.11, which drives three deliberate quirks:
//  - drawing is plain GuiGraphics fill/drawString/scissor only (the one GUI
//    surface that is byte-stable across the whole range — no OriginUi, whose
//    per-module forks drift);
//  - mouseScrolled is implemented in BOTH its historical shapes (3-arg
//    through 1.20.1, 4-arg since 1.20.2) with no @Override: per module,
//    one is the real override and the other is dead weight;
//  - renderBackground likewise: the 4-arg body is empty so 1.20.2+'s
//    super.render() doesn't repaint the backdrop over the list — render()
//    below draws the Origin backdrop itself (fail-soft to a flat charcoal
//    fill), which is also what makes the screen work on 1.20/1.20.1 where
//    Screen.render() never calls renderBackground at all.
public class OriginModsListScreen extends Screen {

	// Mods Origin itself provisions (bundled jar-in-jar or launcher-installed
	// standalone) — everything else in the instance is player-owned. Ids, not
	// filenames: this is the loader's view, not the mods-folder view. Kept
	// here rather than in Mods.java because that file is a 1.21.1 fork.
	private static final Set<String> ORIGIN_IDS = Set.of(
			"originclient", "fabric-api",
			"sodium", "indium", "lithium", "ferritecore", "krypton", "iris",
			"sodium-extra", "moreculling", "cullleaves",
			"cloth-config", "cloth-config2", "midnightlib",
			"immediatelyfast", "modernfix", "betterrenderdistance", "jei",
			"c2me", "starlight", "scalablelux",
			// Always-on QoL batch (2026-07-21): Voice Chat + Clumps, Noisium,
			// World Host, Shulker Box Tooltip, Status Effect Timer.
			"voicechat", "clumps", "noisium", "world_host",
			"shulkerboxtooltip", "effecttimerplus");

	// Runtime plumbing that isn't a "mod" to a player.
	private static final Set<String> HIDDEN_IDS = Set.of("minecraft", "java", "fabricloader");

	private static final int ROW_H = 14;
	private static final int PANEL_W = 300;

	private record Row(String name, String version, boolean header) {
	}

	private final Screen parent;
	private final List<Row> rows = new ArrayList<>();
	private double scrollY;
	private int contentHeight;

	public OriginModsListScreen(Screen parent) {
		super(Component.translatable("originclient.screen.mods.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rows.clear();
		List<Row> origin = new ArrayList<>();
		List<Row> yours = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (HIDDEN_IDS.contains(id)) {
				continue;
			}
			// Nested (jar-in-jar) mods collapse into their parent — except the
			// ones nested inside originclient itself: that's the bundled perf
			// stack on 1.21.1, exactly what the Origin section exists to show.
			var parentMod = mod.getContainingMod();
			if (parentMod.isPresent()
					&& !"originclient".equals(parentMod.get().getMetadata().getId())) {
				continue;
			}
			Row row = new Row(mod.getMetadata().getName(),
					mod.getMetadata().getVersion().getFriendlyString(), false);
			boolean origins = ORIGIN_IDS.contains(id) || parentMod.isPresent();
			(origins ? origin : yours).add(row);
		}
		Comparator<Row> byName = Comparator.comparing(r -> r.name().toLowerCase(Locale.ROOT));
		origin.sort(byName);
		yours.sort(byName);
		rows.add(new Row(Component.translatable("originclient.screen.mods.origin").getString(), "", true));
		rows.addAll(origin);
		if (!yours.isEmpty()) {
			rows.add(new Row(Component.translatable("originclient.screen.mods.yours").getString(), "", true));
			rows.addAll(yours);
		}
		contentHeight = rows.size() * ROW_H;

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(this.width / 2 - 100, this.height - 28, 200, 20)
				.build());
		clampScroll();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		// Same backdrop as the title screen; flat charcoal if the renderer is
		// unhealthy (fail-soft — never a black or garbage screen).
		if (!OriginScreenRenderer.renderTitleBackground(guiGraphics)) {
			guiGraphics.fill(0, 0, this.width, this.height, OriginTheme.BG);
		}
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, false);

		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 18, OriginTheme.TEXT);

		int px = panelX(), py = panelY(), pw = PANEL_W, ph = panelH();
		panel(guiGraphics, px, py, pw, ph);

		guiGraphics.enableScissor(px + 1, py + 1, px + pw - 1, py + ph - 1);
		int y = py + 6 - (int) scrollY;
		for (Row row : rows) {
			if (y + ROW_H >= py && y <= py + ph) {
				if (row.header()) {
					guiGraphics.drawString(this.font, row.name().toUpperCase(Locale.ROOT),
							px + 10, y + 3, OriginTheme.MUTED);
				} else {
					guiGraphics.drawString(this.font, row.name(), px + 18, y + 3, OriginTheme.TEXT);
					int vw = this.font.width(row.version());
					guiGraphics.drawString(this.font, row.version(), px + pw - 10 - vw, y + 3, OriginTheme.TEXT_DIM);
				}
			}
			y += ROW_H;
		}
		guiGraphics.disableScissor();

		super.render(guiGraphics, mouseX, mouseY, partialTick);
	}

	// Translucent dark rectangle + hairline border — the same tokens the
	// restyled title buttons use, hand-rolled with fills so this file needs
	// no per-module drawing kit.
	private static void panel(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, OriginTheme.PANEL_TRANSLUCENT);
		g.fill(x, y, x + w, y + 1, OriginTheme.BORDER_NORMAL);
		g.fill(x, y + h - 1, x + w, y + h, OriginTheme.BORDER_NORMAL);
		g.fill(x, y + 1, x + 1, y + h - 1, OriginTheme.BORDER_NORMAL);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, OriginTheme.BORDER_NORMAL);
	}

	private int panelX() {
		return (this.width - PANEL_W) / 2;
	}

	private int panelY() {
		return 36;
	}

	private int panelH() {
		return this.height - 36 - 40;
	}

	private void clampScroll() {
		double max = Math.max(0, contentHeight + 12 - panelH());
		scrollY = Math.max(0, Math.min(scrollY, max));
	}

	private boolean scrollBy(double wheelDelta) {
		scrollY -= wheelDelta * ROW_H * 2;
		clampScroll();
		return true;
	}

	// mouseScrolled, both era shapes (see class comment). No @Override on
	// purpose: exactly one of these matches the module's Screen signature.
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return scrollBy(amount);
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollYAmount) {
		return scrollBy(scrollYAmount);
	}

	// renderBackground, both era shapes (see class comment): render() owns the
	// backdrop, so both are deliberate no-ops.
	public void renderBackground(GuiGraphics guiGraphics) {
	}

	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
