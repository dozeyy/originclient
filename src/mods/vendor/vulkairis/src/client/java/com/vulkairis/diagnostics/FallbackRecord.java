package com.vulkairis.diagnostics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Phase E: honest fallback records.
 *
 * <p>Every fallback engagement records the requested backend versus the effective backend, the
 * pipeline stage that engaged it, the pack and shader identity, and a sanitized, bounded log
 * excerpt. GUI status text ({@code /vulkairis status|shader}) mirrors these records verbatim, so
 * a running world is never presented as working shaders: when the bridge falls back, the status
 * says exactly what was requested, what is actually rendering, where it engaged, and why.
 */
public final class FallbackRecord {

    /** Maximum excerpt lines retained per engagement. */
    public static final int MAX_EXCERPT_LINES = 20;
    /** Maximum excerpt characters retained per engagement. */
    public static final int MAX_EXCERPT_CHARS = 2000;
    /** Maximum engagements retained in history. */
    public static final int MAX_HISTORY = 50;

    private static final Pattern USERS_SEGMENT =
            Pattern.compile("(?i)([/\\\\]Users[/\\\\])[^/\\\\:\"'\\s]+");
    private static final Pattern HOME_SEGMENT =
            Pattern.compile("(?i)(/home/)[^/:\"'\\s]+");
    private static final Pattern MAC_USERS_SEGMENT =
            Pattern.compile("(?i)(/Users/)[^/:\"'\\s]+");

    /**
     * One fallback engagement.
     *
     * @param id               stable engagement id ({@code FALLBACK-<n>})
     * @param timestamp        when the fallback engaged
     * @param requestedBackend the backend that was requested
     * @param effectiveBackend the backend actually rendering after the fallback
     * @param stage            the pipeline stage that engaged the fallback
     * @param packId           the active shader pack identity
     * @param shaderId         the shader/program identity, when known
     * @param reason           short machine-stable reason
     * @param logExcerpt       sanitized, bounded log excerpt
     */
    public record Entry(String id, Instant timestamp, String requestedBackend,
                        String effectiveBackend, String stage, String packId,
                        String shaderId, String reason, String logExcerpt) {
    }

    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final CopyOnWriteArrayList<Entry> HISTORY = new CopyOnWriteArrayList<>();
    private static volatile Entry last = null;

    private FallbackRecord() {
    }

    /**
     * Records one fallback engagement. Blank fields fall back to explicit unknowns so a record
     * never silently drops an axis; the excerpt is sanitized and bounded.
     *
     * @return the recorded entry
     */
    public static Entry engage(String requestedBackend, String effectiveBackend, String stage,
                               String packId, String shaderId,
                               String reason, String logExcerpt) {
        String id = "FALLBACK-" + SEQUENCE.incrementAndGet();
        Entry entry = new Entry(id, Instant.now(),
                orUnknown(requestedBackend, "unknown-backend"),
                orUnknown(effectiveBackend, "unknown-backend"),
                orUnknown(stage, "unknown-stage"),
                orUnknown(packId, "unknown-pack"),
                orUnknown(shaderId, "unknown-shader"),
                orUnknown(reason, "unspecified reason"),
                boundExcerpt(sanitize(logExcerpt)));
        HISTORY.add(entry);
        while (HISTORY.size() > MAX_HISTORY) {
            HISTORY.remove(0);
        }
        last = entry;
        return entry;
    }

    /** @return the most recent engagement, or {@code null} when the backend is healthy */
    public static Entry last() {
        return last;
    }

    /** @return the most recent engagements, newest last, bounded by {@code limit} */
    public static List<Entry> history(int limit) {
        if (HISTORY.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        int start = Math.max(0, HISTORY.size() - limit);
        return Collections.unmodifiableList(new ArrayList<>(HISTORY.subList(start, HISTORY.size())));
    }

    /**
     * Renders one engagement as status lines. The first line carries the full requested-vs-effective
     * identity; the following lines carry the reason and the sanitized excerpt. Status commands
     * print these lines verbatim.
     */
    public static List<String> describe(Entry entry) {
        if (entry == null) {
            return List.of("fallback: none (backend healthy)");
        }
        List<String> lines = new ArrayList<>();
        lines.add("fallback " + entry.id() + " requested='" + entry.requestedBackend()
                + "' effective='" + entry.effectiveBackend()
                + "' stage='" + entry.stage()
                + "' pack='" + entry.packId()
                + "' shader='" + entry.shaderId() + "'");
        lines.add("reason: " + entry.reason());
        String excerpt = entry.logExcerpt();
        if (excerpt == null || excerpt.isBlank()) {
            lines.add("excerpt: (none)");
        } else {
            for (String line : excerpt.split("\n")) {
                lines.add("excerpt: " + line);
            }
        }
        return List.copyOf(lines);
    }

    /** @return the engagement on one line, for compact overlays */
    public static String describeSingleLine(Entry entry) {
        if (entry == null) {
            return "fallback: none (backend healthy)";
        }
        return "fallback " + entry.id() + " requested='" + entry.requestedBackend()
                + "' effective='" + entry.effectiveBackend()
                + "' stage='" + entry.stage()
                + "' pack='" + entry.packId()
                + "' shader='" + entry.shaderId()
                + "' reason='" + entry.reason() + "'";
    }

    /**
     * Redacts personal paths from a log excerpt: the user home becomes {@code ~} and generic
     * user-profile prefixes ({@code Users\<name>}, {@code /home/<name>},
     * {@code /Users/<name>}) become {@code <user>}. The current login name is redacted only as a
     * path segment, never as a bare word, so shader author names survive.
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String redacted = text;
        try {
            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                redacted = redacted.replace(home, "~");
                String swapped = swapSeparators(home);
                if (!swapped.equals(home)) {
                    redacted = redacted.replace(swapped, "~");
                }
            }
        } catch (Throwable ignored) {
        }
        redacted = USERS_SEGMENT.matcher(redacted).replaceAll("$1<user>");
        redacted = HOME_SEGMENT.matcher(redacted).replaceAll("$1<user>");
        redacted = MAC_USERS_SEGMENT.matcher(redacted).replaceAll("$1<user>");
        try {
            String user = System.getProperty("user.name");
            if (user != null && user.length() >= 2) {
                redacted = redacted.replaceAll("(?i)(?<=[/\\\\:])"
                        + Pattern.quote(user) + "(?=[/\\\\:\"'\\s]|$)", "<user>");
            }
        } catch (Throwable ignored) {
        }
        return redacted;
    }

    /** Clears all engagements; tests only. */
    public static void clearForTests() {
        HISTORY.clear();
        last = null;
    }

    private static String orUnknown(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String swapSeparators(String path) {
        if (path.indexOf('\\') >= 0) {
            return path.replace('\\', '/');
        }
        return path.replace('/', '\\');
    }

    private static String boundExcerpt(String sanitized) {
        if (sanitized == null || sanitized.isEmpty()) {
            return "";
        }
        String[] lines = sanitized.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        int kept = 0;
        for (String line : lines) {
            if (kept >= MAX_EXCERPT_LINES) {
                builder.append("\n... [excerpt truncated at ")
                        .append(MAX_EXCERPT_LINES).append(" lines]");
                break;
            }
            String next = (kept == 0 ? "" : "\n") + line;
            if (builder.length() + next.length() > MAX_EXCERPT_CHARS) {
                int room = MAX_EXCERPT_CHARS - builder.length() - next.length() > 0
                        ? next.length()
                        : Math.max(0, MAX_EXCERPT_CHARS - builder.length());
                builder.append(next, 0, Math.min(next.length(), room));
                builder.append("\n... [excerpt truncated at ")
                        .append(MAX_EXCERPT_CHARS).append(" chars]");
                break;
            }
            builder.append(next);
            kept++;
        }
        return builder.toString();
    }
}
