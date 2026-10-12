package com.origin.client.client.mods;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

// Saturation mod (Lunar's built-in AppleSkin), drawn on top of vanilla's own
// hunger bar from GuiFoodMixin. Pre-1.20.5 there is no Gui.renderFood: the
// shanks are drawn inline by Gui.renderPlayerHealth, so the mixin hooks its
// TAIL and passes vanilla's own row geometry (right edge screenWidth/2 + 91, row
// screenHeight - 39, one shank every 8 px leftward) -- the same positions.
//
//   Saturation Overlay       -- your hidden saturation as glowing shanks
//   Held Item Hunger         -- holding food pulses the shanks it would refill
//   Held Item Saturation     -- ...and the saturation it would add
//   Food Tooltip             -- hunger / saturation lines on every food item
//
// Every number comes from the item's own FoodProperties and the player's
// FoodData, which the client already holds. Nothing is sent anywhere.
// Pre-1.20.5 shapes (decompiled 1.19.4/1.18.1): FoodProperties via
// Item.getFoodProperties() with getNutrition() / getSaturationModifier(), and
// FoodData.eat adds nutrition * modifier * 2 saturation; the shank sprites are
// cells of textures/gui/icons.png (no GUI sprite atlas before 1.20.2): full 52,
// half 61, the Hunger-effect pair 36 further right, row v = 27.
public final class SaturationOverlay {
	private SaturationOverlay() {
	}

	private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");
	private static final int FULL = 52, HALF = 61, HUNGER_SHIFT = 36, ROW_V = 27;

	public static void registerTooltip() {
		net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
			try {
				if (!Mods.on("saturation") || !Mods.bool("saturation", "tooltip")) {
					return;
				}
				FoodProperties food = foodOf(stack);
				if (food == null) {
					return;
				}
				lines.add(Component.literal("Hunger: ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal("+" + food.getNutrition()).withStyle(ChatFormatting.WHITE))
						.append(Component.literal("  Saturation: ").withStyle(ChatFormatting.GRAY))
						.append(Component.literal(String.format(Locale.ROOT, "+%.1f", saturationOf(food)))
								.withStyle(ChatFormatting.GOLD)));
			} catch (Throwable ignored) {
				// a tooltip line is never worth breaking the tooltip over
			}
		});
	}

	/** Draws over the hunger row vanilla just drew. y = row top, right = bar's right edge. */
	public static void render(Gfx g, Player player, int y, int right) {
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
			newFood = Math.min(20, food + held.getNutrition());
			newSat = Math.min(newFood, sat + saturationOf(held));
		} else {
			held = null;
		}
		float pulse = 0.35f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 160.0);

		RenderSystem.enableBlend();
		try {
			// 1) held-item hunger: the shanks eating would refill, pulsing
			if (held != null && Mods.bool("saturation", "heldHunger") && newFood > food) {
				RenderSystem.defaultBlendFunc();
				RenderSystem.setShaderColor(1f, 1f, 1f, pulse);
				for (int i = 0; i < 10; i++) {
					int before = clampUnit(food - i * 2), after = clampUnit(newFood - i * 2);
					if (after > before) {
						int u = (after == 2 ? FULL : HALF) + (hungerFx ? HUNGER_SHIFT : 0);
						g.blit(ICONS, right - i * 8 - 9, y, u, ROW_V, 9, 9);
					}
				}
			}
			// 2) saturation (current, then the held item's gain, pulsing): the
			// shank sprite added on top in the overlay colour, so it glows
			// without hiding the hunger state underneath.
			int c = OriginColorPicker.liveColor("saturation", "overlayColor");
			float r = ((c >> 16) & 0xFF) / 255f, gr = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
			float a = ((c >>> 24) & 0xFF) / 255f;
			RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
			if (Mods.bool("saturation", "overlay")) {
				RenderSystem.setShaderColor(r, gr, b, a);
				drawSaturation(g, 0f, sat, y, right);
			}
			if (held != null && Mods.bool("saturation", "heldSaturation") && newSat > sat) {
				RenderSystem.setShaderColor(r, gr, b, a * pulse * 1.6f);
				drawSaturation(g, sat, newSat, y, right);
			}
		} finally {
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			RenderSystem.defaultBlendFunc();
			RenderSystem.disableBlend();
		}
	}

	/** Saturation between `from` and `to` (in half-shank points) as full/half shanks. */
	private static void drawSaturation(Gfx g, float from, float to, int y, int right) {
		for (int i = 0; i < 10; i++) {
			int before = clampUnit((int) Math.floor(from) - i * 2);
			int after = clampUnit((int) Math.floor(to) - i * 2);
			if (after > before) {
				g.blit(ICONS, right - i * 8 - 9, y, after == 2 ? FULL : HALF, ROW_V, 9, 9);
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
		return s == null || s.isEmpty() || !s.getItem().isEdible() ? null : s.getItem().getFoodProperties();
	}

	/** The saturation eating it adds (FoodData.eat: nutrition x modifier x 2). */
	private static float saturationOf(FoodProperties f) {
		return f.getNutrition() * f.getSaturationModifier() * 2.0F;
	}
}
