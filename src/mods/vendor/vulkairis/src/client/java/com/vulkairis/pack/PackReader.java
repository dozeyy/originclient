package com.vulkairis.pack;

import com.vulkairis.translation.IncludeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Deterministic pack reader.
 * ponytail: Simple implementation. Supports ZIP and Folders.
 */
public class PackReader {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/PackReader");

    public interface PackSource extends IncludeResolver.PackFiles {
        Path root();
    }

    public static PackSource read(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return new FolderPackSource(path);
        } else if (path.toString().endsWith(".zip")) {
            return new ZipPackSource(path);
        }
        throw new IOException("Unsupported pack format: " + path);
    }

    private static class FolderPackSource implements PackSource {
        private final Path root;
        private final Map<String, List<String>> files = new TreeMap<>();

        FolderPackSource(Path root) throws IOException {
            this.root = root;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    try {
                        files.put(IncludeResolver.canonicalKey(rel), Files.readAllLines(p));
                    } catch (IOException e) {
                        LOGGER.error("Failed to read file {}: {}", p, e.getMessage());
                    }
                });
            }
        }

        @Override public Path root() { return root; }
        @Override public boolean available() { return true; }
        @Override public String describe() { return "folder-pack(" + files.size() + ")"; }
        @Override public List<String> readLines(String path) { return files.get(path); }
        @Override public Set<String> knownPaths() { return files.keySet(); }
    }

    private static class ZipPackSource implements PackSource {
        private final Path root;
        private final Map<String, List<String>> files = new TreeMap<>();

        ZipPackSource(Path path) throws IOException {
            this.root = path;
            try (ZipFile zipFile = new ZipFile(path.toFile())) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory()) {
                        String rel = entry.getName().replace('\\', '/');
                        try {
                            List<String> lines;
                            try (var is = zipFile.getInputStream(entry)) {
                                lines = new java.io.BufferedReader(new java.io.InputStreamReader(is))
                                        .lines().collect(Collectors.toList());
                            }
                            files.put(IncludeResolver.canonicalKey(rel), lines);
                        } catch (IOException e) {
                            LOGGER.error("Failed to read zip entry {}: {}", entry.getName(), e.getMessage());
                        }
                    }
                }
            }
        }

        @Override public Path root() { return root; }
        @Override public boolean available() { return true; }
        @Override public String describe() { return "zip-pack(" + files.size() + ")"; }
        @Override public List<String> readLines(String path) { return files.get(path); }
        @Override public Set<String> knownPaths() { return files.keySet(); }
    }
}
