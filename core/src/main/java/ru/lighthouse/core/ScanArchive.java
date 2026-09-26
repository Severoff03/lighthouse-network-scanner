package ru.lighthouse.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Local-only scan index and per-network availability transitions. */
public final class ScanArchive {
    private static final String HEADER_SCANS = "# lighthouse-scan-archive-v1\n";
    private static final String HEADER_EVENTS = "# lighthouse-availability-events-v1\n";

    public static final class Entry {
        public final String scanId;
        public final Instant timestamp;
        public final String contextKey;
        public final ScanReport.Level level;
        public final int available, degraded, unavailable, total;
        public final boolean complete;
        public final String logFile;

        private Entry(String scanId, Instant timestamp, String contextKey, ScanReport.Level level,
                      int available, int degraded, int unavailable, int total, boolean complete, String logFile) {
            this.scanId = scanId; this.timestamp = timestamp; this.contextKey = contextKey; this.level = level;
            this.available = available; this.degraded = degraded; this.unavailable = unavailable;
            this.total = total; this.complete = complete; this.logFile = logFile;
        }
    }

    public static final class Change {
        public final Instant timestamp;
        public final String scanId, contextKey, targetId, serviceName, category;
        public final ProbeResult.Status before, after;

        private Change(Instant timestamp, String scanId, String contextKey, String targetId,
                       String serviceName, String category, ProbeResult.Status before, ProbeResult.Status after) {
            this.timestamp = timestamp; this.scanId = scanId; this.contextKey = contextKey; this.targetId = targetId;
            this.serviceName = serviceName; this.category = category; this.before = before; this.after = after;
        }

        public String description() {
            if (after == ProbeResult.Status.UNAVAILABLE) return "Стал недоступен";
            if (before == ProbeResult.Status.UNAVAILABLE && after == ProbeResult.Status.AVAILABLE) return "Снова доступен";
            if (after == ProbeResult.Status.DEGRADED) return before == ProbeResult.Status.UNAVAILABLE
                ? "Появился частичный ответ" : "Стал ограниченно доступен";
            return "Стабильность восстановилась";
        }
    }

    private ScanArchive() { }

    public static synchronized void record(Path root, ScanReport report, String logFile) throws IOException {
        Path archive = root.resolve("archive");
        Path scans = archive.resolve("scans.tsv");
        Files.createDirectories(archive);
        for (Entry entry : loadScans(root, Integer.MAX_VALUE)) if (entry.scanId.equals(report.scanId)) return;

        int available = 0, degraded = 0, unavailable = 0;
        for (ProbeResult result : report.results) {
            ProbeResult.Status observed = NetworkAssessment.observedStatus(result);
            if (observed == ProbeResult.Status.AVAILABLE) available++;
            else if (observed == ProbeResult.Status.DEGRADED) degraded++; else unavailable++;
        }
        String context = NetworkBaseline.contextKey(report.device);
        append(scans, HEADER_SCANS, enc(report.scanId) + '\t' + report.finishedAt.toEpochMilli() + '\t' + enc(context)
            + '\t' + report.level.name() + '\t' + available + '\t' + degraded + '\t' + unavailable + '\t'
            + report.results.size() + '\t' + report.complete + '\t' + enc(logFile) + '\n');

        if (!report.complete) return;
        Path state = archive.resolve("state-" + context + ".tsv");
        Map<String, ProbeResult.Status> previous = History.load(state);
        if (!previous.isEmpty()) {
            Path events = archive.resolve("events.tsv");
            StringBuilder rows = new StringBuilder();
            for (ProbeResult result : report.results) {
                ProbeResult.Status before = previous.get(result.target.id);
                ProbeResult.Status after = NetworkAssessment.observedStatus(result);
                if (before == null || before == after) continue;
                rows.append(report.finishedAt.toEpochMilli()).append('\t').append(enc(report.scanId)).append('\t')
                    .append(enc(context)).append('\t').append(enc(result.target.id)).append('\t')
                    .append(enc(result.target.name)).append('\t').append(enc(result.target.category)).append('\t')
                    .append(before.name()).append('\t').append(after.name()).append('\n');
            }
            if (rows.length() > 0) append(events, HEADER_EVENTS, rows.toString());
        }
        Map<String, ProbeResult.Status> observed = new java.util.LinkedHashMap<>();
        for (ProbeResult result : report.results)
            observed.put(result.target.id, NetworkAssessment.observedStatus(result));
        History.save(state, observed);
    }

    public static synchronized List<Entry> loadScans(Path root, int limit) {
        List<Entry> values = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(root.resolve("archive/scans.tsv"), StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.split("\\t", -1);
                if (p.length != 10) continue;
                try {
                    values.add(new Entry(dec(p[0]), Instant.ofEpochMilli(Long.parseLong(p[1])), dec(p[2]),
                        ScanReport.Level.valueOf(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]),
                        Integer.parseInt(p[6]), Integer.parseInt(p[7]), Boolean.parseBoolean(p[8]), dec(p[9])));
                } catch (RuntimeException ignored) { /* Skip damaged rows, preserve remaining history. */ }
            }
        } catch (Exception ignored) { }
        values.sort(Comparator.comparing((Entry value) -> value.timestamp).reversed());
        return limited(values, limit);
    }

    public static synchronized List<Change> loadChanges(Path root, int limit) {
        List<Change> values = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(root.resolve("archive/events.tsv"), StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.split("\\t", -1);
                if (p.length != 8) continue;
                try {
                    values.add(new Change(Instant.ofEpochMilli(Long.parseLong(p[0])), dec(p[1]), dec(p[2]),
                        dec(p[3]), dec(p[4]), dec(p[5]), ProbeResult.Status.valueOf(p[6]), ProbeResult.Status.valueOf(p[7])));
                } catch (RuntimeException ignored) { }
            }
        } catch (Exception ignored) { }
        values.sort(Comparator.comparing((Change value) -> value.timestamp).reversed());
        return limited(values, limit);
    }

    public static Map<String, ProbeResult.Status> loadPrevious(Path root, DeviceInfo device) {
        return History.load(root.resolve("archive").resolve("state-" + NetworkBaseline.contextKey(device) + ".tsv"));
    }

    /** Resolves only files below the private application directory. */
    public static Path resolveLog(Path root, String relative) throws IOException {
        Path safeRoot = root.toAbsolutePath().normalize();
        Path result = safeRoot.resolve(relative).normalize();
        if (!result.startsWith(safeRoot) || !Files.isRegularFile(result)) throw new IOException("Лог отсутствует");
        return result;
    }

    private static <T> List<T> limited(List<T> values, int limit) {
        if (limit <= 0) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values.subList(0, Math.min(limit, values.size()))));
    }

    private static void append(Path path, String header, String value) throws IOException {
        if (!Files.exists(path)) Files.write(path, header.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        Files.write(path, value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    private static String enc(String value) {
        if (value == null) value = "";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
