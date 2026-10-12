package com.origin.client.client.mods;

import com.origin.client.client.gui.OriginColorPicker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.TriState;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
// Every number comes from the food's own FoodProperties component and the
// player's FoodData, which the client already holds. Nothing is sent anywhere.
public final class SaturationOverlay {
	private SaturationOverlay() {
	}

	private static final ResourceLocation FULL = ResourceLocation.withDefaultNamespace("hud/food_full");
	private static final ResourceLocation HALF = ResourceLocation.withDefaultNamespace("hud/food_half");
	private static final ResourceLocation FULL_HUNGER = ResourceLocation.withDefaultNamespace("hud/food_full_hunger");
	private static final ResourceLocation HALF_HUNGER = ResourceLocation.withDefaultNamespace("hud/food_half_hunger");

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
	public static void render(GuiGraphics g, Player player, int y, int right) {
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

		// 1) held-item hunger: the shanks eating would refill, pulsing
		if (held != null && Mods.bool("saturation", "heldHunger") && newFood > food) {
			int tint = (Math.round(Math.max(0f, Math.min(1f, pulse)) * 255) << 24) | 0xFFFFFF;
			for (int i = 0; i < 10; i++) {
				int before = clampUnit(food - i * 2), after = clampUnit(newFood - i * 2);
				if (after > before) {
					sprite(g, false, after == 2 ? (hungerFx ? FULL_HUNGER : FULL) : (hungerFx ? HALF_HUNGER : HALF),
							right - i * 8 - 9, y, tint);
				}
			}
		}
		// 2) saturation (current, then the held item's gain, pulsing): the
		// shank sprite added on top in the overlay colour, so it glows
		// without hiding the hunger state underneath.
		int c = OriginColorPicker.liveColor("saturation", "overlayColor");
		float a = ((c >>> 24) & 0xFF) / 255f;
		if (Mods.bool("saturation", "overlay")) {
			drawSaturation(g, 0f, sat, y, right, withAlpha(c, a));
		}
		if (held != null && Mods.bool("saturation", "heldSaturation") && newSat > sat) {
			drawSaturation(g, sat, newSat, y, right, withAlpha(c, a * pulse * 1.6f));
		}
	}

	private static int withAlpha(int rgb, float a) {
		return (Math.round(Math.max(0f, Math.min(1f, a)) * 255) << 24) | (rgb & 0xFFFFFF);
	}

	// PER-VERSION (1.21.2-1.21.4): the GUI is batched, so neither the pulse alpha nor
	// the additive blend can be RenderSystem state as on 1.21.1 -- the alpha/tint
	// ride the blit's own ARGB, and the additive glow is its own render type:
	// vanilla's gui_textured with LIGHTNING_TRANSPARENCY (SRC_ALPHA, ONE -- the
	// exact blend 1.21.1 set here). Built once per sprite atlas location.
	private static final java.util.Map<ResourceLocation, RenderType> ADDITIVE = new java.util.HashMap<>();

	private static RenderType additive(ResourceLocation atlas) {
		return ADDITIVE.computeIfAbsent(atlas, loc -> RenderType.create("originclient_gui_additive",
				DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 786432,
				RenderType.CompositeState.builder()
						.setTextureState(new RenderStateShard.TextureStateShard(loc, TriState.FALSE, false))
						.setShaderState(RenderStateShard.POSITION_TEXTURE_COLOR_SHADER)
						.setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
						.setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
						.createCompositeState(false)));
	}

	private static void sprite(GuiGraphics g, boolean add, ResourceLocation id, int x, int y, int argb) {
		java.util.function.Function<ResourceLocation, RenderType> type = add ? SaturationOverlay::additive : RenderType::guiTextured;
		g.blitSprite(type, id, x, y, 9, 9, argb);
	}

	/** Saturation between `from` and `to` (in half-shank points) as full/half shanks. */
	private static void drawSaturation(GuiGraphics g, float from, float to, int y, int right, int argb) {
		for (int i = 0; i < 10; i++) {
			int before = clampUnit((int) Math.floor(from) - i * 2);
			int after = clampUnit((int) Math.floor(to) - i * 2);
			if (after > before) {
				sprite(g, true, after == 2 ? FULL : HALF, right - i * 8 - 9, y, argb);
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
