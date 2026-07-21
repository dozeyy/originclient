package com.origin.client.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.theme.OriginTheme;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

// The read-only mods list behind the title screen's "Mods" button: every mod
// in the instance, split into "Origin" (the client + the launcher-managed
// stack) and "Your Mods" (anything the player added). Display only — mod
// SETTINGS stay in-game (Right Shift -> HUD editor -> mod menu).
//
// 1.16.5 port (Java 8 + fixed-function GL): no records, no pattern-matching
// instanceof, no Set.of/List.of — HashSet + Arrays.asList and a plain Row
// class instead. Same behavior as the shared modern screen, drawn through
// this module's Gfx (PoseStack) wrapper. Below 1.18, Fabric API's mod id is
// "fabric" (not "fabric-api"), so ORIGIN_IDS carries that spelling.
public class OriginModsListScreen extends Screen {

	private static final Set<String> ORIGIN_IDS = new HashSet<String>(Arrays.asList(
			"originclient", "fabric", "fabric-api",
			"sodium", "indium", "lithium", "ferritecore", "krypton", "iris",
			"sodium-extra", "moreculling", "cullleaves",
			"cloth-config", "cloth-config2", "midnightlib",
			"immediatelyfast", "modernfix", "betterrenderdistance",
			"c2me", "starlight", "scalablelux",
			// Always-on QoL batch (2026-07-21): Voice Chat + Clumps, Noisium,
			// World Host, Shulker Box Tooltip, Status Effect Timer.
			"voicechat", "clumps", "noisium", "world_host",
			"shulkerboxtooltip", "effecttimerplus"));

	private static final Set<String> HIDDEN_IDS = new HashSet<String>(Arrays.asList(
			"minecraft", "java", "fabricloader"));

	private static final int ROW_H = 14;
	private static final int PANEL_W = 300;

	private static final class Row {
		final String name;
		final String version;
		final boolean header;

		Row(String name, String version, boolean header) {
			this.name = name;
			this.version = version;
			this.header = header;
		}
	}

	private final Screen parent;
	private final List<Row> rows = new ArrayList<Row>();
	private double scrollY;
	private int contentHeight;

	public OriginModsListScreen(Screen parent) {
		super(new TranslatableComponent("originclient.screen.mods.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rows.clear();
		List<Row> origin = new ArrayList<Row>();
		List<Row> yours = new ArrayList<Row>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (HIDDEN_IDS.contains(id)) {
				continue;
			}
			// Nested (jar-in-jar) mods collapse into their parent — except those
			// nested inside originclient itself (the bundled stack).
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
		Comparator<Row> byName = new Comparator<Row>() {
			@Override
			public int compare(Row a, Row b) {
				return a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
			}
		};
		Collections.sort(origin, byName);
		Collections.sort(yours, byName);
		rows.add(new Row(new TranslatableComponent("originclient.screen.mods.origin").getString(), "", true));
		rows.addAll(origin);
		if (!yours.isEmpty()) {
			rows.add(new Row(new TranslatableComponent("originclient.screen.mods.yours").getString(), "", true));
			rows.addAll(yours);
		}
		contentHeight = rows.size() * ROW_H;

		this.addButton(new Button(this.width / 2 - 100, this.height - 28, 200, 20,
				CommonComponents.GUI_DONE, b -> onClose()));
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

		String titleStr = this.title.getString();
		g.drawString(this.font, titleStr, (this.width - this.font.width(titleStr)) / 2, 18,
				OriginTheme.TEXT, false);

		int px = panelX(), py = panelY(), pw = PANEL_W, ph = panelH();
		panel(g, px, py, pw, ph);

		g.enableScissor(px + 1, py + 1, px + pw - 1, py + ph - 1);
		int y = py + 6 - (int) scrollY;
		for (Row row : rows) {
			if (y + ROW_H >= py && y <= py + ph) {
				if (row.header) {
					g.drawString(this.font, row.name.toUpperCase(Locale.ROOT),
							px + 10, y + 3, OriginTheme.MUTED, false);
				} else {
					g.drawString(this.font, row.name, px + 18, y + 3, OriginTheme.TEXT, false);
					int vw = this.font.width(row.version);
					g.drawString(this.font, row.version, px + pw - 10 - vw, y + 3, OriginTheme.TEXT_DIM, false);
				}
			}
			y += ROW_H;
		}
		g.disableScissor();

		super.render(poseStack, mouseX, mouseY, partialTick);
	}

	// Translucent dark rectangle + hairline border — the title-button look.
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
