package com.vulkairis.vulkan.pipeline;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import java.util.List;

/**
 * Translates Mojang's VertexFormat into Vulkan VkPipelineVertexInputStateCreateInfo,
 * mapping element types and component counts to VkFormat constants.
 */
public class VertexInputDescription {

    public static VkPipelineVertexInputStateCreateInfo createVertexInputState(
            MemoryStack stack, VertexFormat format, int binding) {
        VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

        if (format == null) {
            return vertexInputInfo;
        }

        List<VertexFormatElement> elements = format.getElements();
        int elementCount = elements.size();

        // 1. Binding description
        VkVertexInputBindingDescription.Buffer bindingDescriptions = VkVertexInputBindingDescription.calloc(1, stack);
        bindingDescriptions.get(0)
                .binding(binding)
                .stride(format.getVertexSize())
                .inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);

        // 2. Attribute descriptions
        VkVertexInputAttributeDescription.Buffer attributeDescriptions =
                VkVertexInputAttributeDescription.calloc(elementCount, stack);

        for (int i = 0; i < elementCount; i++) {
            VertexFormatElement element = elements.get(i);
            int offset = format.getOffset(element);
            int vkFormat = mapElementToVkFormat(element);

            attributeDescriptions.get(i)
                    .binding(binding)
                    .location(i)
                    .format(vkFormat)
                    .offset(offset);
        }

        vertexInputInfo.pVertexBindingDescriptions(bindingDescriptions);
        vertexInputInfo.pVertexAttributeDescriptions(attributeDescriptions);

        return vertexInputInfo;
    }

    public static int mapElementToVkFormat(VertexFormatElement element) {
        VertexFormatElement.Type type = element.type();
        int count = element.count();

        return switch (type) {
            case FLOAT -> switch (count) {
                case 1 -> VK10.VK_FORMAT_R32_SFLOAT;
                case 2 -> VK10.VK_FORMAT_R32G32_SFLOAT;
                case 3 -> VK10.VK_FORMAT_R32G32B32_SFLOAT;
                case 4 -> VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
                default -> VK10.VK_FORMAT_R32G32B32_SFLOAT;
            };
            // Unsigned/signed normalized 8-bit data. The component count must be
            // honored: Mojang only ever emits 4 for COLOR, but generic elements
            // may use fewer components, and mapping them to RGBA would misread
            // the following vertex attributes.
            case UBYTE -> switch (count) {
                case 1 -> VK10.VK_FORMAT_R8_UNORM;
                case 2 -> VK10.VK_FORMAT_R8G8_UNORM;
                case 3 -> VK10.VK_FORMAT_R8G8B8_UNORM;
                case 4 -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
                default -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
            };
            case BYTE -> switch (count) {
                case 1 -> VK10.VK_FORMAT_R8_SNORM;
                case 2 -> VK10.VK_FORMAT_R8G8_SNORM;
                case 3 -> VK10.VK_FORMAT_R8G8B8_SNORM;
                case 4 -> VK10.VK_FORMAT_R8G8B8A8_SNORM;
                default -> VK10.VK_FORMAT_R8G8B8A8_SNORM;
            };
            // Minecraft stores the overlay (UV1) and lightmap (UV2) as signed shorts.
            // Shaders consume them as integer vectors (ivec2 UV1, ivec2 UV2).
            // They MUST be mapped to SINT formats; mapping them to SNORM float formats
            // causes the vertex fetch unit to produce floating-point values that
            // truncate to 0 in ivec2 attributes, breaking text and lightmap sampling.
            case SHORT -> switch (count) {
                case 1 -> VK10.VK_FORMAT_R16_SINT;
                case 2 -> VK10.VK_FORMAT_R16G16_SINT;
                case 3 -> VK10.VK_FORMAT_R16G16B16_SINT;
                case 4 -> VK10.VK_FORMAT_R16G16B16A16_SINT;
                default -> VK10.VK_FORMAT_R16G16_SINT;
            };
            case USHORT -> switch (count) {
                case 1 -> VK10.VK_FORMAT_R16_UINT;
                case 2 -> VK10.VK_FORMAT_R16G16_UINT;
                case 3 -> VK10.VK_FORMAT_R16G16B16_UINT;
                case 4 -> VK10.VK_FORMAT_R16G16B16A16_UINT;
                default -> VK10.VK_FORMAT_R16G16_UINT;
            };
            case INT -> switch (count) {
                case 1 -> VK10.VK_FORMAT_R32_SINT;
                case 2 -> VK10.VK_FORMAT_R32G32_SINT;
                case 3 -> VK10.VK_FORMAT_R32G32B32_SINT;
                case 4 -> VK10.VK_FORMAT_R32G32B32A32_SINT;
                default -> VK10.VK_FORMAT_R32_SINT;
            };
            case UINT -> switch (count) {
                case 1 -> VK10.VK_FORMAT_R32_UINT;
                case 2 -> VK10.VK_FORMAT_R32G32_UINT;
                case 3 -> VK10.VK_FORMAT_R32G32B32_UINT;
                case 4 -> VK10.VK_FORMAT_R32G32B32A32_UINT;
                default -> VK10.VK_FORMAT_R32_UINT;
            };
        };
    }
}
