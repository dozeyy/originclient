package com.vulkairis.client.gui;

import java.util.Objects;

/**
 * One settings row: title, concise description, and a right-aligned control.
 * Rows are data; the list renders them. Every row needs a working control — no decorative rows.
 */
public final class SettingRow {

    /** Control kinds the shell renders. */
    public enum Kind {
        TOGGLE,
        SLIDER,
        SELECT,
        ACTION,
        STATUS
    }

    private final String title;
    private final String description;
    private final String tooltip;
    private final Kind kind;
    private final String key;

    public SettingRow(String title, String description, String tooltip, Kind kind, String key) {
        this.title = Objects.requireNonNull(title, "title");
        this.description = description != null ? description : "";
        this.tooltip = tooltip != null ? tooltip : "";
        this.kind = Objects.requireNonNull(kind, "kind");
        this.key = Objects.requireNonNull(key, "key");
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String tooltip() {
        return tooltip;
    }

    public Kind kind() {
        return kind;
    }

    /** Stable setting key backing this row (config key or pseudo-key for actions). */
    public String key() {
        return key;
    }
}
