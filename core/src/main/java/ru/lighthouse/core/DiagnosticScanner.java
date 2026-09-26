package ru.lighthouse.core;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiagnosticScanner {
    private final int timeoutMs;
    private final int concurrency;
    private final ScanProfile profile;

    public DiagnosticScanner(int timeoutMs, int concurrency) {
        this(timeoutMs, concurrency, ScanProfile.QUICK);
    }

    public DiagnosticScanner(int timeoutMs, int concurrency, ScanProfile profile) {
        if (timeoutMs <= 0 || timeoutMs > 30000 || concurrency <= 0 || concurrency > 8)
            throw new IllegalArgumentException("timeoutMs must be 1..30000; concurrency must be 1..8");
        this.timeoutMs = timeoutMs;
        this.concurrency = concurrency;
        this.profile = profile;
    }

    public ScanReport scan(DeviceInfo device, List<ServiceTarget> targets,
                           Map<String, ProbeResult.Status> previous,
                           Consumer<ProbeResult> progress) {
        return scan(device, targets, previous, progress, null);
    }

    public ScanReport scan(DeviceInfo device, List<ServiceTarget> targets,
                           Map<String, ProbeResult.Status> previous,
                           Consumer<ProbeResult> progress, Consumer<String> stage) {
        return scan(device, targets, previous, Collections.emptyMap(), progress, stage);
    }

    public ScanReport scan(DeviceInfo device, List<ServiceTarget> targets,
                           Map<String, ProbeResult.Status> previous, Map<String, ProbeResult.Status> baseline,
                           Consumer<ProbeResult> progress, Consumer<String> stage) {
        Instant started = Instant.now();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(profile.budgetSeconds);
        long extraDeadline = profile == ScanProfile.DEEP ? deadline : System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        List<NetworkCheckResult> partialChecks = Collections.synchronizedList(new ArrayList<>());
        partialChecks.addAll(device.observations);
        Future<?> environmentFuture = null;
        Future<String[]> originFuture = null;
        List<ProbeResult> results;
        List<NetworkCheckResult> networkChecks;
        String publicIp = "unavailable", origin = "unavailable";
        try {
            try {
                environmentFuture = NetworkDeadline.EXTRA.submit(() -> {
                    new NetworkEnvironmentChecks(timeoutMs).run(partialChecks::add);
                    if (profile == ScanProfile.DEEP) new DeepNetworkChecks(timeoutMs, deadline).run(partialChecks::add);
                });
                originFuture = NetworkDeadline.EXTRA.submit(() -> {
                    String ip = lookupPublicIp();
                    return new String[]{ip, lookupOrigin(ip)};
                });
            } catch (RejectedExecutionException busy) {
                partialChecks.add(incompleteChecks("Предыдущие дополнительные проверки ещё завершаются."));
            }
            Map<String, ProbeResult> combined = new java.util.LinkedHashMap<>();
            for (int pass = 1; pass <= profile.passes && System.nanoTime() < deadline; pass++) {
                if (stage != null) stage.accept("Сервисы: проход " + pass + " из " + profile.passes);
                List<Callable<ProbeResult>> tasks = new ArrayList<>();
                for (ServiceTarget target : targets) tasks.add(new ProbeTask(target, pass));
                ProbeBatch.run(tasks, concurrency, timeoutMs * 5L + 2000, deadline, sample -> {
                    ProbeResult result = ProbeResult.combine(combined.get(sample.target.id), sample);
                    combined.put(sample.target.id, result);
                    if (progress != null) progress.accept(result);
                });
            }
            results = new ArrayList<>(combined.values());
            results.sort((a, b) -> a.target.name.compareToIgnoreCase(b.target.name));
            if (stage != null) stage.accept("Дополнительная диагностика сети");
            if (environmentFuture != null) {
                try { environmentFuture.get(Math.max(1, extraDeadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException interrupted) { throw interrupted; }
                catch (Exception failed) { partialChecks.add(incompleteChecks("Часть дополнительных проверок не завершилась вовремя; результаты сервисов сохранены.")); }
            }
            if (originFuture != null) {
                try {
                    String[] location = originFuture.get(Math.max(1, extraDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    publicIp = location[0]; origin = location[1];
                } catch (InterruptedException interrupted) { throw interrupted; }
                catch (Exception ignored) { /* Optional geolocation must not block the report. */ }
            }
            synchronized (partialChecks) { networkChecks = new ArrayList<>(partialChecks); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Scan cancelled");
        } finally {
            if (environmentFuture != null) environmentFuture.cancel(true);
            if (originFuture != null) originFuture.cancel(true);
        }

        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Scan cancelled");
        if (stage != null) stage.accept("Подготовка отчёта");
        boolean complete = results.size() == targets.size();
        for (ProbeResult result : results) complete &= result.attempts() == profile.passes;

        List<String> newlyUnavailable = new ArrayList<>();
        List<String> recovered = new ArrayList<>();
        for (ProbeResult r : results) {
            ProbeResult.Status old = previous.get(r.target.id);
            ProbeResult.Status current = NetworkAssessment.observedStatus(r);
            if (old == ProbeResult.Status.AVAILABLE && current == ProbeResult.Status.UNAVAILABLE)
                newlyUnavailable.add(r.target.name);
            if (old == ProbeResult.Status.UNAVAILABLE && current != ProbeResult.Status.UNAVAILABLE)
                recovered.add(r.target.name);
        }

        NetworkAssessment assessment = NetworkAssessment.assess(results, baseline, targets.size(), complete,
            NetworkAssessment.hasExternalResponse(networkChecks, publicIp));
        ScanReport.Level level = assessment.level;
        List<String> recommendations = recommendations(level, results);
        if (!complete) recommendations.add(0, "Время скана истекло: сохранены выполненные замеры. Непроверенные сервисы не считаются заблокированными.");
        return new ScanReport(UUID.randomUUID().toString(), started, Instant.now(), device,
            publicIp, origin, level, results, networkChecks, newlyUnavailable, recovered, recommendations,
            profile, targets.size(), complete, assessment);
    }

    private static NetworkCheckResult incompleteChecks(String summary) {
        return new NetworkCheckResult("diagnostics_incomplete", "Дополнительные проверки", "Диагностика",
            NetworkCheckResult.Status.WARNING, summary, Collections.emptyMap());
    }

    private final class ProbeTask implements Callable<ProbeResult> {
        private final ServiceTarget target;
        private final int pass;
        ProbeTask(ServiceTarget target, int pass) { this.target = target; this.pass = pass; }

        @Override public ProbeResult call() {
            long dnsMs = -1, pingMs = -1, tcpMs = -1, httpsMs = -1;
            int code = -1;
            String ip = null;
            String error = null;
            try {
                long started = System.nanoTime();
                InetAddress address = selectAddress(NetworkDeadline.resolve(target.host, timeoutMs), pass);
                dnsMs = elapsed(started);
                ip = address.getHostAddress();

                started = System.nanoTime();
                try { if (address.isReachable(Math.min(timeoutMs, 900))) pingMs = elapsed(started); }
                catch (java.io.IOException ignored) { /* Optional reachability cannot abort service probes. */ }

                if (target.probeKind == ServiceTarget.ProbeKind.DNS) {
                    String evidence;
                    try { tcpMs = queryDns(address); evidence = "DNS UDP/53: example.com A"; }
                    catch (Exception udp) {
                        try { tcpMs = queryDnsTcp(address); evidence = "DNS TCP/53: ответ; UDP/53: " + udp.getClass().getSimpleName(); }
                        catch (Exception tcp) { throw new java.io.IOException("DNS UDP/53: " + safeMessage(udp.getMessage())
                            + "; TCP/53: " + safeMessage(tcp.getMessage()) + ". ICMP — отдельная проверка."); }
                    }
                    return new ProbeResult(target, ProbeResult.Status.AVAILABLE, dnsMs, pingMs,
                        tcpMs, -1, -1, ip, null).withDetail(evidence);
                }

                try (Socket socket = new Socket()) {
                    started = System.nanoTime();
                    socket.connect(new InetSocketAddress(address, target.port), timeoutMs);
                    tcpMs = elapsed(started);
                    if (target.probeKind == ServiceTarget.ProbeKind.TCP)
                        return new ProbeResult(target, ProbeResult.Status.AVAILABLE, dnsMs, pingMs, tcpMs, -1, -1, ip, null);

                    socket.setSoTimeout(timeoutMs);
                    started = System.nanoTime();
                    try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, target.host, target.port, true)) {
                        SSLParameters parameters = tls.getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        tls.setSSLParameters(parameters); tls.setSoTimeout(timeoutMs); tls.startHandshake();
                        code = requestStatus(tls, target);
                    }
                    httpsMs = elapsed(started);
                }
                ProbeResult.Status status = code >= 200 && code < 400
                    ? ProbeResult.Status.AVAILABLE : ProbeResult.Status.DEGRADED;
                return new ProbeResult(target, status, dnsMs, pingMs, tcpMs, httpsMs, code, ip, null);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + safeMessage(e.getMessage());
                ProbeResult.Status status = target.probeKind == ServiceTarget.ProbeKind.HTTPS
                    ? ProbeResult.Status.UNAVAILABLE
                    : tcpMs >= 0 || pingMs >= 0 ? ProbeResult.Status.DEGRADED : ProbeResult.Status.UNAVAILABLE;
                return new ProbeResult(target, status, dnsMs, pingMs, tcpMs, httpsMs, code, ip, error);
            }
        }
    }

    /** Alternate address families across deep passes so IPv6-only failure is not confused with total failure. */
    private static InetAddress selectAddress(InetAddress[] addresses, int pass) throws java.net.UnknownHostException {
        if (addresses == null || addresses.length == 0) throw new java.net.UnknownHostException("No addresses returned");
        boolean preferV6 = pass % 2 == 0;
        for (InetAddress address : addresses)
            if (preferV6 == (address instanceof java.net.Inet6Address)) return address;
        return addresses[(pass - 1) % addresses.length];
    }

    /** Small browser-like request through the exact resolved address, preserving SNI and certificate checks. */
    private static int requestStatus(SSLSocket socket, ServiceTarget target) throws Exception {
        String path = target.path == null || target.path.isEmpty() ? "/" : target.path;
        String request = "GET " + path + " HTTP/1.1\r\nHost: " + target.host
            + "\r\nUser-Agent: Lighthouse network-diagnostic\r\nAccept: */*\r\nRange: bytes=0-0\r\nConnection: close\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.US_ASCII)); output.flush();
        InputStream input = socket.getInputStream(); StringBuilder line = new StringBuilder();
        while (line.length() < 512) {
            int value = input.read();
            if (value < 0 || value == '\n') break;
            if (value != '\r') line.append((char) value);
        }
        Matcher match = Pattern.compile("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})(?:\\s|$)").matcher(line);
        if (!match.find()) throw new java.io.IOException("Invalid HTTPS status line");
        return Integer.parseInt(match.group(1));
    }

    private long queryDns(InetAddress server) throws Exception {
        int id = (int) (System.nanoTime() & 0xffff);
        byte[] query = dnsQuery(id, "example.com");
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            long started = System.nanoTime();
            socket.connect(server, 53);
            socket.send(new DatagramPacket(query, query.length));
            byte[] response = new byte[1024];
            DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            validateDnsAnswer(java.util.Arrays.copyOf(response, packet.getLength()), id, query);
            return elapsed(started);
        }
    }

    private long queryDnsTcp(InetAddress server) throws Exception {
        int id = (int) (System.nanoTime() & 65535);
        byte[] query = dnsQuery(id, "example.com");
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(server, 53), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
            out.writeShort(query.length); out.write(query); out.flush();
            java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());
            int length = in.readUnsignedShort();
            if (length < 12) throw new java.io.IOException("Invalid DNS length");
            byte[] bytes = new byte[length]; in.readFully(bytes);
            validateDnsAnswer(bytes, id, query);
            return elapsed(started);
        }
    }

    static void validateDnsAnswer(byte[] bytes, int id, byte[] query) throws java.io.IOException {
        String answer = DnsWire.describe(bytes, id);
        if (bytes.length < query.length || bytes[4] != 0 || bytes[5] != 1
            || !java.util.Arrays.equals(java.util.Arrays.copyOfRange(query, 12, query.length),
                java.util.Arrays.copyOfRange(bytes, 12, query.length))
            || !answer.startsWith("rcode=0;") || answer.contains("truncated=true")
            || answer.endsWith("addresses=")) throw new java.io.IOException("Invalid DNS answer: " + answer);
    }

    private static byte[] dnsQuery(int id, String host) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64);
        out.write((id >>> 8) & 0xff); out.write(id & 0xff);
        out.write(0x01); out.write(0x00);
        out.write(0x00); out.write(0x01);
        out.write(new byte[6]);
        for (String label : host.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length); out.write(bytes);
        }
        out.write(0); out.write(0); out.write(1); out.write(0); out.write(1);
        return out.toByteArray();
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000L; }
    private static String safeMessage(String message) {
        if (message == null) return "unknown";
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private static List<String> recommendations(ScanReport.Level level, List<ProbeResult> results) {
        List<String> r = new ArrayList<>();
        if (level == ScanReport.Level.NO_CONNECTION) {
            r.add("Проверьте Wi-Fi/мобильную сеть, перезапустите соединение и попробуйте другого оператора.");
            r.add("Для экстренной связи используйте обычный звонок или SMS.");
            return r;
        }
        if (level == ScanReport.Level.ALLOWLIST_SUSPECTED)
            r.add("Похоже на режим белых списков: сохраните лог и сравните результат через другую сеть.");
        if (level == ScanReport.Level.SEVERE_RESTRICTIONS || level == ScanReport.Level.DEGRADED)
            r.add("Повторите тест без VPN и с VPN, затем сравните два лога.");
        List<String> messengers = available(results, TargetCatalog.CHATS);
        List<String> files = available(results, TargetCatalog.FILES);
        List<String> dns = available(results, TargetCatalog.DNS);
        if (!messengers.isEmpty()) r.add("Ответили проверочные узлы мессенджеров: " + String.join(", ", messengers) + ". Отправка сообщений не проверялась.");
        if (!files.isEmpty()) r.add("Ответили веб-узлы файлообменников: " + String.join(", ", files) + ". Загрузка файлов не проверялась.");
        if (!dns.isEmpty()) r.add("Отвечающие DNS-серверы: " + String.join(", ", dns) + ".");
        else r.add("Публичные DNS-серверы не отвечают: возможна фильтрация UDP/TCP 53 или проблема сети.");
        r.add("Проверяйте законность средств обхода в вашей юрисдикции; используйте только доверенные приложения.");
        return r;
    }

    private static List<String> available(List<ProbeResult> results, String category) {
        List<String> names = new ArrayList<>();
        for (ProbeResult p : results)
            if (category.equals(p.target.category) && NetworkAssessment.observedStatus(p) == ProbeResult.Status.AVAILABLE) names.add(p.target.name);
        return names;
    }

    private String lookupPublicIp() {
        String[] urls = {"https://api.ipify.org", "https://ifconfig.me/ip"};
        for (String value : urls) {
            try {
                String body = get(value, 96).trim();
                if (body.matches("[0-9a-fA-F:.]+")) return body;
            } catch (Exception ignored) { }
        }
        return "unavailable";
    }

    private String lookupOrigin(String ip) {
        if ("unavailable".equals(ip)) return "unavailable";
        try {
            String body = get("https://ipwho.is/" + ip + "?fields=success,country,region,city,connection", 4096);
            String country = field(body, "country");
            String region = field(body, "region");
            String city = field(body, "city");
            String isp = field(body, "isp");
            String value = String.join(", ", nonEmpty(country, region, city, isp));
            return value.isEmpty() ? "unavailable" : value;
        } catch (Exception ignored) { return "unavailable"; }
    }

    private String get(String url, int maxBytes) throws Exception {
        return NetworkDeadline.http(() -> getResponse(url, maxBytes), timeoutMs * 2);
    }

    private String getResponse(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "Lighthouse/0.1 network-diagnostic");
        try (InputStream in = c.getInputStream()) {
            byte[] data = new byte[maxBytes];
            int n = in.read(data);
            return n < 0 ? "" : new String(data, 0, n, StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static List<String> nonEmpty(String... values) {
        List<String> out = new ArrayList<>();
        // String.isBlank is absent before Android 13 (API 33).
        for (String v : values) if (v != null && !v.trim().isEmpty()) out.add(v);
        return out;
    }
}
