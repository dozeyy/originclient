package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.origin.client.OriginClient;
import com.origin.client.client.mixin.OldAnimationsItemLayerAccessor;
import com.origin.client.client.mixin.OldAnimationsItemStateAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FoodOnAStickItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

/**
 * 1.7 Animations: the 1.7 first- and third-person look, one switch per part.
 *
 * Where the numbers come from: 1.7.10's ItemRenderer drew every held item with fixed GL
 * transforms; 26.2's ItemInHandRenderer runs the SAME hand chain as 1.21.1 (arm translate,
 * swing translate, RY45 + swing rotations; the two translates commute, so their swapped order
 * in 26.2's submitArmWithItem is the same matrix) and then hands the item to its model's JSON
 * display transform after an extra RY(-45). So "1.7 held items" = vanilla's hand chain up to
 * the swing rotations, then 1.7's tail (scale 0.4, the use pose, the rod flip, 1.7's flat-item
 * transform) with the model's own display transform switched off (ItemDisplayContext.NONE:
 * ItemTransforms.getTransform(NONE) = ItemTransform.NO_TRANSFORM, which only centres the model
 * with T(-0.5), exactly as 1.21.1). Check: for block items 1.7's tail equals vanilla's block
 * JSON (RY45, scale 0.4), so blocks are left to vanilla. 1.7 kept the swing rotations during
 * every use pose (that IS block-hitting); the swing translate only ran when idle.
 *
 * Left arm = the right-arm chain mirrored in X (translate x and Y/Z rotations times i),
 * exactly what vanilla does for the off hand.
 *
 * Sword blocking needs a shield in the off hand (how servers like Hypixel let 1.9+ players
 * block): using it shows the 1.7 sword block instead, and the shield is never drawn while
 * a sword is held. Everything here is visual only; nothing is sent to the server.
 *
 * 26.2 port (from 1.21.1): no SwordItem (the swords item tag), ItemUseAnimation, submit-node
 * drawing (SubmitNodeCollector), flat-vs-3D from the ItemStackRenderState the item model
 * resolver fills (BakedModel is gone), and third person reads the avatar render state.
 */
public final class OldAnimations {
	public static final String MOD = "oldanimations";

	private OldAnimations() {
	}

	/** Set by the first exception in any hook: every part then stays vanilla until restart. */
	private static boolean broken = false;

	/** Render-thread scratch for the flat-item check; resolved, read, never submitted (so reuse is safe). */
	private static final ItemStackRenderState FLAT_PROBE = new ItemStackRenderState();

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

	/** Swords: the vanilla #swords item tag (SwordItem no longer exists on 26.x). */
	public static boolean isSword(ItemStack s) {
		return s.is(ItemTags.SWORDS);
	}

	/** Shield, or any modded item that blocks like one. */
	public static boolean isShield(ItemStack s) {
		return !s.isEmpty() && s.getUseAnimation() == ItemUseAnimation.BLOCK;
	}

	/** Sword in the main hand, shield in the off hand, shield raised right now. */
	public static boolean swordBlocking(LivingEntity e) {
		return isSword(e.getMainHandItem()) && isShield(e.getOffhandItem()) && e.isUsingItem()
				&& e.getUseItemRemainingTicks() > 0 && e.getUsedItemHand() == InteractionHand.OFF_HAND;
	}

	/** The same test from a player's render state (third person reads states, not entities, on 26.x). */
	private static boolean swordBlocking(AvatarRenderState s) {
		return isSword(s.getMainHandItemStack()) && isShield(offHandStack(s)) && s.isUsingItem
				&& s.useItemHand == InteractionHand.OFF_HAND;
	}

	private static ItemStack offHandStack(ArmedEntityRenderState s) {
		return s.mainArm == HumanoidArm.RIGHT ? s.leftHandItemStack : s.rightHandItemStack;
	}

	// ---- first person (ItemInHandRenderer.submitArmWithItem, OldAnimationsHandMixin) ----

	private enum Pose { IDLE, EAT, BOW, BLOCK }

	/**
	 * Draws this hand the 1.7 way and returns true (vanilla's draw is then skipped), or
	 * returns false to leave the hand to vanilla. Parameters are submitArmWithItem's.
	 */
	public static boolean renderFirstPerson(ItemInHandRenderer renderer, AbstractClientPlayer p, float partial,
											InteractionHand hand, float swing, ItemStack stack, float equip,
											PoseStack ps, SubmitNodeCollector collector, int light) {
		if (stack.isEmpty() || p.isScoping() || !active()) {
			return false;
		}
		boolean main = hand == InteractionHand.MAIN_HAND;
		boolean swordBlock = Mods.bool(MOD, "swordBlock");
		if (!main && swordBlock && isShield(stack) && isSword(p.getMainHandItem())) {
			return true; // 1.7 had no shield: never drawn while a sword is held
		}
		boolean right = (main ? p.getMainArm() : p.getMainArm().getOpposite()) == HumanoidArm.RIGHT;
		boolean positions = Mods.bool(MOD, "itemPositions") && isFlat(stack, p);
		boolean usingThis = p.isUsingItem() && p.getUseItemRemainingTicks() > 0 && p.getUsedItemHand() == hand;

		Pose pose;
		if (main && swordBlock && swordBlocking(p)) {
			pose = Pose.BLOCK;
		} else if (usingThis) {
			ItemUseAnimation anim = stack.getUseAnimation();
			if (!positions || !Mods.bool(MOD, "oldUse")) {
				return false;
			} else if (anim == ItemUseAnimation.EAT || anim == ItemUseAnimation.DRINK) {
				pose = Pose.EAT;
			} else if (anim == ItemUseAnimation.BOW) {
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
			drawHand(renderer, p, partial, pose, swing, stack, equip, ps, collector, light, i, right, positions);
		} finally {
			ps.popPose(); // balanced even if a draw throws, so vanilla can still draw the hand
		}
		return true;
	}

	private static void drawHand(ItemInHandRenderer renderer, AbstractClientPlayer p, float partial, Pose pose,
								 float swing, ItemStack stack, float equip, PoseStack ps, SubmitNodeCollector collector,
								 int light, int i, boolean right, boolean positions) {
		if (pose == Pose.IDLE) {
			ps.translate(i * -0.4F * Mth.sin(Mth.sqrt(swing) * Mth.PI), 0.2F * Mth.sin(Mth.sqrt(swing) * Mth.TWO_PI),
					-0.2F * Mth.sin(swing * Mth.PI));
		} else if (pose == Pose.EAT) {
			eatTransform(ps, partial, i, stack, p);
		}
		ps.translate(i * 0.56F, -0.52F + equip * -0.6F, -0.72F);
		float s = pose == Pose.BLOCK && !Mods.bool(MOD, "blockHit") ? 0F : swing;
		float f = Mth.sin(s * s * Mth.PI);
		float f1 = Mth.sin(Mth.sqrt(s) * Mth.PI);
		ry(ps, i, 45F + f * -20F);
		rz(ps, i, f1 * -20F);
		rx(ps, f1 * -80F);
		ps.scale(0.4F, 0.4F, 0.4F);
		if (pose == Pose.BLOCK) {
			blockTransform(ps, i);
		} else if (pose == Pose.BOW) {
			bowTransform(ps, partial, i, stack, p);
		}
		ItemDisplayContext ctx;
		if (positions) {
			if (stack.getItem() instanceof FishingRodItem || stack.getItem() instanceof FoodOnAStickItem) {
				ry(ps, i, 180F);
			}
			flatItemTransform(ps, i);
			ctx = ItemDisplayContext.NONE;
		} else {
			// Block pose on the item's own first-person display: undo the 1.7 scale and
			// vanilla's RY45 so the model's JSON transform lands where vanilla expects it.
			ps.scale(2.5F, 2.5F, 2.5F);
			ry(ps, i, -45F);
			ctx = right ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
		}
		// 26.2 renderItem(LivingEntity, ItemStack, ItemDisplayContext, PoseStack, SubmitNodeCollector, int):
		// the left-hand mirror comes from the context itself (NONE has no transform to mirror).
		renderer.renderItem(p, stack, ctx, ps, collector, light);
	}

	/**
	 * A flat (2D) item 1.7 drew with its fixed transform. Blocks, 3D/custom models (shield,
	 * trident), maps and items with a use pose 1.7 never had (crossbow, spyglass, horn,
	 * brush, trident, and 26.x's bundle and spear poses) stay vanilla.
	 * 26.2: BakedModel.isGui3d/isCustomRenderer are gone; the resolved item state answers the
	 * same two questions (usesBlockLight = gui_light "side", a special renderer = custom model).
	 */
	private static boolean isFlat(ItemStack stack, LivingEntity e) {
		if (stack.has(DataComponents.MAP_ID)) {
			return false;
		}
		ItemUseAnimation anim = stack.getUseAnimation();
		if (anim != ItemUseAnimation.NONE && anim != ItemUseAnimation.EAT && anim != ItemUseAnimation.DRINK
				&& anim != ItemUseAnimation.BOW && anim != ItemUseAnimation.BLOCK) {
			return false;
		}
		ItemStackRenderState probe = FLAT_PROBE;
		Minecraft.getInstance().getItemModelResolver().updateForTopItem(probe, stack, ItemDisplayContext.NONE,
				e.level(), e, e.getId());
		if (probe.isEmpty() || probe.usesBlockLight()) {
			return false;
		}
		OldAnimationsItemStateAccessor state = (OldAnimationsItemStateAccessor) probe;
		ItemStackRenderState.LayerRenderState[] layers = state.originclient$layers();
		int n = state.originclient$activeLayerCount();
		for (int k = 0; k < n; k++) {
			if (((OldAnimationsItemLayerAccessor) layers[k]).originclient$specialRenderer() != null) {
				return false;
			}
		}
		return true;
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
		float t = stack.getUseDuration(p) - (p.getUseItemRemainingTicks() - partial + 1F);
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
		float g = f / stack.getUseDuration(p);
		if (g < 0.8F) {
			ps.translate(0F, Mth.abs(Mth.cos(f / 4F * Mth.PI) * 0.1F), 0F);
		}
		float h = 1F - (float) Math.pow(g, 27.0);
		ps.translate(h * 0.6F * i, h * -0.5F, 0F);
		ry(ps, i, h * 90F);
		rx(ps, h * 10F);
		rz(ps, i, h * 30F);
	}

	private static void rx(PoseStack ps, float deg) {
		ps.mulPose(Axis.XP.rotationDegrees(deg));
	}

	private static void ry(PoseStack ps, int i, float deg) {
		ps.mulPose(Axis.YP.rotationDegrees(i * deg));
	}

	private static void rz(PoseStack ps, int i, float deg) {
		ps.mulPose(Axis.ZP.rotationDegrees(i * deg));
	}

	// ---- third person (OldAnimationsThirdPersonMixin, OldAnimationsHandLayerMixin) ----

	/** The arm pose to force for this hand, or null for vanilla's. */
	public static HumanoidModel.ArmPose armPose(LivingEntity p, InteractionHand hand) {
		if (!on("thirdPersonBlock") || !isSword(p.getMainHandItem()) || !isShield(p.getOffhandItem())) {
			return null;
		}
		if (hand == InteractionHand.OFF_HAND) {
			return HumanoidModel.ArmPose.EMPTY; // the shield isn't drawn, so the arm hangs free
		}
		return swordBlocking(p) ? HumanoidModel.ArmPose.BLOCK : null;
	}

	/** True when this third-person item is a player's off-hand shield next to a sword: not drawn. */
	public static boolean hideThirdPerson(ArmedEntityRenderState s, ItemStack stack, HumanoidArm arm) {
		return s instanceof AvatarRenderState && arm != s.mainArm && isShield(stack)
				&& isSword(s.getMainHandItemStack()) && on("thirdPersonBlock");
	}

	/**
	 * Right after translateToHand: 1.7's block turn for the held sword, pivoting on 1.7's item
	 * anchor in the arm (T(-1/16, 7/16, 1/16)), so the sword sits across the body like 1.7's
	 * T(0.05,0,-0.1) RY-50 RX-10 RZ-60. The arm angles themselves come from the BLOCK pose.
	 */
	public static void thirdPersonBlock(ArmedEntityRenderState s, HumanoidArm arm, PoseStack ps) {
		if (!(s instanceof AvatarRenderState a) || arm != a.mainArm || !swordBlocking(a) || !on("thirdPersonBlock")) {
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
			{"net.minecraft.client.renderer.entity.player.AvatarRenderer", "originclient$oldArmPose"},
			{"net.minecraft.client.renderer.entity.layers.ItemInHandLayer", "originclient$hideShield"},
			{"net.minecraft.client.renderer.entity.layers.ItemInHandLayer", "originclient$blockTurn"},
			// the two flat-item accessors (26.x only: BakedModel's flags became item render state)
			{"net.minecraft.client.renderer.item.ItemStackRenderState", "originclient$layers"},
			{"net.minecraft.client.renderer.item.ItemStackRenderState$LayerRenderState", "originclient$specialRenderer"},
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

	/** Red Armor Flash: 1.7 tinted hurt armor red at 40% (RendererLivingEntity's overlay). */
	public static int redArmor(int argb) {
		int g = Math.round(((argb >> 8) & 0xFF) * 0.6F);
		int b = Math.round((argb & 0xFF) * 0.6F);
		return (argb & 0xFFFF0000) | (g << 8) | b;
	}
}
