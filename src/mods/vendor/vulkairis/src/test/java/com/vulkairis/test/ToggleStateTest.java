package com.vulkairis.test;

import com.vulkairis.client.gui.GuiCategory;
import com.vulkairis.client.gui.ToggleState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * GUI plan Revision 2 §12.1: the boolean toggle contract. ON is green plus a visible ON
 * label; OFF is red plus a visible OFF label; both derive from the actual value, and failed
 * applies roll back instead of displaying a lie.
 */
public class ToggleStateTest {

    @Test
    public void testLabelAndFillDeriveFromValue() {
        Assertions.assertEquals("ON", ToggleState.labelFor(true));
        Assertions.assertEquals("OFF", ToggleState.labelFor(false));
        Assertions.assertEquals(ToggleState.ON_FILL, ToggleState.fillFor(true));
        Assertions.assertEquals(ToggleState.OFF_FILL, ToggleState.fillFor(false));
        Assertions.assertNotEquals(ToggleState.fillFor(true), ToggleState.fillFor(false),
                "ON and OFF must never share a fill");
    }

    @Test
    public void testSuccessfulApplyTakesRequested() {
        Assertions.assertTrue(ToggleState.resolveAfterClick(true, true, false));
        Assertions.assertFalse(ToggleState.resolveAfterClick(false, true, true));
    }

    @Test
    public void testFailedApplyRollsBack() {
        Assertions.assertFalse(ToggleState.resolveAfterClick(true, false, false),
                "a rejected ON must keep displaying OFF");
        Assertions.assertTrue(ToggleState.resolveAfterClick(false, false, true),
                "a rejected OFF must keep displaying ON");
    }

    @Test
    public void testCategoriesStable() {
        GuiCategory[] categories = GuiCategory.values();
        Assertions.assertEquals(6, categories.length);
        Assertions.assertEquals(GuiCategory.GENERAL, categories[0]);
        Assertions.assertEquals(GuiCategory.ADVANCED, categories[categories.length - 1]);
        for (GuiCategory category : categories) {
            Assertions.assertFalse(category.title().isBlank());
        }
    }
}
