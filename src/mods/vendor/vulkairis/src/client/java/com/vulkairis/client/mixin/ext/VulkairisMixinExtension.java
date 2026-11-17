package com.vulkairis.client.mixin.ext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Set;

public class VulkairisMixinExtension implements IExtension {
    private static final Logger LOGGER = LogManager.getLogger("Vulkairis/MixinExtension");

    private static final Set<String> CONFLICTING_IRIS_MIXINS = Set.of(
            "MixinRenderSystem",
            "MixinShaderInstance",
            "MixinProgram",
            "MixinProgramManager",
            "MixinProgramType",
            "MixinUniform",
            "MixinVertexBuffer",
            "MixinGlStateManager",
            "MixinLevelRenderer",
            "MixinGameRenderer",
            "MixinChunkBorderRenderer",
            "MixinBufferBuilder",
            "MixinByteBufferBuilder",
            "MixinChunkVertex",
            "MixinRenderRegion",
            "MixinSodiumOptions",
            "MixinShaderChunkRenderer",
            "MixinSodiumWorldRenderer",
            "MixinSodiumGameOptions",
            "EnumOptionBuilderImplAccessor",
            "IntegerOptionBuilderImplAccessor",
            // Iris hand cancellation mixin: cancels vanilla hand/item rendering when shaders are on
            // because HandRenderer.renderSolid() is not called from stripped MixinGameRenderer.
            // Stripping MixinItemInHandRenderer lets vanilla/VulkanMod render first-person hands & held items.
            "MixinItemInHandRenderer",
            // Armor and entity layer mixins that fail or conflict in 1.21.1:
            // MixinHumanoidArmorLayer throws InvalidMixinException / NPE parsing @Inject on changeId,
            // which crashes resource reload and prevents loading to the main menu.
            "MixinHumanoidArmorLayer",
            "MixinHorseArmorLayer",
            "MixinCapeLayer",
            "MixinElytraLayer",
            "MixinEnderDragonRenderer"
    );

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
        if (context == null) return;
        try {
            Field mixinsField = null;
            Class<?> clazz = context.getClass();
            while (clazz != null && mixinsField == null) {
                try {
                    mixinsField = clazz.getDeclaredField("mixins");
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }

            if (mixinsField != null) {
                mixinsField.setAccessible(true);
                Object mixinsObj = mixinsField.get(context);
                if (mixinsObj instanceof Collection<?> collection) {
                    collection.removeIf(item -> {
                        if (item == null) return false;
                        String str = item.toString();
                        for (String conflicting : CONFLICTING_IRIS_MIXINS) {
                            if (str.contains(conflicting)) {
                                LOGGER.info("[Vulkairis] Neutralized conflicting mixin '{}' targeting '{}'",
                                        conflicting, context.getClassNode() != null ? context.getClassNode().name : "unknown");
                                return true;
                            }
                        }
                        return false;
                    });
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void postApply(ITargetClassContext context) {
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {
    }
}
