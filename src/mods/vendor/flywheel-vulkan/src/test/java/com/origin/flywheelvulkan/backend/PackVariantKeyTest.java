package com.origin.flywheelvulkan.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;

/** The fixed-function key a shader-pack variant of a draw is built and cached under (PackDraw). */
class PackVariantKeyTest {
	private static final VkPipelines.StateKey CUTOUT_BACKFACE =
			new VkPipelines.StateKey(true, DepthTest.LEQUAL, Transparency.OPAQUE, WriteMask.COLOR_DEPTH);
	private static final VkPipelines.StateKey TRANSLUCENT =
			new VkPipelines.StateKey(true, DepthTest.LEQUAL, Transparency.TRANSLUCENT, WriteMask.COLOR);

	@Test
	void mainRoleKeepsTheMaterialsOwnState() {
		assertSame(CUTOUT_BACKFACE, VkPipelines.StateKey.forRole(CUTOUT_BACKFACE, false));
		assertSame(TRANSLUCENT, VkPipelines.StateKey.forRole(TRANSLUCENT, false));
	}

	@Test
	void shadowRoleIsOneOpaqueTwoSidedDepthWritingState() {
		VkPipelines.StateKey a = VkPipelines.StateKey.forRole(CUTOUT_BACKFACE, true);
		VkPipelines.StateKey b = VkPipelines.StateKey.forRole(TRANSLUCENT, true);
		// Every material collapses to one shadow state, so one pipeline per program serves them all.
		assertEquals(a, b);
		assertFalse(a.backfaceCulling(), "the sun sees back faces");
		assertEquals(Transparency.OPAQUE, a.transparency());
		assertTrue(a.writeMask().depth(), "a caster must write the shadow map's depth");
		assertNotEquals(DepthTest.OFF, a.depthTest());
	}

	@Test
	void mainAndShadowVariantsOfOneDrawNeverShareAKey() {
		assertNotEquals(VkPipelines.StateKey.forRole(CUTOUT_BACKFACE, false), VkPipelines.StateKey.forRole(CUTOUT_BACKFACE, true));
		// Equal materials give equal keys: the per-role pipeline map is hit, not rebuilt.
		VkPipelines.StateKey copy = new VkPipelines.StateKey(true, DepthTest.LEQUAL, Transparency.OPAQUE, WriteMask.COLOR_DEPTH);
		assertEquals(VkPipelines.StateKey.forRole(CUTOUT_BACKFACE, false), VkPipelines.StateKey.forRole(copy, false));
		assertEquals(VkPipelines.StateKey.forRole(CUTOUT_BACKFACE, false).hashCode(), VkPipelines.StateKey.forRole(copy, false).hashCode());
	}
}
