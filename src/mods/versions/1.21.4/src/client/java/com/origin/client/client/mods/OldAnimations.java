package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.origin.client.OriginClient;
import com.origin.client.client.ext.OldAnimationsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FoodOnAStickItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.SwordItem;

/**
 * 1.7 Animations: the 1.7 first- and third-person look, one switch per part.
 *
 * Where the numbers come from: 1.7.10's ItemRenderer drew every held item with fixed GL
 * transforms; 1.21.1's ItemInHandRenderer runs the SAME hand chain (swing translate, arm
 * translate, RY45 + swing rotations) and then hands the item to its model's JSON display
 * transform after an extra RY(-45). So "1.7 held items" = vanilla's hand chain up to the
 * swing rotations, then 1.7's tail (scale 0.4, the use pose, the rod flip, 1.7's flat-item
 * transform) with the model's own display transform switched off (ItemDisplayContext.NONE).
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
 *
 * PER-VERSION (1.21.2-1.21.4, javap of this module's mapped jar): same math as 1.21.1, three
 * mechanisms differ. UseAnim is ItemUseAnimation. The item model rework removed
 * ItemRenderer.getModel, so "flat" asks ItemModelResolver for the stack's render state
 * (ItemStackRenderState.isGui3d(): false exactly for models rooted in item/generated, the
 * set 1.21.1's BakedModel.isGui3d() picked; special models like the shield keep their base
 * model's true). Third person: renderers see render states, not the player, so
 * PlayerRenderer.extractRenderState stores which arm's sword to turn on the state
 * (OldAnimationsState) for ItemInHandLayer. SwordItem still exists here.
 */
public final class OldAnimations {
	public static final String MOD = "oldanimations";

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

	/** Hooks call this from their catch: log once, then the whole mod falls back to vanilla. Never throws, and
	 *  this class's static state holds no version-specific type, so it always loads (1.21.2/1.21.3 too). */
	public static void fail(String where, Throwable t) {
		if (!broken) {
			broken = true;
			try {
				OriginClient.LOGGER.warn("Origin 1.7 Animations: {} failed, switching to vanilla animations", where, t);
			} catch (Throwable ignored) {
				// fail() runs inside every hook's catch: it must never throw itself
			}
		}
	}

	public static boolean isSword(ItemStack s) {
		return s.getItem() instanceof SwordItem;
	}

	/** Shield, or any modded item that blocks like one. */
	public static boolean isShield(ItemStack s) {
		return !s.isEmpty() && s.getUseAnimation() == ItemUseAnimation.BLOCK;
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
		ps.mulPose(Axis.XP.rotationDegrees(f1 * -80F));
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
		renderer.renderItem(p, stack, ctx, !right, ps, buffers, light);
	}

	/**
	 * A flat (2D) item 1.7 drew with its fixed transform. Blocks, 3D/custom models (shield,
	 * trident), maps and items with a use pose 1.7 never had (crossbow, spyglass, horn,
	 * brush, trident, and 1.21.2's bundle) stay vanilla.
	 *
	 * 1.21.4: maps are anything with a map id (vanilla's own map-hand test here). The model test
	 * resolves the stack into one reused render state (no allocation per frame), the same
	 * resolve vanilla's renderItem does for this hand anyway.
	 * 1.21.2/1.21.3 (same jar): ItemModelResolver and ItemStackRenderState don't exist yet (1.21.4's
	 * item-model rework), so the model test lives in ModelProbe, a class only loaded on first use;
	 * when it can't link, flatness falls back to "not a block item and not a shield" (what
	 * item/generated covers in vanilla; flat-model block items like saplings then stay vanilla).
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
		if (!noModelProbe) {
			try {
				return ModelProbe.isFlat(stack, e);
			} catch (LinkageError err) {
				// NoClassDefFoundError / NoSuchMethodError on 1.21.2/1.21.3: never retried (a class whose
				// init failed throws again on every touch).
				noModelProbe = true;
				OriginClient.LOGGER.info("Origin 1.7 Animations: no item-model resolver on this Minecraft"
						+ " (pre-1.21.4), using the block-item test for flat items");
			}
		}
		return !(stack.getItem() instanceof BlockItem) && !isShield(stack);
	}

	/** Set once ModelProbe failed to link (1.21.2/1.21.3); render thread only. */
	private static boolean noModelProbe = false;

	/** Every 1.21.4-only type OldAnimations touches, kept out of its own static state: this class
	 *  links (and builds its probe) only when isFlat first asks, so OldAnimations itself always loads. */
	private static final class ModelProbe {
		/** Reused by isFlat on the render thread only. */
		private static final net.minecraft.client.renderer.item.ItemStackRenderState FLAT_PROBE =
				new net.minecraft.client.renderer.item.ItemStackRenderState();

		static boolean isFlat(ItemStack stack, LivingEntity e) {
			Minecraft.getInstance().getItemModelResolver().updateForTopItem(FLAT_PROBE, stack, ItemDisplayContext.NONE,
					false, e.level(), e, 0);
			return !FLAT_PROBE.isEmpty() && !FLAT_PROBE.isGui3d();
		}
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
		ps.mulPose(Axis.XP.rotationDegrees(-80F));
		ry(ps, i, 60F);
	}

	/** 1.7 bow pull (ItemRenderer, EnumAction.bow). */
	private static void bowTransform(PoseStack ps, float partial, int i, ItemStack stack, LivingEntity p) {
		rz(ps, i, -18F);
		ry(ps, i, -12F);
		ps.mulPose(Axis.XP.rotationDegrees(-8F));
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
		ps.mulPose(Axis.XP.rotationDegrees(h * 10F));
		rz(ps, i, h * 30F);
	}

	private static void ry(PoseStack ps, int i, float deg) {
		ps.mulPose(Axis.YP.rotationDegrees(i * deg));
	}

	private static void rz(PoseStack ps, int i, float deg) {
		ps.mulPose(Axis.ZP.rotationDegrees(i * deg));
	}

	// ---- third person (OldAnimationsThirdPersonMixin) ----

	/** The arm pose to force for this hand, or null for vanilla's (always null with a raised shield). */
	public static HumanoidModel.ArmPose armPose(Player p, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND || !on("thirdPersonBlock") || !on("swordBlock")) {
			return null;
		}
		return swordBlocking(p) ? HumanoidModel.ArmPose.BLOCK : null;
	}

	/**
	 * PlayerRenderer.extractRenderState TAIL: the layer only gets the render state, so the
	 * sword-block fact is decided here, while the player still exists (swordBlocking compares it
	 * to mc.player: only the local player's own right-click can be seen). Always written (the
	 * renderer reuses one state object for every player it draws). turn = the sword arm while
	 * sword blocking, else null; a raised shield is never touched.
	 */
	public static void extractThirdPerson(AbstractClientPlayer p, OldAnimationsState state) {
		boolean turn = on("thirdPersonBlock") && on("swordBlock") && swordBlocking(p);
		state.originclient$setOldTurnArm(turn ? p.getMainArm() : null);
	}

	/**
	 * Right after translateToHand: 1.7's block turn for the held sword, pivoting on 1.7's item
	 * anchor in the arm (T(-1/16, 7/16, 1/16)), so the sword sits across the body like 1.7's
	 * T(0.05,0,-0.1) RY-50 RX-10 RZ-60. The arm angles themselves come from the BLOCK pose.
	 */
	public static void thirdPersonBlock(Object state, HumanoidArm arm, PoseStack ps) {
		if (!(state instanceof OldAnimationsState s) || arm != s.originclient$oldTurnArm()) {
			return;
		}
		int i = arm == HumanoidArm.RIGHT ? 1 : -1;
		ps.translate(i * -0.0625F, 0.4375F, 0.0625F);
		ps.translate(i * 0.05F, 0F, -0.1F);
		ry(ps, i, -50F);
		ps.mulPose(Axis.XP.rotationDegrees(-10F));
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
			{"net.minecraft.client.renderer.entity.player.PlayerRenderer", "originclient$oldArmState"},
			{"net.minecraft.client.renderer.entity.state.PlayerRenderState", "originclient$setOldTurnArm"},
			{"net.minecraft.client.renderer.entity.layers.ItemInHandLayer", "originclient$blockTurn"},
	};

	/** Hooks written to 1.21.4's item-render-state shapes: on 1.21.2/1.21.3 (same jar) they don't bind
	 *  (defaultRequire 0) and that part stays vanilla there (third-person sword turn). The CI in-world
	 *  step on a covered version logs these instead of failing on them. */
	public static final java.util.Set<String> HOOKS_1214_ONLY =
			java.util.Set.of("originclient$blockTurn", "originclient$oldArmPose");

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
