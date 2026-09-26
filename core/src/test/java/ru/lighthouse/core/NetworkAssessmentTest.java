package ru.lighthouse.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NetworkAssessmentTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        List<ProbeResult> ordinary = services(20);
        ordinary.set(4, down(ordinary.get(4))); ordinary.set(14, down(ordinary.get(14))); ordinary.set(17, down(ordinary.get(17)));
        Map<String, ProbeResult.Status> baseline = statuses(ordinary);
        check(assess(ordinary, baseline).state == NetworkAssessment.State.GREEN, "existing blocked services stay green");
        check(assess(ordinary, Collections.emptyMap()).state == NetworkAssessment.State.GREEN, "first mostly available scan is provisional green");
        check(!assess(ordinary, Collections.emptyMap()).baselineCompared, "first scan does not claim historical comparison");
        List<ProbeResult> firstMobileScan = services(20);
        for (int i : new int[]{0, 1, 2, 10, 11, 12, 13}) firstMobileScan.set(i, down(firstMobileScan.get(i)));
        check(assess(firstMobileScan, Collections.emptyMap()).state == NetworkAssessment.State.GREEN,
            "first mobile scan establishes its own ordinary level instead of becoming yellow from latency or transient loss");
        List<ProbeResult> unusual = new ArrayList<>(ordinary);
        for (int i : new int[]{0, 1, 10}) unusual.set(i, down(unusual.get(i)));
        NetworkAssessment yellow = assess(unusual, baseline);
        check(yellow.state == NetworkAssessment.State.YELLOW && yellow.newlyAffected.size() == 3, "new persistent failures raise yellow");
        check(assess(unusual, baseline).state == NetworkAssessment.State.YELLOW, "repeated outage is not learned as normal");
        List<ProbeResult> single = new ArrayList<>(ordinary); single.set(0, down(single.get(0)));
        check(assess(single, baseline).state == NetworkAssessment.State.GREEN, "one anomaly does not imply mass interference");

        List<ProbeResult> allowlist = services(20);
        for (int i = 11; i < 20; i++) allowlist.set(i, down(allowlist.get(i)));
        check(assess(allowlist, baseline).state == NetworkAssessment.State.RED, "allowlist-shaped reachability is red");
        ScanReport red = report(allowlist, assess(allowlist, baseline), true);
        check(NetworkAssessment.availableServices(red).size() == 11, "all reachable services included, no arbitrary cap");
        check(ReportAnalytics.availableServicesText(red).contains("Service 10") && !ReportAnalytics.availableServicesText(red).contains("Service 19"), "available list is exact");
        ProbeResult denied = new ProbeResult(allowlist.get(0).target, ProbeResult.Status.AVAILABLE, 2, 2, 2, 2, 403, "192.0.2.1", null);
        check(!NetworkAssessment.accessible(denied), "HTTP 403 is not successful service access even in old logs");
        ProbeResult mixed = ProbeResult.combine(allowlist.get(0), down(allowlist.get(0)));
        check(!NetworkAssessment.accessible(mixed), "one successful HTTPS sample out of two is not stable service access");
        check(NetworkAssessment.observedStatus(mixed) == ProbeResult.Status.DEGRADED,
            "split successful and failed HTTPS samples stay unstable");
        ProbeResult mostlyUp = ProbeResult.combine(mixed, allowlist.get(0));
        check(NetworkAssessment.accessible(mostlyUp) && NetworkAssessment.observedStatus(mostlyUp) == ProbeResult.Status.AVAILABLE,
            "a majority of successful HTTPS samples proves availability");
        ProbeResult noHttps = new ProbeResult(allowlist.get(0).target, ProbeResult.Status.DEGRADED,
            5, 80, 40, -1, -1, "192.0.2.1", "TLS timeout");
        check(NetworkAssessment.observedStatus(noHttps) == ProbeResult.Status.DEGRADED,
            "TCP response without HTTPS status means limited evidence, not no response");
        String unavailableJson = new String(JsonLog.encode(report(List.of(noHttps), NetworkAssessment.legacy(ScanReport.Level.INCOMPLETE), false)), StandardCharsets.UTF_8);
        check(unavailableJson.contains("\"status\":\"DEGRADED\",\"rawStatus\":\"DEGRADED\""),
            "JSON preserves limited transport evidence");
        ProbeResult highPing = new ProbeResult(allowlist.get(1).target, ProbeResult.Status.AVAILABLE, 800, 2400, 1700, 2600, 200, "192.0.2.1", null);
        check(NetworkAssessment.accessible(highPing), "high mobile latency alone does not change availability");

        List<ProbeResult> none = services(20); none.replaceAll(NetworkAssessmentTest::down);
        check(assess(none, baseline).state == NetworkAssessment.State.BLACK, "no service or transport responses is black");
        List<ProbeResult> tcpOnly = new ArrayList<>(none);
        tcpOnly.set(0, new ProbeResult(none.get(0).target, ProbeResult.Status.DEGRADED, 2, -1, 10, -1, -1, "192.0.2.1", null));
        check(assess(tcpOnly, baseline).state == NetworkAssessment.State.YELLOW, "TCP evidence prevents false black");
        List<ProbeResult> denial = new ArrayList<>(none);
        denial.set(0, denied);
        check(assess(denial, baseline).state != NetworkAssessment.State.BLACK, "HTTP denial proves some connectivity");
        check(NetworkAssessment.assess(none, baseline, 20, false).state == null, "partial scan has no global color");
        check(NetworkAssessment.assess(none.subList(0, 1), baseline, 20, true).state == null, "one failed target cannot cause black");
        check(NetworkAssessment.assess(none, baseline, 20, true, true).state == NetworkAssessment.State.YELLOW, "control response prevents false black");
        check(NetworkAssessment.hasExternalResponse(Collections.emptyList(), "192.0.2.5"), "external IP lookup is evidence");
        NetworkCheckResult local = new NetworkCheckResult("interfaces", "Interfaces", "test", NetworkCheckResult.Status.OK, "up", Collections.emptyMap());
        check(!NetworkAssessment.hasExternalResponse(List.of(local), "unavailable"), "connected local adapter is not internet evidence");
        NetworkCheckResult external = new NetworkCheckResult("tcp_google", "TCP", "test", NetworkCheckResult.Status.WARNING, "loss", Map.of("successful", "1"));
        check(NetworkAssessment.hasExternalResponse(List.of(external), "unavailable"), "partial external control response is evidence");
        NetworkCheckResult doh = new NetworkCheckResult("doh_dns.google", "DoH", "test", NetworkCheckResult.Status.OK, "ok", Collections.emptyMap());
        check(NetworkAssessment.hasExternalResponse(List.of(doh), "unavailable"), "DoH control success prevents black");

        Path temporary = Files.createTempDirectory("lighthouse-baseline-test-");
        DeviceInfo device = device(false, "192.0.2.4", "Example");
        ScanReport normal = report(ordinary, assess(ordinary, Collections.emptyMap()), true);
        try {
            NetworkBaseline.rememberFirstNormal(temporary, normal);
            byte[] original = Files.readAllBytes(NetworkBaseline.file(temporary, normal.device));
            NetworkBaseline.rememberFirstNormal(temporary, report(unusual, yellow, true));
            check(java.util.Arrays.equals(original, Files.readAllBytes(NetworkBaseline.file(temporary, normal.device))), "yellow never overwrites normal baseline");
            check(NetworkBaseline.load(temporary, normal.device).equals(baseline), "normal reference roundtrip");
            NetworkBaseline.replace(temporary, report(unusual, yellow, true));
            check(NetworkBaseline.load(temporary, normal.device).equals(statuses(unusual)), "explicit normal reference update");
            List<ProbeResult> mixedRows = new ArrayList<>(ordinary); mixedRows.set(0, mostlyUp);
            NetworkBaseline.replace(temporary, report(mixedRows, assess(mixedRows, Collections.emptyMap()), true));
            check(NetworkBaseline.load(temporary, normal.device).get(mostlyUp.target.id) == ProbeResult.Status.AVAILABLE,
                "baseline stores majority-observed reachability rather than a single transient failure");
            boolean rejected = false;
            try { NetworkBaseline.replace(temporary, red); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "red cannot become ordinary reference");
            rejected = false;
            try { NetworkBaseline.replace(temporary, report(ordinary, assess(ordinary, baseline), false)); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "partial scan cannot become ordinary reference");
        } finally {
            Path folder = temporary.resolve("baselines");
            if (Files.exists(folder)) {
                try (var files = Files.list(folder)) { for (Path file : files.toList()) Files.delete(file); }
                Files.delete(folder);
            }
            Files.delete(temporary);
        }
        check(!NetworkBaseline.contextKey(device).equals(NetworkBaseline.contextKey(device(true, "192.0.2.4", "Example"))), "VPN and direct baselines separated");
        check(NetworkBaseline.contextKey(device).equals(NetworkBaseline.contextKey(device(false, "192.0.2.55", "Example"))), "DHCP host change retains same reference");
        check(!NetworkBaseline.contextKey(device).equals(NetworkBaseline.contextKey(device(false, "192.0.2.4", "Other"))), "Wi-Fi SSID change separates reference");
        String json = new String(JsonLog.encode(red), StandardCharsets.UTF_8);
        check(json.contains("\"networkState\": \"RED\"") && json.contains("availableServiceIds") && json.contains("baselineCompared"), "JSON includes four-state assessment and available services");
        check(NetworkAssessment.State.values().length == 4, "exactly four global colors");
        for (ScanReport.Level value : new ScanReport.Level[]{ScanReport.Level.NORMAL, ScanReport.Level.DEGRADED,
            ScanReport.Level.ALLOWLIST_SUSPECTED, ScanReport.Level.NO_CONNECTION})
            check(!NetworkAssessment.stateTitle(value).matches("(?iu).*(зел|желт|жёлт|красн|черн|чёрн).*") ,
                "user-facing level title does not spell out a color");
        System.out.println("Network assessment: " + assertions + " checks passed.");
    }

    private static NetworkAssessment assess(List<ProbeResult> rows, Map<String, ProbeResult.Status> baseline) {
        return NetworkAssessment.assess(rows, baseline, rows.size(), true);
    }
    private static List<ProbeResult> services(int count) {
        List<ProbeResult> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ServiceTarget target = new ServiceTarget("service-" + i, "Service " + i, TargetCatalog.CHATS,
                "example" + i + ".invalid", "/", i < count / 2 ? "RU" : "GLOBAL");
            result.add(new ProbeResult(target, ProbeResult.Status.AVAILABLE, 2, 2, 2, 2, 200, "192.0.2.1", null));
        }
        return result;
    }
    private static ProbeResult down(ProbeResult before) {
        return new ProbeResult(before.target, ProbeResult.Status.UNAVAILABLE, 2, -1, -1, -1, -1, "192.0.2.1", "timeout");
    }
    private static Map<String, ProbeResult.Status> statuses(List<ProbeResult> rows) {
        Map<String, ProbeResult.Status> values = new LinkedHashMap<>();
        for (ProbeResult row : rows) values.put(row.target.id, row.status);
        return values;
    }
    private static DeviceInfo device(boolean vpn, String ip, String ssid) {
        NetworkCheckResult wifi = new NetworkCheckResult("start_wifi_summary", "Wi-Fi", "test", NetworkCheckResult.Status.OK,
            "example", Map.of("ssid", ssid));
        return new DeviceInfo("test", "test", ip, "unavailable", "Wi-Fi", vpn, "unknown", "unknown", "unknown", List.of(wifi));
    }
    private static ScanReport report(List<ProbeResult> rows, NetworkAssessment assessment, boolean complete) {
        return new ScanReport("test", Instant.now(), Instant.now(), device(false, "192.0.2.4", "Example"), "unavailable", "unavailable",
            assessment.level, rows, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            ScanProfile.QUICK, rows.size(), complete, assessment);
    }
    private static void check(boolean condition, String description) {
        assertions++; if (!condition) throw new AssertionError(description);
    }
}
