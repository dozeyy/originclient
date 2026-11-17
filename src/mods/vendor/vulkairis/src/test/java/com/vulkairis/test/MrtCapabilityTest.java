package com.vulkairis.test;

import com.vulkairis.compatibility.MrtCapability;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MrtCapabilityTest {

    @Test
    public void nativeMrtSatisfiesWithinLimit() {
        MrtCapability cap = new MrtCapability(4, MrtCapability.Strategy.NATIVE_MRT, "ok");
        Assertions.assertTrue(cap.canSatisfy(2));
        Assertions.assertFalse(cap.canSatisfy(8));
    }

    @Test
    public void fallbackSatisfiesAny() {
        MrtCapability cap = new MrtCapability(1, MrtCapability.Strategy.MULTIPASS_FALLBACK, "fallback");
        Assertions.assertTrue(cap.canSatisfy(8));
    }
}