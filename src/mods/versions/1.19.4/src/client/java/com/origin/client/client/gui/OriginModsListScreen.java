package com.origin.client.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.theme.OriginTheme;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

// The read-only mods list behind the title screen's "Mods" button: every mod
// in the instance, split into "Origin" (the client + the launcher-managed
// stack) and "Your Mods" (anything the player added). Display only — mod
// SETTINGS stay in-game (Right Shift -> HUD editor -> mod menu).
//
// Pre-1.20 port of the shared/ modern screen: same behavior, drawn through
// this module's Gfx (PoseStack) wrapper instead of GuiGraphics. The Origin
// backdrop comes from renderTitleBackground (fail-soft to a flat charcoal
// fill), the same source the title screen uses.
public class OriginModsListScreen extends Screen {

	// Mods Origin itself provisions (bundled or launcher-installed) — ids, not
	// filenames. Kept here rather than in Mods.java (a per-version fork).
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
			// Nested (jar-in-jar) mods collapse into their parent — except those
			// nested inside originclient itself (the bundled perf stack).
			Optional<ModContainer> parentMod = mod.getContainingMod();
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
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		Gfx g = new Gfx(poseStack);
		// Same backdrop as the title screen; flat charcoal if the renderer is
		// unhealthy (fail-soft — never a black or garbage screen).
		if (!OriginScreenRenderer.renderTitleBackground(g)) {
			g.fill(0, 0, this.width, this.height, OriginTheme.BG);
		}
		OriginScreenRenderer.renderTitleCursorGlow(g, mouseX, mouseY, false);

		int titleX = (this.width - this.font.width(this.title)) / 2;
		g.drawString(this.font, this.title, titleX, 18, OriginTheme.TEXT, false);

		int px = panelX(), py = panelY(), pw = PANEL_W, ph = panelH();
		panel(g, px, py, pw, ph);

		g.enableScissor(px + 1, py + 1, px + pw - 1, py + ph - 1);
		int y = py + 6 - (int) scrollY;
		for (Row row : rows) {
			if (y + ROW_H >= py && y <= py + ph) {
				if (row.header()) {
					g.drawString(this.font, row.name().toUpperCase(Locale.ROOT),
							px + 10, y + 3, OriginTheme.MUTED);
				} else {
					g.drawString(this.font, row.name(), px + 18, y + 3, OriginTheme.TEXT);
					int vw = this.font.width(row.version());
					g.drawString(this.font, row.version(), px + pw - 10 - vw, y + 3, OriginTheme.TEXT_DIM);
				}
			}
			y += ROW_H;
		}
		g.disableScissor();

		super.render(poseStack, mouseX, mouseY, partialTick);
	}

	// Translucent dark rectangle + hairline border — the title-button look,
	// hand-rolled with fills so this file needs no per-module drawing kit.
	private static void panel(Gfx g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, OriginTheme.PANEL_TRANSLUCENT);
		g.fill(x, y, x + w, y + 1, OriginTheme.STROKE);
		g.fill(x, y + h - 1, x + w, y + h, OriginTheme.STROKE);
		g.fill(x, y + 1, x + 1, y + h - 1, OriginTheme.STROKE);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, OriginTheme.STROKE);
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

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		scrollY -= amount * ROW_H * 2;
		clampScroll();
		return true;
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
