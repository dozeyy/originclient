package com.vulkairis.pipeline;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Authoritative pipeline linking and virtual ID registry.
 * ponytail: Minimal registry for linking.
 */
public class PipelineRegistry {
    private static final AtomicInteger ID_GEN = new AtomicInteger(10000);
    private static final Map<Integer, List<Integer>> PIPELINES = new ConcurrentHashMap<>();
    private static final Map<String, Integer> NAME_TO_ID = new ConcurrentHashMap<>();

    public static int registerPipeline(String name, List<Integer> shaderIds) {
        int id = ID_GEN.incrementAndGet();
        PIPELINES.put(id, shaderIds);
        NAME_TO_ID.put(name, id);
        return id;
    }

    public static List<Integer> getShaders(int pipelineId) {
        return PIPELINES.get(pipelineId);
    }

    public static Integer getIdByName(String name) {
        return NAME_TO_ID.get(name);
    }
}
