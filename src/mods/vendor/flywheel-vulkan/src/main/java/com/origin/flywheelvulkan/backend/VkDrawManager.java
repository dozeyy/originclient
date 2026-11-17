package com.origin.flywheelvulkan.backend;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import com.origin.flywheelvulkan.FlywheelVulkanClient;
import com.origin.flywheelvulkan.shader.VkLayout;
import com.origin.flywheelvulkan.vk.HostBuffer;
import com.origin.flywheelvulkan.vk.Retirement;
import com.origin.flywheelvulkan.vk.VkStateSync;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.compile.ContextShader;
import dev.engine_room.flywheel.backend.engine.AbstractInstancer;
import dev.engine_room.flywheel.backend.engine.CommonCrumbling;
import dev.engine_room.flywheel.backend.engine.DrawManager;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.MaterialEncoder;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The origin:vulkan draw manager: Flywheel's InstancedDrawManager (MIT, Copyright (c) 2021-2024
 * Jozufozu; see LICENSE_flywheel-vulkan) with every GL call replaced by Vulkan recorded on
 * VulkanMod's own command buffer, inside VulkanMod's bound render pass.
 *
 * <p>Per frame: CPU-cull every instancer into one storage buffer (one region per instancer),
 * copy Flywheel's five uniform blocks and the light storage into that frame slot's buffers, then
 * one vkCmdDrawIndexed per (instancer, mesh) with firstInstance pointing at the region. Every
 * buffer the GPU reads exists once per VulkanMod frame in flight and is only rewritten after
 * VulkanMod has waited on that slot's fence.
 */
public final class VkDrawManager extends DrawManager<VkInstancer<?>> {
	/** Set when this backend failed once; the backend then reports unsupported for the session. */
	private static volatile boolean failed;

	public static boolean failed() {
		return failed;
	}

	private static final Comparator<VkDraw> DRAW_COMPARATOR = Comparator.comparingInt(VkDraw::bias)
			.thenComparingInt(VkDraw::indexOfMeshInModel)
			.thenComparing(VkDraw::material, MaterialRenderState.COMPARATOR);

	/** The largest minUniformBufferOffsetAlignment the Vulkan spec allows, so valid on every device. */
	private static final long UBO_ALIGN = 256;
	private static final int SETS_PER_POOL = 128;
	private static final int STORAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
	private static final int SHADER_READ = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
	private static final int ALL_STAGES = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
	/** Embedded pose (mat4) + normal (mat3 as 3 x vec4) as EmbeddedEnvironment.flush writes them. */
	private static final int MATRIX_BYTES = 112;

	private record SetKey(long ubo, long instanceBuffer, long diffuseView, long diffuseSampler, long crumblingView, long crumblingSampler) {
	}

	private static final class Slot {
		HostBuffer ubo;
		HostBuffer instances;
		// Shadow pass (PackDraw.SHADOW): Flywheel's uniforms with this frame's camera, last frame's instances.
		HostBuffer shadowUbo;
		HostBuffer shadowInstances;
		HostBuffer crumbling;
		long crumblingCursor;
		HostBuffer lightLut;
		HostBuffer lightSections;
		int lightVersion = -1;
		long lightBytes = -1;
		int lutVersion = -1;
		final List<Long> pools = new ArrayList<>();
		int poolIndex;
		final Map<SetKey, Long> sets = new HashMap<>();
	}

	private final List<VkDraw> allDraws = new ArrayList<>();
	private final List<VkDraw> draws = new ArrayList<>();
	private boolean needSort;

	private final VkMeshPool meshPool = new VkMeshPool();
	private final VkShaders shaders = new VkShaders();
	private final VkPipelines pipelines = new VkPipelines();
	private Retirement retirement;
	private Slot[] slots;
	private Slot slot;
	private int slotIndex;

	private final MemoryBlock[] uniformBlocks;
	private final long[] uniformOffsets;
	private final long uniformBytes;

	private int lightVersion;
	private int lutVersion;
	private IntArrayList lut = new IntArrayList();

	private final ByteBuffer push = MemoryUtil.memCalloc(VkLayout.PUSH_SIZE);
	private final InstanceRegions regions = new InstanceRegions();
	private final SimpleMaterial.Builder crumblingMaterial = SimpleMaterial.builder();
	private final FrustumIntersection frustum = new FrustumIntersection();
	private final Matrix4f frustumScratch = new Matrix4f();
	private final Matrix4f embedPose = new Matrix4f();
	private final float[] embedFloats = new float[16];
	private final Vector4f sphere = new Vector4f();
	private final Vector3f scratch = new Vector3f();

	// Frame bookkeeping: Flywheel calls render then renderCrumbling with the same RenderContext once per frame.
	private Object frameCtx;
	private Object renderedCtx;
	private boolean uploaded;
	private VulkanImage overlayImage;
	private VulkanImage lightImage;
	private boolean loggedFirstDraw;
	private boolean deleted;

	// ---- shader pack (null without Origin's VulkaIris fork: phase 1 only)
	private final PackDraw pack;
	private final Runnable shadowCaster = this::castShadows;
	/** PackDraw's generation when the caster was last (re-)registered. */
	private int casterGeneration = Integer.MIN_VALUE;
	/** The slot the shadow pass already began this frame (it runs before Flywheel's render), or -1. */
	private int pendingEarlySlot = -1;
	// Last frame's unculled instances + draws, for the next frame's shadow pass (which runs first).
	private final InstanceRegions shadowRegions = new InstanceRegions();
	private long snapInstances;
	private long snapInstanceCapacity;
	private long snapInstanceBytes;
	private VkDraw[] snapDraws = new VkDraw[64];
	private long snapMatrices = MemoryUtil.nmemAlloc(64L * MATRIX_BYTES);
	private boolean[] snapEmbedded = new boolean[64];
	private int snapCount;
	private int snapOriginX, snapOriginY, snapOriginZ;
	private boolean loggedFirstShadow;
	private boolean loggedFirstPackDraw;
	/** _flw_cameraPos inside Flywheel's frame block (std140, after the planes, cull data and 9 mat4). */
	private static final int FRAME_CAMERA_POS = 720;

	public VkDrawManager() {
		uniformBlocks = FlywheelInternals.uniformBlocks();
		uniformOffsets = new long[uniformBlocks.length];
		long cursor = 0;
		for (int i = 0; i < uniformBlocks.length; i++) {
			uniformOffsets[i] = cursor;
			cursor = InstanceRegions.alignUp(cursor + uniformBlocks[i].size(), UBO_ALIGN);
		}
		uniformBytes = cursor;
		ensureSlots();
		pack = PackBridge.AVAILABLE ? new PackDraw(pipelines) : null;
		if (pack != null) {
			registerShadowCaster();
		}
	}

	private void registerShadowCaster() {
		try {
			PackBridge.addShadowCaster(shadowCaster);
		} catch (Throwable t) {
			FlywheelVulkanClient.LOGGER.warn("[flywheel-vulkan] could not register the shadow caster: {}", t.toString());
		}
	}

	/** Meshes this backend holds (shown in Flywheel's debug info instead of a GL MeshPool's). */
	public int meshCount() {
		return meshPool.meshes().size();
	}

	// ---------------------------------------------------------------- frame

	void renderFrame(RenderContext ctx, Vec3i renderOrigin, LightStorage light, EnvironmentStorage environments) {
		if (deleted || ctx == renderedCtx) {
			return;
		}
		renderedCtx = ctx;

		// DrawManager.render: initialize the instancers created since last frame.
		render(light, environments);

		instancers.values().removeIf(instancer -> {
			if (instancer.instanceCount() == 0) {
				instancer.delete();
				return true;
			}
			return false;
		});
		needSort |= allDraws.removeIf(VkDraw::deleted);
		if (needSort) {
			allDraws.sort(DRAW_COMPARATOR);
			draws.clear();
			// Flywheel draws order-independent transparency after everything else; no OIT pass here,
			// so those draws keep that order and blend as plain translucency.
			for (VkDraw d : allDraws) {
				if (d.material().transparency() != Transparency.ORDER_INDEPENDENT) draws.add(d);
			}
			for (VkDraw d : allDraws) {
				if (d.material().transparency() == Transparency.ORDER_INDEPENDENT) draws.add(d);
			}
			needSort = false;
		}

		if (!Renderer.isRecording()) {
			return;
		}
		RenderPass renderPass = Renderer.getInstance().getBoundRenderPass();
		if (renderPass == null) {
			return;
		}
		frame(ctx);
		meshPool.flush(retirement, slotIndex);
		if (draws.isEmpty() || meshPool.vertexBuffer() == 0) {
			return;
		}
		upload(light);
		cullAndWrite(ctx, renderOrigin, environments);
		record(renderPass, environments);
		if (pack != null && pack.active()) {
			snapshot(renderOrigin, environments);
		} else {
			clearSnapshot();
		}
	}

	/** First call with a new RenderContext starts this manager's work in VulkanMod's current frame slot. */
	private void frame(RenderContext ctx) {
		if (ctx == frameCtx) {
			return;
		}
		frameCtx = ctx;
		ensureSlots();
		slotIndex = Renderer.getCurrentFrame();
		slot = slots[slotIndex];
		if (pendingEarlySlot != slotIndex) {
			beginSlot(slotIndex);
		}
		pendingEarlySlot = -1;
		uploaded = false;
	}

	/**
	 * VulkanMod waited on this slot's fence: what the GPU read from it last time may go. Runs once
	 * per frame, from whichever comes first: the shadow pass (castShadows) or Flywheel's render.
	 */
	private void beginSlot(int k) {
		Slot s = slots[k];
		retirement.beginFrame(k);
		VkDevice dev = Vulkan.getVkDevice();
		for (long pool : s.pools) {
			vkResetDescriptorPool(dev, pool, 0);
		}
		s.poolIndex = 0;
		s.sets.clear();
		s.crumblingCursor = 0;
		if (pack != null) {
			pack.beginSlot(k);
		}
	}

	/** VulkanMod's frames-in-flight setting can change at runtime (it waits for the device then). */
	private void ensureSlots() {
		int frames = Renderer.getFramesNum();
		if (slots != null && slots.length == frames) {
			return;
		}
		if (slots != null) {
			vkDeviceWaitIdle(Vulkan.getVkDevice());
			destroySlots();
		}
		retirement = new Retirement(frames);
		slots = new Slot[frames];
		for (int i = 0; i < frames; i++) {
			slots[i] = new Slot();
		}
		frameCtx = null;
	}

	private HostBuffer ensure(HostBuffer b, long needed, int usage) {
		if (b != null && b.size >= needed) {
			return b;
		}
		if (b != null) {
			retirement.retire(slotIndex, b::destroy);
		}
		return HostBuffer.create(HostBuffer.grownSize(needed, b == null ? 0 : b.size), usage);
	}

	/** Uniform blocks and light, once per frame (render or crumbling, whichever runs first). */
	private void upload(LightStorage light) {
		if (uploaded) {
			return;
		}
		uploaded = true;

		slot.ubo = ensure(slot.ubo, uniformBytes, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
		for (int i = 0; i < uniformBlocks.length; i++) {
			MemoryUtil.memCopy(uniformBlocks[i].ptr(), slot.ubo.ptr() + uniformOffsets[i], uniformBlocks[i].size());
		}

		// Light: the same data the GL backends upload, versioned so every frame slot catches up.
		if (light.checkNeedsLutRebuildAndClear()) {
			lut = light.createLut();
			lutVersion++;
		}
		BitSet changed = FlywheelInternals.lightChanged(light);
		if (!changed.isEmpty()) {
			changed.clear();
			lightVersion++;
		}
		long sectionBytes = (long) light.capacity() * LightStorage.SECTION_SIZE_BYTES;
		if (slot.lightVersion != lightVersion || slot.lightBytes != sectionBytes || slot.lightSections == null) {
			slot.lightSections = ensure(slot.lightSections, Math.max(sectionBytes, 16), STORAGE);
			if (sectionBytes > 0) {
				MemoryUtil.memCopy(light.arena.indexToPointer(0), slot.lightSections.ptr(), sectionBytes);
			}
			slot.lightVersion = lightVersion;
			slot.lightBytes = sectionBytes;
		}
		if (slot.lutVersion != lutVersion || slot.lightLut == null) {
			int n = lut.size();
			slot.lightLut = ensure(slot.lightLut, Math.max((long) n * 4, 16), STORAGE);
			long p = slot.lightLut.ptr();
			// An empty LUT reads as zeros: light.glsl treats that as "no section" (a miss).
			MemoryUtil.memSet(p, 0, Math.min(slot.lightLut.size, 16));
			for (int i = 0; i < n; i++) {
				MemoryUtil.memPutInt(p + 4L * i, lut.getInt(i));
			}
			slot.lutVersion = lutVersion;
		}
	}

	private void cullAndWrite(RenderContext ctx, Vec3i origin, EnvironmentStorage environments) {
		Vec3 cam = ctx.camera().getPosition();
		CullMath.frustum(ctx.viewProjection(), cam.x, cam.y, cam.z, origin.getX(), origin.getY(), origin.getZ(),
				frustumScratch, frustum);

		long upper = 0;
		for (VkInstancer<?> instancer : instancers.values()) {
			upper += (long) (instancer.instanceCountUnsafe() + 1) * instancer.strideBytes;
		}
		slot.instances = ensure(slot.instances, Math.max(upper, 16), STORAGE);
		long base = slot.instances.ptr();

		regions.reset();
		for (VkInstancer<?> instancer : instancers.values()) {
			instancer.visibleCount = 0;
			if (instancer.draws.isEmpty()) {
				continue;
			}
			long offset = regions.add(instancer.instanceCountUnsafe(), instancer.strideBytes);
			instancer.regionOffset = offset;
			Matrix4f pose = instancer.key.environment() instanceof EmbeddedEnvironment e ? embedPose(environments, e) : null;
			instancer.visibleCount = instancer.writeVisible(base + offset, frustum, pose, sphere, scratch);
		}
	}

	private Matrix4f embedPose(EnvironmentStorage environments, EmbeddedEnvironment e) {
		long p = environments.arena.indexToPointer(e.matrixIndex());
		for (int i = 0; i < 16; i++) {
			embedFloats[i] = MemoryUtil.memGetFloat(p + 4L * i);
		}
		return embedPose.set(embedFloats);
	}

	// ---------------------------------------------------------------- recording

	private void record(RenderPass renderPass, EnvironmentStorage environments) {
		for (VkDraw d : draws) {
			if (shaders.prepare(d.material())) {
				pipelines.retireAll(retirement, slotIndex);
				if (pack != null) {
					pack.invalidatePrograms(retirement, slotIndex);
				}
			}
		}
		boolean packMain = pack != null && pack.begin(PackDraw.MAIN, retirement, slotIndex, renderPass);
		if (pack != null && pack.generation() != casterGeneration) {
			// New pack or reload: make sure the shadow caster is registered (idempotent in Vulkairis), so
			// shadows come back even if an earlier pack's shadow pass dropped it.
			casterGeneration = pack.generation();
			registerShadowCaster();
		}
		int packDraws = 0;

		VkCommandBuffer cb = Renderer.getCommandBuffer();
		VulkanImage white = VTextureSelector.getWhiteTexture();
		long boundPipeline = 0;
		long boundLayout = 0;
		long boundSet = 0;
		long boundPackSet = 0;
		boolean offset = false;
		int drawCount = 0;
		long instanceCount = 0;
		beginLightAndOverlay(white);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkCmdBindVertexBuffers(cb, 0, stack.longs(meshPool.vertexBuffer()), stack.longs(0));
			vkCmdBindIndexBuffer(cb, meshPool.indexBuffer(), 0, VK_INDEX_TYPE_UINT32);
			for (VkDraw draw : draws) {
				VkInstancer<?> instancer = draw.instancer;
				if (instancer.visibleCount == 0 || !draw.mesh.drawable()) {
					continue;
				}
				Material material = draw.material();
				VulkanImage diffuse = texture(material.texture(), material.blur(), material.mipmap(), true);
				if (diffuse == null) {
					continue;
				}
				var environment = instancer.key.environment();
				long pipeline = 0;
				long layout = pipelines.pipelineLayout;
				long packSet = 0;
				if (packMain) {
					// The pack's gbuffers_block; a draw whose pack pipeline won't build keeps phase 1.
					pipeline = packPipeline(draw, PackDraw.MAIN, renderPass);
					if (pipeline != 0) {
						// Never throws; a set that can't be built switches the role off for this pack and
						// this draw and the rest of the frame go back to phase 1 (origin:vulkan stays on).
						packSet = pack.descriptorSet(PackDraw.MAIN, slotIndex, diffuse, lightImage, white);
						if (pack.failed(PackDraw.MAIN)) {
							packMain = false;
							pipeline = 0;
							packSet = 0;
						} else {
							layout = pack.layout(PackDraw.MAIN);
							packDraws++;
						}
					}
				}
				if (pipeline == 0) {
					pipeline = pipeline(renderPass, instancer.key.type(), environment.contextShader(), material);
				}
				long set = descriptorSet(slot.ubo, slot.instances.buffer, diffuse, white);
				if (pipeline != boundPipeline) {
					vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
					boundPipeline = pipeline;
				}
				if (layout != boundLayout) {
					boundLayout = layout;
					boundSet = 0;
					boundPackSet = 0;
				}
				if (set != boundSet) {
					vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(set), null);
					boundSet = set;
				}
				if (packSet != 0 && packSet != boundPackSet) {
					vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, PackDraw.SET, stack.longs(packSet), null);
					boundPackSet = packSet;
				}
				long matrices = environment instanceof EmbeddedEnvironment e ? environments.arena.indexToPointer(e.matrixIndex()) : 0;
				pushConstants(cb, layout, matrices, material, draw.mesh.baseVertex);
				offset = polygonOffset(material.polygonOffset(), offset);
				vkCmdDrawIndexed(cb, draw.mesh.indexCount(), instancer.visibleCount, draw.mesh.firstIndex, draw.mesh.baseVertex,
						InstanceRegions.firstInstance(instancer.regionOffset, instancer.strideBytes));
				drawCount++;
				instanceCount += instancer.visibleCount;
			}
		} finally {
			polygonOffset(false, offset);
			if (boundPipeline != 0) {
				VkStateSync.afterForeignBind();
			}
			endLightAndOverlay();
		}
		if (!loggedFirstDraw && drawCount > 0) {
			loggedFirstDraw = true;
			FlywheelVulkanClient.LOGGER.info("[flywheel-vulkan] origin:vulkan first frame drawn: {} draws, {} instances, {} pipelines, render pass {}",
					drawCount, instanceCount, pipelines.size(), Long.toHexString(renderPass.getId()));
		}
		if (packDraws > 0 && !loggedFirstPackDraw) {
			loggedFirstPackDraw = true;
			FlywheelVulkanClient.LOGGER.info("[flywheel-vulkan] origin:vulkan drawing through the shader pack: {} of {} draws use its gbuffers program",
					packDraws, drawCount);
		}
	}

	/** The draw's pack pipeline for a role, cached on the draw until the role's epoch moves. */
	private long packPipeline(VkDraw draw, int role, RenderPass renderPass) {
		int epoch = pack.epoch(role);
		if (draw.packEpoch[role] != epoch) {
			VkShaders.ProgramKey key = draw.programKey();
			draw.packPipeline[role] = pack.pipeline(role, renderPass, key, shaders.get(key), draw.stateKey());
			draw.packEpoch[role] = epoch;
		}
		return draw.packPipeline[role];
	}

	// ---------------------------------------------------------------- shadow pass (shader pack)

	private void clearSnapshot() {
		for (int i = 0; i < snapCount; i++) {
			snapDraws[i] = null;
		}
		snapCount = 0;
	}

	/**
	 * After this frame's draws: every instance unculled (the sun sees what the camera doesn't) and
	 * every draw, for the NEXT frame's shadow pass, which runs at the head of the solid layer, before
	 * Flywheel updates anything. CPU memory only; castShadows copies it into that frame's slot.
	 */
	private void snapshot(Vec3i origin, EnvironmentStorage environments) {
		clearSnapshot();
		long upper = 0;
		for (VkInstancer<?> instancer : instancers.values()) {
			upper += (long) (instancer.instanceCountUnsafe() + 1) * instancer.strideBytes;
		}
		if (upper > snapInstanceCapacity) {
			long size = HostBuffer.grownSize(upper, snapInstanceCapacity);
			snapInstances = MemoryUtil.nmemRealloc(snapInstances, size);
			snapInstanceCapacity = size;
		}
		shadowRegions.reset();
		for (VkInstancer<?> instancer : instancers.values()) {
			instancer.shadowCount = 0;
			if (instancer.draws.isEmpty()) {
				continue;
			}
			long offset = shadowRegions.add(instancer.instanceCountUnsafe(), instancer.strideBytes);
			instancer.shadowOffset = offset;
			instancer.shadowCount = instancer.writeVisible(snapInstances + offset, null, null, sphere, scratch);
		}
		snapInstanceBytes = shadowRegions.totalBytes();
		for (VkDraw draw : draws) {
			VkInstancer<?> instancer = draw.instancer;
			if (instancer.shadowCount == 0 || !draw.mesh.drawable()) {
				continue;
			}
			if (snapCount == snapDraws.length) {
				int n = snapCount * 2;
				snapDraws = java.util.Arrays.copyOf(snapDraws, n);
				snapEmbedded = java.util.Arrays.copyOf(snapEmbedded, n);
				snapMatrices = MemoryUtil.nmemRealloc(snapMatrices, (long) n * MATRIX_BYTES);
			}
			boolean embedded = instancer.key.environment() instanceof EmbeddedEnvironment;
			if (embedded) {
				EmbeddedEnvironment e = (EmbeddedEnvironment) instancer.key.environment();
				MemoryUtil.memCopy(environments.arena.indexToPointer(e.matrixIndex()), snapMatrices + (long) snapCount * MATRIX_BYTES, MATRIX_BYTES);
			}
			snapEmbedded[snapCount] = embedded;
			snapDraws[snapCount] = draw;
			snapCount++;
		}
		snapOriginX = origin.getX();
		snapOriginY = origin.getY();
		snapOriginZ = origin.getZ();
	}

	/**
	 * Vulkairis's shadow pass calls this with the shadow map's render pass bound: last frame's
	 * instances through the pack's shadow program. Flywheel's uniform blocks still hold last frame's
	 * values here (VkEngine updates them at render), so the shadow copy gets this frame's camera,
	 * relative to the snapshot's render origin, which is what the splice subtracts.
	 */
	private void castShadows() {
		try {
			castShadowsOrThrow();
		} catch (Throwable t) {
			// Never throw into Vulkairis's shadow pass: it drops a throwing caster for the session. The
			// shadow role goes off for this pack instead; a pack change or reload turns it back on.
			if (pack != null) {
				pack.disableRole(PackDraw.SHADOW, "shadow pass: " + t);
			}
		}
	}

	private void castShadowsOrThrow() {
		if (deleted || pack == null || snapCount == 0 || !Renderer.isRecording() || slots == null
				|| slots.length != Renderer.getFramesNum() || meshPool.vertexBuffer() == 0) {
			return;
		}
		RenderPass renderPass = Renderer.getInstance().getBoundRenderPass();
		if (renderPass == null) {
			return;
		}
		int k = Renderer.getCurrentFrame();
		if (pendingEarlySlot != k) {
			beginSlot(k);
			pendingEarlySlot = k;
		}
		slotIndex = k;
		slot = slots[k];
		if (slot.lightLut == null || slot.lightSections == null || !pack.begin(PackDraw.SHADOW, retirement, k, renderPass)) {
			return;
		}

		slot.shadowUbo = ensure(slot.shadowUbo, uniformBytes, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
		long ubo = slot.shadowUbo.ptr();
		for (int i = 0; i < uniformBlocks.length; i++) {
			MemoryUtil.memCopy(uniformBlocks[i].ptr(), ubo + uniformOffsets[i], uniformBlocks[i].size());
		}
		Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		long camPos = ubo + uniformOffsets[VkLayout.FRAME] + FRAME_CAMERA_POS;
		MemoryUtil.memPutFloat(camPos, (float) (cam.x - snapOriginX));
		MemoryUtil.memPutFloat(camPos + 4, (float) (cam.y - snapOriginY));
		MemoryUtil.memPutFloat(camPos + 8, (float) (cam.z - snapOriginZ));
		slot.shadowInstances = ensure(slot.shadowInstances, Math.max(snapInstanceBytes, 16), STORAGE);
		MemoryUtil.memCopy(snapInstances, slot.shadowInstances.ptr(), snapInstanceBytes);

		VkCommandBuffer cb = Renderer.getCommandBuffer();
		VulkanImage white = VTextureSelector.getWhiteTexture();
		long layout = pack.layout(PackDraw.SHADOW);
		long boundPipeline = 0;
		long boundSet = 0;
		long boundPackSet = 0;
		int drawn = 0;
		beginLightAndOverlay(white);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkCmdBindVertexBuffers(cb, 0, stack.longs(meshPool.vertexBuffer()), stack.longs(0));
			vkCmdBindIndexBuffer(cb, meshPool.indexBuffer(), 0, VK_INDEX_TYPE_UINT32);
			for (int i = 0; i < snapCount; i++) {
				VkDraw draw = snapDraws[i];
				if (draw.deleted() || !draw.mesh.drawable() || draw.instancer.shadowCount == 0) {
					continue;
				}
				Material material = draw.material();
				VulkanImage diffuse = texture(material.texture(), material.blur(), material.mipmap(), false);
				if (diffuse == null) {
					continue;
				}
				long pipeline = packPipeline(draw, PackDraw.SHADOW, renderPass);
				if (pipeline == 0) {
					continue;
				}
				long packSet = pack.descriptorSet(PackDraw.SHADOW, k, diffuse, lightImage, white);
				if (pack.failed(PackDraw.SHADOW)) {
					break; // switched off for this pack (logged); never draw a pack pipeline without its set
				}
				long set = descriptorSet(slot.shadowUbo, slot.shadowInstances.buffer, diffuse, white);
				if (pipeline != boundPipeline) {
					vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
					boundPipeline = pipeline;
				}
				if (set != boundSet) {
					vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(set), null);
					boundSet = set;
				}
				if (packSet != 0 && packSet != boundPackSet) {
					vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, PackDraw.SET, stack.longs(packSet), null);
					boundPackSet = packSet;
				}
				long matrices = snapEmbedded[i] ? snapMatrices + (long) i * MATRIX_BYTES : 0;
				pushConstants(cb, layout, matrices, material, draw.mesh.baseVertex);
				VkInstancer<?> instancer = draw.instancer;
				vkCmdDrawIndexed(cb, draw.mesh.indexCount(), instancer.shadowCount, draw.mesh.firstIndex, draw.mesh.baseVertex,
						InstanceRegions.firstInstance(instancer.shadowOffset, instancer.strideBytes));
				drawn++;
			}
		} finally {
			if (boundPipeline != 0) {
				VkStateSync.afterForeignBind();
			}
			endLightAndOverlay();
		}
		if (drawn > 0 && !loggedFirstShadow) {
			loggedFirstShadow = true;
			FlywheelVulkanClient.LOGGER.info("[flywheel-vulkan] origin:vulkan casting into the shader pack's shadow map: {} draws", drawn);
		}
	}

	private long pipeline(RenderPass renderPass, InstanceType<?> type, ContextShader context, Material material) {
		VkShaders.ProgramKey program = VkShaders.ProgramKey.of(type, context, material);
		return pipelines.get(renderPass, program, shaders.get(program), VkPipelines.StateKey.of(material));
	}

	/** Push block (VkLayout): embedding matrices (identity outside an embedding), packed material, base vertex. */
	private void pushConstants(VkCommandBuffer cb, long layout, long matrices, Material material, int baseVertex) {
		long p = MemoryUtil.memAddress(push);
		if (matrices != 0) {
			MemoryUtil.memCopy(matrices, p, MATRIX_BYTES);
		} else {
			MemoryUtil.memSet(p, 0, MATRIX_BYTES);
			for (int i = 0; i < 4; i++) {
				MemoryUtil.memPutFloat(p + VkLayout.PUSH_MODEL + 20L * i, 1f); // mat4 diagonal
			}
			for (int i = 0; i < 3; i++) {
				MemoryUtil.memPutFloat(p + VkLayout.PUSH_NORMAL + 20L * i, 1f); // mat3 diagonal, vec4 columns
			}
		}
		MemoryUtil.memPutInt(p + VkLayout.PUSH_MATERIAL, MaterialEncoder.packUberShader(material));
		MemoryUtil.memPutInt(p + VkLayout.PUSH_MATERIAL + 4, MaterialEncoder.packProperties(material));
		MemoryUtil.memPutInt(p + VkLayout.PUSH_BASE_VERTEX, baseVertex);
		vkCmdPushConstants(cb, layout, ALL_STAGES, 0, push);
	}

	/**
	 * MaterialRenderState.setupPolygonOffset through VulkanMod's own depth-bias state, so VulkanMod
	 * stays in sync: VRenderSystem.polygonOffset(factor, units) stores units as the constant and
	 * factor as the slope and records vkCmdSetDepthBias at once (javap 0.6.7).
	 */
	private static boolean polygonOffset(boolean want, boolean on) {
		if (want == on) {
			return on;
		}
		if (want) {
			VRenderSystem.polygonOffset(-1f, -10f);
			VRenderSystem.enablePolygonOffset();
		} else {
			VRenderSystem.disablePolygonOffset();
			VRenderSystem.polygonOffset(0f, 0f);
		}
		return want;
	}

	/** TextureBinder.bindLightAndOverlay: vanilla puts the overlay in shader slot 1 and the lightmap in 2. */
	private void beginLightAndOverlay(VulkanImage white) {
		var gameRenderer = Minecraft.getInstance().gameRenderer;
		gameRenderer.overlayTexture().setupOverlayColor();
		gameRenderer.lightTexture().turnOnLightLayer();
		overlayImage = readable(VRenderSystem.getShaderTexture(1), white);
		lightImage = readable(VRenderSystem.getShaderTexture(2), white);
	}

	private static void endLightAndOverlay() {
		var gameRenderer = Minecraft.getInstance().gameRenderer;
		gameRenderer.overlayTexture().teardownOverlayColor();
		gameRenderer.lightTexture().turnOffLightLayer();
	}

	private static VulkanImage readable(VulkanImage image, VulkanImage fallback) {
		VulkanImage i = image != null ? image : fallback;
		// Same as VulkanMod's DescriptorSets: makes sure the image is in SHADER_READ_ONLY_OPTIMAL
		// (recorded on its upload command buffer, never inside our render pass).
		i.readOnlyLayout();
		return i;
	}

	private static VulkanImage texture(ResourceLocation location, boolean blur, boolean mipmap, boolean filter) {
		AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
		if (filter) {
			texture.setFilter(blur, mipmap);
		}
		VkGlTexture gl = VkGlTexture.getTexture(texture.getId());
		VulkanImage image = gl == null ? null : gl.getVulkanImage();
		if (image != null) {
			image.readOnlyLayout();
		}
		return image;
	}

	private long descriptorSet(HostBuffer ubo, long instanceBuffer, VulkanImage diffuse, VulkanImage crumbling) {
		SetKey key = new SetKey(ubo.buffer, instanceBuffer, diffuse.getImageView(), diffuse.getSampler(), crumbling.getImageView(), crumbling.getSampler());
		Long cached = slot.sets.get(key);
		if (cached != null) {
			return cached;
		}
		long set = allocateSet();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(VkLayout.BINDING_COUNT, stack);
			for (int i = 0; i < VkLayout.UNIFORM_BLOCKS; i++) {
				buffer(writes.get(i), stack, set, i, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, ubo.buffer, uniformOffsets[i], uniformBlocks[i].size());
			}
			buffer(writes.get(VkLayout.INSTANCES), stack, set, VkLayout.INSTANCES, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, instanceBuffer, 0, VK_WHOLE_SIZE);
			buffer(writes.get(VkLayout.LIGHT_LUT), stack, set, VkLayout.LIGHT_LUT, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, slot.lightLut.buffer, 0, VK_WHOLE_SIZE);
			buffer(writes.get(VkLayout.LIGHT_SECTIONS), stack, set, VkLayout.LIGHT_SECTIONS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, slot.lightSections.buffer, 0, VK_WHOLE_SIZE);
			image(writes.get(VkLayout.DIFFUSE_TEX), stack, set, VkLayout.DIFFUSE_TEX, diffuse);
			image(writes.get(VkLayout.OVERLAY_TEX), stack, set, VkLayout.OVERLAY_TEX, overlayImage);
			image(writes.get(VkLayout.LIGHT_TEX), stack, set, VkLayout.LIGHT_TEX, lightImage);
			image(writes.get(VkLayout.CRUMBLING_TEX), stack, set, VkLayout.CRUMBLING_TEX, crumbling);
			vkUpdateDescriptorSets(Vulkan.getVkDevice(), writes, null);
		}
		slot.sets.put(key, set);
		return set;
	}

	private static void buffer(VkWriteDescriptorSet w, MemoryStack stack, long set, int binding, int type, long buffer, long offset, long range) {
		VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
		info.get(0).buffer(buffer).offset(offset).range(range);
		w.sType$Default().dstSet(set).dstBinding(binding).descriptorType(type).descriptorCount(1).pBufferInfo(info);
	}

	private static void image(VkWriteDescriptorSet w, MemoryStack stack, long set, int binding, VulkanImage image) {
		VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
		info.get(0).imageView(image.getImageView()).sampler(image.getSampler()).imageLayout(SHADER_READ);
		w.sType$Default().dstSet(set).dstBinding(binding).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.descriptorCount(1).pImageInfo(info);
	}

	private long allocateSet() {
		VkDevice dev = Vulkan.getVkDevice();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer p = stack.mallocLong(1);
			while (true) {
				if (slot.poolIndex == slot.pools.size()) {
					slot.pools.add(createPool());
				}
				long pool = slot.pools.get(slot.poolIndex);
				int r = vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
						.descriptorPool(pool).pSetLayouts(stack.longs(pipelines.setLayout)), p);
				if (r == VK_SUCCESS) {
					return p.get(0);
				}
				if (r != VK11.VK_ERROR_OUT_OF_POOL_MEMORY && r != VK_ERROR_FRAGMENTED_POOL) {
					VkPipelines.check(r, "vkAllocateDescriptorSets");
				}
				slot.poolIndex++;
			}
		}
	}

	private static long createPool() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
			sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(VkLayout.UNIFORM_BLOCKS * SETS_PER_POOL);
			sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3 * SETS_PER_POOL);
			sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4 * SETS_PER_POOL);
			LongBuffer p = stack.mallocLong(1);
			VkPipelines.check(vkCreateDescriptorPool(Vulkan.getVkDevice(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
					.maxSets(SETS_PER_POOL).pPoolSizes(sizes), null, p), "vkCreateDescriptorPool");
			return p.get(0);
		}
	}

	// ---------------------------------------------------------------- crumbling

	void renderCrumbling(RenderContext ctx, List<Engine.CrumblingBlock> blocks, LightStorage light) {
		if (deleted || !Renderer.isRecording()) {
			return;
		}
		// AbstractInstancer implements InstanceHandleImpl.State, so this rejects other engines' and hidden instances.
		var byType = DrawManager.<VkInstancer<?>>doCrumblingSort(blocks, state -> state instanceof VkInstancer<?> i ? i : null);
		if (byType.isEmpty()) {
			return;
		}
		RenderPass renderPass = Renderer.getInstance().getBoundRenderPass();
		if (renderPass == null || meshPool.vertexBuffer() == 0) {
			return;
		}
		frame(ctx);
		upload(light);

		long need = 0;
		for (var byProgress : byType.values()) {
			for (var list : byProgress.values()) {
				for (var pair : list) {
					need += 2L * pair.getFirst().strideBytes;
				}
			}
		}
		HostBuffer previous = slot.crumbling;
		slot.crumbling = ensure(previous, Math.max(slot.crumblingCursor + need, 16), STORAGE);
		if (slot.crumbling != previous) {
			// Draws already recorded keep the old buffer (retired, not destroyed); start the new one at 0.
			slot.crumblingCursor = 0;
		}

		VkCommandBuffer cb = Renderer.getCommandBuffer();
		VulkanImage white = VTextureSelector.getWhiteTexture();
		long boundPipeline = 0;
		long boundSet = 0;
		boolean offset = false;
		beginLightAndOverlay(white);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkCmdBindVertexBuffers(cb, 0, stack.longs(meshPool.vertexBuffer()), stack.longs(0));
			vkCmdBindIndexBuffer(cb, meshPool.indexBuffer(), 0, VK_INDEX_TYPE_UINT32);
			for (var groupEntry : byType.entrySet()) {
				InstanceType<?> type = groupEntry.getKey().instanceType();
				for (var progressEntry : groupEntry.getValue().int2ObjectEntrySet()) {
					VulkanImage breaking = texture(ModelBakery.BREAKING_LOCATIONS.get(progressEntry.getIntKey()), false, false, false);
					if (breaking == null) {
						continue;
					}
					for (var pair : progressEntry.getValue()) {
						VkInstancer<?> instancer = pair.getFirst();
						int stride = instancer.strideBytes;
						long at = InstanceRegions.alignUp(slot.crumblingCursor, stride);
						if (!instancer.writeOne(slot.crumbling.ptr() + at, pair.getSecond().index)) {
							continue;
						}
						slot.crumblingCursor = at + stride;
						int firstInstance = InstanceRegions.firstInstance(at, stride);
						for (VkDraw draw : instancer.draws) {
							if (!draw.mesh.drawable()) {
								continue;
							}
							CommonCrumbling.applyCrumblingProperties(crumblingMaterial, draw.material());
							if (shaders.prepare(crumblingMaterial)) {
								pipelines.retireAll(retirement, slotIndex);
								if (pack != null) {
									pack.invalidatePrograms(retirement, slotIndex);
								}
								boundPipeline = 0;
							}
							VulkanImage diffuse = texture(crumblingMaterial.texture(), crumblingMaterial.blur(), crumblingMaterial.mipmap(), true);
							if (diffuse == null) {
								continue;
							}
							long pipeline = pipeline(renderPass, type, ContextShader.CRUMBLING, crumblingMaterial);
							long set = descriptorSet(slot.ubo, slot.crumbling.buffer, diffuse, breaking);
							if (pipeline != boundPipeline) {
								vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
								boundPipeline = pipeline;
							}
							if (set != boundSet) {
								vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelines.pipelineLayout, 0, stack.longs(set), null);
								boundSet = set;
							}
							// Crumbling ignores embeddings, as in Flywheel's InstancedDrawManager.
							pushConstants(cb, pipelines.pipelineLayout, 0, crumblingMaterial, draw.mesh.baseVertex);
							offset = polygonOffset(crumblingMaterial.polygonOffset(), offset);
							vkCmdDrawIndexed(cb, draw.mesh.indexCount(), 1, draw.mesh.firstIndex, draw.mesh.baseVertex, firstInstance);
						}
					}
				}
			}
		} finally {
			polygonOffset(false, offset);
			// boundPipeline may be reset to 0 after a retire; something may still have been bound.
			VkStateSync.afterForeignBind();
			endLightAndOverlay();
		}
	}

	@Override
	public void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
		// Unused: VkEngine calls renderCrumbling(ctx, blocks, light), which needs the frame's context.
	}

	// ---------------------------------------------------------------- DrawManager

	@Override
	protected <I extends Instance> VkInstancer<?> create(InstancerKey<I> key) {
		return new VkInstancer<>(key, new AbstractInstancer.Recreate<>(key, this));
	}

	@Override
	protected <I extends Instance> void initialize(InstancerKey<I> key, VkInstancer<?> instancer) {
		var meshes = key.model().meshes();
		for (int i = 0; i < meshes.size(); i++) {
			var entry = meshes.get(i);
			VkDraw draw = new VkDraw(instancer, meshPool, meshPool.acquire(entry.mesh()), entry.material(), key.bias(), i);
			allDraws.add(draw);
			instancer.draws.add(draw);
			needSort = true;
		}
	}

	@Override
	public void triggerFallback() {
		failed = true;
		FlywheelVulkanClient.LOGGER.warn("[flywheel-vulkan] origin:vulkan switched off for this session; Flywheel picks its next backend");
		Minecraft.getInstance().levelRenderer.allChanged();
	}

	/** No GL MeshPool exists here; FlwDebugInfoMixin keeps Flywheel's debug screen from calling it. */
	@Override
	public MeshPool meshPool() {
		return null;
	}

	@Override
	public void delete() {
		if (deleted) {
			return;
		}
		deleted = true;
		if (pack != null) {
			PackBridge.removeShadowCaster(shadowCaster);
		}
		clearSnapshot();
		instancers.values().forEach(VkInstancer::delete);
		allDraws.forEach(VkDraw::delete);
		allDraws.clear();
		draws.clear();
		super.delete();
		// Draws recorded this frame may still sit in VulkanMod's unsubmitted command buffer, so the
		// Vulkan objects go to the graveyard, which waits for the device on the next client tick.
		Graveyard.bury(() -> {
			meshPool.destroy();
			shaders.destroyModules();
			if (pack != null) {
				pack.destroy();
			}
			pipelines.destroy();
			destroySlots();
			MemoryUtil.memFree(push);
			MemoryUtil.nmemFree(snapInstances);
			MemoryUtil.nmemFree(snapMatrices);
		});
	}

	private void destroySlots() {
		retirement.destroyAll();
		VkDevice dev = Vulkan.getVkDevice();
		for (Slot s : slots) {
			for (HostBuffer b : new HostBuffer[] { s.ubo, s.instances, s.crumbling, s.lightLut, s.lightSections, s.shadowUbo, s.shadowInstances }) {
				if (b != null) {
					b.destroy();
				}
			}
			for (long pool : s.pools) {
				vkDestroyDescriptorPool(dev, pool, null);
			}
		}
		slots = null;
	}
}
