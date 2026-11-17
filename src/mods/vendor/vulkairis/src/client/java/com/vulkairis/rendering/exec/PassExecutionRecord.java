package com.vulkairis.rendering.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which requested passes actually ran (Phase 11).
 *
 * <p>A cumulative per-session record: every executed frame stamps the passes it recorded, so
 * the compatibility report can say per program family whether it ran, fell back, or was
 * rejected — instead of labeling a pack supported from compile results alone. Cleared when
 * the pipeline plan is rebuilt.</p>
 */
public final class PassExecutionRecord {

    /** What happened to one requested pass. */
    public enum Outcome {
        RAN,
        FELL_BACK,
        REJECTED
    }

    /** One recorded pass outcome. */
    public record Entry(String pass, Outcome outcome, String note) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** Records an outcome; a later RAN overwrites an earlier lesser outcome, never the reverse. */
    public synchronized void record(String pass, Outcome outcome, String note) {
        if (pass == null || outcome == null) {
            return;
        }
        Entry existing = entries.get(pass);
        if (existing != null && existing.outcome() == Outcome.RAN && outcome != Outcome.RAN) {
            return;
        }
        entries.put(pass, new Entry(pass, outcome, note != null ? note : ""));
    }

    /** @return the recorded outcome, or {@code null} when never recorded */
    public synchronized Outcome outcomeOf(String pass) {
        Entry entry = entries.get(pass);
        return entry != null ? entry.outcome() : null;
    }

    /** @return every entry in recording order */
    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    /** @return whether any pass ran at all */
    public synchronized boolean ranAnything() {
        return entries.values().stream().anyMatch(e -> e.outcome() == Outcome.RAN);
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }
}
