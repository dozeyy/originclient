package com.vulkairis.test;

import com.vulkairis.core.ShaderLifecycleState;
import com.vulkairis.testpack.ShaderPackTestHarness;
import com.vulkairis.testpack.ShaderStageFixture;
import com.vulkairis.testpack.ShaderTestPack;
import com.vulkairis.testpack.ShaderTestPacks;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 0: runs every known-good fixture pack through the real translate/compile/validate/reflect
 * pipeline and asserts each stage reaches {@link ShaderLifecycleState#REFLECTED}, plus a negative
 * case proving the harness reports failures explicitly instead of silently succeeding.
 */
public class ShaderTestHarnessTest {

    @Test
    public void testAllFixturesReachReflected() {
        for (ShaderTestPack pack : ShaderTestPacks.all()) {
            ShaderPackTestHarness.PackReport report = ShaderPackTestHarness.run(pack);
            Assertions.assertTrue(report.passed(), "fixture pack failed:\n" + report.summary());
            for (ShaderPackTestHarness.StageResult stage : report.stages()) {
                Assertions.assertEquals(ShaderLifecycleState.REFLECTED, stage.reached(),
                        stage.stage() + " did not reach REFLECTED");
            }
        }
    }

    @Test
    public void testSolidColorFinalReachesReflected() {
        ShaderPackTestHarness.PackReport report = ShaderPackTestHarness.run(ShaderTestPacks.solidColorFinal());
        Assertions.assertTrue(report.passed(), report.summary());
        Assertions.assertTrue(report.stages().get(1).spirvBytes() > 0);
    }

    /** The harness must never report success for a stage that does not compile. */
    @Test
    public void testInvalidStageIsReportedAsFailure() {
        ShaderTestPack broken = new ShaderTestPack(
                "broken_fixture",
                "A fragment stage that cannot compile.",
                List.of(ShaderStageFixture.fragment(
                        "broken.fsh",
                        """
                        #version 150 core
                        out vec4 fragColor;
                        void main() {
                            this is not valid glsl;
                        }
                        """,
                        List.of(), List.of())));

        ShaderPackTestHarness.PackReport report = ShaderPackTestHarness.run(broken);
        Assertions.assertFalse(report.passed(), "a non-compiling stage must not be reported as passed");
        Assertions.assertTrue(
                report.stages().get(0).reached().ordinal() < ShaderLifecycleState.REFLECTED.ordinal(),
                "a failed stage must not claim to have reached REFLECTED");
    }
}
