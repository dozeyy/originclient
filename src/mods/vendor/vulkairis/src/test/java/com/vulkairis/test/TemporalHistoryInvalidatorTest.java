package com.vulkairis.test;

import com.vulkairis.rendering.TemporalHistoryInvalidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TemporalHistoryInvalidatorTest {

    @Test
    public void invalidatesAndClears() {
        TemporalHistoryInvalidator inv = new TemporalHistoryInvalidator();
        Assertions.assertFalse(inv.isInvalidated());
        inv.invalidate(TemporalHistoryInvalidator.Event.RESIZE);
        inv.invalidate(TemporalHistoryInvalidator.Event.RESIZE);
        Assertions.assertTrue(inv.isInvalidated());
        Assertions.assertTrue(inv.isPending(TemporalHistoryInvalidator.Event.RESIZE));
        Assertions.assertEquals(1, inv.pendingEvents().size());
        inv.clear();
        Assertions.assertFalse(inv.isInvalidated());
        Assertions.assertTrue(inv.pendingEvents().isEmpty());
    }
}