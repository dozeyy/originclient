package com.vulkairis.client.gui;

import java.util.List;

/**
 * View-model contract between settings screens and their backing state (plan §5). The screen
 * layer reads rows and applies values through this boundary; it never owns Vulkan, OpenGL,
 * Iris, or compilation logic. Real implementations land per settings phase; development
 * scaffolding stays behind an explicit mock (never shipped as real data).
 */
public interface GuiViewModel {

    /** @return rows for a category, in display order */
    List<SettingRow> rowsFor(GuiCategory category);

    /** @return the persisted boolean for a toggle key */
    boolean booleanValue(String key);

    /**
     * @return whether the requested value was accepted and applied (a failed apply rolls the
     * control back instead of displaying a lie)
     */
    boolean setBoolean(String key, boolean requested);

    /** @return the persisted numeric value for a slider key */
    double numericValue(String key);

    /** Sets a numeric value (already validated by the range). */
    void setNumeric(String key, double value);

    /** @return the slider range for a key */
    NumericRange numericRange(String key);

    /** Runs an action row (folder open, rescan, test launch, export, reset). */
    void runAction(String key);

    /** @return offered values for a select row */
    List<String> selectOptions(String key);

    /** @return the current value for a select row */
    String selectValue(String key);

    /**
     * @return whether the requested select value was accepted (rejected values roll back)
     */
    boolean setSelect(String key, String value);

    /** @return a pending navigation target ("" when none); default models never navigate */
    default String consumeNavigation() {
        return "";
    }

    /** @return monotonically increasing model version; screens refresh when it changes */
    default long version() {
        return 0L;
    }

    /** @return diagnostic status for a status row */
    StatusBadge.Status statusOf(String key);

    /** @return diagnostic text for a status row */
    String statusText(String key);

    /** @return footer notice (restart/reload required), or "" when clean */
    String footerNotice();

    /** Saves and applies pending values. */
    ApplyResult saveAndApply();

    /** Resets one category to defaults (with confirmation handled by the screen). */
    void resetCategory(GuiCategory category);

    /** Numeric slider range with display formatting. */
    record NumericRange(double min, double max, double step) {
        public NumericRange {
            if (!(max > min)) {
                throw new IllegalArgumentException("slider max must exceed min");
            }
            if (step < 0.0) {
                throw new IllegalArgumentException("slider step must be non-negative");
            }
        }
    }

    /** Save/apply outcome: restart/reload notices plus rows that refused to apply. */
    record ApplyResult(boolean restartRequired, boolean reloadRequired, List<String> failures) {
        public ApplyResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public static ApplyResult clean(boolean restartRequired, boolean reloadRequired) {
            return new ApplyResult(restartRequired, reloadRequired, List.of());
        }
    }
}
