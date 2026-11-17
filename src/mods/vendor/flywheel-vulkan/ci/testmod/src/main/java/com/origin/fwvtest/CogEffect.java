package com.origin.fwvtest;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

/**
 * Cogs in front of where the player stood when the level loaded, drawn only through Flywheel:
 * <ul>
 * <li>a wall of 20 x 12 TRANSFORMED cogs facing the camera, each spinning (pose rewritten per frame),</li>
 * <li>an 8 x 8 floor of ORIENTED cogs turning the other way, lit from the level,</li>
 * <li>a 4 x 4 group in a VisualEmbedding whose whole transform rotates (how Create draws
 * contraptions and trains).</li>
 * </ul>
 * Three meshes on three block textures, one material (Materials.SOLID_BLOCK).
 */
final class CogEffect implements Effect {
	static final int WALL_COLS = 20, WALL_ROWS = 12, FLOOR = 8, GROUP = 4;
	static final int TOTAL = WALL_COLS * WALL_ROWS + FLOOR * FLOOR + GROUP * GROUP;

	private final Level level;
	private final Vec3 eye;
	private final Direction facing;

	CogEffect(Level level, Vec3 eye, Direction facing) {
		this.level = level;
		this.eye = eye;
		this.facing = facing;
	}

	@Override
	public LevelAccessor level() {
		return level;
	}

	@Override
	public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
		return new Visual(ctx);
	}

	private static SingleMeshModel model(String texture) {
		var atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
		var sprite = atlas.getSprite(ResourceLocation.withDefaultNamespace(texture));
		return new SingleMeshModel(new SimpleQuadMesh(CogMesh.build(sprite), "fwv_cog_" + texture), Materials.SOLID_BLOCK);
	}

	private final class Visual implements EffectVisual<CogEffect>, SimpleDynamicVisual {
		private final Vec3i origin;
		private final TransformedInstance[] wall = new TransformedInstance[WALL_COLS * WALL_ROWS];
		private final float[] wallPos = new float[wall.length * 3];
		private final OrientedInstance[] floor = new OrientedInstance[FLOOR * FLOOR];
		private final VisualEmbedding embedding;
		private final TransformedInstance[] group = new TransformedInstance[GROUP * GROUP];
		private final Matrix4f facingBasis;
		private final Quaternionf faceCamera;
		private final Vec3 groupAnchor;
		private final Matrix4f pose = new Matrix4f();
		private final Quaternionf q = new Quaternionf();

		Visual(VisualizationContext ctx) {
			origin = ctx.renderOrigin();
			Direction right = facing.getClockWise();
			float fx = facing.getStepX(), fz = facing.getStepZ(), rx = right.getStepX(), rz = right.getStepZ();
			// Columns: right, up, toward the camera (right x up = -facing, right-handed).
			facingBasis = new Matrix4f(rx, 0, rz, 0, 0, 1, 0, 0, -fx, 0, -fz, 0, 0, 0, 0, 1);
			faceCamera = new Quaternionf().rotationTo(0, 1, 0, -fx, 0, -fz);

			Instancer<TransformedInstance> wallI = ctx.instancerProvider().instancer(InstanceTypes.TRANSFORMED, model("block/iron_block"));
			double baseX = Math.floor(eye.x) + 0.5 + fx * 12 - rx * WALL_COLS / 2.0;
			double baseZ = Math.floor(eye.z) + 0.5 + fz * 12 - rz * WALL_COLS / 2.0;
			double baseY = Math.floor(eye.y) - 4;
			for (int row = 0; row < WALL_ROWS; row++) {
				for (int col = 0; col < WALL_COLS; col++) {
					int i = row * WALL_COLS + col;
					TransformedInstance t = wallI.createInstance();
					t.light(LightTexture.FULL_BRIGHT);
					t.color(255, 255 - row * 12, 140 + col * 5);
					wall[i] = t;
					wallPos[i * 3] = (float) (baseX + rx * col - origin.getX() - 0.5);
					wallPos[i * 3 + 1] = (float) (baseY + row - origin.getY());
					wallPos[i * 3 + 2] = (float) (baseZ + rz * col - origin.getZ() - 0.5);
				}
			}

			Instancer<OrientedInstance> floorI = ctx.instancerProvider().instancer(InstanceTypes.ORIENTED, model("block/copper_block"));
			int feetY = (int) Math.floor(eye.y - 1.62);
			for (int a = 0; a < FLOOR; a++) {
				for (int b = 0; b < FLOOR; b++) {
					BlockPos pos = BlockPos.containing(eye.x + fx * (3 + a) + rx * (b - FLOOR / 2), feetY, eye.z + fz * (3 + a) + rz * (b - FLOOR / 2));
					OrientedInstance o = floorI.createInstance();
					o.position(pos.getX() - origin.getX(), pos.getY() - origin.getY() - 0.4f, pos.getZ() - origin.getZ());
					o.pivot(0.5f, 0.5f, 0.5f);
					o.light(LevelRenderer.getLightColor(level, pos));
					floor[a * FLOOR + b] = o;
					o.setChanged();
				}
			}

			groupAnchor = new Vec3(Math.floor(eye.x) + fx * 7 + rx * 3, Math.floor(eye.y) + 1, Math.floor(eye.z) + fz * 7 + rz * 3);
			embedding = ctx.createEmbedding(BlockPos.containing(groupAnchor));
			Instancer<TransformedInstance> groupI = embedding.instancerProvider().instancer(InstanceTypes.TRANSFORMED, model("block/gold_block"));
			for (int i = 0; i < group.length; i++) {
				TransformedInstance t = groupI.createInstance();
				t.light(LightTexture.FULL_BRIGHT);
				// Static inside the group: each cog faces local +Z; the embedding moves them all.
				t.setIdentityTransform().translate(i % GROUP, i / GROUP, 0)
						.translate(0.5f, 0.5f, 0.5f).rotate(new Quaternionf().rotationX((float) (Math.PI / 2))).translate(-0.5f, -0.5f, -0.5f);
				t.setChanged();
				group[i] = t;
			}
			FwvTestMod.LOGGER.info("[fwv-test] cog visual created: {} instances ({} transformed, {} oriented, {} embedded), render origin {}",
					TOTAL, wall.length, floor.length, group.length, origin);
		}

		@Override
		public void beginFrame(DynamicVisual.Context ctx) {
			float t = (level.getGameTime() + ctx.partialTick()) * 0.08f;
			for (int i = 0; i < wall.length; i++) {
				wall[i].setIdentityTransform()
						.translate(wallPos[i * 3], wallPos[i * 3 + 1], wallPos[i * 3 + 2])
						.translate(0.5f, 0.5f, 0.5f)
						.rotate(faceCamera)
						.rotate(q.rotationY(t + i * 0.13f))
						.translate(-0.5f, -0.5f, -0.5f);
				wall[i].setChanged();
			}
			q.rotationY(-t);
			for (OrientedInstance o : floor) {
				o.rotation(q);
				o.setChanged();
			}
			// Contraption-style: the whole group turns about its centre, facing the camera.
			pose.translation((float) (groupAnchor.x - origin.getX()), (float) (groupAnchor.y - origin.getY()), (float) (groupAnchor.z - origin.getZ()))
					.mul(facingBasis)
					.translate(GROUP / 2f, GROUP / 2f, 0.5f)
					.rotateZ(t * 0.5f)
					.translate(-GROUP / 2f, -GROUP / 2f, -0.5f);
			embedding.transforms(pose, new Matrix3f(pose).normal());
		}

		@Override
		public void update(float partialTick) {
		}

		@Override
		public void delete() {
			for (TransformedInstance t : wall) t.delete();
			for (OrientedInstance o : floor) o.delete();
			for (TransformedInstance t : group) t.delete();
			embedding.delete();
		}
	}
}
