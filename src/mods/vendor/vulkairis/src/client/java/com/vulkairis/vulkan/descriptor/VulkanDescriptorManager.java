package com.vulkairis.vulkan.descriptor;

import com.vulkairis.diagnostics.StageDiagnostics;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages rigid Vulkan descriptor set layouts, pools, allocations, and binding updates.
 */
public class VulkanDescriptorManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/DescriptorManager");

    public record BindingInfo(int binding, int descriptorType, int stageFlags, int descriptorCount) {}
    public record LayoutKey(List<BindingInfo> bindings) {}

    private static final Map<LayoutKey, Long> LAYOUT_CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, Long> POOL_MAP = new ConcurrentHashMap<>();
    /** Tracks which descriptor-set layout backs each allocated set, for write-time validation. */
    private static final Map<Long, Long> SET_LAYOUT_MAP = new ConcurrentHashMap<>();
    private static long defaultDescriptorPool = 0L;
    private static VulkanImage fallbackWhiteTexture = null;

    public static long getOrCreateDescriptorSetLayout(List<BindingInfo> bindings) {
        LayoutKey key = new LayoutKey(Collections.unmodifiableList(new ArrayList<>(bindings)));
        return LAYOUT_CACHE.computeIfAbsent(key, VulkanDescriptorManager::createDescriptorSetLayout);
    }

    private static long createDescriptorSetLayout(LayoutKey key) {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device)) {
            LOGGER.warn("[Vulkairis] Cannot create VkDescriptorSetLayout: VkDevice not available");
            return 0L;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            List<BindingInfo> bindings = key.bindings();
            VkDescriptorSetLayoutBinding.Buffer layoutBindings = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);

            for (int i = 0; i < bindings.size(); i++) {
                BindingInfo info = bindings.get(i);
                layoutBindings.get(i)
                        .binding(info.binding())
                        .descriptorType(info.descriptorType())
                        .descriptorCount(info.descriptorCount() > 0 ? info.descriptorCount() : 1)
                        .stageFlags(info.stageFlags());
            }

            VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(layoutBindings);

            LongBuffer pSetLayout = stack.mallocLong(1);
            int result = VK10.vkCreateDescriptorSetLayout(device, createInfo, null, pSetLayout);
            if (result != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create VkDescriptorSetLayout, Vulkan error: " + result);
            }

            long layoutHandle = pSetLayout.get(0);
            LOGGER.info("[Vulkairis] Created VkDescriptorSetLayout 0x{} with {} bindings",
                    Long.toHexString(layoutHandle), bindings.size());
            return layoutHandle;
        }
    }

    public static synchronized long getOrCreateDefaultPool() {
        if (defaultDescriptorPool != 0L) return defaultDescriptorPool;

        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device)) return 0L;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(4, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(512);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1024);
            poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(256);
            poolSizes.get(3).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(256);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(1024)
                    .pPoolSizes(poolSizes);

            LongBuffer pDescriptorPool = stack.mallocLong(1);
            int result = VK10.vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool);
            if (result != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create VkDescriptorPool, error: " + result);
            }

            defaultDescriptorPool = pDescriptorPool.get(0);
            LOGGER.info("[Vulkairis] Created default VkDescriptorPool 0x{}", Long.toHexString(defaultDescriptorPool));
            return defaultDescriptorPool;
        }
    }

    public static long allocateDescriptorSet(long descriptorSetLayout) {
        long pool = getOrCreateDefaultPool();
        if (pool == 0L || descriptorSetLayout == 0L) return 0L;

        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device)) return 0L;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSetLayout = stack.longs(descriptorSetLayout);
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pool)
                    .pSetLayouts(pSetLayout);

            LongBuffer pDescriptorSet = stack.mallocLong(1);
            int result = VK10.vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);
            if (result != VK10.VK_SUCCESS) {
                LOGGER.error("[Vulkairis] Failed to allocate descriptor set, error: {}", result);
                return 0L;
            }

            long setHandle = pDescriptorSet.get(0);
            POOL_MAP.put(setHandle, pool);
            SET_LAYOUT_MAP.put(setHandle, descriptorSetLayout);
            return setHandle;
        }
    }

    public static void updateTextureBinding(long descriptorSet, int binding, int openGlTextureId) {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device) || descriptorSet == 0L) return;

        if (!validateBindingForWrite(descriptorSet, binding,
                VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, "texture binding")) {
            return;
        }

        VulkanImage vulkanImage = null;
        if (openGlTextureId > 0) {
            VkGlTexture glTexture = VkGlTexture.getTexture(openGlTextureId);
            if (glTexture != null) {
                vulkanImage = glTexture.getVulkanImage();
            }
        }

        if (vulkanImage == null) {
            if (fallbackWhiteTexture == null) {
                fallbackWhiteTexture = VulkanImage.createWhiteTexture();
            }
            vulkanImage = fallbackWhiteTexture;
        }

        if (vulkanImage == null) {
            StageDiagnostics.fail("Descriptor contract", DescriptorContractValidator.describeFailure(
                    "texture binding", binding,
                    "VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER backed by a non-null image",
                    "no Vulkan image (fallback texture unavailable)"), null);
            return;
        }

        long imageView = vulkanImage.getImageView();
        long sampler = vulkanImage.getSampler();
        if (imageView == 0L || sampler == 0L) {
            StageDiagnostics.fail("Descriptor contract", DescriptorContractValidator.describeFailure(
                    "texture binding", binding,
                    "non-zero image view and sampler",
                    "imageView=0x" + Long.toHexString(imageView) + ", sampler=0x" + Long.toHexString(sampler)), null);
            return;
        }

        updateTextureBinding(descriptorSet, binding, imageView, sampler);
    }

    /**
     * Writes a combined image sampler using explicit Vulkan handles. Kept separate from the
     * OpenGL-texture-id path so callers that already own an image view/sampler can be validated
     * without a VulkanMod texture lookup.
     *
     * @param descriptorSet destination descriptor set (must be non-zero)
     * @param binding       destination binding number (must exist in the set layout)
     * @param imageView     non-zero {@code VkImageView}
     * @param sampler       non-zero {@code VkSampler}
     */
    public static void updateTextureBinding(long descriptorSet, int binding, long imageView, long sampler) {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device) || descriptorSet == 0L) return;

        if (!validateBindingForWrite(descriptorSet, binding,
                VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, "texture binding")) {
            return;
        }

        if (imageView == 0L || sampler == 0L) {
            StageDiagnostics.fail("Descriptor contract", DescriptorContractValidator.describeFailure(
                    "texture binding", binding,
                    "non-zero image view and sampler",
                    "imageView=0x" + Long.toHexString(imageView) + ", sampler=0x" + Long.toHexString(sampler)), null);
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(imageView)
                    .sampler(sampler);

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(binding)
                    .dstArrayElement(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo);

            VK10.vkUpdateDescriptorSets(device, descriptorWrite, null);
        }
    }

    public static void updateUniformBufferBinding(long descriptorSet, int binding, long bufferHandle, long offset, long range) {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device) || descriptorSet == 0L) return;

        if (bufferHandle == 0L) {
            StageDiagnostics.fail("Descriptor contract", DescriptorContractValidator.describeFailure(
                    "uniform buffer binding", binding, "non-zero VkBuffer", "0x0"), null);
            return;
        }

        if (range <= 0L) {
            StageDiagnostics.fail("Descriptor contract", DescriptorContractValidator.describeFailure(
                    "uniform buffer binding", binding, "range > 0", "range=" + range), null);
            return;
        }

        if (offset < 0L) {
            StageDiagnostics.fail("Descriptor contract", DescriptorContractValidator.describeFailure(
                    "uniform buffer binding", binding, "offset >= 0", "offset=" + offset), null);
            return;
        }

        if (!validateBindingForWrite(descriptorSet, binding,
                VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, "uniform buffer binding")) {
            return;
        }

        // The manager does not own a buffer-size registry; the non-zero/positive and layout checks
        // above are the strongest guarantees available here. A caller that knows the allocation
        // size is expected to bound offset + range before calling.
        if (range > Integer.MAX_VALUE) {
            LOGGER.debug("[Vulkairis] Uniform buffer binding {} range {} exceeds typical UBO size; offset {}",
                    binding, range, offset);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(bufferHandle)
                    .offset(offset)
                    .range(range);

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(binding)
                    .dstArrayElement(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(bufferInfo);

            VK10.vkUpdateDescriptorSets(device, descriptorWrite, null);
        }
    }

    public static void bindDescriptorSets(VkCommandBuffer cmdBuffer, long pipelineLayout, long... descriptorSets) {
        if (cmdBuffer == null || pipelineLayout == 0L || descriptorSets == null || descriptorSets.length == 0) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pDescriptorSets = stack.mallocLong(descriptorSets.length);
            for (long set : descriptorSets) {
                pDescriptorSets.put(set);
            }
            pDescriptorSets.flip();

            VK10.vkCmdBindDescriptorSets(
                    cmdBuffer,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipelineLayout,
                    0,
                    pDescriptorSets,
                    null
            );
        }
    }

    public static void freeDescriptorSet(long descriptorSet) {
        Long pool = POOL_MAP.remove(descriptorSet);
        SET_LAYOUT_MAP.remove(descriptorSet);
        if (pool != null && pool != 0L) {
            VkDevice device = VulkanBackendAdapter.getDevice();
            if (VulkanBackendAdapter.isLive(device)) {
                // GPU-consumption guard: never hand the pool a set the device may still be reading.
                LOGGER.debug("[Vulkairis] Waiting for device idle before freeing descriptor set 0x{}",
                        Long.toHexString(descriptorSet));
                VulkanBackendAdapter.waitIdle();
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    LongBuffer pSets = stack.longs(descriptorSet);
                    VK10.vkFreeDescriptorSets(device, pool, pSets);
                }
            }
        }
    }

    /**
     * Compares {@code required} against every descriptor-set layout this manager has created and
     * logs one standardized {@link StageDiagnostics#fail(String, String, Throwable)} line per
     * problem. Intended to be called before a pass begins consuming descriptors.
     *
     * @param required bindings the pass requires; may be {@code null}
     * @param passName pass/render-pass identifier included in every message
     * @return the problems found; empty when the contract is satisfied
     */
    public static List<String> validateAgainst(List<DescriptorContractValidator.BindingSpec> required, String passName) {
        List<DescriptorContractValidator.BindingSpec> provided = collectProvidedSpecs();
        List<String> problems = DescriptorContractValidator.validate(required, provided,
                (passName == null || passName.isBlank()) ? "descriptor contract" : passName);
        for (String problem : problems) {
            StageDiagnostics.fail("Descriptor contract", problem, null);
        }
        if (problems.isEmpty()) {
            LOGGER.debug("[Vulkairis] Descriptor contract {}: PASS", passName);
        }
        return problems;
    }

    /**
     * Validates that {@code binding} exists in the layout backing {@code descriptorSet} and matches
     * the expected descriptor type. Unknown sets (not allocated through this manager) pass through
     * unchanged so legacy callers are not broken.
     *
     * @return {@code true} when the write may proceed, {@code false} when it must be rejected
     */
    private static boolean validateBindingForWrite(long descriptorSet, int binding, int expectedType, String context) {
        Long layout = SET_LAYOUT_MAP.get(descriptorSet);
        if (layout == null || layout == 0L) {
            LOGGER.debug("[Vulkairis] {}: descriptor set 0x{} has no tracked layout; skipping layout validation",
                    context, Long.toHexString(descriptorSet));
            return true;
        }

        List<BindingInfo> bindings = findLayoutBindings(layout);
        if (bindings == null) {
            LOGGER.debug("[Vulkairis] {}: layout 0x{} bindings not tracked; skipping layout validation",
                    context, Long.toHexString(layout));
            return true;
        }

        BindingInfo match = null;
        for (BindingInfo info : bindings) {
            if (info.binding() == binding) {
                match = info;
                break;
            }
        }

        if (match == null) {
            StageDiagnostics.fail("Descriptor contract", DescriptorContractValidator.describeFailure(
                    context, binding,
                    DescriptorContractValidator.describeType(expectedType),
                    "binding absent from set layout 0x" + Long.toHexString(layout)), null);
            return false;
        }

        if (match.descriptorType() != expectedType) {
            StageDiagnostics.fail("Descriptor contract", DescriptorContractValidator.describeFailure(
                    context, binding,
                    DescriptorContractValidator.describeType(expectedType),
                    DescriptorContractValidator.describeType(match.descriptorType())), null);
            return false;
        }

        return true;
    }

    private static List<BindingInfo> findLayoutBindings(long layout) {
        for (Map.Entry<LayoutKey, Long> entry : LAYOUT_CACHE.entrySet()) {
            Long handle = entry.getValue();
            if (handle != null && handle == layout) {
                return entry.getKey().bindings();
            }
        }
        return null;
    }

    private static List<DescriptorContractValidator.BindingSpec> collectProvidedSpecs() {
        List<DescriptorContractValidator.BindingSpec> specs = new ArrayList<>();
        for (LayoutKey key : LAYOUT_CACHE.keySet()) {
            for (BindingInfo info : key.bindings()) {
                specs.add(new DescriptorContractValidator.BindingSpec(
                        info.binding(),
                        info.descriptorType(),
                        info.descriptorCount() > 0 ? info.descriptorCount() : 1,
                        info.stageFlags()));
            }
        }
        return specs;
    }

    public static void cleanUp() {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (VulkanBackendAdapter.isLive(device)) {
            LAYOUT_CACHE.forEach((key, layout) -> {
                if (layout != 0L) {
                    try {
                        VK10.vkDestroyDescriptorSetLayout(device, layout, null);
                    } catch (Throwable ignored) {
                    }
                }
            });

            if (defaultDescriptorPool != 0L) {
                try {
                    VK10.vkDestroyDescriptorPool(device, defaultDescriptorPool, null);
                } catch (Throwable ignored) {
                }
            }

            if (fallbackWhiteTexture != null) {
                try {
                    fallbackWhiteTexture.free();
                } catch (Throwable ignored) {
                }
            }
        }

        LAYOUT_CACHE.clear();
        POOL_MAP.clear();
        SET_LAYOUT_MAP.clear();
        defaultDescriptorPool = 0L;
        fallbackWhiteTexture = null;
        LOGGER.info("[Vulkairis] Cleaned up descriptor layouts and pools.");
    }
}
