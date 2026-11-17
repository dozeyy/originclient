package com.vulkairis.translation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Translation cache (Phase 12): translated GLSL keyed by input hash, stage, translator
 * version and nothing else — translation is a pure function of its input, so identical
 * inputs share output without changing it. Bounded LRU; hits/misses are recorded for the
 * performance report. Only non-blank outputs are stored; failures are never cached.
 */
public final class TranslationCache {

    /**
     * Translator version baked into every key. Bump whenever translation logic changes so a
     * stale entry from an older translator can never be reused.
     */
    public static final String TRANSLATOR_VERSION = "13.0";

    private static final int MAX_ENTRIES = 256;

    private static final Map<String, String> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    private static final AtomicLong HITS = new AtomicLong(0);
    private static final AtomicLong MISSES = new AtomicLong(0);

    private TranslationCache() {
    }

    /** @return the cache key for an input (changing any compile-affecting input changes it) */
    public static String key(String sourceHash, boolean isVertex) {
        return (sourceHash != null ? sourceHash : "null") + "|" + (isVertex ? "vert" : "frag")
                + "|" + TRANSLATOR_VERSION;
    }

    /**
     * @return the full translation key over the include-expanded text, the owning program
     *         and the active defines. Translation is a pure function of exactly these
     *         inputs, so identical expanded inputs share output (unchanged) while a program
     *         or define change can never reuse a stale entry.
     */
    public static String key(String expandedSourceHash, String program, String definesDigest,
                             boolean isVertex) {
        return (expandedSourceHash != null ? expandedSourceHash : "null") + "|"
                + (program != null && !program.isBlank() ? program : "anonymous") + "|"
                + (definesDigest != null ? definesDigest : "nodefines") + "|"
                + (isVertex ? "vert" : "frag") + "|" + TRANSLATOR_VERSION;
    }

    /** @return the cached translation, or {@code null} on miss (counted) */
    public static String lookup(String key) {
        String cached = CACHE.get(key);
        if (cached != null) {
            HITS.incrementAndGet();
            return cached;
        }
        MISSES.incrementAndGet();
        return null;
    }

    /** Stores a translation; blank outputs are never stored. */
    public static void store(String key, String translated) {
        if (key == null || translated == null || translated.isBlank()) {
            return;
        }
        CACHE.put(key, translated);
    }

    /** @return cache hits so far */
    public static long hits() {
        return HITS.get();
    }

    /** @return cache misses so far */
    public static long misses() {
        return MISSES.get();
    }

    /** @return live entries (never above the bound) */
    public static int size() {
        return CACHE.size();
    }

    /** Clears entries and stats; tests only. */
    public static void clearForTests() {
        CACHE.clear();
        HITS.set(0);
        MISSES.set(0);
    }
}
