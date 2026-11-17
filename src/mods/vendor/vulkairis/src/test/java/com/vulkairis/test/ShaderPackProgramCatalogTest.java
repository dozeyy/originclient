package com.vulkairis.test;

import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.iris.ShaderProgramKind;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Device-free verification of the Phase 1 program-discovery model: graphics/compute classification,
 * Iris group execution order, array-element detection, and the catalog's execution ordering.
 *
 * <p>These tests build synthetic {@link ShaderPackProgram} instances (the originating Iris source is
 * {@code null}) so they run without a live shader pack or GPU.</p>
 */
public class ShaderPackProgramCatalogTest {

    private static ShaderPackProgram.Graphics graphics(
            String name, ProgramGroup group, int index, int length) {
        return new ShaderPackProgram.Graphics(
                name, group, index, length, null,
                true, true, false, false, false, null);
    }

    private static ShaderPackProgram.Compute compute(String name, ProgramGroup group) {
        return new ShaderPackProgram.Compute(name, group, 0, 1, null, null);
    }

    /** A graphics program reports GRAPHICS; a compute program reports COMPUTE. */
    @Test
    public void testKindDerivation() {
        Assertions.assertEquals(ShaderProgramKind.GRAPHICS,
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers, 0, 1).kind());
        Assertions.assertEquals(ShaderProgramKind.COMPUTE,
                compute("setup", ProgramGroup.Setup).kind());
        Assertions.assertEquals(ShaderProgramKind.COMPUTE, ShaderProgramKind.of(true));
        Assertions.assertEquals(ShaderProgramKind.GRAPHICS, ShaderProgramKind.of(false));
    }

    /** Execution order must follow the Iris program-group pipeline order. */
    @Test
    public void testExecutionOrderFollowsIrisGroupOrder() {
        int setup = compute("setup", ProgramGroup.Setup).executionOrder();
        int shadow = graphics("shadow", ProgramGroup.Shadow, 0, 1).executionOrder();
        int shadowComposite = graphics("shadowcomp", ProgramGroup.ShadowComposite, 0, 1).executionOrder();
        int prepare = graphics("prepare", ProgramGroup.Prepare, 0, 1).executionOrder();
        int gbuffers = graphics("gbuffers_terrain", ProgramGroup.Gbuffers, 0, 1).executionOrder();
        int deferred = graphics("deferred", ProgramGroup.Deferred, 0, 1).executionOrder();
        int composite = graphics("composite", ProgramGroup.Composite, 0, 1).executionOrder();
        int fin = graphics("final", ProgramGroup.Final, 0, 1).executionOrder();

        Assertions.assertTrue(setup < shadow, "setup must run before shadow");
        Assertions.assertTrue(shadow < shadowComposite, "shadow must run before shadowcomp");
        Assertions.assertTrue(shadowComposite < prepare, "shadowcomp must run before prepare");
        Assertions.assertTrue(prepare < gbuffers, "prepare must run before gbuffers");
        Assertions.assertTrue(gbuffers < deferred, "gbuffers must run before deferred");
        Assertions.assertTrue(deferred < composite, "deferred must run before composite");
        Assertions.assertTrue(composite < fin, "composite must run before final");
    }

    /** The catalog's ordering must group programs and preserve array index within a group. */
    @Test
    public void testOrderByExecutionSortsGroupsAndArrayIndex() {
        ShaderPackProgram finalProgram = graphics("final", ProgramGroup.Final, 0, 1);
        ShaderPackProgram composite1 = graphics("composite1", ProgramGroup.Composite, 1, 2);
        ShaderPackProgram composite0 = graphics("composite", ProgramGroup.Composite, 0, 2);
        ShaderPackProgram gbuffers = graphics("gbuffers_terrain", ProgramGroup.Gbuffers, 0, 1);
        ShaderPackProgram setup = compute("setup", ProgramGroup.Setup);

        List<ShaderPackProgram> ordered = ShaderPackProgramCatalog.orderByExecution(
                List.of(finalProgram, composite1, gbuffers, setup, composite0));

        Assertions.assertEquals(
                List.of(setup, gbuffers, composite0, composite1, finalProgram),
                ordered,
                "programs must be ordered by group then array index");
    }

    /** Array elements are detected only when the array length exceeds one. */
    @Test
    public void testArrayElementDetection() {
        Assertions.assertFalse(graphics("final", ProgramGroup.Final, 0, 1).isArrayElement());
        ShaderPackProgram element = graphics("composite1", ProgramGroup.Composite, 1, 2);
        Assertions.assertTrue(element.isArrayElement());
        Assertions.assertEquals(1, element.arrayIndex());
        Assertions.assertEquals(2, element.arrayLength());
    }

    /** Descriptions must carry the program identity, group and real stages for diagnostics. */
    @Test
    public void testDescribeIncludesIdentityAndStages() {
        String described = graphics("gbuffers_terrain", ProgramGroup.Gbuffers, 0, 1).describe();
        Assertions.assertTrue(described.contains("gbuffers_terrain"), described);
        Assertions.assertTrue(described.contains("Gbuffers"), described);
        Assertions.assertTrue(described.contains("graphics"), described);
        Assertions.assertTrue(described.contains("v"), described);
        Assertions.assertTrue(described.contains("f"), described);

        String computeDescribed = compute("setup", ProgramGroup.Setup).describe();
        Assertions.assertTrue(computeDescribed.contains("setup"), computeDescribed);
        Assertions.assertTrue(computeDescribed.contains("compute"), computeDescribed);
    }
}
