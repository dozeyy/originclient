package com.origin.flywheelvulkan.spike;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import com.origin.flywheelvulkan.vk.VkStateSync;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.SPIRVUtils;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The riskiest step of the Vulkan Flywheel backend, alone: draw a row of cubes with ONE
 * instanced vkCmdDrawIndexed, recorded on VulkanMod's own command buffer inside VulkanMod's
 * own render pass. Instance offsets come from a storage buffer read by gl_InstanceIndex
 * (VulkanMod's descriptor system has no SSBO type, so this builds its own set layout and pool).
 * GLSL 450 goes through VulkanMod's shaderc wrapper (SPIRVUtils).
 *
 * Off unless the JVM runs with -Dorigin.flywheel_vulkan.spike=true (CI only). Never used by
 * the shipped backend; kept as the minimal repro for "does raw Vulkan drawing inside
 * VulkanMod's pass work on this driver".
 */
public final class InstancedCubeSpike {
	public static final boolean ENABLED = Boolean.getBoolean("origin.flywheel_vulkan.spike");
	public static final int INSTANCES = 8;

	private static final String VERT = """
			#version 450
			layout(location = 0) in vec3 aPos;
			layout(push_constant) uniform PC { mat4 viewProj; vec4 origin; } pc;
			layout(std430, set = 0, binding = 0) readonly buffer Instances { vec4 offsets[]; };
			layout(location = 0) out vec3 vColor;
			void main() {
				vec4 o = offsets[gl_InstanceIndex];
				gl_Position = pc.viewProj * vec4(aPos + o.xyz - pc.origin.xyz, 1.0);
				vColor = mix(vec3(1.0, 0.35, 0.1), vec3(0.1, 0.6, 1.0), o.w) * (0.55 + 0.45 * aPos.y);
			}
			""";
	private static final String FRAG = """
			#version 450
			layout(location = 0) in vec3 vColor;
			layout(location = 0) out vec4 fragColor;
			void main() { fragColor = vec4(vColor, 1.0); }
			""";

	private static long setLayout, pipelineLayout, pool, set, pipeline, pipelineRenderPass;
	private static long vbo, vboAlloc, ibo, iboAlloc, ssbo, ssboAlloc;
	private static boolean failed;
	private static boolean loggedFirstDraw;

	private InstancedCubeSpike() {
	}

	/** Records the draw; call on the render thread while VulkanMod is recording the main pass. */
	public static void draw(Matrix4f viewProj, double camX, double camY, double camZ, double baseX, double baseY, double baseZ) {
		if (!ENABLED || failed || !Renderer.isRecording()) return;
		try {
			RenderPass rp = Renderer.getInstance().getBoundRenderPass();
			if (rp == null) return;
			if (setLayout == 0) createStatic();
			if (pipeline == 0 || pipelineRenderPass != rp.getId()) createPipeline(rp.getId());

			VkCommandBuffer cb = Renderer.getCommandBuffer();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
				vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(set), null);
				ByteBuffer pc = stack.malloc(80);
				viewProj.get(0, pc);
				pc.putFloat(64, (float) (camX - baseX)).putFloat(68, (float) (camY - baseY)).putFloat(72, (float) (camZ - baseZ)).putFloat(76, 0f);
				vkCmdPushConstants(cb, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pc);
				vkCmdBindVertexBuffers(cb, 0, stack.longs(vbo), stack.longs(0));
				vkCmdBindIndexBuffer(cb, ibo, 0, VK_INDEX_TYPE_UINT16);
				vkCmdDrawIndexed(cb, 36, INSTANCES, 0, 0, 0);
			}
			// VulkanMod caches the last pipeline it bound and skips a re-bind of the same one.
			VkStateSync.afterForeignBind();
			if (!loggedFirstDraw) {
				loggedFirstDraw = true;
				org.slf4j.LoggerFactory.getLogger("origin-flywheel-vulkan")
						.info("[spike] instanced cube drawn: vkCmdDrawIndexed(36, {}) in render pass {}", INSTANCES, Long.toHexString(rp.getId()));
			}
		} catch (Throwable t) {
			failed = true;
			org.slf4j.LoggerFactory.getLogger("origin-flywheel-vulkan").error("[spike] failed, spike off", t);
		}
	}

	private static void createStatic() {
		VkDevice dev = Vulkan.getVkDevice();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(1, stack);
			b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
			LongBuffer p = stack.mallocLong(1);
			check(vkCreateDescriptorSetLayout(dev, VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType$Default().pBindings(b), null, p), "set layout");
			setLayout = p.get(0);

			VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
			pcr.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(80);
			check(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
					.pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(pcr), null, p), "pipeline layout");
			pipelineLayout = p.get(0);

			VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(1, stack);
			ps.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
			check(vkCreateDescriptorPool(dev, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
					.maxSets(1).pPoolSizes(ps), null, p), "descriptor pool");
			pool = p.get(0);
			check(vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
					.descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), p), "descriptor set");
			set = p.get(0);

			// Cube: 8 corners, 0..1 unit box; 12 triangles.
			float[] v = { 0,0,0, 1,0,0, 1,1,0, 0,1,0, 0,0,1, 1,0,1, 1,1,1, 0,1,1 };
			short[] idx = { 0,2,1, 0,3,2, 4,5,6, 4,6,7, 0,1,5, 0,5,4, 3,6,2, 3,7,6, 0,4,7, 0,7,3, 1,2,6, 1,6,5 };
			long[] r = hostBuffer(v.length * 4L, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
			vbo = r[0]; vboAlloc = r[1];
			write(vboAlloc, bb -> { for (float f : v) bb.putFloat(f); });
			r = hostBuffer(idx.length * 2L, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
			ibo = r[0]; iboAlloc = r[1];
			write(iboAlloc, bb -> { for (short s : idx) bb.putShort(s); });
			r = hostBuffer(INSTANCES * 16L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
			ssbo = r[0]; ssboAlloc = r[1];
			write(ssboAlloc, bb -> {
				for (int i = 0; i < INSTANCES; i++) bb.putFloat(i * 1.5f).putFloat(0).putFloat(0).putFloat(i / (float) (INSTANCES - 1));
			});

			VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack);
			bi.get(0).buffer(ssbo).offset(0).range(VK_WHOLE_SIZE);
			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
			w.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
					.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bi);
			vkUpdateDescriptorSets(dev, w, null);
		}
	}

	private static void createPipeline(long renderPass) {
		VkDevice dev = Vulkan.getVkDevice();
		SPIRVUtils.SPIRV vs = SPIRVUtils.compileShader("origin_spike.vsh", VERT, SPIRVUtils.ShaderKind.VERTEX_SHADER);
		SPIRVUtils.SPIRV fs = SPIRVUtils.compileShader("origin_spike.fsh", FRAG, SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer p = stack.mallocLong(1);
			check(vkCreateShaderModule(dev, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(vs.bytecode()), null, p), "vs module");
			long vsm = p.get(0);
			check(vkCreateShaderModule(dev, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(fs.bytecode()), null, p), "fs module");
			long fsm = p.get(0);

			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vsm).pName(stack.UTF8("main"));
			stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fsm).pName(stack.UTF8("main"));

			VkVertexInputBindingDescription.Buffer bind = VkVertexInputBindingDescription.calloc(1, stack);
			bind.get(0).binding(0).stride(12).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
			VkVertexInputAttributeDescription.Buffer attr = VkVertexInputAttributeDescription.calloc(1, stack);
			attr.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);

			VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
			blend.get(0).colorWriteMask(0xF).blendEnable(false);

			VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
			ci.get(0).sType$Default()
					.pStages(stages)
					.pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
							.pVertexBindingDescriptions(bind).pVertexAttributeDescriptions(attr))
					.pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
							.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
					.pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
					.pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
							.polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
							.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f))
					.pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
							.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
					.pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
							.depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL))
					.pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
					// Same dynamic set as VulkanMod's GraphicsPipeline (javap 0.6.7: DEPTH_BIAS, VIEWPORT,
					// SCISSOR, LINE_WIDTH): binding a pipeline with a smaller set would leave VulkanMod's
					// next pipeline reading undefined dynamic state.
					.pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
							.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT,
									VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_LINE_WIDTH)))
					.layout(pipelineLayout)
					.renderPass(renderPass)
					.subpass(0)
					.basePipelineIndex(-1);
			check(vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, ci, null, p), "pipeline");
			if (pipeline != 0) vkDestroyPipeline(dev, pipeline, null);
			pipeline = p.get(0);
			pipelineRenderPass = renderPass;
			vkDestroyShaderModule(dev, vsm, null);
			vkDestroyShaderModule(dev, fsm, null);
		} finally {
			vs.free();
			fs.free();
		}
	}

	/** Host-visible, coherent buffer through VulkanMod's own VMA allocator. Returns {buffer, allocation}. */
	static long[] hostBuffer(long size, int usage) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer pBuf = stack.mallocLong(1);
			PointerBuffer pAlloc = stack.mallocPointer(1);
			check(Vma.vmaCreateBuffer(Vulkan.getAllocator(),
					VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE),
					VmaAllocationCreateInfo.calloc(stack).requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT),
					pBuf, pAlloc, null), "vmaCreateBuffer");
			return new long[] { pBuf.get(0), pAlloc.get(0) };
		}
	}

	interface Fill { void fill(ByteBuffer bb); }

	static void write(long alloc, Fill fill) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer p = stack.mallocPointer(1);
			check(Vma.vmaMapMemory(Vulkan.getAllocator(), alloc, p), "vmaMapMemory");
			// Size is irrelevant for a write cursor; the callers stay inside their allocations.
			ByteBuffer bb = MemoryUtil.memByteBuffer(p.get(0), Integer.MAX_VALUE).order(java.nio.ByteOrder.nativeOrder());
			fill.fill(bb);
			Vma.vmaUnmapMemory(Vulkan.getAllocator(), alloc);
		}
	}

	static void check(int result, String what) {
		if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
	}
}
