package com.vulkairis.origin;

import com.vulkairis.origin.composite.CompositeRouting;
import com.vulkairis.origin.composite.CompositeRouting.PassSpec;
import com.vulkairis.origin.composite.CompositeRouting.Side;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeRoutingTest {
	@Test
	void writesGoToTheOtherSideAndFlip() {
		var plan = CompositeRouting.plan(List.of(
				new PassSpec("composite", new int[]{0}, null),
				new PassSpec("composite1", new int[]{0, 3}, null)));
		var c0 = plan.passes().get(0);
		assertEquals(Side.MAIN, c0.readSide(0));
		assertArrayEquals(new Side[]{Side.ALT}, c0.writeSides());
		var c1 = plan.passes().get(1);
		assertEquals(Side.ALT, c1.readSide(0), "composite1 reads what composite wrote");
		assertEquals(Side.MAIN, c1.readSide(3));
		assertArrayEquals(new Side[]{Side.MAIN, Side.ALT}, c1.writeSides());
		assertEquals(Side.MAIN, plan.finalReadSide(0), "flipped twice");
		assertEquals(Side.ALT, plan.finalReadSide(3));
		assertEquals(Side.MAIN, plan.finalReadSide(7), "never written");
	}

	@Test
	void explicitFlipFalseKeepsTheReadSide() {
		var plan = CompositeRouting.plan(List.of(
				new PassSpec("composite", new int[]{2}, Map.of(2, false)),
				new PassSpec("composite1", new int[]{1}, Map.of(5, true))));
		assertEquals(Side.MAIN, plan.passes().get(1).readSide(2));
		assertEquals(Side.ALT, plan.finalReadSide(1));
		assertEquals(Side.ALT, plan.finalReadSide(5), "explicit flip of an unwritten buffer");
	}

	@Test
	void sceneBufferFollowsTerrainThenTextured() {
		assertEquals(1, CompositeRouting.sceneBuffer(new int[]{1, 2}, new int[]{0}));
		assertEquals(4, CompositeRouting.sceneBuffer(new int[0], new int[]{4}));
		assertEquals(0, CompositeRouting.sceneBuffer(null, null));
	}

	@Test
	void rejectsOutOfRangeDrawBuffers() {
		assertThrows(IllegalArgumentException.class, () -> new PassSpec("x", new int[]{16}, null));
	}
}
