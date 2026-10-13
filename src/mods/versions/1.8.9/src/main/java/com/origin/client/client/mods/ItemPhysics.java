package com.origin.client.client.mods;

import com.origin.client.client.gui.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemTransformVec3f;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.MathHelper;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Item Physics (Lunar parity): dropped items stop floating and spinning in place.
 * In the air they tumble end over end around their own centre, each on its own
 * slight tilt (Rotation Speed); once they land -- or float on water -- they ease
 * down flat: flat items onto their back, blocks onto their nearest face, each at
 * its own fixed random heading. Render-only: the entity, its hitbox and pickup
 * are untouched.
 *
 * Hooked by RenderEntityItemMixin at the RETURN of RenderEntityItem's private
 * pose helper (func_177077_a: translate to the entity + bob, spin about Y, and
 * for flat items the stack offset -- which the mixin takes back off before calling
 * here and puts back after, so the copies still pile on top of the new pose).
 * This first UNDOES exactly vanilla's bob + spin (same maths as vanilla 1.8.9),
 * then applies the physics pose. Items keep their GROUND model transform (size,
 * the per-model offset), applied later by vanilla.
 *
 * The physics pose is one shape for every state, so air -> ground can ease:
 *   translate(height) . [about the model centre: tilt(Z), tumble(X)]
 * The model centre sits ground.translation.y above the pivot (the ground
 * transform translates before the model's own -0.5 centring), so rotating about
 * the pivot itself would swing the item round in a circle instead of tumbling it.
 *
 * 1.8.9: the pose is the fixed-function GL matrix (gui/Pose); EntityItem's bob
 * offset is hoverStart, its age getAge(); the ground transform is the baked
 * model's ItemCameraTransforms.ground (lwjgl Vector3f fields).
 */
public final class ItemPhysics {
	private static final String MOD = "itemphysics";
	// Degrees per tick at Rotation Speed 1.0x (default 0.5x = half a turn a second).
	private static final float DEG_PER_TICK = 18f;
	// Easing time constant, in ticks, for landing (~0.1 s to settle flat).
	private static final float SETTLE_TICKS = 1.5f;
	// Largest per-item tilt of the tumble axis while airborne.
	private static final float MAX_TILT_DEG = 25f;
	private static final float RAD_TO_DEG = 180F / (float) Math.PI;

	/** Per-item animation state; weak keys, so unloaded items drop out by themselves. */
	private static final class State {
		float time;     // age + partialTick when last drawn
		float tumble;   // degrees about X
		float tilt;     // degrees about Z
		float height;   // pivot lift above the entity origin
	}

	private static final Map<EntityItem, State> STATES = new WeakHashMap<>();

	private ItemPhysics() {
	}

	public static boolean active() {
		return Mods.on(MOD);
	}

	/** Same as {@link #apply(EntityItem, float, Pose, boolean)} with vanilla's bobbing on. */
	public static void apply(EntityItem e, float partialTick, Pose pose) {
		apply(e, partialTick, pose, true);
	}

	/**
	 * @param bobbing whether vanilla applied its bob (RenderEntityItem.shouldBob(),
	 *                Forge's switch -- true for vanilla's renderer)
	 */
	public static void apply(EntityItem e, float partialTick, Pose pose, boolean bobbing) {
		if (!Mods.on(MOD) || e.getEntityItem() == null) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		IBakedModel model = mc.getRenderItem().getItemModelMesher().getItemModel(e.getEntityItem());
		if (model == null) {
			return;
		}
		ItemTransformVec3f ground = model.getItemCameraTransforms().getTransform(ItemCameraTransforms.TransformType.GROUND);
		float scaleY = ground.scale.y;
		float scaleZ = ground.scale.z;
		float centre = ground.translation.y;
		boolean block = model.isGui3d();

		// 1) undo vanilla: rotate(spin) was applied last, so it comes off first.
		float now = (float) e.getAge() + partialTick;
		float bob = bobbing ? MathHelper.sin(now / 10.0F + e.hoverStart) * 0.1F + 0.1F : 0F;
		float spinDeg = (now / 20.0F + e.hoverStart) * RAD_TO_DEG;
		pose.mulPose(Pose.Axis.YP.rotationDegrees(-spinDeg));
		pose.translate(0.0, -(bob + 0.25F * scaleY), 0.0);

		// 2) where this item is heading: tumbling in the air, or lying at rest.
		boolean resting = e.onGround || e.isInWater();
		float restHeight;
		if (block) {
			// the model's bottom face (centre - half its scaled unit cube) on the ground
			restHeight = -(centre - 0.5F * scaleY);
		} else {
			// lying on its back (local +Z -> world up, so vanilla's stacked copies pile
			// upward), lifted by the stack's half-depth + the sprite's own thickness.
			// 1.8.9: copies step 0.046875 x ground scale Z apart.
			int copies = renderedCopies(e.getEntityItem().stackSize);
			float halfStack = 0.046875F * (copies - 1) * 0.5F * scaleZ;
			restHeight = halfStack + scaleZ / 32F + 0.01F - centre;
		}

		State st = STATES.get(e);
		float dt = st == null ? -1 : now - st.time;
		if (st == null || dt < 0 || dt > 20) {
			// first sight (or a long gap): start in place, no animation into it
			st = new State();
			st.tumble = resting ? restTumble(block, 0) : RAD_TO_DEG * e.hoverStart;
			st.tilt = resting ? 0 : tiltFor(e);
			st.height = resting ? restHeight : 0.25F * scaleY;
			STATES.put(e, st);
			dt = 0;
		}
		st.time = now;

		if (!resting) {
			st.tumble = (st.tumble + dt * DEG_PER_TICK * (float) Mods.num(MOD, "rotationSpeed")) % 360f;
			st.tilt = ease(st.tilt, tiltFor(e), dt);
			// vanilla's resting height without the bob
			st.height = ease(st.height, 0.25F * scaleY, dt);
		} else {
			float target = restTumble(block, st.tumble);
			st.tumble = ease(st.tumble, target, dt);
			st.tilt = ease(st.tilt, 0f, dt);
			st.height = ease(st.height, restHeight, dt);
		}

		// 3) physics pose. hoverStart is a per-entity random angle: a stable heading.
		pose.mulPose(Pose.Axis.YP.rotation(e.hoverStart));
		pose.translate(0.0, st.height + centre, 0.0);
		pose.mulPose(Pose.Axis.ZP.rotationDegrees(st.tilt));
		pose.mulPose(Pose.Axis.XP.rotationDegrees(st.tumble));
		pose.translate(0.0, -centre, 0.0);
	}

	/**
	 * The resting tumble angle nearest the current one: blocks settle on whichever
	 * face is closest (every 90 deg), flat items always on their back (-90 deg, so
	 * the stack piles upward rather than into the floor).
	 */
	private static float restTumble(boolean block, float current) {
		if (block) {
			return Math.round(current / 90f) * 90f;
		}
		return current + MathHelper.wrapAngleTo180_float(-90f - current);
	}

	/** A fixed tilt per item, from its random bob offset (-MAX_TILT..MAX_TILT). */
	private static float tiltFor(EntityItem e) {
		return MathHelper.sin(e.hoverStart * 3.7F) * MAX_TILT_DEG;
	}

	/** Frame-rate independent approach of value toward target over dt ticks. */
	private static float ease(float value, float target, float dt) {
		float k = 1f - (float) Math.exp(-dt / SETTLE_TICKS);
		return value + (target - value) * k;
	}

	/** Vanilla's RenderEntityItem.func_177078_a (copies drawn for a stack size). */
	public static int renderedCopies(int count) {
		if (count > 48) {
			return 5;
		} else if (count > 32) {
			return 4;
		} else if (count > 16) {
			return 3;
		}
		return count > 1 ? 2 : 1;
	}
}
