package com.vulkairis.client.mixin.vulkan;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.vulkairis.vulkan.pipeline.VertexInputDescription;
import net.vulkanmod.interfaces.VertexFormatMixed;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.List;

/**
 * Patches VulkanMod's {@link GraphicsPipeline#getAttributeDescriptions} to support all vertex attribute
 * element types (including {@code SHORT} for UV1 overlay and UV2 lightmap) instead of throwing
 * {@code RuntimeException: Unknown type: Short}.
 */
@Mixin(value = GraphicsPipeline.class, remap = false)
public class GraphicsPipelineMixin {

    /**
     * @author Vulkairis
     * @reason Support SHORT and USHORT vertex format elements without throwing RuntimeException.
     */
    @Overwrite
    private static VkVertexInputAttributeDescription.Buffer getAttributeDescriptions(VertexFormat vertexFormat) {
        List<VertexFormatElement> elements = vertexFormat.getElements();
        int size = elements.size();
        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(size);

        for (int i = 0; i < size; i++) {
            VertexFormatElement element = elements.get(i);
            VkVertexInputAttributeDescription desc = attributeDescriptions.get(i);
            desc.binding(0);
            desc.location(i);
            desc.format(VertexInputDescription.mapElementToVkFormat(element));
            desc.offset(((VertexFormatMixed) vertexFormat).getOffset(i));
        }

        return attributeDescriptions.rewind();
    }
}
