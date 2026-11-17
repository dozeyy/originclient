package com.vulkairis.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Development-only view model with illustrative rows (plan Phase 2).
 *
 * <p><b>Never shipped as real data:</b> every row here is scaffolding so the visual shell can
 * be built and navigated before the real services land per settings phase. Real rows replace
 * these one category at a time; no mock row may survive into a release.</p>
 */
public final class DevGuiViewModel implements GuiViewModel {

    private final Map<String, Boolean> booleans = new LinkedHashMap<>();
    private final Map<String, Double> numerics = new LinkedHashMap<>();
    private String footerNotice = "";

    public DevGuiViewModel() {
        booleans.put("dev.bridge", true);
        booleans.put("dev.fallback", false);
        numerics.put("dev.scale", 2.0);
    }

    @Override
    public List<SettingRow> rowsFor(GuiCategory category) {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(new SettingRow("Development bridge", "Mock toggle proving the shell contract.",
                "Dev-only mock row.", SettingRow.Kind.TOGGLE, "dev.bridge"));
        if (category == GuiCategory.RENDERING) {
            rows.add(new SettingRow("Development scale", "Mock slider proving numeric rows.",
                    "Dev-only mock row.", SettingRow.Kind.SLIDER, "dev.scale"));
        }
        if (category == GuiCategory.DIAGNOSTICS) {
            rows.add(new SettingRow("Development status", "Mock status badge row.",
                    "Dev-only mock row.", SettingRow.Kind.STATUS, "dev.status"));
        }
        if (category == GuiCategory.GENERAL) {
            rows.add(new SettingRow("Development action", "Mock action button row.",
                    "Dev-only mock row.", SettingRow.Kind.ACTION, "dev.action"));
        }
        return rows;
    }

    @Override
    public boolean booleanValue(String key) {
        return booleans.getOrDefault(key, false);
    }

    @Override
    public boolean setBoolean(String key, boolean requested) {
        booleans.put(key, requested);
        return true;
    }

    @Override
    public double numericValue(String key) {
        return numerics.getOrDefault(key, 0.0);
    }

    @Override
    public void setNumeric(String key, double value) {
        numerics.put(key, value);
    }

    @Override
    public NumericRange numericRange(String key) {
        return new NumericRange(1.0, 4.0, 1.0);
    }

    @Override
    public void runAction(String key) {
        footerNotice = "Development action ran: " + key;
    }

    @Override
    public List<String> selectOptions(String key) {
        return List.of("Alpha", "Beta");
    }

    @Override
    public String selectValue(String key) {
        return "Alpha";
    }

    @Override
    public boolean setSelect(String key, String value) {
        return true;
    }

    @Override
    public StatusBadge.Status statusOf(String key) {
        return StatusBadge.Status.NEUTRAL;
    }

    @Override
    public String statusText(String key) {
        return "Not tested";
    }

    @Override
    public String footerNotice() {
        return footerNotice;
    }

    @Override
    public ApplyResult saveAndApply() {
        footerNotice = "";
        return ApplyResult.clean(false, false);
    }

    @Override
    public void resetCategory(GuiCategory category) {
        booleans.replaceAll((key, value) -> false);
        numerics.replaceAll((key, value) -> 0.0);
        footerNotice = "";
    }
}
