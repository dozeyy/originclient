package com.vulkairis.test;

import com.vulkairis.rendering.ColorSpace;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ColorSpaceTest {
    @Test
    public void roundTrip() {
        double v = 0.5;
        double lin = ColorSpace.toLinear(v);
        double back = ColorSpace.toSrgb(lin);
        Assertions.assertEquals(v, back, 1e-9);
    }
}