package ru.lighthouse.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;

/** A pinned normal reference; later outages must never silently become the new normal. */
public final class NetworkBaseline {
    private NetworkBaseline() {}

    public static Map<String, ProbeResult.Status> load(Path directory, DeviceInfo device) {
        return History.load(file(directory, device));
    }

    public static void rememberFirstNormal(Path directory, ScanReport report) throws IOException {
        Path file = file(directory, report.device);
        if (report.complete && report.level == ScanReport.Level.NORMAL && !Files.exists(file)) saveNormalized(file, report);
    }

    public static void replace(Path directory, ScanReport report) throws IOException {
        if (!report.complete || report.level == ScanReport.Level.NO_CONNECTION || report.level == ScanReport.Level.ALLOWLIST_SUSPECTED
            || report.level == ScanReport.Level.INCOMPLETE)
            throw new IllegalArgumentException("Нельзя использовать неполный скан, отсутствие интернета или признаки белых списков как обычный уровень.");
        saveNormalized(file(directory, report.device), report);
    }

    public static Path file(Path directory, DeviceInfo device) {
        return directory.resolve("baselines").resolve(contextKey(device) + ".tsv");
    }

    public static String contextKey(DeviceInfo device) {
        StringBuilder identity = new StringBuilder(device.networkName == null ? "unknown" : device.networkName);
        // Keep tunnel and untunnelled contexts separate. No public IP: it can change during an outage.
        identity.append('|').append(device.vpnDetected).append('|').append(subnet(device.localIp));
        for (NetworkCheckResult check : device.observations) {
            if (check.id.endsWith("wifi_summary")) identity.append('|').append(check.metrics.get("ssid"));
            if (check.metrics.containsKey("operatorMccMnc")) identity.append('|').append(check.metrics.get("operatorMccMnc"));
            if ("pc_wifi".equals(check.id)) {
                String output = check.metrics.get("output");
                if (output != null) for (String line : output.split("\\r?\\n"))
                    if (line.trim().matches("SSID\\s*:.*")) identity.append('|').append(line.trim());
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte b : digest) key.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String subnet(String ip) {
        if (ip == null) return "unknown";
        int dot = ip.lastIndexOf('.');
        return dot < 0 ? ip : ip.substring(0, dot);
    }

    private static void saveNormalized(Path file, ScanReport report) throws IOException {
        Map<String, ProbeResult.Status> values = new java.util.LinkedHashMap<>();
        for (ProbeResult result : report.results)
            values.put(result.target.id, NetworkAssessment.observedStatus(result));
        History.save(file, values);
    }
}
