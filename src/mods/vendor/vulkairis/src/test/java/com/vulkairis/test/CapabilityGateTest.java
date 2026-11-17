package com.vulkairis.test;

import com.vulkairis.compatibility.CapabilityGate;
import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 12: pack-load capability gates reject unrenderable packs before partial rendering,
 * with one actionable verdict.
 */
public class CapabilityGateTest {

    private static ShaderPackProgram graphics(String name, ProgramGroup group) {
        return new ShaderPackProgram.Graphics(name, group, 0, 1, null, true, true,
                false, false, false, null);
    }

    private static ShaderPackProgram geometry(String name, ProgramGroup group) {
        return new ShaderPackProgram.Graphics(name, group, 0, 1, null, true, true,
                true, false, false, null);
    }

    private static ShaderPackProgram tess(String name, ProgramGroup group) {
        return new ShaderPackProgram.Graphics(name, group, 0, 1, null, true, true,
                false, true, true, null);
    }

    private static ShaderPackProgram compute(String name, ProgramGroup group) {
        return new ShaderPackProgram.Compute(name, group, 0, 1, null, null);
    }

    @Test
    public void testVanillaAccepted() {
        Assertions.assertEquals(CapabilityGate.PackVerdict.ACCEPT,
                CapabilityGate.evaluatePack(null).verdict());
        Assertions.assertEquals(CapabilityGate.PackVerdict.ACCEPT,
                CapabilityGate.evaluatePack(ShaderPackProgramCatalog.of(List.of())).verdict());
    }

    @Test
    public void testCleanPackAccepted() {
        var verdict = CapabilityGate.evaluatePack(ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers),
                graphics("composite", ProgramGroup.Composite),
                graphics("final", ProgramGroup.Final))));
        Assertions.assertEquals(CapabilityGate.PackVerdict.ACCEPT, verdict.verdict());
        Assertions.assertTrue(verdict.notes().isEmpty());
    }

    @Test
    public void testGeometryPackRejectedWithProgram() {
        var verdict = CapabilityGate.evaluatePack(ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers),
                geometry("gbuffers_water", ProgramGroup.Gbuffers))));
        Assertions.assertEquals(CapabilityGate.PackVerdict.REJECT, verdict.verdict());
        Assertions.assertTrue(verdict.notes().stream()
                        .anyMatch(n -> n.contains("gbuffers_water") && n.contains("geometry")),
                verdict.notes().toString());
    }

    @Test
    public void testTessellationPackRejected() {
        var verdict = CapabilityGate.evaluatePack(ShaderPackProgramCatalog.of(List.of(
                tess("gbuffers_terrain", ProgramGroup.Gbuffers))));
        Assertions.assertEquals(CapabilityGate.PackVerdict.REJECT, verdict.verdict());
        Assertions.assertTrue(verdict.notes().stream().anyMatch(n -> n.contains("tessellation")),
                verdict.notes().toString());
    }

    @Test
    public void testPackWithoutExecutablePathRejected() {
        var verdict = CapabilityGate.evaluatePack(ShaderPackProgramCatalog.of(List.of(
                graphics("shadow", ProgramGroup.Shadow))));
        Assertions.assertEquals(CapabilityGate.PackVerdict.REJECT, verdict.verdict());
        Assertions.assertTrue(verdict.notes().stream().anyMatch(n -> n.contains("no")
                && n.contains("execution path")), verdict.notes().toString());
    }

    @Test
    public void testComputePresenceIsPartialNotFatal() {
        var verdict = CapabilityGate.evaluatePack(ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers),
                graphics("composite", ProgramGroup.Composite),
                compute("prepare", ProgramGroup.Prepare))));
        Assertions.assertEquals(CapabilityGate.PackVerdict.ACCEPT_PARTIAL, verdict.verdict());
        Assertions.assertTrue(verdict.notes().stream().anyMatch(n -> n.contains("compute")),
                verdict.notes().toString());
    }
}
