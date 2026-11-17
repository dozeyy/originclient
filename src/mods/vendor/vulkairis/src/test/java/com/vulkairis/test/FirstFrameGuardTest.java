package com.vulkairis.test;

import com.vulkairis.rendering.FirstFrameGuard;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class FirstFrameGuardTest {
    @Test
    public void guardsFirstFrame() {
        FirstFrameGuard g = new FirstFrameGuard();
        Assertions.assertTrue(g.isFirstFrame());
        Assertions.assertFalse(g.isReady(1L));
        g.markReady(1L);
        Assertions.assertTrue(g.isReady(1L));
        Assertions.assertFalse(g.isReady(2L));
        g.reset();
        Assertions.assertTrue(g.isFirstFrame());
    }
}