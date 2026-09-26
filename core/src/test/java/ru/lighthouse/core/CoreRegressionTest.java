package ru.lighthouse.core;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline regression suite, no external test libraries or public network required. */
public final class CoreRegressionTest {
    public static void main(String[] args) throws Exception {
        completionOrder();
        idleDeadline();
        cancellation();
        operationDeadlineAndPoolBound();
        compatibilityErrorPropagation();
        historyAndReport();
        repeatedSamples();
        partialDeadline();
        dnsWireValidation();
        partialHistoryAndJson();
        scanArchive();
        detector404Parsing();
        proxyConfiguration();
        targetCatalog();
        NetworkAssessmentTest.main(new String[0]);
        System.out.println("All core regression checks passed.");
    }

    private static void completionOrder() throws Exception {
        CountDownLatch firstMayFinish = new CountDownLatch(1);
        List<String> published = new ArrayList<>();
        List<Callable<String>> tasks = Arrays.asList(
            () -> { if (!firstMayFinish.await(2, TimeUnit.SECONDS)) throw new AssertionError("Progress blocked behind slow target"); return "slow"; },
            () -> "fast");
        try {
            List<String> result = ProbeBatch.run(tasks, 2, 2500, value -> {
                published.add(value);
                if (value.equals("fast")) firstMayFinish.countDown();
            });
            check(published.equals(Arrays.asList("fast", "slow")) && result.size() == 2, "slow first target cannot freeze later progress");
        } finally { firstMayFinish.countDown(); }
    }

    private static void idleDeadline() {
        long start = System.nanoTime();
        boolean failed = false;
        try { ProbeBatch.run(Collections.singletonList(() -> { Thread.sleep(10000); return 1; }), 1, 70, null); }
        catch (IllegalStateException expected) { failed = true; }
        check(failed && elapsed(start) < 2000, "stalled batch stops at deadline");
    }

    private static void cancellation() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try { ProbeBatch.run(Collections.singletonList(() -> { running.countDown(); Thread.sleep(10000); return 1; }), 1, 11000, null); }
            catch (CancellationException expected) { cancelled.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start();
        check(running.await(2, TimeUnit.SECONDS), "cancellation fixture starts");
        caller.interrupt(); caller.join(2000);
        check(!caller.isAlive() && cancelled.get(), "cancellation stops coordinator and preserves interruption");
    }

    private static void operationDeadlineAndPoolBound() throws Exception {
        ThreadPoolExecutor pool = NetworkDeadline.pool("regression", 2);
        CountDownLatch release = new CountDownLatch(1);
        Callable<Integer> uninterruptible = () -> {
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* Simulated native DNS. */ }
            }
            return 1;
        };
        try {
            for (int i = 0; i < 2; i++) {
                long start = System.nanoTime(); boolean timedOut = false;
                try { NetworkDeadline.call(pool, uninterruptible, 80, "test DNS"); }
                catch (SocketTimeoutException expected) { timedOut = true; }
                check(timedOut && elapsed(start) < 2000, "uninterruptible call respects caller deadline");
            }
            boolean rejected = false;
            try { NetworkDeadline.call(pool, () -> 2, 80, "test DNS"); }
            catch (IOException expected) { rejected = true; }
            check(rejected && pool.getLargestPoolSize() == 2 && pool.getQueue().isEmpty(), "retries cannot leak unbounded threads/queue");
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS); }
    }

    private static void compatibilityErrorPropagation() throws Exception {
        ThreadPoolExecutor pool = NetworkDeadline.pool("compatibility", 1);
        try {
            boolean surfaced = false;
            try { NetworkDeadline.call(pool, () -> { throw new NoSuchMethodError("old Android API"); }, 1000, "test"); }
            catch (NoSuchMethodError expected) { surfaced = true; }
            check(surfaced, "Android linkage errors reach reporting boundary, not silently lost");
        } finally { pool.shutdownNow(); }
    }

    private static void historyAndReport() throws Exception {
        ServiceTarget target = new ServiceTarget("test", "Тест", TargetCatalog.CHATS, "example.com", "/", "RU");
        ProbeResult result = new ProbeResult(target, ProbeResult.Status.AVAILABLE, 1, 1, 1, 1, 200, "127.0.0.1", null);
        ScanReport report = new ScanReport("test", Instant.now(), Instant.now(),
            new DeviceInfo("test", "Android", "unavailable", "unavailable", "test", false, "test"),
            "unavailable", "unavailable", ScanReport.Level.NORMAL, Collections.singletonList(result),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        Path directory = Files.createTempDirectory("lighthouse-history-test");
        Path history = directory.resolve("last-state.tsv");
        try {
            History.save(history, report);
            check(History.load(history).get("test") == ProbeResult.Status.AVAILABLE, "history saves and reloads UTF-8");
            check(JsonLog.encode(report).length > 0 && !ReportAnalytics.plainOverview(report).isEmpty(), "report serialization and analytics complete");
        } finally { Files.deleteIfExists(history); Files.deleteIfExists(directory); }
    }

    private static ProbeResult sample(String id, ProbeResult.Status status) {
        return new ProbeResult(new ServiceTarget(id, "Тест\t\u0001", TargetCatalog.CHATS, "example.com", "/", "RU"),
            status, 1, -1, 2, 3, 200, "127.0.0.1", null);
    }

    private static void repeatedSamples() {
        ProbeResult up = sample("repeat", ProbeResult.Status.AVAILABLE);
        ProbeResult down = sample("repeat", ProbeResult.Status.UNAVAILABLE);
        ProbeResult mixed = ProbeResult.combine(ProbeResult.combine(up, down), up);
        check(mixed.status == ProbeResult.Status.DEGRADED && mixed.attempts() == 3,
            "intermittent service stays degraded after successful final sample");
        check(mixed.samples.get(1).status == ProbeResult.Status.UNAVAILABLE && mixed.samples.get(0).samples.isEmpty(),
            "raw samples preserved without recursive aggregation");
        check(ProbeResult.combine(up, up).status == ProbeResult.Status.AVAILABLE
            && ProbeResult.combine(down, down).status == ProbeResult.Status.UNAVAILABLE, "consistent samples retain status");
    }

    private static void partialDeadline() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        List<Callable<Integer>> tasks = Arrays.asList(() -> 1, () -> {
            try { Thread.sleep(10000); return 2; } finally { interrupted.countDown(); }
        });
        List<Integer> results = ProbeBatch.run(tasks, 2, 5000,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150), null);
        check(results.equals(Collections.singletonList(1)), "budget saves completed samples, not fabricated failures");
        check(interrupted.await(2, TimeUnit.SECONDS), "budget cancels remaining probes");
    }

    private static void dnsWireValidation() throws Exception {
        byte[] query = DnsWire.query(123, "example.com", 1);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] header = query.clone(); header[2] = (byte) 0x81; header[3] = (byte) 0x80; header[7] = 1;
        out.write(header);
        out.write(new byte[]{(byte) 0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4});
        byte[] response = out.toByteArray();
        check(DnsWire.describe(response, 123).contains("addresses=1.2.3.4"), "DNS parser reads compressed A answer");
        byte[] ipv6 = Arrays.copyOf(response, response.length + 12);
        ipv6[header.length + 3] = 28; ipv6[header.length + 11] = 16;
        Arrays.fill(ipv6, header.length + 12, ipv6.length, (byte) 0); ipv6[ipv6.length - 1] = 1;
        check(DnsWire.describe(ipv6, 123).contains("0:0:0:0:0:0:0:1"), "DNS parser reads AAAA answer");
        int rejected = 0;
        for (byte[] malformed : Arrays.asList(query, Arrays.copyOf(response, response.length - 1), new byte[3])) {
            try { DnsWire.describe(malformed, 123); } catch (IOException expected) { rejected++; }
        }
        try { DnsWire.describe(response, 124); } catch (IOException expected) { rejected++; }
        check(rejected == 4, "DNS rejects queries, truncation and mismatched transaction IDs");
    }

    private static void partialHistoryAndJson() throws Exception {
        Path directory = Files.createTempDirectory("lighthouse-partial-test"); Path path = directory.resolve("history.tsv");
        DeviceInfo device = new DeviceInfo("test", "Android", "unavailable", "unavailable", "Wi-Fi", false, "unknown");
        ProbeResult result = ProbeResult.combine(sample("new", ProbeResult.Status.AVAILABLE), sample("new", ProbeResult.Status.UNAVAILABLE));
        ScanReport partial = new ScanReport("test", Instant.now(), Instant.now(), device, "unknown", "unknown",
            ScanReport.Level.DEGRADED, Collections.singletonList(result), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), ScanProfile.DEEP, 114, false);
        try {
            Files.write(path, "old\tAVAILABLE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            History.save(path, partial);
            check(History.load(path).size() == 2, "partial history preserves unmeasured services");
            String json = new String(JsonLog.encode(partial), java.nio.charset.StandardCharsets.UTF_8);
            check(json.contains("\\u0009\\u0001") && json.contains("\"complete\": false") && json.contains("\"attempts\":2")
                && json.contains("measuredAtMillis"), "JSON escapes SSID controls and includes completeness/raw samples");
            check(ReportAnalytics.plainOverview(partial).stream().anyMatch(line -> line.contains("частичный")),
                "partial report warns about incomplete coverage");
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(directory); }
    }

    private static void targetCatalog() {
        List<ServiceTarget> targets = TargetCatalog.defaults();
        java.util.Set<String> ids = new java.util.HashSet<>(), hosts = new java.util.HashSet<>();
        int chats = 0; boolean validHosts = true;
        for (ServiceTarget target : targets) {
            if (!ids.add(target.id)) throw new AssertionError("Duplicate catalog ID: " + target.id);
            if (!hosts.add(target.host + ":" + target.port)) throw new AssertionError("Duplicate catalog endpoint: " + target.host);
            validHosts &= target.host.matches("[A-Za-z0-9.-]+") && !target.host.contains("..");
            if (TargetCatalog.CHATS.equals(target.category)) chats++;
        }
        check(ids.size() == targets.size(), "catalog IDs are unique");
        check(hosts.size() == targets.size(), "catalog endpoints are unique");
        check(validHosts, "catalog contains only syntactically valid hostnames");
        check(chats >= 30, "alternative messenger catalog is extensive");
        check(targets.stream().anyMatch(t -> "briar".equals(t.id))
            && targets.stream().anyMatch(t -> "jami".equals(t.id))
            && targets.stream().anyMatch(t -> "deltachat".equals(t.id))
            && targets.stream().anyMatch(t -> "tamtam".equals(t.id)), "alternative messenger families are present");
    }

    private static void scanArchive() throws Exception {
        Path directory = Files.createTempDirectory("lighthouse-archive-test");
        Path logs = directory.resolve("logs"); Files.createDirectories(logs);
        Files.write(logs.resolve("one.json"), new byte[]{1});
        Files.write(logs.resolve("two.json"), new byte[]{2});
        DeviceInfo device = new DeviceInfo("Телефон", "Android", "192.168.1.2", "unavailable", "Wi-Fi", false, "none");
        ServiceTarget firstTarget = new ServiceTarget("chat", "Чат\tтест", TargetCatalog.CHATS, "example.com", "/", "RU");
        ServiceTarget secondTarget = new ServiceTarget("cloud", "Облако", TargetCatalog.FILES, "example.org", "/", "INT");
        Instant now = Instant.now();
        ScanReport first = new ScanReport("scan-one", now, now, device, "unknown", "unknown", ScanReport.Level.NORMAL,
            Arrays.asList(new ProbeResult(firstTarget, ProbeResult.Status.AVAILABLE, 1, 1, 1, 1, 200, "1.1.1.1", null),
                new ProbeResult(secondTarget, ProbeResult.Status.UNAVAILABLE, -1, -1, -1, -1, -1, "", "timeout")),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        ScanReport second = new ScanReport("scan-two", now, now.plusSeconds(2), device, "unknown", "unknown", ScanReport.Level.DEGRADED,
            Arrays.asList(new ProbeResult(firstTarget, ProbeResult.Status.UNAVAILABLE, -1, -1, -1, -1, -1, "", "timeout"),
                new ProbeResult(secondTarget, ProbeResult.Status.AVAILABLE, 1, 1, 1, 1, 200, "1.1.1.1", null)),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        try {
            ScanArchive.record(directory, first, "logs/one.json");
            ScanArchive.record(directory, second, "logs/two.json");
            ScanArchive.record(directory, second, "logs/two.json");
            List<ScanArchive.Entry> scans = ScanArchive.loadScans(directory, 20);
            List<ScanArchive.Change> changes = ScanArchive.loadChanges(directory, 20);
            check(scans.size() == 2 && scans.get(0).scanId.equals("scan-two"), "archive indexes every scan once, newest first");
            check(changes.size() == 2 && changes.stream().anyMatch(c -> c.description().equals("Стал недоступен"))
                && changes.stream().anyMatch(c -> c.description().equals("Снова доступен")), "archive records unavailable and recovered transitions");
            check(changes.stream().anyMatch(c -> c.serviceName.equals("Чат\tтест")), "archive safely preserves Unicode and control characters");
            check(ScanArchive.resolveLog(directory, scans.get(0).logFile).equals(logs.resolve("two.json").toAbsolutePath().normalize()),
                "archive resolves an internal log for export");
            boolean escaped = false;
            try { ScanArchive.resolveLog(directory, "../outside.json"); } catch (IOException expected) { escaped = true; }
            check(escaped, "archive rejects paths outside private storage");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
            }
        }
    }

    private static void detector404Parsing() {
        NetworkCheckResult alerts = Detector404Client.parse("{\"success\":true,\"data\":[{\"type\":\"complaints\",\"service\":\"Telegram\"},{\"type\":\"url\",\"service\":\"Wikipedia\"}]}");
        check(alerts.status == NetworkCheckResult.Status.WARNING && "2".equals(alerts.metrics.get("activeAlerts"))
            && alerts.metrics.get("services").contains("Telegram"), "Detector404 alerts are summarized without exposing its token");
        check(Detector404Client.parse("{\"success\":true,\"data\":[]}").status == NetworkCheckResult.Status.OK,
            "Detector404 empty alert list stays informational");
    }

    private static void proxyConfiguration() {
        NetworkCheckResult empty = TelegramProxyCheck.test("", 100);
        check("false".equals(empty.metrics.get("configured")), "Telegram proxy is opt-in");
        NetworkCheckResult malformed = TelegramProxyCheck.test("file:///not-a-proxy", 100);
        check(malformed.status == NetworkCheckResult.Status.WARNING && !malformed.metrics.containsKey("proxyHost"),
            "invalid Telegram proxy configuration fails without exposing credentials");
        List<NetworkCheckResult> proxies = TelegramProxyCheck.testAll(List.of(
            new NamedConfiguration("Первый", "file:///secret"), new NamedConfiguration("Пустой", "")), 100);
        check(proxies.size() == 2 && "telegram_proxy_1".equals(proxies.get(0).id)
            && "0".equals(proxies.get(1).metrics.get("working")), "multiple Telegram proxies keep individual and aggregate results");
        check(proxies.stream().noneMatch(value -> value.metrics.toString().contains("file:///secret")),
            "multiple proxy diagnostics do not expose configuration values");
        List<NetworkCheckResult> vpns = VpnEndpointCheck.testAll(List.of(
            new NamedConfiguration("Резервный VPN", "file:///private-key")), 100, false);
        check(vpns.size() == 2 && "0".equals(vpns.get(1).metrics.get("reachable"))
            && "false".equals(vpns.get(1).metrics.get("privateKeysAccepted")), "VPN profiles report endpoint failures without accepting keys");
        NamedConfiguration preview = new NamedConfiguration("Личный", "socks5://user:password@example.org:1080?secret=value");
        check("socks5://example.org:1080".equals(preview.safePreview()), "saved connection list hides credentials and secrets");
        check(DefaultConnections.telegramProxies().size() >= 6 && DefaultConnections.vpnSubscriptions().size() >= 4,
            "public default proxy and VPN catalogs are bundled");
        NetworkCheckResult telegramLink = TelegramProxyCheck.test(
            "https://t.me/proxy?server=127.0.0.1&port=9&secret=public", 50);
        check(!"IllegalArgumentException".equals(telegramLink.metrics.get("error")),
            "Telegram https proxy links are accepted");
        List<NetworkCheckResult> massVpn = List.of(
            new NetworkCheckResult("vpn_profile_1", "A", "VPN", NetworkCheckResult.Status.OK, "", Map.of()),
            new NetworkCheckResult("vpn_profile_2", "B", "VPN", NetworkCheckResult.Status.WARNING, "", Map.of()),
            new NetworkCheckResult("vpn_profile_3", "C", "VPN", NetworkCheckResult.Status.WARNING, "", Map.of()));
        check(BypassAnalytics.massVpnUnavailable(massVpn), "mass VPN endpoint failure signal is detected");
        ProbeResult discord = new ProbeResult(new ServiceTarget("discord", "Discord", TargetCatalog.CHATS, "discord.com", "/", "GLOBAL"),
            ProbeResult.Status.AVAILABLE, 1, 1, 1, 1, 200, "192.0.2.1", null);
        ProbeResult youtube = new ProbeResult(new ServiceTarget("youtube", "YouTube", TargetCatalog.MEDIA, "youtube.com", "/", "GLOBAL"),
            ProbeResult.Status.UNAVAILABLE, -1, 1, 1, -1, -1, "192.0.2.2", "timeout");
        NetworkCheckResult zapret = ZapretCheck.evaluate(true, "winws.exe", List.of(discord, youtube));
        check(zapret.status == NetworkCheckResult.Status.WARNING && "1".equals(zapret.metrics.get("workingTargets")),
            "Zapret check distinguishes partial target reachability");
        List<String> bypass = BypassAnalytics.enrichRecommendations(ScanReport.Level.DEGRADED, List.of(), List.of(zapret));
        check(bypass.stream().anyMatch(value -> value.contains("GoodbyeDPI"))
            && BypassAnalytics.summaryText(List.of(zapret)).contains("Zapret"), "restriction report includes bypass methods and their results");
    }

    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        System.out.println("PASS: " + name);
    }
}
