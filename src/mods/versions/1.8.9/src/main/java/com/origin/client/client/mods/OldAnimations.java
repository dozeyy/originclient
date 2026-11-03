package com.origin.client.client.mods;

import com.origin.client.OriginClient;
import com.origin.client.client.hooks.WorldHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemCarrotOnAStick;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemMap;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.MathHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * 1.7 Animations on 1.8.9: only the parts 1.8 changed from 1.7 (1.21.1's OldAnimations is the spec).
 *
 * Where the numbers come from (1.8.9 ItemRenderer, read from the mapped jar): 1.8's first-person
 * chain is ALREADY 1.7's up to the scale -- doItemUsedTransformations (swing translate, idle only),
 * transformFirstPersonItem (arm translate + equip, RY45, swing rotations, S0.4), and the same
 * doBlockTransformations / performDrinking numbers as 1.7. What 1.8 changed:
 *  - every item is then drawn through its JSON model's FIRST_PERSON transform, so flat items, tools,
 *    swords and rods sit differently than 1.7's fixed flat-item transform (1.7 Item Positions);
 *  - while using it passes swing 0 into transformFirstPersonItem, so a sword never swings while
 *    blocking (Block Hitting), and attack clicks while using are thrown away (Swing While Using);
 *  - its bow stretch is a plain S(1,1,1+f*.2) instead of 1.7's pivoted one (1.7 Eating and Bow);
 *  - LayerArmorBase.shouldCombineTextures() is false, so worn armor never flashes red (Red Armor Flash);
 *  - LayerHeldItem has no block turn for a blocking sword (Third Person Blocking).
 * So "1.7 held items" here = 1.8's own chain with the 1.7 tail (rod flip, 1.7 flat-item transform)
 * drawn with TransformType.NONE instead of the model's FIRST_PERSON transform. 1.8.9's
 * RenderItem draws a NONE flat item at unit scale centred on T(-0.5) (preTransform S2 * renderItem
 * S0.5), the same frame 1.21.1's ItemDisplayContext.NONE uses, so 1.21.1's flat-item numbers carry
 * over unchanged. Blocks and 3D/built-in models stay vanilla (their 1.8 JSON tail == 1.7's).
 *
 * Not on 1.8.9 (each would do nothing, dead-option rule):
 *  - Sword Blocking: 1.8 blocks with a sword on right-click natively, and has no shields to hide.
 *  - No Hit Dip: 1.8 has no attack cooldown; ItemRenderer.updateEquippedItem only re-equips on an
 *    item change, the held item never dips after a hit.
 *  - Instant Sneak: EntityPlayer.getEyeHeight() drops 0.08 the tick sneaking starts and
 *    EntityRenderer.orientCamera reads it directly; the camera already snaps like 1.7.
 * 1.8.9 has one hand and no left-handed option, so everything here is the right-hand chain.
 * Everything is visual only; nothing new is sent to the server.
 */
public final class OldAnimations {
	public static final String MOD = "oldanimations";

	private OldAnimations() {
	}

	/** Set by the first exception in any hook: every part then stays vanilla until restart. */
	private static volatile boolean broken = false;

	/** The mod is on (and hasn't failed) and so is this switch. */
	public static boolean on(String key) {
		return active() && Mods.bool(MOD, key);
	}

	private static boolean active() {
		return !broken && Mods.on(MOD);
	}

	/** Hooks call this from their catch: log once, then the whole mod falls back to vanilla. */
	public static void fail(String where, Throwable t) {
		if (!broken) {
			broken = true;
			// log4j 2.0-beta9: message + throwable, no {} placeholder with a trailing throwable
			OriginClient.LOGGER.warn("Origin 1.7 Animations: " + where + " failed, switching to vanilla animations", t);
		}
	}

	/** The player is blocking with a sword right now (vanilla 1.8's own test, EntityPlayer.isBlocking). */
	public static boolean swordBlocking(EntityLivingBase e) {
		return e instanceof EntityPlayer && ((EntityPlayer) e).isBlocking();
	}

	// ---- first person (ItemRenderer.renderItemInFirstPerson HEAD, OldAnimationsHandMixin) ----

	public static final int IDLE = 1, EAT = 2, BOW = 3, BLOCK = 4;

	/**
	 * Which 1.7 pose this frame's held item takes, or 0 to leave the hand to vanilla. Decided before
	 * the hook touches any GL state. Mirrors renderItemInFirstPerson's own branches: maps are vanilla,
	 * "using" is getItemInUseCount() > 0 and the use action comes from the item being drawn.
	 */
	public static int pose(EntityPlayer p, ItemStack stack) {
		if (p == null || stack == null || stack.getItem() instanceof ItemMap || !active()) {
			return 0;
		}
		boolean positions = Mods.bool(MOD, "itemPositions") && isFlat(stack);
		if (p.getItemInUseCount() > 0) {
			EnumAction anim = stack.getItemUseAction();
			if (anim == EnumAction.BLOCK) {
				return Mods.bool(MOD, "blockHit") || positions ? BLOCK : 0;
			}
			if (!positions || !Mods.bool(MOD, "oldUse")) {
				return 0;
			}
			if (anim == EnumAction.EAT || anim == EnumAction.DRINK) {
				return EAT;
			}
			return anim == EnumAction.BOW ? BOW : 0;
		}
		return positions ? IDLE : 0;
	}

	/**
	 * The item part of renderItemInFirstPerson for a pose from {@link #pose}; the hook has already
	 * done vanilla's setup (lighting, light map, arm sway, rescale normal, pushMatrix) around it.
	 */
	public static void drawFirstPerson(ItemRenderer renderer, EntityPlayer p, float partial, int pose,
									   float swing, ItemStack stack, float equip) {
		boolean positions = Mods.bool(MOD, "itemPositions") && isFlat(stack);
		if (pose == IDLE) {
			GlStateManager.translate(-0.4F * MathHelper.sin(MathHelper.sqrt_float(swing) * (float) Math.PI),
					0.2F * MathHelper.sin(MathHelper.sqrt_float(swing) * (float) Math.PI * 2.0F),
					-0.2F * MathHelper.sin(swing * (float) Math.PI));
		} else if (pose == EAT) {
			eatTransform(partial, stack, p);
		}
		GlStateManager.translate(0.56F, -0.52F + equip * -0.6F, -0.72F);
		// 1.7 kept the swing rotations in every use pose (that IS block-hitting); 1.8 passes 0 there.
		float s = pose == BLOCK && !Mods.bool(MOD, "blockHit") ? 0F : swing;
		float f = MathHelper.sin(s * s * (float) Math.PI);
		float f1 = MathHelper.sin(MathHelper.sqrt_float(s) * (float) Math.PI);
		ry(45F + f * -20F);
		rz(f1 * -20F);
		rx(f1 * -80F);
		GlStateManager.scale(0.4F, 0.4F, 0.4F);
		if (pose == BLOCK) {
			blockTransform();
		} else if (pose == BOW) {
			bowTransform(partial, stack, p);
		}
		if (positions) {
			if (stack.getItem() instanceof ItemFishingRod || stack.getItem() instanceof ItemCarrotOnAStick) {
				ry(180F);
			}
			flatItemTransform();
			renderer.renderItem(p, stack, ItemCameraTransforms.TransformType.NONE);
		} else {
			// Block Hitting with 1.8's own held position: 1.8's chain up to here is exactly vanilla's,
			// so the model's FIRST_PERSON transform lands where vanilla puts it (no undo, unlike 1.21.1).
			renderer.renderItem(p, stack, ItemCameraTransforms.TransformType.FIRST_PERSON);
		}
	}

	/** A flat (2D) item 1.7 drew with its fixed transform: not a block, not a 3D or built-in model. */
	private static boolean isFlat(ItemStack stack) {
		IBakedModel model = Minecraft.getMinecraft().getRenderItem().getItemModelMesher().getItemModel(stack);
		return model != null && !model.isGui3d() && !model.isBuiltInRenderer();
	}

	/** 1.7's flat-item tail: T(0,-0.3,0) S1.5 RY50 RZ335 T(-15/16,-1/16,0), moved onto the centred
	 *  item model (+0.5 x/y, -1/32 z) and turned 180 (1.7 drew the icon mirrored). Same as 1.21.1. */
	private static void flatItemTransform() {
		GlStateManager.translate(0F, -0.3F, 0F);
		GlStateManager.scale(1.5F, 1.5F, 1.5F);
		ry(50F);
		rz(335F);
		GlStateManager.translate(-0.4375F, 0.4375F, -0.03125F);
		ry(180F);
	}

	/** 1.7 sword block (== 1.8.9's doBlockTransformations). */
	private static void blockTransform() {
		GlStateManager.translate(-0.5F, 0.2F, 0F);
		ry(30F);
		rx(-80F);
		ry(60F);
	}

	/** 1.7 bow pull: 1.8's first half, then 1.7's stretch pivoted on the arrow (1.8 dropped the pivot). */
	private static void bowTransform(float partial, ItemStack stack, EntityPlayer p) {
		rz(-18F);
		ry(-12F);
		rx(-8F);
		GlStateManager.translate(-0.9F, 0.2F, 0F);
		float t = stack.getMaxItemUseDuration() - (p.getItemInUseCount() - partial + 1F);
		float pull = t / 20F;
		pull = (pull * pull + pull * 2F) / 3F;
		if (pull > 1F) {
			pull = 1F;
		}
		if (pull > 0.1F) {
			GlStateManager.translate(0F, MathHelper.sin((t - 0.1F) * 1.3F) * 0.01F * (pull - 0.1F), 0F);
		}
		GlStateManager.translate(0F, 0F, pull * 0.1F);
		rz(-335F);
		ry(-50F);
		GlStateManager.translate(0F, 0.5F, 0F);
		GlStateManager.scale(1F, 1F, 1F + pull * 0.2F);
		GlStateManager.translate(0F, -0.5F, 0F);
		ry(50F);
		rz(335F);
	}

	/** 1.7's eat/drink bob (== 1.8.9's performDrinking). */
	private static void eatTransform(float partial, ItemStack stack, EntityPlayer p) {
		float f = p.getItemInUseCount() - partial + 1F;
		float g = f / stack.getMaxItemUseDuration();
		if (g < 0.8F) {
			GlStateManager.translate(0F, MathHelper.abs(MathHelper.cos(f / 4F * (float) Math.PI) * 0.1F), 0F);
		}
		float h = 1F - (float) Math.pow(g, 27.0);
		GlStateManager.translate(h * 0.6F, h * -0.5F, 0F);
		ry(h * 90F);
		rx(h * 10F);
		rz(h * 30F);
	}

	private static void rx(float deg) {
		GlStateManager.rotate(deg, 1F, 0F, 0F);
	}

	private static void ry(float deg) {
		GlStateManager.rotate(deg, 0F, 1F, 0F);
	}

	private static void rz(float deg) {
		GlStateManager.rotate(deg, 0F, 0F, 1F);
	}

	// ---- swing while using (OldAnimationsKeysMixin, OldAnimationsKeyBindingMixin) ----

	/**
	 * EntityLivingBase.swingItem() without EntityPlayerSP's C0APacketAnimation: the arm swings on
	 * screen only. Same body as vanilla's (Forge's onEntitySwing veto, getArmSwingAnimationEnd's
	 * Haste/Mining Fatigue length, restart only past half way); the WorldServer broadcast is skipped
	 * because this only ever runs on the client player.
	 */
	public static void visualSwing(EntityLivingBase p) {
		ItemStack s = p.getHeldItem();
		if (s != null && s.getItem() != null && s.getItem().onEntitySwing(p, s)) {
			return;
		}
		int end = p.isPotionActive(Potion.digSpeed)
				? 6 - (1 + p.getActivePotionEffect(Potion.digSpeed).getAmplifier())
				: p.isPotionActive(Potion.digSlowdown) ? 6 + (1 + p.getActivePotionEffect(Potion.digSlowdown).getAmplifier()) * 2 : 6;
		if (!p.isSwingInProgress || p.swingProgressInt >= end / 2 || p.swingProgressInt < 0) {
			p.swingProgressInt = -1;
			p.isSwingInProgress = true;
		}
	}

	// ---- third person (OldAnimationsHandLayerMixin) ----

	/**
	 * Right after ModelBiped.postRenderArm in LayerHeldItem: 1.7's block turn for the held sword
	 * (T(0.05,0,-0.1) RY-50 RX-10 RZ-60), applied on 1.7's item anchor T(-1/16,7/16,1/16) -- vanilla
	 * translates to that anchor next, so pivoting here is the same as 1.7 turning right after it.
	 * The arm needs nothing: 1.8's blocking arm (ModelBiped heldItemRight 3: X*0.5 - 3pi/10, Y -30)
	 * is 1.21.1's BLOCK pose (poseBlockingArm: X*0.5 - 0.9425, Y -30, bytecode-checked).
	 */
	public static void thirdPersonBlock(EntityLivingBase e) {
		if (!swordBlocking(e) || !on("thirdPersonBlock")) {
			return;
		}
		GlStateManager.translate(-0.0625F, 0.4375F, 0.0625F);
		GlStateManager.translate(0.05F, 0F, -0.1F);
		ry(-50F);
		rx(-10F);
		rz(-60F);
		GlStateManager.translate(0.0625F, -0.4375F, -0.0625F);
	}

	// ---- Red Armor Flash (ArmorGlintMixin + HitColorMixin) ----

	private static final FloatBuffer RED = BufferUtils.createFloatBuffer(4);

	/** Set by the armor layer's shouldCombineTextures when the red flash is ours; read by the very
	 *  next setBrightness (RendererLivingEntity.renderLayers calls them back to back). */
	private static boolean armorFlashPending = false;

	/** The armor layer should take the hurt flash for Red Armor Flash: on, and Hit Color isn't
	 *  colouring armor (its Color Armor wins). Skipped under OptiFine shaders like Hit Color: the
	 *  pack then owns the flash (Shaders.setEntityColor), not the texture env. */
	public static boolean redArmorFlashes() {
		boolean want = on("redArmor") && !HitColor.armorFlashes() && !WorldHooks.optifineShadersActive();
		armorFlashPending = want;
		return want;
	}

	/**
	 * From HitColorMixin at setBrightness RETURN (after Hit Color's own recolour): when this flash
	 * is the armor layer's Red Armor Flash, the constant becomes 1.7's red at 40% (1.7 drew the hurt
	 * overlay at alpha 0.4; 1.8's env is 0.3).
	 */
	public static void afterFlash(EntityLivingBase entity, boolean flashSetUp) {
		if (!armorFlashPending) {
			return;
		}
		armorFlashPending = false;   // one-shot: a stray shouldCombineTextures call can't tint a later layer
		if (!flashSetUp || entity == null || !(entity.hurtTime > 0 || entity.deathTime > 0)) {
			return;
		}
		RED.clear();
		RED.put(1F).put(0F).put(0F).put(0.4F);
		RED.flip();
		GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
		GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, RED);
	}

	// ---- proof the hooks landed (CI in-world smoke, OriginDevWorld) ----

	/** {target class, handler method name} for every hook. Mixin 0.7.11 renames an injector handler
	 *  to "prefix$name$uid" (MethodMapper.getHandlerName, format "%s$%s$%s%s"), so the handler name
	 *  sits in the MIDDLE of the merged method's name; a class without it means the mixin silently
	 *  didn't apply (defaultRequire 0). */
	public static final String[][] HOOKS = {
			{"net.minecraft.client.renderer.ItemRenderer", "originclient$oldHand"},
			{"net.minecraft.client.settings.KeyBinding", "originclient$swingWhileUsing"},
			{"net.minecraft.client.Minecraft", "originclient$holdSwingWhileUsing"},
			{"net.minecraft.client.renderer.entity.layers.LayerHeldItem", "originclient$blockTurn"},
			{"net.minecraft.client.renderer.entity.layers.LayerArmorBase", "originclient$armorFlash"},
			{"net.minecraft.client.renderer.entity.RendererLivingEntity", "originclient$hitColor"},
	};

	/** The merged name found for the first hook, for the CI log (records Mixin 0.7.11's naming). */
	public static String sampleMergedName = "";

	/** Names of the hooks missing from their (already loaded) target classes; "" when all applied. */
	public static String missingHooks() {
		StringBuilder missing = new StringBuilder();
		for (String[] h : HOOKS) {
			boolean found = false;
			try {
				for (java.lang.reflect.Method m : Class.forName(h[0]).getDeclaredMethods()) {
					String n = m.getName();
					if (n.equals(h[1]) || n.endsWith("$" + h[1]) || n.contains("$" + h[1] + "$")) {
						found = true;
						if (sampleMergedName.isEmpty()) {
							sampleMergedName = n;
						}
						break;
					}
				}
			} catch (Throwable ignored) {
				// class not found counts as missing
			}
			if (!found) {
				missing.append(missing.length() == 0 ? "" : ", ").append(h[1]);
			}
		}
		return missing.toString();
	}
}
