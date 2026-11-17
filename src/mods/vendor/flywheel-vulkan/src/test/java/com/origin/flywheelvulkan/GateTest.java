package com.origin.flywheelvulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.origin.flywheelvulkan.Gate.Verdict;

class GateTest {
	static final String VK = "0.6.7+1.21.1";
	static final String FW = "1.0.6+1.21.1.build.44";

	@Test
	void onOnlyForBothExactPins() {
		assertEquals(Verdict.ON, Gate.decide(VK, FW, VK, FW));
	}

	@Test
	void missingModsKeepItOff() {
		assertEquals(Verdict.NO_VULKANMOD, Gate.decide(null, FW, VK, FW));
		assertEquals(Verdict.NO_FLYWHEEL, Gate.decide(VK, null, VK, FW));
		assertEquals(Verdict.NO_VULKANMOD, Gate.decide(null, null, VK, FW));
	}

	@Test
	void anyOtherBuildIsUnknown() {
		assertEquals(Verdict.UNKNOWN_FLYWHEEL, Gate.decide(VK, "1.0.6+1.21.1.build.45", VK, FW));
		assertEquals(Verdict.UNKNOWN_FLYWHEEL, Gate.decide(VK, "1.0.4", VK, FW));
		assertEquals(Verdict.UNKNOWN_VULKANMOD, Gate.decide("0.6.8+1.21.1", FW, VK, FW));
	}

	@Test
	void missingPinsNeverOpen() {
		assertEquals(Verdict.UNKNOWN_VULKANMOD, Gate.decide(VK, FW, null, null));
	}

	@Test
	void builtPinsMatchTheJarsTheBuildCompiledAgainst() {
		// processResources expands these from gradle.properties; an unexpanded ${...} would
		// silently close the gate forever.
		Gate.Pins pins = Gate.Pins.load();
		assertNotNull(pins.vulkanmod());
		assertEquals(VK, pins.vulkanmod());
		assertEquals(FW, pins.flywheel());
	}
}
