package com.vulkairis.test;

import com.vulkairis.core.PackReadinessState;
import com.vulkairis.core.PackReadinessStateMachine;
import com.vulkairis.core.PackReadinessTransition;
import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.rendering.exec.PassGates;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 1: Unit tests for production readiness state machine and truthful gating.
 */
public class ReadinessStateMachineTest {

    private PackReadinessStateMachine stateMachine;

    private static ShaderPackProgram graphics(String name, ProgramGroup group) {
        return new ShaderPackProgram.Graphics(name, group, 0, 1, null, true, true,
                false, false, false, null);
    }

    @BeforeEach
    public void setUp() {
        stateMachine = new PackReadinessStateMachine();
    }

    @Test
    public void testInitialStateIsNoPack() {
        Assertions.assertEquals(PackReadinessState.NO_PACK, stateMachine.getState());
        Assertions.assertNull(stateMachine.getLastTransition());
    }

    @Test
    public void testSequentialValidTransitions() {
        // NO_PACK -> DISCOVERED
        var t1 = stateMachine.transitionTo(PackReadinessState.DISCOVERED, null, 100L,
                "TestPack", "overworld", "all", false, null);
        Assertions.assertTrue(t1.success());
        Assertions.assertEquals(PackReadinessState.DISCOVERED, stateMachine.getState());

        // DISCOVERED -> TRANSLATING
        var t2 = stateMachine.transitionTo(PackReadinessState.TRANSLATING, null, 100L,
                "TestPack", "overworld", "all", false, null);
        Assertions.assertTrue(t2.success());
        Assertions.assertEquals(PackReadinessState.TRANSLATING, stateMachine.getState());

        // TRANSLATING -> COMPILED
        var t3 = stateMachine.transitionTo(PackReadinessState.COMPILED, null, 100L,
                "TestPack", "overworld", "all", false, null);
        Assertions.assertTrue(t3.success());
        Assertions.assertEquals(PackReadinessState.COMPILED, stateMachine.getState());

        // COMPILED -> INTERFACE_VALIDATED
        var t4 = stateMachine.transitionTo(PackReadinessState.INTERFACE_VALIDATED, null, 100L,
                "TestPack", "overworld", "all", false, null);
        Assertions.assertTrue(t4.success());
        Assertions.assertEquals(PackReadinessState.INTERFACE_VALIDATED, stateMachine.getState());

        // INTERFACE_VALIDATED -> RESOURCES_READY
        var t5 = stateMachine.transitionTo(PackReadinessState.RESOURCES_READY, null, 100L,
                "TestPack", "overworld", "all", true, null);
        Assertions.assertTrue(t5.success());
        Assertions.assertEquals(PackReadinessState.RESOURCES_READY, stateMachine.getState());

        List<PackReadinessTransition> history = stateMachine.getHistory(10);
        Assertions.assertEquals(5, history.size());
    }

    @Test
    public void testPromotionBlockedByClosedPassGates() {
        // A catalog containing terrain requires the TERRAIN pass gate, which is currently CLOSED
        ShaderPackProgramCatalog catalogWithTerrain = ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers)
        ));

        stateMachine.transitionTo(PackReadinessState.DISCOVERED, catalogWithTerrain, 200L,
                "BSL", "overworld", "all", false, null);
        stateMachine.transitionTo(PackReadinessState.TRANSLATING, catalogWithTerrain, 200L,
                "BSL", "overworld", "all", false, null);
        stateMachine.transitionTo(PackReadinessState.INTERFACE_VALIDATED, catalogWithTerrain, 200L,
                "BSL", "overworld", "all", false, null);

        // Attempting to advance to EXECUTION_READY must fail and transition to DEGRADED
        var promoTransition = stateMachine.transitionTo(PackReadinessState.EXECUTION_READY,
                catalogWithTerrain, 200L, "BSL", "overworld", "all", true, null);

        Assertions.assertFalse(promoTransition.success(), "Promotion must be blocked when required gate is CLOSED");
        Assertions.assertEquals(PackReadinessState.DEGRADED, stateMachine.getState());
        Assertions.assertTrue(promoTransition.failureCause().contains("Required gate TERRAIN is CLOSED"),
                "Failure cause must explicitly cite the closed gate: " + promoTransition.failureCause());
    }

    @Test
    public void testPromotionBlockedByUnbackedGpuResources() {
        // Even if no gates blocked, missing GPU resources must block promotion
        ShaderPackProgramCatalog emptyCatalog = ShaderPackProgramCatalog.of(List.of());

        stateMachine.transitionTo(PackReadinessState.DISCOVERED, emptyCatalog, 300L,
                "Mini", "overworld", "all", false, null);

        var promoTransition = stateMachine.transitionTo(PackReadinessState.EXECUTION_READY,
                emptyCatalog, 300L, "Mini", "overworld", "all", false, null);

        Assertions.assertFalse(promoTransition.success());
        Assertions.assertEquals(PackReadinessState.DEGRADED, stateMachine.getState());
        Assertions.assertTrue(promoTransition.failureCause().contains("GPU resources"));
    }

    @Test
    public void testExplicitFailureTransitionsToRejected() {
        stateMachine.transitionTo(PackReadinessState.DISCOVERED, null, 400L,
                "BadPack", "overworld", "all", false, null);

        var failTransition = stateMachine.transitionTo(PackReadinessState.COMPILED, null, 400L,
                "BadPack", "overworld", "all", false, "Spirv compilation syntax error");

        Assertions.assertFalse(failTransition.success());
        Assertions.assertEquals(PackReadinessState.REJECTED, stateMachine.getState());
        Assertions.assertEquals("Spirv compilation syntax error", failTransition.failureCause());
    }

    @Test
    public void testTerrainBlockNamesExactMissingCondition() {
        // The runClient DEGRADED verdict must carry the gate's exact missing condition, not a
        // bare "TERRAIN is CLOSED": headless nothing is linked, so the cause must name the
        // terrain program and its zero virtual id (the honest-fallback record).
        ShaderPackProgramCatalog catalogWithTerrain = ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers)
        ));

        stateMachine.transitionTo(PackReadinessState.DISCOVERED, catalogWithTerrain, 600L,
                "ComplementaryReimagined_r5.9.3", "overworld", "all", false, null);

        var promoTransition = stateMachine.transitionTo(PackReadinessState.EXECUTION_READY,
                catalogWithTerrain, 600L, "ComplementaryReimagined_r5.9.3", "overworld",
                "all", true, null);

        Assertions.assertFalse(promoTransition.success());
        Assertions.assertEquals(PackReadinessState.DEGRADED, stateMachine.getState());
        Assertions.assertTrue(promoTransition.failureCause().contains("gbuffers_terrain"),
                "DEGRADED cause must name the missing terrain program: "
                        + promoTransition.failureCause());
        Assertions.assertTrue(promoTransition.failureCause().contains("virtual id 0"),
                "DEGRADED cause must name the missing link: " + promoTransition.failureCause());
    }

    @Test
    public void testResetReturnsToNoPack() {
        stateMachine.transitionTo(PackReadinessState.DISCOVERED, null, 500L,
                "SomePack", "overworld", "all", false, null);
        Assertions.assertEquals(PackReadinessState.DISCOVERED, stateMachine.getState());

        var resetTransition = stateMachine.reset(501L, "Pack unloaded");
        Assertions.assertTrue(resetTransition.success());
        Assertions.assertEquals(PackReadinessState.NO_PACK, stateMachine.getState());
    }
}
