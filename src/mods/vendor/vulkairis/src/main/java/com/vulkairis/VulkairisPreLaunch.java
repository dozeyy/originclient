package com.vulkairis;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

public class VulkairisPreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LogManager.getLogger("Vulkairis/PreLaunch");

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
            "MixinEnderDragonRenderer",
            // Origin: Iris's only Sodium-target mixin outside mixins.iris.compat.sodium.json
            // (target net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl).
            "MixinFluidRendererImpl"
    );

    /** Iris's config whose mixins all target Sodium classes (javap of Iris 1.8.14-beta.1, 2026-11-16). */
    private static final String SODIUM_TARGET_CONFIG = "mixins.iris.compat.sodium.json";

    private static void clearAllEntries(Object mixinConfig) {
        try {
            for (Field field : mixinConfig.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                // Only the mixin name lists (strings) and, if Mixin already prepared them, the
                // MixinInfo lists; nothing else in the config object is touched.
                if (field.get(mixinConfig) instanceof List<?> list) {
                    list.removeIf(item -> item instanceof String
                            || item.getClass().getSimpleName().equals("MixinInfo"));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[Vulkairis] Could not clear {}: {}", SODIUM_TARGET_CONFIG, e.getMessage());
        }
    }

    @Override
    public void onPreLaunch() {
        LOGGER.info("[Vulkairis] Pre-launch: configuring Iris <-> VulkanMod Mixin compatibility bridge...");
        applyCompat();
    }

    public static void applyCompat() {
        registerExtension();
        sanitizeConfigs();
    }

    private static void registerExtension() {
        try {
            Object transformer = MixinEnvironment.getDefaultEnvironment().getActiveTransformer();
            if (transformer == null) {
                transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            }

            if (transformer == null) {
                ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                Field delegateField = classLoader.getClass().getDeclaredField("delegate");
                delegateField.setAccessible(true);
                Object delegate = delegateField.get(classLoader);
                if (delegate != null) {
                    Field transformerField = delegate.getClass().getDeclaredField("mixinTransformer");
                    transformerField.setAccessible(true);
                    transformer = transformerField.get(delegate);
                }
            }

            if (transformer != null) {
                Method getExtMethod = null;
                Class<?> cur = transformer.getClass();
                while (cur != null && getExtMethod == null) {
                    try {
                        getExtMethod = cur.getDeclaredMethod("getExtensions");
                    } catch (NoSuchMethodException e) {
                        cur = cur.getSuperclass();
                    }
                }

                if (getExtMethod != null) {
                    getExtMethod.setAccessible(true);
                    Object extRegistry = getExtMethod.invoke(transformer);
                    if (extRegistry != null) {
                        // VulkairisMixinExtension lives in the client source set, so
                        // instantiate via reflection to avoid cross-source-set linkage
                        // issues during preLaunch.
                        Class<?> extClass = Class.forName(
                                "com.vulkairis.client.mixin.ext.VulkairisMixinExtension",
                                true,
                                Thread.currentThread().getContextClassLoader());
                        Object extension = extClass.getDeclaredConstructor().newInstance();
                        Method addMethod = extRegistry.getClass().getMethod("add", IExtension.class);
                        addMethod.setAccessible(true);
                        addMethod.invoke(extRegistry, extension);
                        LOGGER.info("[Vulkairis] Successfully registered VulkairisMixinExtension with Sponge Mixin!");
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[Vulkairis] Extension registration notice: {}", e.getMessage());
        }
    }

    private static void sanitizeConfigs() {
        try {
            for (Config config : Mixins.getConfigs()) {
                String configName = config.getName();
                if (configName != null && (configName.contains("iris") || configName.contains("sodium"))) {
                    Object mixinConfig = config.getConfig();
                    if (mixinConfig != null) {
                        if (SODIUM_TARGET_CONFIG.equals(configName)) {
                            // Origin: every mixin in this config targets a Sodium class. Sodium is not
                            // loaded under VulkanMod, and Origin's sodium-compat stand-in deliberately
                            // contains none of these targets, so drop the whole config.
                            clearAllEntries(mixinConfig);
                        } else {
                            stripConflictingEntries(mixinConfig);
                        }
                    }
                }
            }
            LOGGER.info("[Vulkairis] Iris mixin configurations sanitized successfully.");
        } catch (Exception e) {
            LOGGER.warn("[Vulkairis] Error sanitizing mixin configs: {}", e.getMessage());
        }
    }

    private static void stripConflictingEntries(Object mixinConfig) {
        try {
            Field[] fields = mixinConfig.getClass().getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                Object val = field.get(mixinConfig);
                if (val instanceof List<?> list) {
                    list.removeIf(item -> {
                        if (item instanceof String s) {
                            for (String conflicting : CONFLICTING_IRIS_MIXINS) {
                                if (s.contains(conflicting)) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    });
                }
            }
        } catch (Exception ignored) {
        }
    }
}
