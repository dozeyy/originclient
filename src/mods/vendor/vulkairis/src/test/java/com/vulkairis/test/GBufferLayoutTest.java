package com.vulkairis.test;

import com.vulkairis.rendering.GBufferLayout;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GBufferLayoutTest {
    @Test
    public void hasAttachments() {
        Assertions.assertFalse(GBufferLayout.all().isEmpty());
        Assertions.assertNotNull(GBufferLayout.find("depthtex0"));
    }
}