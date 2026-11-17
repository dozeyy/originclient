package com.vulkairis.test;

import com.vulkairis.bridge.CustomUniformExpression;
import com.vulkairis.bridge.ShadowFrameLag;
import com.vulkairis.bridge.TerrainTargets;
import com.vulkairis.bridge.TerrainUniforms;
import com.vulkairis.bridge.UniformBridge;
import com.vulkairis.bridge.VulkanModProgramBridge;
import com.vulkairis.rendering.FramebufferValidator;
import com.vulkairis.rendering.RenderStateTracker;
import com.vulkairis.rendering.exec.PassGates;
import com.vulkairis.resource.RenderTargetRegistry;
import com.vulkairis.resource.TargetDescriptor;
import com.vulkairis.rendering.state.FrameState;
import com.vulkairis.rendering.state.ProgramUniformStore;
import com.vulkairis.rendering.state.ResourceBindings;
import com.vulkairis.rendering.state.UniformFrameStore;
import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.vulkan.descriptor.DescriptorContractValidator;
import com.vulkairis.vulkan.descriptor.DescriptorSetLayoutBuilder;
import com.vulkairis.vulkan.descriptor.TerrainDescriptorSwap;
import com.vulkairis.vulkan.pipeline.ShadowCulling;
import com.vulkairis.vulkan.pipeline.TerrainPipelineDoor;
import com.vulkairis.vulkan.pipeline.TerrainVertexContract;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Phase D terrain door: mesh contract, compile/render decisions, pack targets, shadow
 * timing/culling, per-frame uniforms, and honest fallback reasons. All device-free.
 */
public class PhaseDTerrainDoorTest {

    // -- Mesh contract: Sodium 0.8 base + five appended attributes, settled once. --

    @Test
    public void testVertexContractLayout() {
        Assertions.assertEquals(20, TerrainVertexContract.BASE_STRIDE_BYTES);
        Assertions.assertEquals(20 + 4 + 8 + 12 + 16 + 8,
                TerrainVertexContract.EXTENDED_STRIDE_BYTES);
        Assertions.assertEquals(List.of("BLOCK_ID", "MID_TEX_COORD", "MID_BLOCK",
                "TANGENT_FRAME", "TINT_AND_AO"),
                TerrainVertexContract.EXTENDED_ATTRIBUTES.stream()
                        .map(TerrainVertexContract.TerrainAttribute::name).toList());

        int running = TerrainVertexContract.BASE_STRIDE_BYTES;
        for (TerrainVertexContract.TerrainAttribute attr
                : TerrainVertexContract.EXTENDED_ATTRIBUTES) {
            Assertions.assertEquals(running, TerrainVertexContract.offsetOf(attr.name()));
            running += attr.sizeBytes();
        }
        Assertions.assertEquals(running, TerrainVertexContract.EXTENDED_STRIDE_BYTES);

        Map<String, Integer> locations = TerrainVertexContract.inputLocations(5);
        Assertions.assertEquals(5, locations.get("BLOCK_ID"));
        Assertions.assertEquals(9, locations.get("TINT_AND_AO"));

        Assertions.assertTrue(TerrainVertexContract.validate(
                TerrainVertexContract.BASE_STRIDE_BYTES,
                TerrainVertexContract.EXTENDED_STRIDE_BYTES).isEmpty());
        Assertions.assertFalse(TerrainVertexContract.validate(24,
                TerrainVertexContract.EXTENDED_STRIDE_BYTES).isEmpty());
        Assertions.assertFalse(TerrainVertexContract.validate(
                TerrainVertexContract.BASE_STRIDE_BYTES, 64).isEmpty());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TerrainVertexContract.offsetOf("NOPE"));
    }

    @Test
    public void testVertexContractSettlesOnce() {
        TerrainVertexContract.resetForTests();
        Assertions.assertFalse(TerrainVertexContract.isSettled());
        Assertions.assertTrue(TerrainVertexContract.settle("init"));
        Assertions.assertTrue(TerrainVertexContract.isSettled());
        Assertions.assertEquals("init", TerrainVertexContract.settledBy());
        Assertions.assertFalse(TerrainVertexContract.settle("second"),
                "second settle must be a no-op");
        Assertions.assertEquals("init", TerrainVertexContract.settledBy());
        TerrainVertexContract.resetForTests();
        Assertions.assertTrue(TerrainVertexContract.settle("IrisRenderSystem.initRenderer"));
    }

    // -- Terrain door: compile/render decisions around the frozen Sodium path. --

    @Test
    public void testDoorPassMapping() {
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.TERRAIN,
                TerrainPipelineDoor.forPassName("TERRAIN"));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.SHADOW_CUTOUT,
                TerrainPipelineDoor.forPassName("shadow_cutout"));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.TERRAIN,
                TerrainPipelineDoor.forPassName("unknown-pass"),
                "unknown passes fall back to TERRAIN, never throw");
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.TERRAIN,
                TerrainPipelineDoor.forPassName(null));
        Assertions.assertFalse(TerrainPipelineDoor.isKnownPassName("nope"));
        Assertions.assertTrue(TerrainPipelineDoor.isKnownPassName("TRANSLUCENT"));
        Assertions.assertEquals(6, TerrainPipelineDoor.knownPassNames().size());

        Assertions.assertEquals("gbuffers_terrain",
                TerrainPipelineDoor.programNameFor(TerrainPipelineDoor.TerrainPass.TERRAIN));
        Assertions.assertEquals("shadow",
                TerrainPipelineDoor.programNameFor(TerrainPipelineDoor.TerrainPass.SHADOW));
        Assertions.assertEquals("colortex0",
                TerrainPipelineDoor.targetFor(TerrainPipelineDoor.TerrainPass.TERRAIN));
        Assertions.assertEquals("shadowtex0",
                TerrainPipelineDoor.targetFor(TerrainPipelineDoor.TerrainPass.SHADOW));

        Assertions.assertTrue(TerrainPipelineDoor.isShadow(TerrainPipelineDoor.TerrainPass.SHADOW));
        Assertions.assertFalse(TerrainPipelineDoor.isShadow(TerrainPipelineDoor.TerrainPass.TERRAIN));
        Assertions.assertTrue(TerrainPipelineDoor.requiresBlend(TerrainPipelineDoor.TerrainPass.TRANSLUCENT));
        Assertions.assertFalse(TerrainPipelineDoor.requiresDepthWrite(
                TerrainPipelineDoor.TerrainPass.TRANSLUCENT));
        Assertions.assertTrue(TerrainPipelineDoor.requiresDepthWrite(
                TerrainPipelineDoor.TerrainPass.TERRAIN));
    }

    @Test
    public void testDoorCompileNeedsLinkedPipeline() {
        TerrainPipelineDoor.DoorDecision missing = TerrainPipelineDoor.decideCompile(
                TerrainPipelineDoor.TerrainPass.TERRAIN, 0);
        Assertions.assertFalse(missing.usePackPipeline());
        Assertions.assertFalse(missing.fallbackReason().isBlank());
        Assertions.assertTrue(missing.fallbackReason().contains("gbuffers_terrain"));

        TerrainPipelineDoor.DoorDecision pack = TerrainPipelineDoor.decideCompile(
                TerrainPipelineDoor.TerrainPass.SHADOW, 77);
        Assertions.assertTrue(pack.usePackPipeline());
        Assertions.assertEquals("shadow", pack.passLabel());
        Assertions.assertEquals("shadowtex0", pack.targetLabel());
    }

    @Test
    public void testDoorDescriptorSwapValidation() {
        List<String> noReflection = TerrainPipelineDoor.checkDescriptorSwap(
                null, 16L, List.of(), "gbuffers_terrain");
        Assertions.assertFalse(noReflection.isEmpty());

        ShaderReflection reflection = new ShaderReflection();
        reflection.addUniformBlock("VulkairisUniformBlock", 0, 0, 16, 80, List.of(
                new ShaderReflection.UniformInfo("projection", "mat4", 0, 64)));
        reflection.addSampler("colortex0", 1, 0);
        List<DescriptorSetLayoutBuilder.DescriptorBinding> plan =
                DescriptorSetLayoutBuilder.forGraphics(reflection, 16L);
        List<DescriptorContractValidator.BindingSpec> provided =
                DescriptorSetLayoutBuilder.toSpecs(plan);
        Assertions.assertTrue(TerrainPipelineDoor.checkDescriptorSwap(
                reflection, 16L, provided, "gbuffers_terrain").isEmpty());

        List<String> mismatch = TerrainPipelineDoor.checkDescriptorSwap(
                reflection, 16L, List.of(), "gbuffers_terrain");
        Assertions.assertFalse(mismatch.isEmpty());

        TerrainPipelineDoor.DoorDecision renderFallback = TerrainPipelineDoor.decideRender(
                TerrainPipelineDoor.TerrainPass.TERRAIN, 0, reflection, 16L, provided);
        Assertions.assertFalse(renderFallback.usePackPipeline(),
                "unlinked compile must dominate a valid descriptor plan");
    }

    @Test
    public void testDoorHandedPassResolution() {
        // Behavioral mapping (Iris mapTerrainRenderPass equivalent): translucent wins,
        // then fragment-discard cutout, else solid terrain.
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.TERRAIN,
                TerrainPipelineDoor.resolveHandedPass(false, false));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.TERRAIN_CUTOUT,
                TerrainPipelineDoor.resolveHandedPass(false, true));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.TRANSLUCENT,
                TerrainPipelineDoor.resolveHandedPass(true, false));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.TRANSLUCENT,
                TerrainPipelineDoor.resolveHandedPass(true, true));

        // Shadow bit: every base pass reaches its shadow variant; shadow stays shadow.
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.SHADOW,
                TerrainPipelineDoor.withShadow(TerrainPipelineDoor.TerrainPass.TERRAIN, true));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.SHADOW_CUTOUT,
                TerrainPipelineDoor.withShadow(TerrainPipelineDoor.TerrainPass.TERRAIN_CUTOUT, true));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.SHADOW_TRANS,
                TerrainPipelineDoor.withShadow(TerrainPipelineDoor.TerrainPass.TRANSLUCENT, true));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.SHADOW,
                TerrainPipelineDoor.withShadow(TerrainPipelineDoor.TerrainPass.SHADOW, true));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.SHADOW,
                TerrainPipelineDoor.withShadow(null, true));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.TERRAIN,
                TerrainPipelineDoor.withShadow(TerrainPipelineDoor.TerrainPass.TERRAIN, false));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.TERRAIN,
                TerrainPipelineDoor.withShadow(null, false));

        // Combined: cutout and shadow are reachable, never collapsed to TERRAIN.
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.SHADOW_CUTOUT,
                TerrainPipelineDoor.resolveHandedPass(false, true, true));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.SHADOW_TRANS,
                TerrainPipelineDoor.resolveHandedPass(true, false, true));
        Assertions.assertEquals(TerrainPipelineDoor.TerrainPass.TERRAIN_CUTOUT,
                TerrainPipelineDoor.resolveHandedPass(false, true, false));

        Assertions.assertTrue(TerrainPipelineDoor.isCutout(TerrainPipelineDoor.TerrainPass.TERRAIN_CUTOUT));
        Assertions.assertTrue(TerrainPipelineDoor.isCutout(TerrainPipelineDoor.TerrainPass.SHADOW_CUTOUT));
        Assertions.assertFalse(TerrainPipelineDoor.isCutout(TerrainPipelineDoor.TerrainPass.TERRAIN));
        Assertions.assertFalse(TerrainPipelineDoor.isCutout(TerrainPipelineDoor.TerrainPass.TRANSLUCENT));
        Assertions.assertFalse(TerrainPipelineDoor.isCutout(null));
    }

    @Test
    public void testDoorRenderLenientGate() {
        ShaderReflection reflection = new ShaderReflection();
        reflection.addUniformBlock("VulkairisUniformBlock", 0, 0, 16, 80, List.of(
                new ShaderReflection.UniformInfo("projection", "mat4", 0, 64)));
        reflection.addSampler("colortex0", 1, 0);
        long flags = 16L;
        List<DescriptorContractValidator.BindingSpec> provided =
                DescriptorSetLayoutBuilder.toSpecs(
                        DescriptorSetLayoutBuilder.forGraphics(reflection, flags));

        // Unlinked compile dominates even a valid descriptor plan.
        TerrainPipelineDoor.DoorDecision unlinked = TerrainPipelineDoor.decideRenderLenient(
                TerrainPipelineDoor.TerrainPass.TERRAIN, 0, reflection, flags, provided);
        Assertions.assertFalse(unlinked.usePackPipeline());

        // Unknown gate inputs keep the compile verdict and name the skip.
        TerrainPipelineDoor.DoorDecision noReflection = TerrainPipelineDoor.decideRenderLenient(
                TerrainPipelineDoor.TerrainPass.TERRAIN, 77, null, flags, provided);
        Assertions.assertTrue(noReflection.usePackPipeline());
        TerrainPipelineDoor.DoorDecision noProvided = TerrainPipelineDoor.decideRenderLenient(
                TerrainPipelineDoor.TerrainPass.TERRAIN, 77, reflection, flags, null);
        Assertions.assertTrue(noProvided.usePackPipeline());
        Assertions.assertFalse(TerrainPipelineDoor.isDescriptorGateApplicable(null, provided));
        Assertions.assertFalse(TerrainPipelineDoor.isDescriptorGateApplicable(reflection, null));
        Assertions.assertTrue(TerrainPipelineDoor.isDescriptorGateApplicable(reflection, provided));
        Assertions.assertFalse(TerrainPipelineDoor.descriptorGateSkipReason(null, provided).isBlank());
        Assertions.assertFalse(TerrainPipelineDoor.descriptorGateSkipReason(reflection, null).isBlank());
        Assertions.assertTrue(TerrainPipelineDoor.descriptorGateSkipReason(reflection, provided).isBlank());

        // Complete inputs run the strict gate: valid plan passes, mismatch refuses.
        TerrainPipelineDoor.DoorDecision valid = TerrainPipelineDoor.decideRenderLenient(
                TerrainPipelineDoor.TerrainPass.TERRAIN, 77, reflection, flags, provided);
        Assertions.assertTrue(valid.usePackPipeline());
        TerrainPipelineDoor.DoorDecision mismatch = TerrainPipelineDoor.decideRenderLenient(
                TerrainPipelineDoor.TerrainPass.TERRAIN, 77, reflection, flags, List.of());
        Assertions.assertFalse(mismatch.usePackPipeline());
        Assertions.assertFalse(mismatch.fallbackReason().isBlank());

        // Incomplete reflection is untrustworthy partial data: refuse with a reason.
        ShaderReflection incomplete = new ShaderReflection();
        incomplete.markIncomplete();
        TerrainPipelineDoor.DoorDecision partial = TerrainPipelineDoor.decideRenderLenient(
                TerrainPipelineDoor.TerrainPass.TERRAIN, 77, incomplete, flags, List.of());
        Assertions.assertFalse(partial.usePackPipeline());
        Assertions.assertTrue(partial.fallbackReason().contains("incomplete"));
    }

    @Test
    public void testVertexContractBlockEvidence() {
        Assertions.assertEquals(List.of("Position", "Color", "UV0", "UV2", "Normal"),
                TerrainVertexContract.BLOCK_ATTRIBUTE_NAMES);

        Assertions.assertTrue(TerrainVertexContract.isCoveredByBlock(
                List.of("Position", "Color", "UV0", "UV2", "Normal")));
        Assertions.assertTrue(TerrainVertexContract.isCoveredByBlock(List.of()));
        Assertions.assertTrue(TerrainVertexContract.isCoveredByBlock(null));
        Assertions.assertFalse(TerrainVertexContract.isCoveredByBlock(List.of("BLOCK_ID")));
        Assertions.assertFalse(TerrainVertexContract.isCoveredByBlock(
                List.of("Position", "TANGENT_FRAME")));

        Assertions.assertEquals(5, TerrainVertexContract.offsets().size());
        for (String name : TerrainVertexContract.EXTENDED_ATTRIBUTES.stream()
                .map(TerrainVertexContract.TerrainAttribute::name).toList()) {
            Assertions.assertEquals(TerrainVertexContract.offsetOf(name),
                    TerrainVertexContract.offsets().get(name));
        }

        String evidence = TerrainVertexContract.describeBlockMapping();
        Assertions.assertTrue(evidence.contains("68"), evidence);
        Assertions.assertTrue(evidence.contains("BLOCK"), evidence);
    }

    @Test
    public void testBridgeTerrainVertexHelpers() {
        Assertions.assertEquals(68, VulkanModProgramBridge.terrainVertexStride());
        Assertions.assertEquals(TerrainVertexContract.EXTENDED_STRIDE_BYTES,
                VulkanModProgramBridge.terrainVertexStride());
        Assertions.assertEquals(TerrainVertexContract.BASE_STRIDE_BYTES,
                VulkanModProgramBridge.terrainVertexBaseStride());
        Assertions.assertEquals(TerrainVertexContract.offsets(),
                VulkanModProgramBridge.terrainVertexOffsets());
        Assertions.assertEquals(TerrainVertexContract.inputLocations(5),
                VulkanModProgramBridge.terrainVertexInputLocations(5));
        Assertions.assertTrue(VulkanModProgramBridge.isTerrainVertexCoveredByBlock(
                List.of("Position", "Color", "UV0", "UV2", "Normal")));
        Assertions.assertFalse(VulkanModProgramBridge.isTerrainVertexCoveredByBlock(
                List.of("BLOCK_ID")));
        Assertions.assertFalse(VulkanModProgramBridge.terrainVertexEvidence().isBlank());
    }

    @Test
    public void testBridgeTerrainRenderHelpers() {
        Assertions.assertEquals(0x1L | 0x10L, VulkanModProgramBridge.TERRAIN_STAGE_FLAGS);

        // Headless lookups report unknown programs as null and never throw. ("gbuffers_terrain"
        // is deliberately not asserted here: earlier runner blocks register that name in the
        // shared registry, so its presence is order-dependent, not a helper contract.)
        Assertions.assertNull(VulkanModProgramBridge.terrainReflectionFor("no-such-terrain-program"));
        Assertions.assertNull(VulkanModProgramBridge.terrainReflectionFor(null));
        Assertions.assertNull(VulkanModProgramBridge.terrainReflectionFor("   "));
        ShaderReflection reflection = new ShaderReflection();
        reflection.addSampler("colortex0", 1, 0);
        Assertions.assertNull(VulkanModProgramBridge.terrainProvidedFor(
                0, reflection, VulkanModProgramBridge.TERRAIN_STAGE_FLAGS));
        Assertions.assertNull(VulkanModProgramBridge.terrainProvidedFor(
                4242, reflection, VulkanModProgramBridge.TERRAIN_STAGE_FLAGS));
        Assertions.assertNull(VulkanModProgramBridge.terrainProvidedFor(
                4242, null, VulkanModProgramBridge.TERRAIN_STAGE_FLAGS));
    }

    @Test
    public void testPackPassMirror() {
        RenderStateTracker tracker = RenderStateTracker.getInstance();
        tracker.observePackPass("gbuffers_terrain", "colortex0");
        Assertions.assertEquals("gbuffers_terrain", tracker.getLastPackPass());
        Assertions.assertEquals("colortex0", tracker.getLastPackTarget());
        tracker.observePackPass(null, null);
        Assertions.assertEquals("none", tracker.getLastPackPass());
        Assertions.assertEquals("none", tracker.getLastPackTarget());
    }

    @Test
    public void testFallbackReasonNamesEverything() {
        String reason = TerrainPipelineDoor.fallbackReason(
                "vulkan-pack", "vanilla", "terrain-compile", "pack:gbuffers_terrain", "no pipeline");
        Assertions.assertTrue(reason.contains("requested=vulkan-pack"));
        Assertions.assertTrue(reason.contains("effective=vanilla"));
        Assertions.assertTrue(reason.contains("stage=terrain-compile"));
        Assertions.assertTrue(reason.contains("pack+shader=pack:gbuffers_terrain"));
        Assertions.assertTrue(reason.contains("cause=no pipeline"));

        String blanks = TerrainPipelineDoor.fallbackReason(null, "", null, null, null);
        Assertions.assertTrue(blanks.contains("none"));
    }

    @Test
    public void testBridgeTerrainRegistrationIsHonestHeadless() {
        VulkanModProgramBridge.clearTerrainRegistrations();
        Assertions.assertEquals(0, VulkanModProgramBridge.terrainLinkedId("gbuffers_terrain"));
        VulkanModProgramBridge.noteTerrainProgram("gbuffers_terrain", 4242);
        // No live LINKED pipeline headless, so the id is not reported as linked.
        Assertions.assertEquals(0, VulkanModProgramBridge.terrainLinkedId("gbuffers_terrain"));
        VulkanModProgramBridge.noteTerrainProgram("gbuffers_terrain", 0);
        Assertions.assertEquals(0, VulkanModProgramBridge.terrainLinkedId("gbuffers_terrain"));

        TerrainPipelineDoor.DoorDecision decision =
                VulkanModProgramBridge.terrainCompileDecision("TERRAIN");
        Assertions.assertFalse(decision.usePackPipeline());
        Assertions.assertFalse(decision.fallbackReason().isBlank());
        VulkanModProgramBridge.clearTerrainRegistrations();
    }

    // -- Descriptor swap helper. --

    @Test
    public void testDescriptorSwapDecisions() {
        TerrainDescriptorSwap.SwapDecision noReflection =
                TerrainDescriptorSwap.decide(null, 16L, List.of(), "terrain");
        Assertions.assertFalse(noReflection.usePackDescriptor());
        Assertions.assertFalse(noReflection.fallbackReason().isBlank());

        ShaderReflection empty = new ShaderReflection();
        TerrainDescriptorSwap.SwapDecision emptyPlan =
                TerrainDescriptorSwap.decide(empty, 16L, List.of(), "terrain");
        Assertions.assertTrue(emptyPlan.usePackDescriptor(),
                "a program with no descriptors needs no swap");

        ShaderReflection reflection = new ShaderReflection();
        reflection.addUniformBlock("VulkairisUniformBlock", 0, 0, 16, 80, List.of(
                new ShaderReflection.UniformInfo("projection", "mat4", 0, 64)));
        reflection.addSampler("colortex0", 1, 0);
        List<DescriptorContractValidator.BindingSpec> provided =
                DescriptorSetLayoutBuilder.toSpecs(
                        DescriptorSetLayoutBuilder.forGraphics(reflection, 16L));
        Assertions.assertTrue(TerrainDescriptorSwap.decide(
                reflection, 16L, provided, "terrain").usePackDescriptor());
        TerrainDescriptorSwap.SwapDecision mismatch = TerrainDescriptorSwap.decide(
                reflection, 16L, List.of(), "terrain");
        Assertions.assertFalse(mismatch.usePackDescriptor());
        Assertions.assertFalse(mismatch.problems().isEmpty());

        Assertions.assertFalse(TerrainDescriptorSwap.groupedPlan(null, 16L).size() > 0);
        Assertions.assertEquals(2, TerrainDescriptorSwap.groupedPlan(reflection, 16L).size(),
                "blocks in FRAME set, samplers in PASS set");
    }

    // -- Targets: format/dimension/binding/lifetime + mip/resize + forward-Z. --

    @Test
    public void testPackTargetsCreateAndValidate() {
        RenderTargetRegistry registry = TerrainTargets.create(1920, 1080, 4, 2048);
        Assertions.assertTrue(registry.get("colortex0").isPresent());
        Assertions.assertTrue(registry.get("colortex3").isPresent());
        Assertions.assertTrue(registry.get("colortex4").isEmpty());
        Assertions.assertEquals(2048, registry.get("shadowtex0").orElseThrow().getWidth());
        Assertions.assertEquals(1.0f, TerrainTargets.SHADOW_CLEAR_DEPTH, 1e-6f);

        List<String> problems = TerrainTargets.validate(registry, 1920, 1080, 2048);
        Assertions.assertTrue(problems.isEmpty(), problems.toString());

        List<TargetDescriptor> descriptors = TerrainTargets.descriptors(2);
        Assertions.assertEquals("colortex0", descriptors.get(0).logicalId());
        TargetDescriptor shadow = descriptors.stream()
                .filter(d -> d.logicalId().equals("shadowtex0")).findFirst().orElseThrow();
        Assertions.assertEquals(TargetDescriptor.Role.DEPTH, shadow.role());
        Assertions.assertEquals(1.0f, shadow.clearDepth(), 1e-6f);

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TerrainTargets.create(0, 1080, 1, 1024));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TerrainTargets.create(1920, 1080, 0, 1024));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TerrainTargets.create(1920, 1080, 1, 2000));
        Assertions.assertFalse(TerrainTargets.validate(null, 1920, 1080, 2048).isEmpty());
    }

    @Test
    public void testPackTargetsResizeAndMip() {
        RenderTargetRegistry registry = TerrainTargets.create(1280, 720, 2, 1024);
        Assertions.assertTrue(TerrainTargets.resize(registry, 1920, 1080));
        Assertions.assertEquals(1920, registry.get("colortex0").orElseThrow().getWidth());
        Assertions.assertEquals(1024, registry.get("shadowtex0").orElseThrow().getWidth(),
                "shadow map keeps its fixed resolution");
        Assertions.assertFalse(TerrainTargets.resize(registry, 1920, 1080), "no-op resize");
        Assertions.assertFalse(TerrainTargets.resize(registry, 0, 100));
        Assertions.assertFalse(TerrainTargets.resize(null, 1920, 1080));

        Assertions.assertEquals(1, TerrainTargets.mipLevelsFor(1, 1));
        Assertions.assertEquals(2, TerrainTargets.mipLevelsFor(2, 2));
        Assertions.assertEquals(11, TerrainTargets.mipLevelsFor(1920, 1080),
                "full chain from min(1920,1080)=1080 down to 1x1");
    }

    @Test
    public void testPackTargetsDimensionMismatchReported() {
        RenderTargetRegistry registry = TerrainTargets.create(1280, 720, 1, 1024);
        List<String> problems = TerrainTargets.validate(registry, 1920, 1080, 1024);
        Assertions.assertTrue(problems.stream().anyMatch(p -> p.contains("colortex0")),
                problems.toString());
    }

    @Test
    public void testPackTargetFramebufferValidation() {
        FramebufferValidator.Verdict ok = TerrainTargets.validateFramebuffer(7, Map.of(
                36064, new ResourceBindings.FramebufferAttachment(36064, 11, 0)));
        Assertions.assertTrue(ok.complete());
        FramebufferValidator.Verdict missing =
                TerrainTargets.validateFramebuffer(7, Map.of());
        Assertions.assertFalse(missing.complete());
    }

    // -- Shadow: one-frame-late sampling + culling. --

    @Test
    public void testShadowFrameLagIsOneFrameLate() {
        ShadowFrameLag lag = new ShadowFrameLag();
        Assertions.assertTrue(lag.sampleFor(1L).isEmpty(), "first frame has no shadow map");
        Assertions.assertFalse(lag.hasShadowFor(1L));

        Matrix4f published = new Matrix4f().perspective(1.0f, 1.0f, 0.1f, 100.0f);
        lag.publish(10L, published);
        Assertions.assertEquals(10L, lag.publishedFrame());
        Assertions.assertTrue(lag.sampleFor(10L).isEmpty(), "same frame must not sample");
        Assertions.assertTrue(lag.hasShadowFor(11L));
        Matrix4f sampled = lag.sampleFor(11L).orElseThrow();
        Assertions.assertEquals(published, sampled);
        Assertions.assertEquals(10L, lag.sampledFrame());
        Assertions.assertTrue(lag.sampleFor(12L).isEmpty(), "two frames late is stale, not sampled");

        lag.invalidate();
        Assertions.assertEquals(-1L, lag.publishedFrame());
        Assertions.assertTrue(ShadowFrameLag.describe().contains("one frame late"));
    }

    @Test
    public void testShadowCulling() {
        Assertions.assertFalse(ShadowCulling.cullByDistance(100.0, 160.0f));
        Assertions.assertTrue(ShadowCulling.cullByDistance(160.0 * 160.0 + 1.0, 160.0f));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ShadowCulling.cullByDistance(10.0, 0.0f));

        Assertions.assertTrue(ShadowCulling.shouldRenderInShadowPass(false, true, true));
        Assertions.assertFalse(ShadowCulling.shouldRenderInShadowPass(true, true, true));
        Assertions.assertFalse(ShadowCulling.shouldRenderInShadowPass(false, false, true));
        Assertions.assertFalse(ShadowCulling.shouldRenderInShadowPass(false, true, false));

        Assertions.assertTrue(ShadowCulling.validate(2048, 160.0f).isEmpty());
        Assertions.assertFalse(ShadowCulling.validate(2000, 160.0f).isEmpty());
        Assertions.assertFalse(ShadowCulling.validate(1024, -1.0f).isEmpty());
    }

    // -- Per-frame uniforms: snapshot-once, per-pass write, tiny expressions. --

    @Test
    public void testCustomExpressionEvaluator() {
        Map<String, float[]> snapshot = Map.of(
                "Frame", new float[]{10.0f},
                "ViewSize", new float[]{1920.0f, 1080.0f});
        Assertions.assertEquals(3.0f,
                CustomUniformExpression.eval("1.0 + 2.0", snapshot).value(), 1e-6f);
        Assertions.assertEquals(14.0f,
                CustomUniformExpression.eval("2.0 + 3.0 * 4.0", snapshot).value(), 1e-6f);
        Assertions.assertEquals(20.0f,
                CustomUniformExpression.eval("(2.0 + 3.0) * 4.0", snapshot).value(), 1e-6f);
        Assertions.assertEquals(11.0f,
                CustomUniformExpression.eval("uniform.Frame + 1.0", snapshot).value(), 1e-6f);
        Assertions.assertEquals(20.0f,
                CustomUniformExpression.eval("variable.Frame * 2.0", snapshot).value(), 1e-6f);
        Assertions.assertEquals(10.0f,
                CustomUniformExpression.eval("Frame", snapshot).value(), 1e-6f);
        Assertions.assertEquals(-5.0f,
                CustomUniformExpression.eval("-5.0", snapshot).value(), 1e-6f);

        Assertions.assertFalse(CustomUniformExpression.eval("uniform.Missing + 1.0", snapshot).ok());
        Assertions.assertFalse(CustomUniformExpression.eval("1.0 / 0.0", snapshot).ok());
        Assertions.assertFalse(CustomUniformExpression.eval("sin(Frame)", snapshot).ok());
        Assertions.assertFalse(CustomUniformExpression.eval("", snapshot).ok());
        Assertions.assertFalse(CustomUniformExpression.eval(null, snapshot).ok());
        Assertions.assertFalse(CustomUniformExpression.isSupportedForm("a ? b : c"));
        Assertions.assertTrue(CustomUniformExpression.isSupportedForm("uniform.Frame * 2.0 + 1.0"));
    }

    @Test
    public void testSnapshotOnceThenPerPassWrite() {
        UniformBridge bridge = new UniformBridge();
        bridge.updateMatrices(new Matrix4f(), new Matrix4f());
        bridge.updateResolution(1920, 1080);
        bridge.updateTime(0.033f, 1000L);
        bridge.updateCamera(1.0f, 2.0f, 3.0f, 9L);
        FrameState frame = new FrameState();
        frame.beginFrame(9L, 0.5f, 0.016f);
        frame.setCameraPosition(1.0f, 2.0f, 3.0f);
        frame.setSunDirection(0.0f, -1.0f, 0.0f);

        Map<String, float[]> snapshot = TerrainUniforms.snapshotOnce(
                frame, bridge, new Vector3f(1.0f, 2.0f, 3.0f), 9L);
        Assertions.assertEquals(16, snapshot.get("ModelViewMat").length);
        Assertions.assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f}, snapshot.get("cameraPosition"), 1e-6f);
        Assertions.assertNotNull(snapshot.get("SunDirection"));
        Assertions.assertNotNull(snapshot.get("FogColor"));
        Assertions.assertNotNull(snapshot.get("NearFar"));

        ShaderReflection reflection = new ShaderReflection();
        reflection.addUniform("CameraPosition", "vec3", 0, 12);
        reflection.addUniform("Frame", "float", 12, 4);
        reflection.addUniform("CustomBoost", "float", 16, 4);
        TerrainUniforms.PassValues pass = TerrainUniforms.resolvePassValues(reflection, snapshot,
                Map.of("CustomBoost", "uniform.Frame + 1.0"));
        Assertions.assertTrue(pass.unresolved().isEmpty(), pass.unresolved().toString());
        Assertions.assertEquals(9.0f, pass.values().get("Frame")[0], 1e-6f);
        Assertions.assertEquals(10.0f, pass.values().get("CustomBoost")[0], 1e-6f);

        UniformFrameStore store = new UniformFrameStore();
        store.beginFrame(9L);
        List<Integer> written = TerrainUniforms.publishToStore(5001, pass, store,
                name -> switch (name) {
                    case "CameraPosition" -> 3;
                    case "Frame" -> 4;
                    case "CustomBoost" -> 5;
                    default -> -1;
                });
        Assertions.assertEquals(3, written.size());
        Assertions.assertEquals(10.0f,
                store.read(5001, 5).orElseThrow().floats()[0], 1e-6f);
    }

    @Test
    public void testPassWriteReportsShortAndMissing() {
        Map<String, float[]> snapshot = Map.of("Frame", new float[]{7.0f});
        ShaderReflection reflection = new ShaderReflection();
        reflection.addUniform("Frame", "float", 0, 4);
        reflection.addUniform("CameraPosition", "vec3", 4, 12);
        reflection.addUniform("ProjMat", "mat4", 16, 64);
        TerrainUniforms.PassValues pass = TerrainUniforms.resolvePassValues(
                reflection, Map.of("Frame", new float[]{7.0f}), Map.of());
        Assertions.assertTrue(pass.values().containsKey("Frame"));
        Assertions.assertTrue(pass.unresolved().stream().anyMatch(s -> s.contains("CameraPosition")));
        Assertions.assertEquals(1, TerrainUniforms.floatsFor("float"));
        Assertions.assertEquals(3, TerrainUniforms.floatsFor("vec3"));
        Assertions.assertEquals(16, TerrainUniforms.floatsFor("mat4"));
        Assertions.assertTrue(TerrainUniforms.resolvePassValues(
                null, snapshot, null).unresolved().size() == 1);

        UniformFrameStore store = new UniformFrameStore();
        store.beginFrame(1L);
        Assertions.assertTrue(TerrainUniforms.publishToStore(
                1, pass, null, name -> 0).isEmpty());
        Assertions.assertTrue(TerrainUniforms.publishToStore(
                1, null, store, name -> 0).isEmpty());
    }

    @Test
    public void testUniformValueKindsPublish() {
        UniformFrameStore store = new UniformFrameStore();
        store.beginFrame(2L);
        store.write(6001, 1, ProgramUniformStore.UniformValue.floats("float", 1.5f));
        Assertions.assertEquals(1.5f, store.read(6001, 1).orElseThrow().floats()[0], 1e-6f);
    }

    // -- Terrain readiness gate: opens on link + valid descriptors, else names the miss. --

    private static ShaderReflection terrainReflection() {
        ShaderReflection reflection = new ShaderReflection();
        reflection.addUniformBlock("VulkairisUniformBlock", 0, 0, 16, 80, List.of(
                new ShaderReflection.UniformInfo("projection", "mat4", 0, 64)));
        reflection.addSampler("colortex0", 1, 0);
        return reflection;
    }

    @Test
    public void testTerrainGateClosedWithoutLinkNamesMissingCondition() {
        PassGates.GateStatus closed = PassGates.terrainStatusFor(
                "gbuffers_terrain", 0, null, null, VulkanModProgramBridge.TERRAIN_STAGE_FLAGS);
        Assertions.assertEquals(PassGates.Gate.TERRAIN, closed.gate());
        Assertions.assertEquals(PassGates.Status.CLOSED, closed.status());
        Assertions.assertTrue(closed.note().contains("no terrain execution path yet"), closed.note());
        Assertions.assertTrue(closed.note().contains("gbuffers_terrain"), closed.note());
        Assertions.assertTrue(closed.note().contains("virtual id 0"), closed.note());
        Assertions.assertTrue(closed.note().contains("vanilla fallback"), closed.note());
    }

    @Test
    public void testTerrainGateOpensOnLinkPlusValidDescriptors() {
        ShaderReflection reflection = terrainReflection();
        long flags = VulkanModProgramBridge.TERRAIN_STAGE_FLAGS;
        List<DescriptorContractValidator.BindingSpec> provided =
                DescriptorSetLayoutBuilder.toSpecs(
                        DescriptorSetLayoutBuilder.forGraphics(reflection, flags));

        PassGates.GateStatus open = PassGates.terrainStatusFor(
                "gbuffers_terrain", 77, reflection, provided, flags);
        Assertions.assertEquals(PassGates.Status.OPEN, open.status());
        Assertions.assertTrue(open.note().contains("77"), open.note());
        Assertions.assertTrue(open.note().contains("gbuffers_terrain"), open.note());

        PassGates.GateStatus mismatch = PassGates.terrainStatusFor(
                "gbuffers_terrain", 77, reflection, List.of(), flags);
        Assertions.assertEquals(PassGates.Status.PARTIAL, mismatch.status());
        Assertions.assertFalse(mismatch.note().isBlank());
        Assertions.assertTrue(mismatch.note().contains("gbuffers_terrain"), mismatch.note());
        Assertions.assertTrue(mismatch.note().contains("vanilla fallback"), mismatch.note());
    }

    @Test
    public void testTerrainGateLiveWiringStaysHonestHeadless() {
        // Headless there is no linked pipeline, so the live table must stay CLOSED while
        // still naming the exact missing condition (never an empty or silent note).
        PassGates.GateStatus live = PassGates.implemented().get(PassGates.Gate.TERRAIN);
        Assertions.assertEquals(PassGates.Status.CLOSED, live.status());
        Assertions.assertTrue(live.note().contains("gbuffers_terrain"), live.note());
    }
}
