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

	private record SetKey(long instanceBuffer, long diffuseView, long diffuseSampler, long crumblingView, long crumblingSampler) {
	}

	private static final class Slot {
		HostBuffer ubo;
		HostBuffer instances;
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
		retirement.beginFrame(slotIndex);
		VkDevice dev = Vulkan.getVkDevice();
		for (long pool : slot.pools) {
			vkResetDescriptorPool(dev, pool, 0);
		}
		slot.poolIndex = 0;
		slot.sets.clear();
		slot.crumblingCursor = 0;
		uploaded = false;
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
			}
		}

		VkCommandBuffer cb = Renderer.getCommandBuffer();
		VulkanImage white = VTextureSelector.getWhiteTexture();
		long boundPipeline = 0;
		long boundSet = 0;
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
				long pipeline = pipeline(renderPass, instancer.key.type(), environment.contextShader(), material);
				long set = descriptorSet(slot.instances.buffer, diffuse, white);
				if (pipeline != boundPipeline) {
					vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
					boundPipeline = pipeline;
				}
				if (set != boundSet) {
					vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelines.pipelineLayout, 0, stack.longs(set), null);
					boundSet = set;
				}
				long matrices = environment instanceof EmbeddedEnvironment e ? environments.arena.indexToPointer(e.matrixIndex()) : 0;
				pushConstants(cb, matrices, material, draw.mesh.baseVertex);
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
	}

	private long pipeline(RenderPass renderPass, InstanceType<?> type, ContextShader context, Material material) {
		VkShaders.ProgramKey program = VkShaders.ProgramKey.of(type, context, material);
		return pipelines.get(renderPass, program, shaders.get(program), VkPipelines.StateKey.of(material));
	}

	/** Push block (VkLayout): embedding matrices (identity outside an embedding), packed material, base vertex. */
	private void pushConstants(VkCommandBuffer cb, long matrices, Material material, int baseVertex) {
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
		vkCmdPushConstants(cb, pipelines.pipelineLayout, ALL_STAGES, 0, push);
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

	private long descriptorSet(long instanceBuffer, VulkanImage diffuse, VulkanImage crumbling) {
		SetKey key = new SetKey(instanceBuffer, diffuse.getImageView(), diffuse.getSampler(), crumbling.getImageView(), crumbling.getSampler());
		Long cached = slot.sets.get(key);
		if (cached != null) {
			return cached;
		}
		long set = allocateSet();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(VkLayout.BINDING_COUNT, stack);
			for (int i = 0; i < VkLayout.UNIFORM_BLOCKS; i++) {
				buffer(writes.get(i), stack, set, i, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, slot.ubo.buffer, uniformOffsets[i], uniformBlocks[i].size());
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
								boundPipeline = 0;
							}
							VulkanImage diffuse = texture(crumblingMaterial.texture(), crumblingMaterial.blur(), crumblingMaterial.mipmap(), true);
							if (diffuse == null) {
								continue;
							}
							long pipeline = pipeline(renderPass, type, ContextShader.CRUMBLING, crumblingMaterial);
							long set = descriptorSet(slot.crumbling.buffer, diffuse, breaking);
							if (pipeline != boundPipeline) {
								vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
								boundPipeline = pipeline;
							}
							if (set != boundSet) {
								vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelines.pipelineLayout, 0, stack.longs(set), null);
								boundSet = set;
							}
							// Crumbling ignores embeddings, as in Flywheel's InstancedDrawManager.
							pushConstants(cb, 0, crumblingMaterial, draw.mesh.baseVertex);
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
			pipelines.destroy();
			destroySlots();
			MemoryUtil.memFree(push);
		});
	}

	private void destroySlots() {
		retirement.destroyAll();
		VkDevice dev = Vulkan.getVkDevice();
		for (Slot s : slots) {
			for (HostBuffer b : new HostBuffer[] { s.ubo, s.instances, s.crumbling, s.lightLut, s.lightSections }) {
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
