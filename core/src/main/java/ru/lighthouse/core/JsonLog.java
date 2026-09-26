package ru.lighthouse.core;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class JsonLog {
    private JsonLog() {}

    private static String q(String value) {
        if (value == null) return "null";
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append("\\u00").append(Character.forDigit(c >>> 4, 16)).append(Character.forDigit(c & 15, 16));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    public static byte[] encode(ScanReport report) {
        StringBuilder b = new StringBuilder(32768);
        b.append("{\n  \"schemaVersion\": 6,");
        b.append("\n  \"scanProfile\": ").append(q(report.profile.name())).append(',');
        b.append("\n  \"expectedTargets\": ").append(report.expectedTargets).append(',');
        b.append("\n  \"requestedPasses\": ").append(report.profile.passes).append(',');
        b.append("\n  \"complete\": ").append(report.complete).append(',');
        b.append("\n  \"scanId\": ").append(q(report.scanId)).append(',');
        b.append("\n  \"startedAt\": ").append(q(report.startedAt.toString())).append(',');
        b.append("\n  \"finishedAt\": ").append(q(report.finishedAt.toString())).append(',');
        b.append("\n  \"device\": {");
        b.append("\n    \"name\": ").append(q(report.device.deviceName)).append(',');
        b.append("\n    \"os\": ").append(q(report.device.os)).append(',');
        b.append("\n    \"macAddress\": ").append(q(report.device.macAddress)).append(',');
        b.append("\n    \"localIp\": ").append(q(report.device.localIp)).append(',');
        b.append("\n    \"publicIp\": ").append(q(report.publicIp)).append(',');
        b.append("\n    \"network\": ").append(q(report.device.networkName)).append(',');
        b.append("\n    \"networkOrigin\": ").append(q(report.networkOrigin)).append(',');
        b.append("\n    \"vpnDetected\": ").append(report.device.vpnDetected).append(',');
        b.append("\n    \"circumvention\": ").append(q(report.device.circumvention)).append(',');
        b.append("\n    \"networkValidation\": ").append(q(report.device.networkValidation)).append(',');
        b.append("\n    \"metered\": ").append(q(report.device.metered));
        b.append("\n  },");
        b.append("\n  \"level\": ").append(q(report.level.name())).append(',');
        b.append("\n  \"networkState\": ").append(q(report.assessment.state == null ? null : report.assessment.state.name())).append(',');
        b.append("\n  \"assessment\": {\"explanation\": ").append(q(report.assessment.explanation))
            .append(", \"baselineCompared\": ").append(report.assessment.baselineCompared)
            .append(", \"comparedServices\": ").append(report.assessment.comparedServices).append('}').append(',');
        appendStrings(b, "newlyAffectedVsBaseline", report.assessment.newlyAffected);
        b.append(',');
        java.util.List<String> availableIds = new java.util.ArrayList<>();
        for (ProbeResult available : NetworkAssessment.availableServices(report)) availableIds.add(available.target.id);
        appendStrings(b, "availableServiceIds", availableIds);
        b.append(',');
        appendStrings(b, "newlyUnavailable", report.newlyUnavailable);
        b.append(',');
        appendStrings(b, "recovered", report.recovered);
        b.append(',');
        appendStrings(b, "recommendations", report.recommendations);
        b.append(",\n  \"results\": [");
        for (int i = 0; i < report.results.size(); i++) {
            ProbeResult r = report.results.get(i);
            if (i > 0) b.append(',');
            b.append("\n    {\"id\":").append(q(r.target.id))
                .append(",\"name\":").append(q(r.target.name))
                .append(",\"category\":").append(q(r.target.category))
                .append(",\"host\":").append(q(r.target.host))
                .append(",\"region\":").append(q(r.target.region))
                .append(",\"probeKind\":").append(q(r.target.probeKind.name()))
                .append(",\"port\":").append(r.target.port)
                .append(",\"status\":").append(q(NetworkAssessment.observedStatus(r).name()))
                .append(",\"rawStatus\":").append(q(r.status.name()))
                .append(",\"dnsMs\":").append(r.dnsMs)
                .append(",\"pingMs\":").append(r.pingMs)
                .append(",\"tcpMs\":").append(r.tcpMs)
                .append(",\"httpsMs\":").append(r.httpsMs)
                .append(",\"httpCode\":").append(r.httpCode)
                .append(",\"resolvedIp\":").append(q(r.resolvedIp))
                .append(",\"error\":").append(q(r.error)).append(",\"probeDetail\":").append(q(r.probeDetail))
                .append(",\"attempts\":").append(r.attempts())
                .append(",\"samples\":[");
            java.util.List<ProbeResult> samples = r.samples.isEmpty() ? java.util.Collections.singletonList(r) : r.samples;
            for (int j = 0; j < samples.size(); j++) {
                ProbeResult sample = samples.get(j);
                if (j > 0) b.append(',');
                b.append("{\"measuredAtMillis\":").append(sample.measuredAtMillis)
                    .append(",\"status\":").append(q(sample.status.name()))
                    .append(",\"dnsMs\":").append(sample.dnsMs).append(",\"pingMs\":").append(sample.pingMs)
                    .append(",\"tcpMs\":").append(sample.tcpMs).append(",\"httpsMs\":").append(sample.httpsMs)
                    .append(",\"httpCode\":").append(sample.httpCode).append(",\"resolvedIp\":").append(q(sample.resolvedIp))
                    .append(",\"error\":").append(q(sample.error)).append(",\"probeDetail\":").append(q(sample.probeDetail)).append('}');
            }
            b.append("]}");
        }
        b.append("\n  ],\n  \"networkChecks\": [");
        for (int i = 0; i < report.networkChecks.size(); i++) {
            NetworkCheckResult check = report.networkChecks.get(i);
            if (i > 0) b.append(',');
            b.append("\n    {\"id\":").append(q(check.id))
                .append(",\"name\":").append(q(check.name))
                .append(",\"category\":").append(q(check.category))
                .append(",\"status\":").append(q(check.status.name()))
                .append(",\"summary\":").append(q(check.summary))
                .append(",\"metrics\":{");
            int metricIndex = 0;
            for (java.util.Map.Entry<String, String> metric : check.metrics.entrySet()) {
                if (metricIndex++ > 0) b.append(',');
                b.append(q(metric.getKey())).append(':').append(q(metric.getValue()));
            }
            b.append("}}");
        }
        b.append("\n  ]\n}\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendStrings(StringBuilder b, String key, java.util.List<String> values) {
        b.append("\n  ").append(q(key)).append(": [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append(',');
            b.append(q(values.get(i)));
        }
        b.append(']');
    }
}
