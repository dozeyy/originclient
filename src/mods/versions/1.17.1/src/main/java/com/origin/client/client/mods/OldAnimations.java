package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FoodOnAStickItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.UseAnim;

/**
 * 1.7 Animations: the 1.7 first- and third-person look, one switch per part.
 * Ported from 1.21.1 (the design truth); the math is identical, only the era's names differ
 * (1.17.1: ItemTransforms.TransformType + Vector3f.XP/YP/ZP,
 * Entity.level is a field, getUseDuration() takes no entity).
 *
 * Where the numbers come from: 1.7.10's ItemRenderer drew every held item with fixed GL
 * transforms; this era's ItemInHandRenderer runs the SAME hand chain (swing translate, arm
 * translate, RY45 + swing rotations) and then hands the item to its model's JSON display
 * transform after an extra RY(-45). So "1.7 held items" = vanilla's hand chain up to the
 * swing rotations, then 1.7's tail (scale 0.4, the use pose, the rod flip, 1.7's flat-item
 * transform) with the model's own display transform switched off (TransformType.NONE).
 * Check: for block items 1.7's tail equals vanilla's block JSON (RY45, scale 0.4), so blocks
 * are left to vanilla. 1.7 kept the swing rotations during every use pose (that IS
 * block-hitting); the swing translate only ran when idle.
 *
 * Left arm = the right-arm chain mirrored in X (translate x and Y/Z rotations times i),
 * exactly what vanilla does for the off hand.
 *
 * Shields are never touched (Will, 2026-11-02: "the 1.7 animations shouldn't stop shields from
 * working normally"): a raised shield keeps vanilla's pose, blocking and look in both views.
 * Sword blocking is the 1.7 pose while right-click is held with a sword and nothing is being
 * used (1.9+ swords don't block), so it is looks only and the local player's own. Everything
 * here is visual only; nothing is sent to the server.
 */
public final class OldAnimations {
	public static final String MOD = "oldanimations";
	private static final float PI = (float) Math.PI;

	private OldAnimations() {
	}

	/** Set by the first exception in any hook: every part then stays vanilla until restart. */
	private static boolean broken = false;

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
			OriginClient.LOGGER.warn("Origin 1.7 Animations: {} failed, switching to vanilla animations", where, t);
		}
	}

	public static boolean isSword(ItemStack s) {
		return s.getItem() instanceof SwordItem;
	}

	/** Shield, or any modded item that blocks like one. */
	public static boolean isShield(ItemStack s) {
		return !s.isEmpty() && s.getUseAnimation() == UseAnim.BLOCK;
	}

	/**
	 * The local player holds right-click with a sword in the main hand while nothing is being
	 * used: 1.7's sword block, looks only. Never while a shield could rise (a shield in the off
	 * hand raises on that same click and stays vanilla), and not with an off-hand block, which
	 * that click places. Other players' right-click can't be seen, so only the local player.
	 */
	public static boolean swordBlocking(LivingEntity e) {
		Minecraft mc = Minecraft.getInstance();
		if (e != mc.player || mc.screen != null || !mc.options.keyUse.isDown() || e.isUsingItem()
				|| !isSword(e.getMainHandItem())) {
			return false;
		}
		ItemStack off = e.getOffhandItem();
		return !isShield(off) && !(off.getItem() instanceof BlockItem);
	}

	// ---- first person (ItemInHandRenderer.renderArmWithItem, OldAnimationsHandMixin) ----

	private enum Pose { IDLE, EAT, BOW, BLOCK }

	/**
	 * Draws this hand the 1.7 way and returns true (vanilla's draw is then skipped), or
	 * returns false to leave the hand to vanilla. Parameters are renderArmWithItem's.
	 */
	public static boolean renderFirstPerson(ItemInHandRenderer renderer, AbstractClientPlayer p, float partial,
											InteractionHand hand, float swing, ItemStack stack, float equip,
											PoseStack ps, MultiBufferSource buffers, int light) {
		if (stack.isEmpty() || p.isScoping() || !active()) {
			return false;
		}
		boolean main = hand == InteractionHand.MAIN_HAND;
		boolean swordBlock = Mods.bool(MOD, "swordBlock");
		boolean right = (main ? p.getMainArm() : p.getMainArm().getOpposite()) == HumanoidArm.RIGHT;
		boolean positions = Mods.bool(MOD, "itemPositions") && isFlat(stack, p);
		boolean usingThis = p.isUsingItem() && p.getUseItemRemainingTicks() > 0 && p.getUsedItemHand() == hand;

		Pose pose;
		if (main && swordBlock && swordBlocking(p)) {
			pose = Pose.BLOCK;
		} else if (usingThis) {
			UseAnim anim = stack.getUseAnimation();
			if (!positions || !Mods.bool(MOD, "oldUse")) {
				return false;
			} else if (anim == UseAnim.EAT || anim == UseAnim.DRINK) {
				pose = Pose.EAT;
			} else if (anim == UseAnim.BOW) {
				pose = Pose.BOW;
			} else {
				return false;
			}
		} else if (positions && !p.isAutoSpinAttack()) {
			pose = Pose.IDLE;
		} else {
			return false;
		}

		int i = right ? 1 : -1;
		ps.pushPose();
		try {
			drawHand(renderer, p, partial, pose, swing, stack, equip, ps, buffers, light, i, right, positions);
		} finally {
			ps.popPose(); // balanced even if a draw throws, so vanilla can still draw the hand
		}
		return true;
	}

	private static void drawHand(ItemInHandRenderer renderer, AbstractClientPlayer p, float partial, Pose pose,
								 float swing, ItemStack stack, float equip, PoseStack ps, MultiBufferSource buffers,
								 int light, int i, boolean right, boolean positions) {
		if (pose == Pose.IDLE) {
			ps.translate(i * -0.4F * Mth.sin(Mth.sqrt(swing) * PI), 0.2F * Mth.sin(Mth.sqrt(swing) * PI * 2F),
					-0.2F * Mth.sin(swing * PI));
		} else if (pose == Pose.EAT) {
			eatTransform(ps, partial, i, stack, p);
		}
		ps.translate(i * 0.56F, -0.52F + equip * -0.6F, -0.72F);
		float s = pose == Pose.BLOCK && !Mods.bool(MOD, "blockHit") ? 0F : swing;
		float f = Mth.sin(s * s * PI);
		float f1 = Mth.sin(Mth.sqrt(s) * PI);
		ry(ps, i, 45F + f * -20F);
		rz(ps, i, f1 * -20F);
		rx(ps, f1 * -80F);
		ps.scale(0.4F, 0.4F, 0.4F);
		if (pose == Pose.BLOCK) {
			blockTransform(ps, i);
		} else if (pose == Pose.BOW) {
			bowTransform(ps, partial, i, stack, p);
		}
		ItemTransforms.TransformType ctx;
		if (positions) {
			if (stack.getItem() instanceof FishingRodItem || stack.getItem() instanceof FoodOnAStickItem) {
				ry(ps, i, 180F);
			}
			flatItemTransform(ps, i);
			ctx = ItemTransforms.TransformType.NONE;
		} else {
			// Block pose on the item's own first-person display: undo the 1.7 scale and
			// vanilla's RY45 so the model's JSON transform lands where vanilla expects it.
			ps.scale(2.5F, 2.5F, 2.5F);
			ry(ps, i, -45F);
			ctx = right ? ItemTransforms.TransformType.FIRST_PERSON_RIGHT_HAND : ItemTransforms.TransformType.FIRST_PERSON_LEFT_HAND;
		}
		renderer.renderItem(p, stack, ctx, !right, ps, buffers, light);
	}

	/**
	 * A flat (2D) item 1.7 drew with its fixed transform. Blocks, 3D/custom models (shield,
	 * trident), maps and items with a use pose 1.7 never had (crossbow, spyglass, horn,
	 * brush, trident) stay vanilla.
	 */
	private static boolean isFlat(ItemStack stack, LivingEntity e) {
		if (stack.getItem() == Items.FILLED_MAP) {
			return false;
		}
		UseAnim anim = stack.getUseAnimation();
		if (anim != UseAnim.NONE && anim != UseAnim.EAT && anim != UseAnim.DRINK && anim != UseAnim.BOW
				&& anim != UseAnim.BLOCK) {
			return false;
		}
		BakedModel model = Minecraft.getInstance().getItemRenderer().getModel(stack, e.level, e, 0);
		return !model.isGui3d() && !model.isCustomRenderer();
	}

	/** 1.7's flat-item tail: T(0,-0.3,0) S1.5 RY50 RZ335 T(-15/16,-1/16,0), moved onto vanilla's
	 *  centred item model (+0.5 x/y, -1/32 z) and turned 180 (1.7 drew the icon mirrored, i.e.
	 *  the model's back face toward +z). */
	private static void flatItemTransform(PoseStack ps, int i) {
		ps.translate(0F, -0.3F, 0F);
		ps.scale(1.5F, 1.5F, 1.5F);
		ry(ps, i, 50F);
		rz(ps, i, 335F);
		ps.translate(i * -0.4375F, 0.4375F, -0.03125F);
		ry(ps, i, 180F);
	}

	/** 1.7 sword block (ItemRenderer, EnumAction.block). */
	private static void blockTransform(PoseStack ps, int i) {
		ps.translate(i * -0.5F, 0.2F, 0F);
		ry(ps, i, 30F);
		rx(ps, -80F);
		ry(ps, i, 60F);
	}

	/** 1.7 bow pull (ItemRenderer, EnumAction.bow). */
	private static void bowTransform(PoseStack ps, float partial, int i, ItemStack stack, LivingEntity p) {
		rz(ps, i, -18F);
		ry(ps, i, -12F);
		rx(ps, -8F);
		ps.translate(i * -0.9F, 0.2F, 0F);
		float t = stack.getUseDuration() - (p.getUseItemRemainingTicks() - partial + 1F);
		float pull = t / 20F;
		pull = (pull * pull + pull * 2F) / 3F;
		if (pull > 1F) {
			pull = 1F;
		}
		if (pull > 0.1F) {
			ps.translate(0F, Mth.sin((t - 0.1F) * 1.3F) * 0.01F * (pull - 0.1F), 0F);
		}
		ps.translate(0F, 0F, pull * 0.1F);
		rz(ps, i, -335F);
		ry(ps, i, -50F);
		ps.translate(0F, 0.5F, 0F);
		ps.scale(1F, 1F, 1F + pull * 0.2F);
		ps.translate(0F, -0.5F, 0F);
		ry(ps, i, 50F);
		rz(ps, i, 335F);
	}

	/** 1.7's eat/drink bob, which is also vanilla's applyEatTransform (same formula). */
	private static void eatTransform(PoseStack ps, float partial, int i, ItemStack stack, LivingEntity p) {
		float f = p.getUseItemRemainingTicks() - partial + 1F;
		float g = f / stack.getUseDuration();
		if (g < 0.8F) {
			ps.translate(0F, Mth.abs(Mth.cos(f / 4F * PI) * 0.1F), 0F);
		}
		float h = 1F - (float) Math.pow(g, 27.0);
		ps.translate(h * 0.6F * i, h * -0.5F, 0F);
		ry(ps, i, h * 90F);
		rx(ps, h * 10F);
		rz(ps, i, h * 30F);
	}

	// The era's rotation API lives only in these three (1.19.3+: Axis; older: Vector3f.XP/YP/ZP).
	private static void rx(PoseStack ps, float deg) {
		ps.mulPose(Vector3f.XP.rotationDegrees(deg));
	}

	private static void ry(PoseStack ps, int i, float deg) {
		ps.mulPose(Vector3f.YP.rotationDegrees(i * deg));
	}

	private static void rz(PoseStack ps, int i, float deg) {
		ps.mulPose(Vector3f.ZP.rotationDegrees(i * deg));
	}

	// ---- third person (OldAnimationsThirdPersonMixin) ----

	/** The arm pose to force for this hand, or null for vanilla's (always null with a raised shield). */
	public static HumanoidModel.ArmPose armPose(AbstractClientPlayer p, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND || !on("thirdPersonBlock") || !on("swordBlock")) {
			return null;
		}
		return swordBlocking(p) ? HumanoidModel.ArmPose.BLOCK : null;
	}

	/**
	 * Right after translateToHand: 1.7's block turn for the held sword, pivoting on 1.7's item
	 * anchor in the arm (T(-1/16, 7/16, 1/16)), so the sword sits across the body like 1.7's
	 * T(0.05,0,-0.1) RY-50 RX-10 RZ-60. The arm angles themselves come from the BLOCK pose.
	 */
	public static void thirdPersonBlock(LivingEntity e, ItemStack stack, HumanoidArm arm, PoseStack ps) {
		if (!(e instanceof net.minecraft.world.entity.player.Player) || stack != e.getMainHandItem()
				|| !swordBlocking(e) || !on("thirdPersonBlock") || !on("swordBlock")) {
			return;
		}
		int i = arm == HumanoidArm.RIGHT ? 1 : -1;
		ps.translate(i * -0.0625F, 0.4375F, 0.0625F);
		ps.translate(i * 0.05F, 0F, -0.1F);
		ry(ps, i, -50F);
		rx(ps, -10F);
		rz(ps, i, -60F);
		ps.translate(i * 0.0625F, -0.4375F, -0.0625F);
	}

	// ---- proof the hooks landed (CI in-world smoke, OriginDevWorld) ----

	/** {target class, handler method name} for every hook. Mixin keeps the handler's name as the
	 *  suffix of the merged method ("handler$zbc000$originclient$oldHand"), so a class without
	 *  it means the mixin silently didn't apply (defaultRequire 0). */
	public static final String[][] HOOKS = {
			{"net.minecraft.client.renderer.ItemInHandRenderer", "originclient$oldHand"},
			{"net.minecraft.client.renderer.ItemInHandRenderer", "originclient$noHitDip"},
			{"net.minecraft.client.Camera", "originclient$instantSneak"},
			{"net.minecraft.client.Minecraft", "originclient$swingWhileUsing"},
			{"net.minecraft.client.Minecraft", "originclient$holdSwingWhileUsing"},
			{"net.minecraft.client.renderer.entity.player.PlayerRenderer", "originclient$oldArmPose"},
			{"net.minecraft.client.renderer.entity.layers.ItemInHandLayer", "originclient$blockTurn"},
	};

	/** Names of the hooks missing from their (already loaded) target classes; "" when all applied. */
	public static String missingHooks() {
		StringBuilder missing = new StringBuilder();
		for (String[] h : HOOKS) {
			boolean found = false;
			try {
				for (java.lang.reflect.Method m : Class.forName(h[0]).getDeclaredMethods()) {
					if (m.getName().equals(h[1]) || m.getName().endsWith("$" + h[1])) {
						found = true;
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

	// ---- extras ----

	/** Red Armor Flash: 1.7 tinted hurt armor red at 40% (RendererLivingEntity's overlay).
	 *  Pre-1.21 the armor tint is three floats (HitColor.tintArmor's channel index 0/1/2):
	 *  red stays, green and blue drop to 60% (1.21.1's ARGB redArmor, per channel). */
	public static float redArmor(float channel, int index) {
		return index == 0 ? channel : channel * 0.6F;
	}
}
