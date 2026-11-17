package com.vulkairis.client.gui;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared base for real category models: version tracking for screen refresh plus helpers.
 * Values always come from the persisted config or live services — never fabricated.
 */
public abstract class AbstractCategoryModel implements GuiViewModel {

    private final AtomicLong version = new AtomicLong(0);
    private volatile String pendingNotice = "";
    private volatile String pendingNavigation = "";

    /** @return monotonically increasing model version for screen refresh */
    @Override
    public long version() {
        return version.get();
    }

    /**
     * @return a pending navigation target ("browser") and clears it; "" when none
     */
    public String consumeNavigation() {
        String target = pendingNavigation;
        pendingNavigation = "";
        return target;
    }

    /** Bumps the version so open screens refresh (async completions, external changes). */
    protected void touch() {
        version.incrementAndGet();
    }

    /** @return shared footer notice text */
    protected String pendingNotice() {
        return pendingNotice;
    }

    protected void pendingNotice(String notice) {
        pendingNotice = notice != null ? notice : "";
        touch();
    }

    @Override
    public boolean booleanValue(String key) {
        return false;
    }

    @Override
    public boolean setBoolean(String key, boolean requested) {
        return false;
    }

    /** Requests screen navigation (consumed once by the screen). */
    protected void navigateTo(String target) {
        pendingNavigation = target != null ? target : "";
        touch();
    }

    @Override
    public double numericValue(String key) {
        return 0.0;
    }

    @Override
    public void setNumeric(String key, double value) {
    }

    @Override
    public NumericRange numericRange(String key) {
        return new NumericRange(0.0, 1.0, 0.0);
    }

    @Override
    public void runAction(String key) {
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
        return pendingNotice();
    }

    @Override
    public ApplyResult saveAndApply() {
        com.vulkairis.config.VulkairisConfigManager.saveConfig();
        return ApplyResult.clean(false, false);
    }

    @Override
    public void resetCategory(GuiCategory category) {
    }

    @Override
    public List<String> selectOptions(String key) {
        return List.of();
    }

    @Override
    public String selectValue(String key) {
        return "";
    }

    @Override
    public boolean setSelect(String key, String value) {
        return false;
    }
}
