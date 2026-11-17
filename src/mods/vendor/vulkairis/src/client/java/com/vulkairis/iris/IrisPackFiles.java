package com.vulkairis.iris;

import com.vulkairis.translation.IncludeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Phase A: Iris-owned pack file reads for {@link IncludeResolver}.
 *
 * <p>The pack root stays Iris-owned: this reads through the {@code ShaderPack} Iris already
 * loaded — its include graph (raw per-file lines) plus its source-provider function as a
 * fallback — and never mounts a second copy of the zip. Everything Iris-internal is reached by
 * reflection (private fields, synthetic lambda captures) so no Iris include-graph types leak onto
 * our compile classpath; any structural surprise degrades to
 * {@link IncludeResolver#unavailable(String)} instead of throwing into the render path.</p>
 *
 * <p>Path-string ground truth is discovered from the graph itself: node paths look like
 * {@code /shaders/...} (pack-absolute) and are canonicalized to shaders-relative keys by the same
 * rule the resolver uses for specs, so no prefix assumption is load-bearing. Reads served from
 * the graph are the file's raw lines (our own recursion adds cycle/depth guards and
 * {@code #line} markers); the source-provider fallback serves Iris-expanded text and is only
 * used for files the graph does not contain.</p>
 */
final class IrisPackFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/IrisPackFiles");

    private static final String INCLUDE_PROCESSOR_CLASS =
            "net.irisshaders.iris.shaderpack.include.IncludeProcessor";
    private static final String ABSOLUTE_PACK_PATH_CLASS =
            "net.irisshaders.iris.shaderpack.include.AbsolutePackPath";

    private IrisPackFiles() {
    }

    /**
     * Snapshots the given Iris pack's readable files.
     *
     * @param pack the live {@code ShaderPack} from {@code Iris.getCurrentPack()}
     * @return pack file access backed by Iris's own loaded data
     * @throws Exception when the pack cannot be introspected (the caller converts this to an
     *         unavailable-files miss, never a render-path throw)
     */
    static IncludeResolver.PackFiles snapshot(Object pack) throws Exception {
        if (pack == null) {
            throw new IllegalArgumentException("pack must not be null");
        }

        Map<String, List<String>> byRel = new LinkedHashMap<>();
        Function<Object, Object> provider = extractSourceProvider(pack);
        Object processor = extractIncludeProcessor(provider);
        if (processor != null) {
            readGraphNodes(processor, byRel);
        }
        if (byRel.isEmpty()) {
            LOGGER.debug("[Vulkairis/IrisPackFiles] Include graph yielded no nodes; "
                    + "reads will rely on the source-provider fallback");
        }

        Map<String, List<String>> frozen = new LinkedHashMap<>(byRel);
        Set<String> known = Set.copyOf(frozen.keySet());
        return new IncludeResolver.PackFiles() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String describe() {
                return "iris-pack(" + frozen.size() + " files)";
            }

            @Override
            public List<String> readLines(String shadersRelativePath) {
                if (shadersRelativePath == null) {
                    return null;
                }
                List<String> lines = frozen.get(shadersRelativePath);
                if (lines != null) {
                    return lines;
                }
                return readViaProvider(provider, shadersRelativePath);
            }

            @Override
            public Set<String> knownPaths() {
                return known;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Function<Object, Object> extractSourceProvider(Object pack) throws Exception {
        Field field = pack.getClass().getDeclaredField("sourceProvider");
        field.setAccessible(true);
        Object value = field.get(pack);
        if (!(value instanceof Function)) {
            throw new IllegalStateException("sourceProvider is not a Function");
        }
        return (Function<Object, Object>) value;
    }

    /**
     * The source-provider lambda captures the pack's {@code IncludeProcessor}; it is the only
     * captured field of that type, so it is found by type rather than by synthetic field name.
     */
    private static Object extractIncludeProcessor(Function<Object, Object> provider) {
        try {
            for (Field field : provider.getClass().getDeclaredFields()) {
                if (field.getType().getName().equals(INCLUDE_PROCESSOR_CLASS)) {
                    field.setAccessible(true);
                    return field.get(provider);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[Vulkairis/IrisPackFiles] IncludeProcessor capture not reachable: {}",
                    String.valueOf(t));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void readGraphNodes(Object processor, Map<String, List<String>> byRel) {
        try {
            Field graphField = processor.getClass().getDeclaredField("graph");
            graphField.setAccessible(true);
            Object graph = graphField.get(processor);
            Method getNodes = graph.getClass().getMethod("getNodes");
            Object nodes = getNodes.invoke(graph);
            if (!(nodes instanceof Map)) {
                return;
            }
            Method getPathString = null;
            Method getLines = null;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) nodes).entrySet()) {
                Object key = entry.getKey();
                Object node = entry.getValue();
                if (key == null || node == null) {
                    continue;
                }
                if (getPathString == null) {
                    getPathString = key.getClass().getMethod("getPathString");
                    getLines = node.getClass().getMethod("getLines");
                }
                String pathString = String.valueOf(getPathString.invoke(key));
                Object lines = getLines.invoke(node);
                if (!(lines instanceof List)) {
                    continue;
                }
                String rel = IncludeResolver.canonicalKey(pathString);
                if (rel.isEmpty()) {
                    continue;
                }
                List<String> copy = List.copyOf((List<String>) lines);
                byRel.putIfAbsent(rel, copy);
            }
        } catch (Throwable t) {
            LOGGER.debug("[Vulkairis/IrisPackFiles] Include-graph read failed: {}",
                    String.valueOf(t));
        }
    }

    /**
     * Best-effort fallback for files outside the include graph: asks Iris's own provider (which
     * serves Iris-expanded text) under both plausible absolute forms.
     */
    private static List<String> readViaProvider(
            Function<Object, Object> provider, String rel) {
        if (provider == null) {
            return null;
        }
        try {
            Class<?> pathClass = Class.forName(ABSOLUTE_PACK_PATH_CLASS);
            Method fromAbsolutePath = pathClass.getMethod("fromAbsolutePath", String.class);
            for (String candidate : new String[]{"/shaders/" + rel, "/" + rel}) {
                Object key;
                try {
                    key = fromAbsolutePath.invoke(null, candidate);
                } catch (Throwable ignored) {
                    continue;
                }
                Object value;
                try {
                    value = provider.apply(key);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value instanceof String text && !text.isEmpty()) {
                    List<String> lines = new ArrayList<>(List.of(
                            text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
                    if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                        lines.remove(lines.size() - 1);
                    }
                    return List.copyOf(lines);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[Vulkairis/IrisPackFiles] Source-provider read failed for '{}': {}",
                    rel, String.valueOf(t));
        }
        return null;
    }
}
