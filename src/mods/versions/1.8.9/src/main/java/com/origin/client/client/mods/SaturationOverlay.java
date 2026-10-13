package com.origin.client.client.mods;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemAppleGold;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.FoodStats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Locale;

// Saturation mod (Lunar's built-in AppleSkin), drawn on top of vanilla's own
// hunger bar (the shank positions are exactly vanilla's: right edge `right`, one
// shank every 8 px leftward, row y).
//
//   Saturation Overlay       -- your hidden saturation as glowing shanks
//   Held Item Hunger         -- holding food pulses the shanks it would refill
//   Held Item Saturation     -- ...and the saturation it would add
//   Food Tooltip             -- hunger / saturation lines on every food item
//
// Every number comes from the item's own ItemFood values and the player's
// FoodStats, which the client already holds. Nothing is sent anywhere.
//
// 1.8.9: drawn from HudHooks at RenderGameOverlayEvent.Post(FOOD) -- the event
// GuiIngameForge.renderFood fires right after it drew the shanks; the row is
// GuiIngameForge.right_height - 10 above the bottom (Forge stacks the right-hand
// rows with that counter, so mods that add rows move it too). The shank cells are
// vanilla's on textures/gui/icons.png (full 52,27 / half 61,27, Hunger-effect
// full 88,27 / half 97,27 -- the same cells 1.16.5 uses). Food values are
// ItemFood.getHealAmount / getSaturationModifier (per stack), the saturation a
// food adds is nutrition x modifier x 2 (FoodStats.addStats' own maths), and
// "always edible" is the golden apple (ItemFood keeps that flag private). The
// tooltip line comes from Forge's ItemTooltipEvent (HudHooks -> onTooltip).
public final class SaturationOverlay {
	private SaturationOverlay() {
	}

	private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");
	private static final int FULL_U = 52, HALF_U = 61, FULL_HUNGER_U = 88, HALF_HUNGER_U = 97, ROW_V = 27;

	/** 1.16.5 registered the tooltip callback here. 1.8.9: HudHooks subscribes to
	 *  ItemTooltipEvent and calls {@link #onTooltip}; kept as a no-op so callers of
	 *  the 1.16.5 API still compile. */
	public static void registerTooltip() {
	}

	/** Forge ItemTooltipEvent: appends "Hunger: +n  Saturation: +s" to food tooltips. */
	public static void onTooltip(ItemStack stack, List<String> lines) {
		try {
			if (!Mods.on("saturation") || !Mods.bool("saturation", "tooltip") || lines == null) {
				return;
			}
			ItemFood food = foodOf(stack);
			if (food == null) {
				return;
			}
			lines.add(EnumChatFormatting.GRAY + "Hunger: " + EnumChatFormatting.WHITE + "+" + food.getHealAmount(stack)
					+ EnumChatFormatting.GRAY + "  Saturation: " + EnumChatFormatting.GOLD
					+ String.format(Locale.ROOT, "+%.1f", saturationGain(food, stack)));
		} catch (Throwable ignored) {
			// a tooltip line is never worth breaking the tooltip over
		}
	}

	/** Saturation points eating this food adds (FoodStats.addStats: nutrition x modifier x 2). */
	private static float saturationGain(ItemFood food, ItemStack stack) {
		return food.getHealAmount(stack) * food.getSaturationModifier(stack) * 2.0F;
	}

	/** Draws over the hunger row vanilla just drew. y = row top, right = bar's right edge. */
	public static void render(Gfx g, EntityPlayer player, int y, int right) {
		if (!Mods.on("saturation")) {
			return;
		}
		FoodStats data = player.getFoodStats();
		int food = data.getFoodLevel();
		float sat = data.getSaturationLevel();
		boolean hungerFx = player.isPotionActive(Potion.hunger);

		// What eating the held food would leave you at (vanilla FoodStats.addStats
		// rules: hunger caps at 20, saturation can never exceed the hunger level).
		ItemStack heldStack = player.getCurrentEquippedItem();
		ItemFood held = foodOf(heldStack);
		int newFood = food;
		float newSat = sat;
		if (held != null && (food < 20 || held instanceof ItemAppleGold)) {
			newFood = Math.min(20, food + held.getHealAmount(heldStack));
			newSat = Math.min(newFood, sat + saturationGain(held, heldStack));
		} else {
			held = null;
		}
		float pulse = 0.35f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 160.0);

		Gfx.defaultBlend();
		try {
			// 1) held-item hunger: the shanks eating would refill, pulsing
			if (held != null && Mods.bool("saturation", "heldHunger") && newFood > food) {
				Gfx.defaultBlend();
				GlStateManager.color(1f, 1f, 1f, pulse);
				for (int i = 0; i < 10; i++) {
					int before = clampUnit(food - i * 2), after = clampUnit(newFood - i * 2);
					if (after > before) {
						int u = after == 2 ? (hungerFx ? FULL_HUNGER_U : FULL_U) : (hungerFx ? HALF_HUNGER_U : HALF_U);
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
			GlStateManager.enableBlend();
			GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
			if (Mods.bool("saturation", "overlay")) {
				GlStateManager.color(r, gr, b, a);
				drawSaturation(g, 0f, sat, y, right);
			}
			if (held != null && Mods.bool("saturation", "heldSaturation") && newSat > sat) {
				GlStateManager.color(r, gr, b, Math.min(1f, a * pulse * 1.6f));
				drawSaturation(g, sat, newSat, y, right);
			}
		} finally {
			Gfx.resetColor();
			Gfx.defaultBlend();
			GlStateManager.disableBlend();
			// vanilla leaves icons.png bound for the rows drawn after the food bar
			Gfx.bind(ICONS);
		}
	}

	/** Saturation between `from` and `to` (in half-shank points) as full/half shanks. */
	private static void drawSaturation(Gfx g, float from, float to, int y, int right) {
		for (int i = 0; i < 10; i++) {
			int before = clampUnit((int) Math.floor(from) - i * 2);
			int after = clampUnit((int) Math.floor(to) - i * 2);
			if (after > before) {
				g.blit(ICONS, right - i * 8 - 9, y, after == 2 ? FULL_U : HALF_U, ROW_V, 9, 9);
			}
		}
	}

	/** One shank's fill (0 empty, 1 half, 2 full) from a remaining point count. */
	private static int clampUnit(int points) {
		return Math.max(0, Math.min(2, points));
	}

	private static ItemFood foodOf(ItemStack s) {
		return s == null || s.stackSize <= 0 || !(s.getItem() instanceof ItemFood) ? null : (ItemFood) s.getItem();
	}
}
