package com.origin.client.client.hud;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.mods.ClickStats;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// Every movable HUD element: its owning mod, default placement, measured
// size, and renderer. One dispatcher draws them all; the HUD editor drags the
// same list. Settings are read LIVE from the Mods registry on every frame —
// flipping any option updates the element instantly, no restart.
//
// Sizing rule (Will): an element's measured box is always big enough for the
// element with EVERYTHING turned on, so the edit-screen outline never
// mismatches the content no matter which options are active.
public final class HudElements {
	public static final int TEXT = 0xFFE0E0E0;
	private static final int PANEL = 0x66101010;

	// Set by the HUD editor for its whole lifetime: elements that need worn
	// armor / an active potion to be visible draw sample content instead, and
	// measure/hit-testing see the same preview the render shows.
	public static volatile boolean editorPreview = false;

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

	private static long fpsSampledAt = 0;
	private static int fpsSample = 0;

	// ---- shared option plumbing ----

	/** The mod's own Scale slider (1.0 when the mod has no such option). */
	private static float ms(String modId) {
		double s = Mods.num(modId, "scale");
		return (float) (s <= 0.05 ? 1.0 : s);
	}

	private static boolean chatOpen(Minecraft mc) {
		return mc.screen instanceof ChatScreen;
	}

	/** Standard text row backing, gated by the mod's Show Background toggle. */
	private static void bg(GuiGraphics g, String modId, int w, int h) {
		if (Mods.bool(modId, "showBackground")) {
			OriginUi.panel(g, -3, -3, w + 6, h + 6, 5, PANEL, 0);
		}
	}

	private static void bgColored(GuiGraphics g, String modId, String colorKey, int w, int h) {
		if (Mods.bool(modId, "showBackground")) {
			OriginUi.panel(g, -3, -3, w + 6, h + 6, 5, OriginColorPicker.liveColor(modId, colorKey), 0);
		}
	}

	// ---- current-content builders ----
	// measure() and render() both go through these, so the box (and the
	// edit-screen border) is always exactly the size of what's shown right now
	// and grows the instant an option adds content.

	private static String fpsText(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (now - fpsSampledAt > 250) {
			fpsSampledAt = now;
			fpsSample = mc.getFps();
		}
		String txt = Mods.bool("fps", "reverseOrder") ? fpsSample + " FPS" : "FPS: " + fpsSample;
		return Mods.bool("fps", "showBrackets") ? "[" + txt + "]" : txt;
	}

	private static String cpsText() {
		String value = String.valueOf(ClickStats.leftCps());
		if (Mods.bool("cps", "rightClick")) {
			value += " | " + ClickStats.rightCps();
		}
		return !Mods.bool("cps", "showText") ? value
				: Mods.bool("cps", "reverseText") ? value + " CPS" : "CPS: " + value;
	}

	private static String serverText(Minecraft mc) {
		ServerData server = mc.getCurrentServer();
		if (server == null) {
			return "Singleplayer";
		}
		int players = mc.getConnection() != null ? mc.getConnection().getOnlinePlayers().size() : 0;
		return server.ip + " (" + players + ")";
	}

	private static List<String> coordsLines(Minecraft mc) {
		List<String> lines = new ArrayList<>();
		var pl = mc.player;
		if (pl == null) {
			return lines;
		}
		boolean dec = Mods.bool("coords", "decimal");
		String fx = dec ? String.format("%.1f", pl.getX()) : String.valueOf(pl.blockPosition().getX());
		String fy = dec ? String.format("%.1f", pl.getY()) : String.valueOf(pl.blockPosition().getY());
		String fz = dec ? String.format("%.1f", pl.getZ()) : String.valueOf(pl.blockPosition().getZ());
		boolean sx = Mods.bool("coords", "x"), sy = Mods.bool("coords", "y"), sz = Mods.bool("coords", "z");
		if (Mods.mode("coords", "listMode").equals("Horizontal")) {
			if (sx || sy || sz) {
				StringBuilder sb = new StringBuilder("XYZ:");
				if (sx) sb.append(' ').append(fx);
				if (sy) sb.append(sx ? " / " : " ").append(fy);
				if (sz) sb.append(sx || sy ? " / " : " ").append(fz);
				lines.add(sb.toString());
			}
		} else {
			if (sx) lines.add("X: " + fx);
			if (sy) lines.add("Y: " + fy);
			if (sz) lines.add("Z: " + fz);
		}
		if (Mods.bool("coords", "renderers")) {
			int c;
			try {
				c = mc.levelRenderer.countRenderedSections();
			} catch (Throwable t) {
				c = -1;
			}
			if (c >= 0) lines.add("C: " + c);
		}
		if (Mods.bool("coords", "direction")) {
			Direction d = pl.getDirection();
			lines.add(String.format("Facing: %s (%.0f°)",
					d.getName().substring(0, 1).toUpperCase() + d.getName().substring(1),
					net.minecraft.util.Mth.wrapDegrees(pl.getYRot())));
		}
		if (Mods.bool("coords", "biome") && mc.level != null) {
			var biome = mc.level.getBiome(BlockPos.containing(pl.position()));
			lines.add("Biome: " + biome.unwrapKey().map(k -> k.location().getPath().replace('_', ' ')).orElse("unknown"));
		}
		return lines;
	}

	/** Keystrokes vertical extent {top, bottom} of the currently-shown rows. */
	private static int[] ksBounds() {
		boolean mv = Mods.bool("keystrokes", "showMovement");
		boolean cl = Mods.bool("keystrokes", "showClicks");
		boolean sp = Mods.bool("keystrokes", "showSpace");
		int top = Integer.MAX_VALUE, bottom = 0;
		if (mv) {
			top = Math.min(top, 0);
			bottom = Math.max(bottom, 46);
		}
		if (cl) {
			top = Math.min(top, 48);
			bottom = Math.max(bottom, 62);
		}
		if (sp) {
			int t = (int) Math.max(1, Math.min(10, Mods.num("keystrokes", "spacebarThickness"))) + 4;
			top = Math.min(top, 64);
			bottom = Math.max(bottom, 64 + t);
		}
		if (top == Integer.MAX_VALUE) {
			top = 0;
			bottom = 10;
		}
		return new int[]{top, bottom};
	}

	private static List<ItemStack> armorItems(Minecraft mc) {
		List<ItemStack> items = new ArrayList<>();
		if (mc.player != null) {
			for (ItemStack s : mc.player.getInventory().armor) {
				if (!s.isEmpty()) {
					items.add(s);
				}
			}
			java.util.Collections.reverse(items); // helmet first
			if (!mc.player.getMainHandItem().isEmpty()) {
				items.add(mc.player.getMainHandItem());
			}
		}
		if (items.isEmpty() && editorPreview) {
			items = new ArrayList<>(List.of(new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_CHESTPLATE),
					new ItemStack(Items.DIAMOND_LEGGINGS), new ItemStack(Items.DIAMOND_BOOTS), new ItemStack(Items.DIAMOND_SWORD)));
		}
		return items;
	}

	static {
		// ---- FPS ----
		add("fps", "fps", "FPS", new HudPos(0, 6, 6, 1.0), mc -> {
			float s = ms("fps");
			return new int[]{(int) ((mc.font.width(fpsText(mc)) + 2) * s), (int) (11 * s)};
		}, (g, mc, w, h) -> {
			float s = ms("fps");
			var p = g.pose();
			p.pushPose();
			p.scale(s, s, 1f);
			bg(g, "fps", (int) (w / s), (int) (h / s));
			g.drawString(mc.font, fpsText(mc), 1, 1, OriginColorPicker.liveColor("fps", "color"), Mods.bool("fps", "textShadow"));
			p.popPose();
		});

		// ---- CPS ----
		add("cps", "cps", "CPS", new HudPos(0, 6, 22, 1.0), mc -> {
			float s = ms("cps");
			return new int[]{(int) ((mc.font.width(cpsText()) + 2) * s), (int) (11 * s)};
		}, (g, mc, w, h) -> {
			float s = ms("cps");
			var p = g.pose();
			p.pushPose();
			p.scale(s, s, 1f);
			bg(g, "cps", (int) (w / s), (int) (h / s));
			g.drawString(mc.font, cpsText(), 1, 1, OriginColorPicker.liveColor("cps", "color"), Mods.bool("cps", "textShadow"));
			p.popPose();
		});

		// ---- Coordinates ----
		add("coords", "coords", "Coords", new HudPos(0, 6, 38, 1.0), mc -> {
			float s = ms("coords");
			var lines = coordsLines(mc);
			int wMax = 1;
			for (String l : lines) {
				wMax = Math.max(wMax, mc.font.width(l));
			}
			int n = Math.max(1, lines.size());
			return new int[]{(int) ((wMax + 2) * s), (int) ((n * 10 + 2) * s)};
		}, (g, mc, w, h) -> {
			if (mc.player == null) {
				return;
			}
			if (chatOpen(mc) && !Mods.bool("coords", "showWhileTyping")) {
				return;
			}
			float s = ms("coords");
			var p = g.pose();
			p.pushPose();
			p.scale(s, s, 1f);
			bgColored(g, "coords", "bgColor", (int) (w / s), (int) (h / s));
			boolean shadow = Mods.bool("coords", "textShadow");
			int y = 1;
			for (String line : coordsLines(mc)) {
				g.drawString(mc.font, line, 1, y, TEXT, shadow);
				y += 10;
			}
			p.popPose();
		});

		// ---- Key Strokes ----
		add("keystrokes", "keystrokes", "Keystrokes", new HudPos(3, 6, -40, 1.0), mc -> {
			float s = ks();
			int[] b = ksBounds();
			return new int[]{(int) (70 * s), (int) ((b[1] - b[0]) * s)};
		}, HudElements::renderKeystrokes);

		// ---- Potion Effects ----
		add("potionhud", "potionhud", "Potions", new HudPos(2, -6, 6, 1.0), mc -> {
			// width grows to the widest FULL name+time so nothing is ellipsised;
			// right-anchored, so it just extends further left to fit.
			var rows = potionRows(mc);
			int wMax = 60;
			for (PotionRow row : rows) {
				wMax = Math.max(wMax, 22 + rowTextWidth(mc, row));
			}
			return new int[]{wMax, Math.max(1, rows.size()) * 20};
		}, (g, mc, w, h) -> {
			if (mc.player == null) {
				return;
			}
			if (chatOpen(mc) && !Mods.bool("potionhud", "showWhileTyping")) {
				return;
			}
			var rows = potionRows(mc);
			if (rows.isEmpty()) {
				return; // nothing active in-game: no box, no background at all
			}
			// background hugs the actual rows — one effect gets a one-row
			// backing, more effects grow it; never a big empty gray area
			if (Mods.bool("potionhud", "showBackground")) {
				int wAct = 0;
				for (PotionRow row : rows) {
					wAct = Math.max(wAct, 22 + rowTextWidth(mc, row));
				}
				OriginUi.panel(g, -3, -3, wAct + 6, rows.size() * 20 + 4, 5,
						OriginColorPicker.liveColor("potionhud", "bgColor"), 0);
			}
			int y = 0;
			for (PotionRow row : rows) {
				drawEffectRow(g, mc, y, row.effect, row.duration, row.infinite);
				y += 20;
			}
		});

		// ---- Armor Status ----
		add("armorhud", "armorhud", "Armor", new HudPos(6, 6, -24, 1.0), mc -> {
			var items = armorItems(mc);
			int n = Math.max(1, items.size());
			boolean vertical = Mods.mode("armorhud", "listMode").equals("Vertical");
			boolean dur = !Mods.mode("armorhud", "durabilityPos").equals("Hidden");
			return vertical ? new int[]{19 + (dur ? 34 : 0), n * 19} : new int[]{n * 19, 17 + (dur ? 10 : 0)};
		}, HudElements::renderArmor);

		// ---- Server Address ----
		add("serveraddress", "serveraddress", "Server IP", new HudPos(2, -6, 6, 1.0), mc -> {
			float s = ms("serveraddress");
			int iconW = Mods.bool("serveraddress", "serverIcon") ? 15 : 0;
			return new int[]{(int) ((iconW + mc.font.width(serverText(mc)) + 2) * s), (int) (13 * s)};
		}, (g, mc, w, h) -> {
			float s = ms("serveraddress");
			var p = g.pose();
			p.pushPose();
			p.scale(s, s, 1f);
			bg(g, "serveraddress", (int) (w / s), (int) (h / s));
			boolean shadow = Mods.bool("serveraddress", "textShadow");
			int color = OriginColorPicker.liveColor("serveraddress", "color");
			int x = 0;
			if (Mods.bool("serveraddress", "serverIcon")) {
				OriginUi.icon(g, "serveraddress", 0, 0, 12, color);
				x = 15;
			}
			g.drawString(mc.font, serverText(mc), x, 2, color, shadow);
			p.popPose();
		});

		// ---- Sprint/Sneak state ----
		add("sprintstate", "togglesprint", "Sprint state", new HudPos(6, 6, -6, 1.0), mc -> text("Sprint (Toggled)"),
				(g, mc, w, h) -> {
					if (!Mods.bool("togglesprint", "hud")) {
						return;
					}
					var f = OriginClientMod.FEATURES;
					String s = f.sprintToggledOn ? "Sprint (Toggled)" : f.sneakToggledOn ? "Sneak (Toggled)" : null;
					if (s == null && editorPreview) {
						s = "Sprint (Toggled)";
					}
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

	// ---- potions ----

	private record PotionRow(Holder<MobEffect> effect, int duration, boolean infinite) {
	}

	private static int rowTextWidth(Minecraft mc, PotionRow row) {
		String name = row.effect.value().getDisplayName().getString();
		int secs = row.duration / 20;
		String time = row.infinite ? "∞" : secs / 60 + ":" + String.format("%02d", secs % 60);
		return mc.font.width(name + " " + time);
	}

	private static List<PotionRow> potionRows(Minecraft mc) {
		List<PotionRow> rows = new ArrayList<>();
		if (mc.player != null) {
			boolean excludePerm = Mods.bool("potionhud", "excludePermanent");
			for (MobEffectInstance e : mc.player.getActiveEffects()) {
				if (excludePerm && e.isInfiniteDuration()) {
					continue;
				}
				rows.add(new PotionRow(e.getEffect(), e.getDuration(), e.isInfiniteDuration()));
			}
		}
		if (rows.isEmpty() && editorPreview) {
			rows.add(new PotionRow(MobEffects.MOVEMENT_SPEED, 83 * 20, false));
			rows.add(new PotionRow(MobEffects.DAMAGE_BOOST, 45 * 20, false));
		}
		return rows;
	}

	private static void drawEffectRow(GuiGraphics g, Minecraft mc, int y, Holder<MobEffect> effect, int duration, boolean infinite) {
		int secs = duration / 20;
		// Blink: within the threshold, the row pulses like vanilla's expiring icons
		if (!infinite && Mods.bool("potionhud", "blink")
				&& secs < Mods.num("potionhud", "blinkDuration")
				&& (System.currentTimeMillis() / 400) % 2 == 0) {
			return;
		}
		try {
			var sprite = mc.getMobEffectTextures().get(effect);
			g.blit(0, y, 0, 18, 18, sprite);
		} catch (Throwable t) {
			g.fill(2, y + 4, 12, y + 14, 0xFF000000 | effect.value().getColor());
		}
		boolean shadow = Mods.bool("potionhud", "textShadow");
		boolean minimal = Mods.bool("potionhud", "minimal");
		boolean showName = Mods.bool("potionhud", "effectName") && !minimal;
		String name = effect.value().getDisplayName().getString();
		if (Mods.bool("potionhud", "uppercase")) {
			name = name.toUpperCase(java.util.Locale.ROOT);
		}
		String time = infinite ? "∞"
				: Mods.bool("potionhud", "formattedDurations")
				? secs / 60 + ":" + String.format("%02d", secs % 60)
				: secs + "s";
		int nameColor = Mods.bool("potionhud", "colorByEffect")
				? 0xFF000000 | effect.value().getColor()
				: OriginColorPicker.liveColor("potionhud", "textColor");
		int timeColor = OriginColorPicker.liveColor("potionhud", "durationColor");
		int x = 22;
		if (!showName) {
			g.drawString(mc.font, time, x, y + 5, timeColor, shadow);
			return;
		}
		String nm = name;   // full name, never trimmed (the box grows to fit)
		if (Mods.bool("potionhud", "reversedText")) {
			g.drawString(mc.font, time, x, y + 5, timeColor, shadow);
			g.drawString(mc.font, nm, x + mc.font.width(time + " "), y + 5, nameColor, shadow);
		} else {
			g.drawString(mc.font, nm, x, y + 5, nameColor, shadow);
			g.drawString(mc.font, time, x + mc.font.width(nm + " "), y + 5, timeColor, shadow);
		}
	}

	// ---- armor ----

	private static void renderArmor(GuiGraphics g, Minecraft mc, int w, int h) {
		if (mc.player == null) {
			return;
		}
		List<ItemStack> items = armorItems(mc);
		if (items.isEmpty()) {
			return; // no armor / no held item in-game: draw nothing at all
		}
		boolean vertical = Mods.mode("armorhud", "listMode").equals("Vertical");
		String durPos = Mods.mode("armorhud", "durabilityPos");
		boolean shadow = Mods.bool("armorhud", "textShadow");
		int textColor = OriginColorPicker.liveColor("armorhud", "textColor");
		if (Mods.bool("armorhud", "showBackground")) {
			// background hugs however many pieces are actually worn, growing
			// piece by piece — never the full-loadout gray slab
			boolean dur = !durPos.equals("Hidden");
			int wAct = vertical ? 19 + (dur ? 30 : 0) : items.size() * 19;
			int hAct = vertical ? items.size() * 19 : 17 + (dur ? 10 : 0);
			OriginUi.panel(g, -3, -3, wAct + 6, hAct + 6, 5, PANEL, 0);
		}

		int x = 0, y = 0;
		for (ItemStack stack : items) {
			g.renderItem(stack, x, y);
			if (Mods.bool("armorhud", "itemCount")) {
				g.renderItemDecorations(mc.font, stack, x, y);
			}
			if (!durPos.equals("Hidden") && stack.isDamageableItem()) {
				int remaining = stack.getMaxDamage() - stack.getDamageValue();
				String txt = Mods.mode("armorhud", "damageDisplay").equals("Percent")
						? (int) Math.round(remaining * 100.0 / stack.getMaxDamage()) + "%"
						: String.valueOf(remaining);
				// Left/Right placements only make sense stacked vertically; in
				// the horizontal row they'd overflow the box, so fall to Below.
				String pos2 = vertical ? durPos : "Below";
				int tx = switch (pos2) {
					case "Left" -> x - mc.font.width(txt) - 2;
					case "Below" -> x + (16 - mc.font.width(txt)) / 2;
					default -> x + 19; // Right
				};
				int ty = pos2.equals("Below") ? y + 17 : y + 5;
				// Damage Color: below the threshold (Percent <25% remaining, or
					// Value <50 durability left) the durability text switches colour.
					boolean armorLow = Mods.mode("armorhud", "damageThreshold").equals("Value")
							? remaining <= 50 : remaining * 100.0 / stack.getMaxDamage() <= 25.0;
					g.drawString(mc.font, txt, tx, ty,
							armorLow ? OriginColorPicker.liveColor("armorhud", "damageColor") : textColor, shadow);
			}
			if (vertical) {
				y += 19;
			} else {
				x += 19;
			}
		}
	}

	// ---- keystrokes ----

	/** Combined size factor: the mod's Scale slider x its Box Size slider. */
	private static float ks() {
		double s = Mods.num("keystrokes", "scale");
		double b = Mods.num("keystrokes", "boxSize");
		return (float) ((s <= 0.05 ? 1 : s) * (b <= 0.05 ? 1 : b));
	}

	private static void renderKeystrokes(GuiGraphics g, Minecraft mc, int w, int h) {
		float s = ks();
		var p = g.pose();
		p.pushPose();
		p.scale(s, s, 1f);
		p.translate(0, -ksBounds()[0], 0); // hug the topmost shown row
		var o = mc.options;
		boolean movement = Mods.bool("keystrokes", "showMovement");
		boolean arrows = Mods.bool("keystrokes", "arrows");
		if (movement) {
			key(g, mc, 24, 0, 22, 22, arrows ? "↑" : "W", o.keyUp.isDown());
			key(g, mc, 0, 24, 22, 22, arrows ? "←" : "A", o.keyLeft.isDown());
			key(g, mc, 24, 24, 22, 22, arrows ? "↓" : "S", o.keyDown.isDown());
			key(g, mc, 48, 24, 22, 22, arrows ? "→" : "D", o.keyRight.isDown());
		}
		if (Mods.bool("keystrokes", "showClicks")) {
			key(g, mc, 0, 48, 34, 14, "LMB", ClickStats.leftDown);
			key(g, mc, 36, 48, 34, 14, "RMB", ClickStats.rightDown);
		}
		if (Mods.bool("keystrokes", "showSpace")) {
			int thick = (int) Math.max(1, Math.min(10, Mods.num("keystrokes", "spacebarThickness"))) + 4;
			key(g, mc, 0, 64, 70, thick, "", o.keyJump.isDown());
		}
		p.popPose();
	}

	private static void key(GuiGraphics g, Minecraft mc, int x, int y, int w, int h, String label, boolean down) {
		// Key Fade Delay drives the press/release fade between the two color sets
		double fade = Math.max(50, Mods.num("keystrokes", "keyFadeDelay"));
		float k = OriginUi.anim("ks:" + label + x, down, fade);
		int bgUp = OriginColorPicker.liveColor("keystrokes", "bgColor");
		int bgDown = OriginColorPicker.liveColor("keystrokes", "bgColorPressed");
		int fill = com.origin.client.client.theme.OriginTheme.lerpColor(bgUp, bgDown, k);
		g.fill(x, y, x + w, y + h, fill);
		if (Mods.bool("keystrokes", "border")) {
			int t = (int) Math.max(1, Math.min(4, Math.round(Mods.num("keystrokes", "borderThickness"))));
			int bc = OriginColorPicker.liveColor("keystrokes", "borderColor");
			// top/bottom span full width; left/right fill only the gap between them
			// so corner pixels are never drawn twice (which made corners darker
			// than the straight edges at higher opacity).
			g.fill(x, y, x + w, y + t, bc);
			g.fill(x, y + h - t, x + w, y + h, bc);
			g.fill(x, y + t, x + t, y + h - t, bc);
			g.fill(x + w - t, y + t, x + w, y + h - t, bc);
		}
		if (!label.isEmpty()) {
			int up = OriginColorPicker.liveColor("keystrokes", "color");
			int dn = OriginColorPicker.liveColor("keystrokes", "textColorPressed");
			int tc = com.origin.client.client.theme.OriginTheme.lerpColor(up, dn, k);
			int tw = mc.font.width(label);
			g.drawString(mc.font, label, x + (w - tw) / 2, y + (h - 8) / 2, tc, Mods.bool("keystrokes", "textShadow"));
		}
	}

	/** Per-module rounded backing at the element's own opacity (0 = none). */
	public static void drawBacking(net.minecraft.client.gui.GuiGraphics g, int x, int y, int w, int h, double bg) {
		if (bg <= 0.01) {
			return;
		}
		int a = (int) (bg * 255);
		OriginUi.panel(g, x - 4, y - 4, w + 8, h + 8, 6, (a << 24) | 0x0E0E0E, 0);
	}

	/** Draws just the Potion element over an open inventory/container screen
	 *  (the normal HUD pass is skipped while a screen is open, so "Show In
	 *  Inventory" needs its own draw). */
	public static void renderInInventory(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !Mods.on("potionhud")) {
			return;
		}
		for (Element e : ALL) {
			if (!e.modId().equals("potionhud")) {
				continue;
			}
			HudPos pos = e.pos();
			int[] size = e.measure().apply(mc);
			double w = size[0] * pos.scale, h = size[1] * pos.scale;
			double x = pos.x(g.guiWidth(), w), y = pos.y(g.guiHeight(), h);
			var p = g.pose();
			p.pushPose();
			p.translate(x, y, 0);
			p.scale((float) pos.scale, (float) pos.scale, 1f);
			try {
				e.renderer().render(g, mc, size[0], size[1]);
			} catch (Throwable ignored) {
			}
			p.popPose();
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
			HudPos pos = e.pos();
			int[] size = e.measure().apply(mc);
			double w = size[0] * pos.scale, h = size[1] * pos.scale;
			double x = pos.x(sw, w), y = pos.y(sh, h);
			drawBacking(g, (int) x, (int) y, (int) w, (int) h, pos.bg);
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
