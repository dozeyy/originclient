package com.vulkairis.client.gui.services;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last-test marks per pack identity (GUI plan Phase 5 badges).
 *
 * <p>Selection and compatibility stay distinct: a mark records what a smoke test found for a
 * file <em>stamp</em>, never whether the pack loads. When the file changes, the mark goes
 * stale and the pack requires retesting — it is never presented as freshly tested.</p>
 */
public final class PackTestHistory {

    /** Last-test outcome for one file stamp. */
    public record TestMark(boolean passed, String contentStamp, String detail, long testedAtMillis) {
    }

    private final Map<String, TestMark> marks = new ConcurrentHashMap<>();

    /** Records a smoke-test outcome for a pack identity and stamp. */
    public void record(String stableId, String contentStamp, PackSmokeTest.SmokeResult result) {
        if (stableId == null || result == null) {
            return;
        }
        marks.put(stableId, new TestMark(result.passed(), contentStamp, result.detail(),
                System.currentTimeMillis()));
    }

    /** @return the mark for a pack identity, if any */
    public Optional<TestMark> markFor(String stableId) {
        return Optional.ofNullable(marks.get(stableId));
    }

    /**
     * @return whether the mark is stale (file changed since the test, or never tested)
     */
    public boolean isStale(String stableId, String currentStamp) {
        TestMark mark = marks.get(stableId);
        if (mark == null) {
            return true;
        }
        return !mark.contentStamp().equals(currentStamp);
    }

    /** Drops marks for identities no longer discovered. */
    public void retainOnly(java.util.Set<String> stableIds) {
        marks.keySet().retainAll(stableIds);
    }

    /** @return an unmodifiable snapshot for diagnostics */
    public Map<String, TestMark> snapshot() {
        return Collections.unmodifiableMap(marks);
    }

    public void clear() {
        marks.clear();
    }
}
