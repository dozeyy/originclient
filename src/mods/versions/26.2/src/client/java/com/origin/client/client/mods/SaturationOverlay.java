package com.origin.client.client.mods;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.render.LunarPipelines;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

// Saturation mod (Lunar's built-in AppleSkin), drawn on top of vanilla's own
// hunger bar from GuiFoodMixin (Gui.renderFood TAIL, so the shank positions are
// exactly vanilla's: right edge `right`, one shank every 8 px leftward, row y).
//
//   Saturation Overlay       -- your hidden saturation as glowing shanks
//   Held Item Hunger         -- holding food pulses the shanks it would refill
//   Held Item Saturation     -- ...and the saturation it would add
//   Food Tooltip             -- hunger / saturation lines on every food item
//
// PER-VERSION DELTA (1.21.11): blend + tint are per-blit (RenderPipeline +
// trailing ARGB) instead of RenderSystem state; Identifier for ResourceLocation.
//
// Every number comes from the food's own FoodProperties component and the
// player's FoodData, which the client already holds. Nothing is sent anywhere.
public final class SaturationOverlay {
	private SaturationOverlay() {
	}

	private static final Identifier FULL = Identifier.withDefaultNamespace("hud/food_full");
	private static final Identifier HALF = Identifier.withDefaultNamespace("hud/food_half");
	private static final Identifier FULL_HUNGER = Identifier.withDefaultNamespace("hud/food_full_hunger");
	private static final Identifier HALF_HUNGER = Identifier.withDefaultNamespace("hud/food_half_hunger");

	public static void registerTooltip() {
		net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			try {
				if (!Mods.on("saturation") || !Mods.bool("saturation", "tooltip")) {
					return;
				}
				FoodProperties food = stack.get(DataComponents.FOOD);
				if (food == null) {
					return;
				}
				lines.add(Component.literal("Hunger: ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal("+" + food.nutrition()).withStyle(ChatFormatting.WHITE))
						.append(Component.literal("  Saturation: ").withStyle(ChatFormatting.GRAY))
						.append(Component.literal(String.format(Locale.ROOT, "+%.1f", food.saturation()))
								.withStyle(ChatFormatting.GOLD)));
			} catch (Throwable ignored) {
				// a tooltip line is never worth breaking the tooltip over
			}
		});
	}

	/** Draws over the hunger row vanilla just drew. y = row top, right = bar's right edge. */
	public static void extractRenderState(GuiGraphicsExtractor g, Player player, int y, int right) {
		if (!Mods.on("saturation")) {
			return;
		}
		var data = player.getFoodData();
		int food = data.getFoodLevel();
		float sat = data.getSaturationLevel();
		boolean hungerFx = player.hasEffect(MobEffects.HUNGER);

		// What eating the held food would leave you at (vanilla FoodData.eat rules:
		// hunger caps at 20, saturation can never exceed the hunger level).
		FoodProperties held = heldFood(player);
		int newFood = food;
		float newSat = sat;
		if (held != null && (food < 20 || held.canAlwaysEat())) {
			newFood = Math.min(20, food + held.nutrition());
			newSat = Math.min(newFood, sat + held.saturation());
		} else {
			held = null;
		}
		float pulse = 0.35f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 160.0);

		// 1) held-item hunger: the shanks eating would refill, pulsing. The tint
		// rides the per-blit ARGB (1.21.11's GUI is deferred, so there is no
		// shader colour to set around the draw).
		if (held != null && Mods.bool("saturation", "heldHunger") && newFood > food) {
			int tint = ((int) (pulse * 255f) << 24) | 0xFFFFFF;
			for (int i = 0; i < 10; i++) {
				int before = clampUnit(food - i * 2), after = clampUnit(newFood - i * 2);
				if (after > before) {
					g.blitSprite(RenderPipelines.GUI_TEXTURED,
							after == 2 ? (hungerFx ? FULL_HUNGER : FULL) : (hungerFx ? HALF_HUNGER : HALF),
							right - i * 8 - 9, y, 9, 9, tint);
				}
			}
		}
		// 2) saturation (current, then the held item's gain, pulsing): the shank
		// sprite ADDED on top in the overlay colour (LunarPipelines.GUI_ADDITIVE =
		// 1.21.1's SRC_ALPHA, ONE blend), so it glows without hiding the hunger
		// state underneath.
		int c = OriginColorPicker.liveColor("saturation", "overlayColor");
		int a = (c >>> 24) & 0xFF;
		if (Mods.bool("saturation", "overlay")) {
			drawSaturation(g, 0f, sat, y, right, c);
		}
		if (held != null && Mods.bool("saturation", "heldSaturation") && newSat > sat) {
			int pa = Math.min(255, Math.round(a * pulse * 1.6f));
			drawSaturation(g, sat, newSat, y, right, (pa << 24) | (c & 0xFFFFFF));
		}
	}

	/** Saturation between `from` and `to` (in half-shank points) as full/half shanks. */
	private static void drawSaturation(GuiGraphicsExtractor g, float from, float to, int y, int right, int argb) {
		for (int i = 0; i < 10; i++) {
			int before = clampUnit((int) Math.floor(from) - i * 2);
			int after = clampUnit((int) Math.floor(to) - i * 2);
			if (after > before) {
				g.blitSprite(LunarPipelines.GUI_ADDITIVE, after == 2 ? FULL : HALF, right - i * 8 - 9, y, 9, 9, argb);
			}
		}
	}

	/** One shank's fill (0 empty, 1 half, 2 full) from a remaining point count. */
	private static int clampUnit(int points) {
		return Math.max(0, Math.min(2, points));
	}

	private static FoodProperties heldFood(Player p) {
		FoodProperties f = foodOf(p.getMainHandItem());
		return f != null ? f : foodOf(p.getOffhandItem());
	}

	private static FoodProperties foodOf(ItemStack s) {
		return s == null || s.isEmpty() ? null : s.get(DataComponents.FOOD);
	}
}
