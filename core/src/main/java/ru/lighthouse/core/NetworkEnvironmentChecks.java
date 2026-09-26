package ru.lighthouse.core;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

final class NetworkEnvironmentChecks {
    private final int timeoutMs;

    NetworkEnvironmentChecks(int timeoutMs) { this.timeoutMs = Math.min(timeoutMs, 2500); }

    List<NetworkCheckResult> run(Consumer<NetworkCheckResult> progress) {
        List<NetworkCheckResult> results = new ArrayList<>();
        Consumer<NetworkCheckResult> add = result -> {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            results.add(result);
            if (progress != null) progress.accept(result);
        };
        add.accept(dnsInterception());
        add.accept(ipv6());
        add.accept(tcpSeries("tcp_ozon", "Стабильность Ozon", "ozon.ru", 443));
        add.accept(tcpSeries("tcp_google", "Стабильность Google", "www.google.com", 443));
        add.accept(tcpSeries("tcp_cloudflare", "Стабильность Cloudflare", "1.1.1.1", 443));
        add.accept(ntp());
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            add.accept(trace("trace_ozon", "Маршрут до Ozon", "ozon.ru"));
            add.accept(trace("trace_google", "Маршрут до Google", "www.google.com"));
            add.accept(trace("trace_cloudflare", "Маршрут до Cloudflare DNS", "1.1.1.1"));
        }
        return results;
    }

    private NetworkCheckResult dnsInterception() {
        String randomInvalid = "lighthouse-" + UUID.randomUUID().toString().replace("-", "") + ".invalid";
        try {
            InetAddress address = NetworkDeadline.resolve(randomInvalid, timeoutMs)[0];
            return result("dns_interception", "Проверка подмены DNS", "DNS", NetworkCheckResult.Status.WARNING,
                "Несуществующий адрес неожиданно разрешился. Возможна подмена DNS-ответов.",
                "resolvedIp", address.getHostAddress());
        } catch (java.net.UnknownHostException expected) {
            return result("dns_interception", "Проверка подмены DNS", "DNS", NetworkCheckResult.Status.OK,
                "Признаков подмены ответа для несуществующего домена нет.");
        } catch (Exception error) {
            return result("dns_interception", "Проверка подмены DNS", "DNS", NetworkCheckResult.Status.WARNING,
                "Проверку DNS не удалось завершить.", "error", error.getClass().getSimpleName());
        }
    }

    private NetworkCheckResult ipv6() {
        try {
            InetAddress selected = null;
            for (InetAddress address : NetworkDeadline.resolve("www.google.com", timeoutMs))
                if (address instanceof Inet6Address) { selected = address; break; }
            if (selected == null)
                return result("ipv6", "Подключение IPv6", "Маршрутизация", NetworkCheckResult.Status.WARNING,
                    "IPv6-адрес получен не был. Сеть работает только через IPv4 или DNS не выдаёт AAAA.");
            long started = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(selected, 443), timeoutMs);
            }
            long ms = elapsed(started);
            return result("ipv6", "Подключение IPv6", "Маршрутизация", NetworkCheckResult.Status.OK,
                "IPv6 работает, подключение установлено за " + ms + " мс.",
                "latencyMs", String.valueOf(ms), "address", selected.getHostAddress());
        } catch (Exception error) {
            return result("ipv6", "Подключение IPv6", "Маршрутизация", NetworkCheckResult.Status.WARNING,
                "IPv6-адрес есть, но соединение через него не установлено.",
                "error", error.getClass().getSimpleName());
        }
    }

    private NetworkCheckResult tcpSeries(String id, String name, String host, int port) {
        List<Long> samples = new ArrayList<>();
        int attempts = 4;
        for (int i = 0; i < attempts; i++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            try {
                InetAddress address = NetworkDeadline.resolve(host, timeoutMs)[0];
                long started = System.nanoTime();
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(address, port), Math.min(timeoutMs, 1500));
                }
                samples.add(elapsed(started));
            } catch (Exception ignored) { }
        }
        int lost = attempts - samples.size();
        if (samples.isEmpty())
            return result(id, name, "Стабильность", NetworkCheckResult.Status.FAILED,
                "Все " + attempts + " попытки подключения завершились без ответа.",
                "attempts", String.valueOf(attempts), "lost", String.valueOf(lost));
        long min = Collections.min(samples), max = Collections.max(samples), sum = 0;
        for (long sample : samples) sum += sample;
        long average = sum / samples.size();
        long jitter = jitter(samples);
        NetworkCheckResult.Status status = lost == 0 && jitter < 80
            ? NetworkCheckResult.Status.OK : NetworkCheckResult.Status.WARNING;
        String summary = lost == 0
            ? "Все попытки успешны. Средняя задержка " + average + " мс, разброс " + jitter + " мс."
            : "Потеряно попыток: " + lost + " из " + attempts + ". Средняя задержка " + average + " мс.";
        return result(id, name, "Стабильность", status, summary,
            "attempts", String.valueOf(attempts), "successful", String.valueOf(samples.size()),
            "lossPercent", String.valueOf(lost * 100 / attempts), "minMs", String.valueOf(min),
            "avgMs", String.valueOf(average), "maxMs", String.valueOf(max), "jitterMs", String.valueOf(jitter));
    }

    private NetworkCheckResult ntp() {
        try {
            byte[] request = new byte[48]; request[0] = 0x1b;
            InetAddress server = NetworkDeadline.resolve("time.google.com", timeoutMs)[0];
            try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
                socket.setSoTimeout(timeoutMs);
                long sentAt = System.currentTimeMillis();
                socket.send(new java.net.DatagramPacket(request, request.length, server, 123));
                byte[] response = new byte[48];
                socket.receive(new java.net.DatagramPacket(response, response.length));
                long receivedAt = System.currentTimeMillis();
                long seconds = ((response[40] & 0xffL) << 24) | ((response[41] & 0xffL) << 16)
                    | ((response[42] & 0xffL) << 8) | (response[43] & 0xffL);
                long fraction = ((response[44] & 0xffL) << 24) | ((response[45] & 0xffL) << 16)
                    | ((response[46] & 0xffL) << 8) | (response[47] & 0xffL);
                long serverMillis = (seconds - 2_208_988_800L) * 1000L + ((fraction * 1000L) >>> 32);
                long offset = serverMillis - ((sentAt + receivedAt) / 2L);
                NetworkCheckResult.Status status = Math.abs(offset) <= 5000
                    ? NetworkCheckResult.Status.OK : NetworkCheckResult.Status.WARNING;
                return result("ntp", "Точность системного времени", "Время", status,
                    "Расхождение с сетевым временем: " + offset + " мс.",
                    "offsetMs", String.valueOf(offset), "roundTripMs", String.valueOf(receivedAt - sentAt));
            }
        } catch (Exception error) {
            return result("ntp", "Точность системного времени", "Время", NetworkCheckResult.Status.WARNING,
                "Сервер точного времени не ответил. Это не мешает обычной работе интернета.",
                "error", error.getClass().getSimpleName());
        }
    }

    private NetworkCheckResult trace(String id, String name, String host) {
        Process process = null;
        try {
            process = new ProcessBuilder("tracert", "-d", "-h", "12", "-w", "700", host)
                .redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            int hops = 0;
            boolean finished = process.waitFor(18, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return result(id, name, "Маршрут", NetworkCheckResult.Status.WARNING,
                    "Трассировка остановлена по тайм-ауту.");
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < 5000) {
                    output.append(line).append('\n');
                    if (line.matches("\\s*\\d+\\s+.*")) hops++;
                }
            }
            NetworkCheckResult.Status status = finished && hops > 0
                ? NetworkCheckResult.Status.OK : NetworkCheckResult.Status.WARNING;
            return result(id, name, "Маршрут", status,
                hops > 0 ? "Получен маршрут: " + hops + " сетевых переходов."
                    : "Маршрут не получен или промежуточные узлы не отвечают.",
                "hops", String.valueOf(hops), "trace", output.toString().trim());
        } catch (Exception error) {
            if (process != null) process.destroyForcibly();
            return result(id, name, "Маршрут", NetworkCheckResult.Status.WARNING,
                "Трассировку выполнить не удалось.", "error", error.getClass().getSimpleName());
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static long jitter(List<Long> samples) {
        if (samples.size() < 2) return 0;
        long total = 0;
        for (int i = 1; i < samples.size(); i++) total += Math.abs(samples.get(i) - samples.get(i - 1));
        return total / (samples.size() - 1);
    }

    private static NetworkCheckResult result(String id, String name, String category,
                                             NetworkCheckResult.Status status, String summary,
                                             String... metrics) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < metrics.length; i += 2) values.put(metrics[i], metrics[i + 1]);
        return new NetworkCheckResult(id, name, category, status, summary, values);
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000L; }
}
