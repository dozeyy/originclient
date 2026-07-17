package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/**
 * Every mod-menu icon, drawn as a REAL Minecraft item wherever one fits.
 *
 * This replaces the old 96px hand-drawn line-icon atlas (mod_icons.png) that
 * OriginUi used to blit. Rendering live ItemStacks means zero art to maintain,
 * and the icons automatically match whatever resource pack the player runs --
 * a spyglass here is the same spyglass they see in their hotbar. Items are
 * full-colour and 3D-lit, which is a deliberate, Will-approved break of the
 * theme's no-hue rule (see OriginTheme).
 *
 * Only four ideas have no Minecraft item that says them, so they ship as baked
 * Origin textures under textures/ui/modicon/. Their pixel density is chosen to
 * match what they sit beside: the flat ones are 16x16 native, because a real
 * item icon is a 16x16 texture shown at 2x, and a 32x32 flat custom would read
 * as visibly sharper than every item next to it. blockoverlay is the one
 * exception at 32x32 -- it's an isometric block render, which is already how a
 * real block item looks at that size.
 *
 * "fps" is neither: it draws the live frame count as text, which is both the
 * clearest read and free on every version.
 */
public final class ModIcons {

	/** Mod id -> the vanilla item that represents it. */
	private static final Map<String, Item> ITEMS = new HashMap<>();

	/** Mod id -> baked Origin texture, for the ideas no item expresses. */
	private static final Map<String, ResourceLocation> CUSTOM = new HashMap<>();

	/** Native size of each baked texture, so the blit's UV source is right. */
	private static final Map<String, Integer> CUSTOM_SIZE = new HashMap<>();

	/** Drawn as live text rather than any icon. */
	public static final String FPS = "fps";

	static {
		ITEMS.put("togglesprint", Items.FEATHER);
		ITEMS.put("zoom", Items.SPYGLASS);
		ITEMS.put("armorhud", Items.DIAMOND_CHESTPLATE);
		ITEMS.put("coords", Items.COMPASS);
		ITEMS.put("potionhud", Items.POTION);
		ITEMS.put("serveraddress", Items.COMMAND_BLOCK);
		ITEMS.put("scoreboard", Items.OAK_SIGN);
		ITEMS.put("freelook", Items.ENDER_EYE);
		ITEMS.put("fullbright", Items.GLOWSTONE);
		ITEMS.put("hitboxes", Items.ARMOR_STAND);
		ITEMS.put("nametags", Items.NAME_TAG);
		ITEMS.put("weather", Items.TRIDENT);
		ITEMS.put("timechanger", Items.CLOCK);
		ITEMS.put("motionblur", Items.PHANTOM_MEMBRANE);
		ITEMS.put("chat", Items.WRITABLE_BOOK);
		ITEMS.put("particles", Items.FIREWORK_STAR);
		// JEI = look up an item's recipe. The Knowledge Book is vanilla's own
		// "this teaches you a recipe" item, and nothing else in this table uses
		// it. NOT part of the locked icon table (JEI postdates it) — Will's call
		// to confirm.
		ITEMS.put("jei", Items.KNOWLEDGE_BOOK);

		// Settings tab pseudo-ids (Mods.GENERAL_ID / PERFORMANCE_ID) and the
		// menu's own chrome. These aren't mods, but they draw through the same
		// icon path, so they live in the same table.
		ITEMS.put("@general", Items.COMPARATOR);
		ITEMS.put("@performance", Items.REDSTONE);
		ITEMS.put("@hudeditor", Items.ITEM_FRAME);
		// Search field: a spyglass is Minecraft's only "look closer" item, so it
		// is the closest native thing to a magnifying glass. It doubles with
		// Zoom's icon, but in a different context (a text field vs a mod card).
		ITEMS.put("@search", Items.SPYGLASS);
		// Panel-backing toggle: switches the menu panel between opaque and
		// see-through, and glass is literally the transparency block.
		ITEMS.put("@backing", Items.GLASS);

		custom("cps", 16);
		custom("keystrokes", 16);
		custom("chunkborders", 16);
		custom("blockoverlay", 32);
	}

	private static void custom(String id, int nativeSize) {
		CUSTOM.put(id, ResourceLocation.fromNamespaceAndPath("originclient", "textures/ui/modicon/" + id + ".png"));
		CUSTOM_SIZE.put(id, nativeSize);
	}

	private ModIcons() {
	}

	/** Whether this id draws through the item/texture path at all. */
	public static boolean has(String id) {
		return ITEMS.containsKey(id) || CUSTOM.containsKey(id) || FPS.equals(id);
	}

	/**
	 * Draws the icon for `id` in a size x size box at (x,y). `alpha` (0..1)
	 * carries the mod menu's open/close fade.
	 */
	public static void draw(GuiGraphics g, String id, int x, int y, int size, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		if (FPS.equals(id)) {
			drawFps(g, x, y, size, alpha);
			return;
		}
		ResourceLocation tex = CUSTOM.get(id);
		if (tex != null) {
			drawCustom(g, tex, CUSTOM_SIZE.get(id), x, y, size, alpha);
			return;
		}
		Item item = ITEMS.get(id);
		if (item != null) {
			drawItem(g, item, x, y, size, alpha);
		}
	}

	private static void drawCustom(GuiGraphics g, ResourceLocation tex, int nativeSize,
			int x, int y, int size, float alpha) {
		// 1.21.2+ blit: render-type function + explicit source region; blend and
		// texture bind are owned by the gui render type, not RenderSystem.
		RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
		g.blit(net.minecraft.client.renderer.RenderType::guiTextured, tex, x, y, 0f, 0f, size, size,
				nativeSize, nativeSize, nativeSize, nativeSize);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	private static void drawItem(GuiGraphics g, Item item, int x, int y, int size, float alpha) {
		// renderFakeItem always draws a 16x16 GUI item, so scale the pose to hit
		// the requested box. Translate first, then scale, so the item lands at
		// (x,y) rather than at a scaled-up offset.
		var pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		float s = size / 16f;
		pose.scale(s, s, 1f);
		RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
		g.renderFakeItem(new ItemStack(item), 0, 0);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		pose.popPose();
	}

	private static void drawFps(GuiGraphics g, int x, int y, int size, float alpha) {
		// The live frame count, capped at "999+" so the label can never grow wide
		// enough to overflow the card and shove the title around.
		int fps = Minecraft.getInstance().getFps();
		String text = fps > 999 ? "999+" : Integer.toString(fps);
		Font font = Minecraft.getInstance().font;
		// Scale so the text fills the icon box like an item would, rather than
		// sitting tiny in the middle of it.
		float s = size / 24f;
		var pose = g.pose();
		pose.pushPose();
		pose.translate(x + size / 2.0, y + size / 2.0, 0);
		pose.scale(s, s, 1f);
		int a = Math.max(4, (int) (alpha * 255)) << 24;
		g.drawString(font, text, -font.width(text) / 2, -4, a | 0xFFFFFF, false);
		pose.popPose();
	}
}
