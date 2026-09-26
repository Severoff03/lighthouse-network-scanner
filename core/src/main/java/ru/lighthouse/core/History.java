package ru.lighthouse.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class History {
    private History() {}

    public static Map<String, ProbeResult.Status> load(Path path) {
        Map<String, ProbeResult.Status> result = new HashMap<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\t", 2);
                if (p.length == 2) result.put(p[0], ProbeResult.Status.valueOf(p[1]));
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static void save(Path path, ScanReport report) throws IOException {
        StringBuilder b = new StringBuilder();
        Map<String, ProbeResult.Status> values = report.complete ? new java.util.LinkedHashMap<>() : load(path);
        for (ProbeResult r : report.results) values.put(r.target.id, r.status);
        for (Map.Entry<String, ProbeResult.Status> entry : values.entrySet())
            b.append(entry.getKey()).append('\t').append(entry.getValue().name()).append('\n');
        Files.createDirectories(path.getParent());
        // Files.writeString is a Java 11 API absent on supported Android devices.
        Files.write(path, b.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static void save(Path path, Map<String, ProbeResult.Status> values) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, ProbeResult.Status> entry : values.entrySet())
            b.append(entry.getKey()).append('\t').append(entry.getValue().name()).append('\n');
        Files.createDirectories(path.getParent());
        Files.write(path, b.toString().getBytes(StandardCharsets.UTF_8));
    }
}
