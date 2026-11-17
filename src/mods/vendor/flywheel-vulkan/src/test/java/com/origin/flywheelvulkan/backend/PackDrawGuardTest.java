package com.origin.flywheelvulkan.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.origin.flywheelvulkan.vk.Retirement;

/**
 * PackDraw's fail-soft guard on the set-1 lookup: a throw while building a draw's pack descriptors must
 * not reach VkEngine (whose catch turns origin:vulkan off for the session). It switches that role off for
 * the pack, returns 0, and VkDrawManager draws on phase 1 (main) or stops casting (shadow).
 *
 * <p>The roles here were never built, so building a set throws inside (no bindings, no slots), the
 * same way a pack image lookup, a descriptor allocation error or a layout transition would.</p>
 */
class PackDrawGuardTest {
	@Test
	void aThrowingMainSetSwitchesOnlyTheMainRoleOff() {
		PackDraw pack = new PackDraw(null);
		int epoch = pack.epoch(PackDraw.MAIN);

		assertEquals(0L, pack.descriptorSet(PackDraw.MAIN, 0, null, null, null));

		assertTrue(pack.failed(PackDraw.MAIN), "the caller sees the role is off and draws on phase 1");
		assertNotEquals(epoch, pack.epoch(PackDraw.MAIN), "pipelines cached on draws for the role go stale");
		assertFalse(pack.failed(PackDraw.SHADOW), "the other role is untouched");
	}

	@Test
	void aThrowingShadowSetSwitchesTheShadowRoleOffUntilThePackReloads() {
		PackDraw pack = new PackDraw(null);

		assertEquals(0L, pack.descriptorSet(PackDraw.SHADOW, 0, null, null, null));
		assertTrue(pack.failed(PackDraw.SHADOW));
		assertFalse(pack.failed(PackDraw.MAIN));

		// A pack change or reload (PackDraw.sync -> retireAll) turns it back on.
		pack.retireAll(new Retirement(2), 0);
		assertFalse(pack.failed(PackDraw.SHADOW), "shadows come back after a pack reload");
	}

	@Test
	void switchingARoleOffTwiceIsOneSwitch() {
		PackDraw pack = new PackDraw(null);
		pack.descriptorSet(PackDraw.MAIN, 0, null, null, null);
		int epoch = pack.epoch(PackDraw.MAIN);

		pack.disableRole(PackDraw.MAIN, "again");

		assertEquals(epoch, pack.epoch(PackDraw.MAIN), "no second epoch bump or log line per draw");
	}
}
