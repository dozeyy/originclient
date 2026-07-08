package com.origin.client.client.hud;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.ClickStats;
import com.origin.client.client.mods.Mods;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;

// Every movable HUD element: its owning mod, default placement, measured
// size, and renderer. One dispatcher draws them all from the HUD render
// callback; the HUD editor drags the same list. The vanilla HUD (hotbar,
// health, hunger, XP) is untouched and not movable — these are additions
// only. Sizes are in unscaled GUI px; per-element scale is applied around
// the element's top-left by the dispatcher.
public final class HudElements {
	public static final int TEXT = 0xFFE0E0E0;
	private static final int PANEL = 0x66101010;

	public interface Renderer {
		void render(GuiGraphics g, Minecraft mc, int w, int h);
	}

	public record Element(String id, String modId, String label, HudPos defaults,
						  java.util.function.Function<Minecraft, int[]> measure, Renderer renderer) {
		public HudPos pos() {
			return HudPos.load(id, new HudPos(defaults.anchor, defaults.dx, defaults.dy, defaults.scale));
		}
	}

	public static final List<Element> ALL = new ArrayList<>();

	// FPS threshold coloring is a fixed convention: >=60 green-ish, >=30
	// amber, else red — only applied when the option is on.
	private static long fpsSampledAt = 0;
	private static int fpsSample = 0;

	static {
		add("fps", "fps", "FPS", new HudPos(0, 6, 6, 1.0), mc -> text("FPS: 999"), (g, mc, w, h) -> {
			// Sampled a few times per second, not every frame (spec: cheap).
			long now = System.currentTimeMillis();
			if (now - fpsSampledAt > 250) {
				fpsSampledAt = now;
				fpsSample = mc.getFps();
			}
			int color = TEXT;
			if (Mods.num("fps", "threshold") >= 0.5) {
				color = fpsSample >= 60 ? 0xFF55E055 : fpsSample >= 30 ? 0xFFFFD855 : 0xFFE05555;
			}
			g.drawString(mc.font, "FPS: " + fpsSample, 0, 0, color);
		});

		add("cps", "cps", "CPS", new HudPos(0, 6, 18, 1.0), mc -> text("CPS: 12 | 12"), (g, mc, w, h) ->
				g.drawString(mc.font, "CPS: " + ClickStats.leftCps() + " | " + ClickStats.rightCps(), 0, 0, TEXT));

		add("coords", "coords", "Coords", new HudPos(0, 6, 30, 1.0), mc -> {
			int lines = 1 + (Mods.bool("coords", "biome") ? 1 : 0) + (Mods.bool("coords", "direction") ? 1 : 0);
			return new int[]{150, lines * 10};
		}, (g, mc, w, h) -> {
			var p = mc.player;
			if (p == null) {
				return;
			}
			int y = 0;
			g.drawString(mc.font, String.format("XYZ: %.1f / %.1f / %.1f", p.getX(), p.getY(), p.getZ()), 0, y, TEXT);
			y += 10;
			if (Mods.bool("coords", "biome") && mc.level != null) {
				var biome = mc.level.getBiome(BlockPos.containing(p.position()));
				String name = biome.unwrapKey().map(k -> k.location().getPath().replace('_', ' ')).orElse("unknown");
				g.drawString(mc.font, "Biome: " + name, 0, y, TEXT);
				y += 10;
			}
			if (Mods.bool("coords", "direction")) {
				Direction d = p.getDirection();
				g.drawString(mc.font, String.format("Facing: %s (%.0f°)",
						d.getName().substring(0, 1).toUpperCase() + d.getName().substring(1),
						net.minecraft.util.Mth.wrapDegrees(p.getYRot())), 0, y, TEXT);
			}
		});

		add("keystrokes", "keystrokes", "Keystrokes", new HudPos(3, 6, -40, 1.0),
				mc -> new int[]{70, 70}, HudElements::renderKeystrokes);

		add("potionhud", "potionhud", "Potions", new HudPos(2, -6, 6, 1.0), mc -> {
			int n = mc.player == null ? 1 : Math.max(1, mc.player.getActiveEffects().size());
			return new int[]{110, n * 12};
		}, (g, mc, w, h) -> {
			if (mc.player == null) {
				return;
			}
			int y = 0;
			for (MobEffectInstance e : mc.player.getActiveEffects()) {
				int color = 0xFF000000 | e.getEffect().value().getColor();
				g.fill(0, y + 2, 6, y + 8, color);
				String name = e.getEffect().value().getDisplayName().getString();
				String time = e.isInfiniteDuration() ? "∞" : (e.getDuration() / 20) / 60 + ":" + String.format("%02d", (e.getDuration() / 20) % 60);
				g.drawString(mc.font, trim(mc, name, 70) + " " + time, 10, y, TEXT);
				y += 12;
			}
		});

		add("armorhud", "armorhud", "Armor", new HudPos(6, 6, -24, 1.0),
				mc -> new int[]{4 * 18 + 20, 18}, (g, mc, w, h) -> {
					if (mc.player == null) {
						return;
					}
					int x = 0;
					for (ItemStack stack : mc.player.getInventory().armor) {
						if (!stack.isEmpty()) {
							g.renderItem(stack, x, 0);
							g.renderItemDecorations(mc.font, stack, x, 0);
						}
						x += 18;
					}
					ItemStack held = mc.player.getMainHandItem();
					if (!held.isEmpty()) {
						g.renderItem(held, x + 2, 0);
						g.renderItemDecorations(mc.font, held, x + 2, 0);
					}
				});

		add("serveraddress", "serveraddress", "Server IP", new HudPos(2, -6, 6, 1.0), mc -> text("ip.example.com (99)"),
				(g, mc, w, h) -> {
					ServerData server = mc.getCurrentServer();
					if (server == null) {
						g.drawString(mc.font, "Singleplayer", 0, 0, TEXT);
						return;
					}
					int players = mc.getConnection() != null ? mc.getConnection().getOnlinePlayers().size() : 0;
					g.drawString(mc.font, server.ip + " (" + players + ")", 0, 0, TEXT);
				});

		add("packdisplay", "packdisplay", "Pack", new HudPos(8, -6, -6, 1.0), mc -> text("Pack: Default"),
				(g, mc, w, h) -> {
					var ids = mc.getResourcePackRepository().getSelectedIds();
					String last = "Default";
					for (String id : ids) {
						last = id;
					}
					g.drawString(mc.font, "Pack: " + trim(mc, last.replace("file/", ""), 110), 0, 0, TEXT);
				});

		add("sprintstate", "togglesprint", "Sprint state", new HudPos(6, 6, -6, 1.0), mc -> text("Sprint (Toggled)"),
				(g, mc, w, h) -> {
					var f = OriginClientMod.FEATURES;
					String s = f.sprintToggledOn ? "Sprint (Toggled)" : f.sneakToggledOn ? "Sneak (Toggled)" : null;
					if (s != null) {
						g.drawString(mc.font, s, 0, 0, TEXT);
					}
				});
	}

	private static void add(String id, String modId, String label, HudPos def,
							java.util.function.Function<Minecraft, int[]> measure, Renderer r) {
		ALL.add(new Element(id, modId, label, def, measure, r));
	}

	private static int[] text(String sample) {
		return new int[]{Minecraft.getInstance().font.width(sample), 10};
	}

	private static String trim(Minecraft mc, String s, int px) {
		return mc.font.width(s) <= px ? s : mc.font.plainSubstrByWidth(s, px - 6) + "…";
	}

	private static void renderKeystrokes(GuiGraphics g, Minecraft mc, int w, int h) {
		int color = Mods.color("keystrokes", "color");
		var o = mc.options;
		key(g, mc, 24, 0, 22, 22, "W", o.keyUp.isDown(), color);
		key(g, mc, 0, 24, 22, 22, "A", o.keyLeft.isDown(), color);
		key(g, mc, 24, 24, 22, 22, "S", o.keyDown.isDown(), color);
		key(g, mc, 48, 24, 22, 22, "D", o.keyRight.isDown(), color);
		key(g, mc, 0, 48, 34, 14, "LMB", ClickStats.leftDown, color);
		key(g, mc, 36, 48, 34, 14, "RMB", ClickStats.rightDown, color);
		key(g, mc, 0, 64, 70, 8, "", o.keyJump.isDown(), color);
	}

	private static void key(GuiGraphics g, Minecraft mc, int x, int y, int w, int h, String label, boolean down, int color) {
		g.fill(x, y, x + w, y + h, down ? (0xAA000000 | (color & 0xFFFFFF)) : PANEL);
		if (!label.isEmpty()) {
			int tw = mc.font.width(label);
			g.drawString(mc.font, label, x + (w - tw) / 2, y + (h - 8) / 2, down ? 0xFF101010 : TEXT);
		}
	}

	/** Main in-game dispatcher: draws every enabled element at its anchored,
	 *  scaled position. Skipped entirely while the HUD editor is open (the
	 *  editor draws its own draggable versions). */
	public static void renderAll(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || mc.screen instanceof HudEditorScreen) {
			return;
		}
		int sw = g.guiWidth(), sh = g.guiHeight();
		for (Element e : ALL) {
			if (!Mods.on(e.modId())) {
				continue;
			}
			if (e.id().equals("sprintstate")
					&& !(Mods.on("togglesprint") || Mods.on("togglesneak"))) {
				continue;
			}
			HudPos pos = e.pos();
			int[] size = e.measure().apply(mc);
			double w = size[0] * pos.scale, h = size[1] * pos.scale;
			double x = pos.x(sw, w), y = pos.y(sh, h);
			var p = g.pose();
			p.pushPose();
			p.translate(x, y, 0);
			p.scale((float) pos.scale, (float) pos.scale, 1f);
			try {
				e.renderer().render(g, mc, size[0], size[1]);
			} catch (Throwable t) {
				// One bad element must never take the HUD down.
			}
			p.popPose();
		}
	}
}
