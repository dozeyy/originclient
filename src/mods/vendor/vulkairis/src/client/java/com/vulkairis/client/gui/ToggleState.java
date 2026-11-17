package com.vulkairis.client.gui;

/**
 * The boolean toggle contract (GUI plan, Revision 2 §12.1). Pure logic, no Minecraft classes,
 * so the contract is unit-testable headlessly.
 *
 * <p>ON is a green face plus a visible {@code ON} label; OFF is a red face plus a visible
 * {@code OFF} label. Both derive from the actual setting value on screen open and after every
 * click — never a green fill on an OFF setting, never every toggle initialized ON. A red OFF
 * toggle is not a diagnostic failure. If applying a requested value fails or the feature is
 * unsupported, the toggle rolls back to the last accepted value (or shows a separate
 * pending/error state) instead of displaying ON for an OFF setting.</p>
 */
public final class ToggleState {

    /** ARGB fill for ON. */
    public static final int ON_FILL = 0xFF2E7D32;
    /** ARGB fill for OFF. */
    public static final int OFF_FILL = 0xFFB3261E;
    /** ARGB border shared by both states. */
    public static final int BORDER = 0xFF1B1B1B;

    private ToggleState() {
    }

    /** @return the visible label for a value */
    public static String labelFor(boolean value) {
        return value ? "ON" : "OFF";
    }

    /** @return the face fill for a value */
    public static int fillFor(boolean value) {
        return value ? ON_FILL : OFF_FILL;
    }

    /**
     * Resolves what the toggle must display after a user click.
     *
     * @param requested the value the user asked for
     * @param applied   whether the settings service accepted and applied it
     * @param lastAccepted the last value known applied
     * @return the value to display and persist
     */
    public static boolean resolveAfterClick(boolean requested, boolean applied, boolean lastAccepted) {
        return applied ? requested : lastAccepted;
    }
}
