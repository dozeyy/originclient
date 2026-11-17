package com.vulkairis.origin.composite;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.vulkairis.bridge.TextureBridge;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.targets.backed.NativeImageBackedNoiseTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Origin (step 4): the pack's own textures for the post chain: {@code noisetex} and the
 * {@code customTexture.<sampler>} entries of shaders.properties (composite/final stage, plus Iris's
 * stage-independent custom textures). Iris normally builds these in {@code CustomTextureManager}, which
 * runs inside the pipeline VulkaIris drives without GL; here they are plain Minecraft textures, which
 * VulkanMod already turns into Vulkan images ({@code VkGlTexture}).
 *
 * <p>Supported: the noise texture (pack PNG or Iris's generated noise at the pack's resolution), PNG
 * custom textures (blur/clamp honoured) and resource-location textures. Raw/lightmap custom textures
 * are not mapped yet: the sampler stays white and the log says so once.</p>
 */
public final class PackTextures {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Composite");
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_TEXTURE_WRAP_S = 0x2802;
    private static final int GL_TEXTURE_WRAP_T = 0x2803;
    private static final int GL_CLAMP_TO_EDGE = 0x812F;
    private static final int GL_REPEAT = 0x2901;

    private static ProgramSet owner;
    private static final Map<String, Integer> IDS = new HashMap<>();
    private static final List<AbstractTexture> OWNED = new ArrayList<>();
    private static final Set<String> MISSING = new HashSet<>();

    private PackTextures() {
    }

    /** The Vulkan image for {@code sampler}, or null when the pack defines none (caller binds white). */
    static VulkanImage resolve(ProgramSet set, String sampler) {
        if (set != owner) {
            release();
            owner = set;
        }
        Integer id = IDS.get(sampler);
        if (id == null) {
            if (MISSING.contains(sampler)) {
                return null;
            }
            try {
                id = create(set, sampler);
            } catch (Throwable t) {
                LOGGER.warn("[Vulkairis/Composite] texture '{}' could not be loaded: {}", sampler, t.toString());
                id = null;
            }
            if (id == null) {
                MISSING.add(sampler);
                return null;
            }
            IDS.put(sampler, id);
        }
        return TextureBridge.getVulkanImage(id);
    }

    /**
     * Origin (step 5): the image for {@code sampler} only when it is already loaded for {@code set}
     * (terrain binds textures inside VulkanMod's render pass, where uploading a new one is not allowed).
     */
    public static VulkanImage cached(ProgramSet set, String sampler) {
        Integer id = set == owner ? IDS.get(sampler) : null;
        return id == null ? null : TextureBridge.getVulkanImage(id);
    }

    private static Integer create(ProgramSet set, String sampler) throws Exception {
        ShaderPack pack = set.getPack();
        CustomTextureData data = null;
        Map<String, CustomTextureData> staged = pack.getCustomTextureDataMap().get(TextureStage.COMPOSITE_AND_FINAL);
        if (staged != null) {
            data = staged.get(sampler);
        }
        if (data == null) {
            data = pack.getIrisCustomTextureDataMap().get(sampler);
        }
        if (data == null && sampler.equals("noisetex")) {
            data = pack.getCustomNoiseTexture();
            if (data == null) {
                NativeImageBackedNoiseTexture noise = new NativeImageBackedNoiseTexture(set.getPackDirectives().getNoiseTextureResolution());
                OWNED.add(noise);
                return noise.getId();
            }
        }
        if (data == null) {
            return null;
        }
        if (data instanceof CustomTextureData.PngData png) {
            DynamicTexture texture = new DynamicTexture(NativeImage.read(png.getContent()));
            OWNED.add(texture);
            texture.setFilter(png.getFilteringData().shouldBlur(), false);
            GlStateManager._bindTexture(texture.getId());
            int wrap = png.getFilteringData().shouldClamp() ? GL_CLAMP_TO_EDGE : GL_REPEAT;
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
            return texture.getId();
        }
        if (data instanceof CustomTextureData.ResourceData resource) {
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(resource.getNamespace(), resource.getLocation());
            return Minecraft.getInstance().getTextureManager().getTexture(location).getId();
        }
        LOGGER.warn("[Vulkairis/Composite] texture '{}' is a {} custom texture; not mapped under Vulkan yet (white)",
                sampler, data.getClass().getSimpleName());
        return null;
    }

    /** Frees the textures this class created; called when the pack (ProgramSet) changes. */
    static void release() {
        for (AbstractTexture texture : OWNED) {
            try {
                texture.close();
            } catch (Throwable ignored) {
            }
        }
        OWNED.clear();
        IDS.clear();
        MISSING.clear();
        owner = null;
    }
}
