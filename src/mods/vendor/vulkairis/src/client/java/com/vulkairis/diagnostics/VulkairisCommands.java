package com.vulkairis.diagnostics;

import com.vulkairis.config.ConfigManager;
import com.vulkairis.core.DebugRenderMode;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import com.vulkairis.core.VulkairisController;
import com.vulkairis.iris.IrisBridge;
import com.vulkairis.vulkan.VulkanCapabilities;
import com.vulkairis.vulkan.test.StandaloneVulkanTest;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

/**
 * Registers /vulkairis client-side debug commands.
 */
public class VulkairisCommands {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("vulkairis")
                    .then(ClientCommandManager.literal("status").executes(ctx -> {
                        VulkairisController controller = VulkairisController.getInstance();
                        ctx.getSource().sendFeedback(Component.literal("§6=== Vulkairis Status ==="));
                        ctx.getSource().sendFeedback(Component.literal("Bridge: §a"
                                + (ConfigManager.getConfig().isEnabled() ? "enabled" : "disabled")
                                + "§f, State: §a" + controller.getState()));
                        ctx.getSource().sendFeedback(Component.literal("Device: "
                                + describeDevice()));
                        ctx.getSource().sendFeedback(Component.literal("Shader Pack: " + controller.getActiveShaderPackName()));
                        ctx.getSource().sendFeedback(Component.literal("Frames Processed: " + controller.getFrameCount()));
                        ctx.getSource().sendFeedback(Component.literal("Compiled Shaders: " + controller.getCompiledShadersCount()));
                        ctx.getSource().sendFeedback(Component.literal("Last Compile Error: " + controller.getLastCompileError()));
                        ctx.getSource().sendFeedback(Component.literal("Last Link Error: " + controller.getLastLinkError()));
                        ctx.getSource().sendFeedback(Component.literal("Active Pass: " + controller.getActivePass()
                                + ", Target: " + controller.getCurrentTarget()));
                        ctx.getSource().sendFeedback(Component.literal("Pending Reload: " + controller.isPendingReload()
                                + ", Fallback: " + (controller.isFallbackTriggered()
                                        ? DiagnosticManager.getLastFallbackReason() : "no")));
                        ctx.getSource().sendFeedback(Component.literal("Debug Render Mode: §b" + ConfigManager.getConfig().getDebugRenderMode()));
                        ctx.getSource().sendFeedback(Component.literal("Iris Pipeline Link: " + ConfigManager.getConfig().isIrisPipelineLink()));
                        ctx.getSource().sendFeedback(Component.literal("Caches (hit/miss): translate "
                                + com.vulkairis.translation.TranslationCache.hits() + "/"
                                + com.vulkairis.translation.TranslationCache.misses() + ", spirv "
                                + com.vulkairis.translation.ShaderCompileCache.hits() + "/"
                                + com.vulkairis.translation.ShaderCompileCache.misses() + ", pipelines "
                                + com.vulkairis.vulkan.pipeline.PipelineManager.memoryHits() + "/"
                                + com.vulkairis.vulkan.pipeline.PipelineManager.memoryMisses()));
                        String guidance = controller.getFallbackGuidance();
                        if (!guidance.isBlank()) {
                            ctx.getSource().sendFeedback(Component.literal("§e" + guidance));
                        }
                        // Phase E: mirror the structured fallback engagement and the chain plan
                        // verbatim, so status never implies shaders work when the bridge fell back.
                        for (String line : statusExtraLines()) {
                            ctx.getSource().sendFeedback(Component.literal("§7" + line));
                        }
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("vulkan").executes(ctx -> {
                        VulkanCapabilities caps = VulkanCapabilities.getActiveCapabilities();
                        ctx.getSource().sendFeedback(Component.literal("§6=== Vulkan Capabilities ==="));
                        ctx.getSource().sendFeedback(Component.literal("GPU: §b" + caps.getDeviceName()));
                        ctx.getSource().sendFeedback(Component.literal("Vendor: " + caps.getVendorName()));
                        ctx.getSource().sendFeedback(Component.literal("Vulkan API: " + caps.getApiVersionString()));
                        ctx.getSource().sendFeedback(Component.literal("Device Type: " + caps.getDeviceType()));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("shader").executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal("§6=== Vulkairis Shader Diagnostics ==="));
                        ctx.getSource().sendFeedback(Component.literal("Active Pack: " + IrisBridge.getCurrentPackName()));
                        ctx.getSource().sendFeedback(Component.literal("Last Fallback: " + DiagnosticManager.getLastFallbackReason()));
                        // Phase E: the full structured engagement plus substitute-final labeling,
                        // verbatim. A substitute terminal is a present-equivalent, never a real final.
                        for (String line : shaderExtraLines()) {
                            ctx.getSource().sendFeedback(Component.literal("§7" + line));
                        }
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("targets").executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal("§6=== Vulkairis Render Targets ==="));
                        com.vulkairis.rendering.exec.PackRenderPipeline pipeline =
                                com.vulkairis.rendering.exec.VulkairisFrameCoordinator.getActivePipeline();
                        if (pipeline == null || pipeline.getTargets() == null) {
                            ctx.getSource().sendFeedback(Component.literal("§7No active target set."));
                        } else {
                            for (String line : com.vulkairis.resource.TargetViewer
                                    .describe(pipeline.getTargets())) {
                                ctx.getSource().sendFeedback(Component.literal("§7" + line));
                            }
                        }
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("programs").executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal("§6=== Vulkairis Program Discovery ==="));
                        com.vulkairis.iris.ShaderPackProgramCatalog catalog = IrisBridge.getProgramCatalog();
                        if (catalog == null) {
                            ctx.getSource().sendFeedback(Component.literal("§7No shader pack programs discovered."));
                        } else {
                            for (String line : catalog.describe().split("\n")) {
                                ctx.getSource().sendFeedback(Component.literal("§7" + line));
                            }
                        }
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("compatibility").executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal("§6=== Vulkairis Compatibility ==="));
                        com.vulkairis.compatibility.ShaderPackCompatibilityReport report =
                                com.vulkairis.compatibility.ShaderPackCompatibilityReport.evaluate(
                                        IrisBridge.getCurrentPackName(), IrisBridge.getProgramCatalog(), true);
                        com.vulkairis.rendering.exec.PassExecutionRecord record =
                                com.vulkairis.rendering.exec.VulkairisFrameCoordinator.lastExecutionRecord();
                        if (IrisBridge.getProgramCatalog() != null && record.size() > 0) {
                            report = report.withRuntime(IrisBridge.getProgramCatalog(), record);
                        }
                        for (String line : report.summarize().split("\n")) {
                            ctx.getSource().sendFeedback(Component.literal("§7" + line));
                        }
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("architecture").executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal("§6=== Vulkairis Architecture Report ==="));
                        com.vulkairis.diagnostics.VulkairisArchitectureReport report =
                                com.vulkairis.diagnostics.VulkairisArchitectureReport.capture();
                        for (String line : report.render().split("\n")) {
                            ctx.getSource().sendFeedback(Component.literal("§7" + line));
                        }
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("reload").executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal("§e[Vulkairis] Triggering shader pack reload..."));
                        IrisBridge.onShaderPackChanged();
                        ctx.getSource().sendFeedback(Component.literal("§a[Vulkairis] Reload complete."));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("mode")
                            .then(ClientCommandManager.literal("off").executes(ctx -> setMode(ctx, DebugRenderMode.OFF)))
                            .then(ClientCommandManager.literal("triangle").executes(ctx -> setMode(ctx, DebugRenderMode.TRIANGLE)))
                            .then(ClientCommandManager.literal("shader_test").executes(ctx -> setMode(ctx, DebugRenderMode.SHADER_TEST)))
                            .then(ClientCommandManager.literal("normal").executes(ctx -> setMode(ctx, DebugRenderMode.NORMAL)))
                            .then(ClientCommandManager.literal("flat").executes(ctx -> setMode(ctx, DebugRenderMode.FLAT)))
                            .then(ClientCommandManager.literal("uv").executes(ctx -> setMode(ctx, DebugRenderMode.UV)))
                            .then(ClientCommandManager.literal("lightmap").executes(ctx -> setMode(ctx, DebugRenderMode.LIGHTMAP)))
                            .then(ClientCommandManager.literal("depth").executes(ctx -> setMode(ctx, DebugRenderMode.DEPTH)))
                            .then(ClientCommandManager.literal("no_post").executes(ctx -> setMode(ctx, DebugRenderMode.NO_POST)))
                            .then(ClientCommandManager.literal("no-post").executes(ctx -> setMode(ctx, DebugRenderMode.NO_POST)))
                            .then(ClientCommandManager.literal("nopost").executes(ctx -> setMode(ctx, DebugRenderMode.NO_POST))))
                    .then(ClientCommandManager.literal("link")
                            .then(ClientCommandManager.literal("on").executes(ctx -> setLink(ctx, true)))
                            .then(ClientCommandManager.literal("off").executes(ctx -> setLink(ctx, false))))
                    .then(ClientCommandManager.literal("test").executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal("§e[Vulkairis] Executing Phase 3 Standalone Vulkan Test..."));
                        boolean ok = StandaloneVulkanTest.initTestPipeline();
                        if (ok) {
                            ctx.getSource().sendFeedback(Component.literal("§a[Vulkairis] Standalone Vulkan pipeline test passed!"));
                        } else {
                            ctx.getSource().sendFeedback(Component.literal("§c[Vulkairis] Standalone Vulkan pipeline test failed."));
                        }
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("config").executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal("§e[Vulkairis] Opening settings..."));
                        net.minecraft.client.Minecraft.getInstance().setScreen(
                                new com.vulkairis.client.gui.VulkairisConfigScreen(
                                        net.minecraft.client.Minecraft.getInstance().screen,
                                        com.vulkairis.client.gui.VulkairisGuiModels::forCategory));
                        return 1;
                    }))
            );
        });
    }

    private static String describeDevice() {
        try {
            boolean live = com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(
                    com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice());
            return live ? "available" : "unavailable";
        } catch (Throwable t) {
            return "unknown (" + t.getMessage() + ")";
        }
    }

    /**
     * Phase E: the extra {@code /vulkairis status} lines — the structured fallback engagement
     * plus the chain plan — built without any client/command objects so tests can assert the
     * status text carries the fallback reason verbatim. The command handler sends exactly these
     * lines.
     */
    public static java.util.List<String> statusExtraLines() {
        java.util.List<String> lines = new java.util.ArrayList<>(
                VulkairisController.getInstance().getFallbackStatusLines());
        lines.addAll(com.vulkairis.rendering.exec.VulkairisFrameCoordinator.describeChainPlan());
        return java.util.List.copyOf(lines);
    }

    /**
     * Phase E: the extra {@code /vulkairis shader} lines — the structured fallback engagement
     * plus the terminal-step labeling (real final vs explicitly labeled substitute). The command
     * handler sends exactly these lines.
     */
    public static java.util.List<String> shaderExtraLines() {
        java.util.List<String> lines = new java.util.ArrayList<>(
                VulkairisController.getInstance().getFallbackStatusLines());
        com.vulkairis.rendering.exec.PackChainPlan plan =
                com.vulkairis.rendering.exec.VulkairisFrameCoordinator.getChainPlan();
        if (plan == null) {
            lines.add("final: none (no chain plan installed)");
        } else {
            lines.add(plan.finalLabel());
        }
        return java.util.List.copyOf(lines);
    }

    private static int setMode(CommandContext<FabricClientCommandSource> ctx, DebugRenderMode mode) {        ConfigManager.getConfig().setDebugRenderMode(mode);
        ConfigManager.saveConfig();
        ctx.getSource().sendFeedback(Component.literal("§a[Vulkairis] Debug render mode set to §b" + mode));
        if (mode == DebugRenderMode.SHADER_TEST) {
            ctx.getSource().sendFeedback(Component.literal("§7Edit run/vulkairis/debug_shader.vert/.frag to see the quad change."));
        }
        return 1;
    }

    private static int setLink(CommandContext<FabricClientCommandSource> ctx, boolean enabled) {
        ConfigManager.getConfig().setIrisPipelineLink(enabled);
        ConfigManager.saveConfig();
        ctx.getSource().sendFeedback(Component.literal("§a[Vulkairis] Iris pipeline link: §b" + enabled
                + " §7(requires shader reload to take effect)"));
        return 1;
    }
}
