package ru.lighthouse.core;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Small protocol probes only: no port sweep, large downloads or logins. */
final class DeepNetworkChecks {
    private final int timeout;
    private final long deadline;
    private Consumer<NetworkCheckResult> output;
    DeepNetworkChecks(int timeout, long deadline) { this.timeout = Math.min(timeout, 2500); this.deadline = deadline; }

    void run(Consumer<NetworkCheckResult> output) {
        this.output = output;
        check("interfaces", "Сетевые интерфейсы", "Интерфейсы", () -> {
            Map<String, String> metrics = new LinkedHashMap<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                metrics.put(nic.getName(), "up=" + nic.isUp() + "; loopback=" + nic.isLoopback()
                    + "; virtual=" + nic.isVirtual() + "; MTU=" + nic.getMTU()
                    + "; addresses=" + Collections.list(nic.getInetAddresses()));
            }
            return metrics.toString();
        });
        for (String host : Arrays.asList("ya.ru", "www.google.com", "ozon.ru", "www.cloudflare.com")) {
            for (boolean ipv6 : new boolean[]{false, true}) {
                check("address_" + host + "_" + ipv6, host + " / " + (ipv6 ? "IPv6" : "IPv4"), "IPv4 и IPv6", () -> {
                    List<String> successes = new ArrayList<>(), failures = new ArrayList<>();
                    int attempts = 0;
                    for (InetAddress address : NetworkDeadline.resolve(host, timeout)) {
                        if ((address instanceof Inet6Address) != ipv6 || attempts >= 3) continue;
                        attempts++; guard(); long start = System.nanoTime();
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress(address, 443), timeout);
                            successes.add(address.getHostAddress() + " " + elapsed(start) + " ms");
                        } catch (Exception error) { failures.add(address.getHostAddress() + " " + error.getClass().getSimpleName()); }
                    }
                    if (attempts == 0) throw new java.io.IOException("DNS не вернул адреса этой версии IP");
                    if (successes.isEmpty()) throw new java.io.IOException("Нет TCP-ответов: " + failures);
                    return "TCP/443; success=" + successes + "; failed=" + failures + "; это не проверка содержания сайта";
                });
            }
        }
        String[][] dns = {{"Cloudflare", "1.1.1.1"}, {"Google", "8.8.8.8"}, {"Яндекс", "77.88.8.8"},
            {"Quad9", "9.9.9.9"}, {"AdGuard", "94.140.14.14"}, {"OpenDNS", "208.67.222.222"}};
        for (String[] server : dns) for (int type : new int[]{1, 28}) for (boolean tcp : new boolean[]{false, true}) {
            check("dns_" + server[0] + type + tcp, server[0] + " " + (type == 1 ? "A" : "AAAA") + " / " + (tcp ? "TCP" : "UDP"),
                "DNS: UDP и TCP", () -> dns(server[1], type, tcp));
        }
        for (String host : Arrays.asList("dns.google", "cloudflare-dns.com", "dns.quad9.net")) {
            check("dot_" + host, "DNS-over-TLS / " + host, "Защищённый DNS", () -> {
                int id = (int) System.nanoTime(); byte[] query = DnsWire.query(id, "example.com", 1);
                try (SSLSocket socket = secureSocket(host, 853, null)) {
                    return DnsWire.describe(exchange(socket, query), id);
                }
            });
        }
        for (String host : Arrays.asList("dns.google", "cloudflare-dns.com")) {
            check("doh_" + host, "DNS-over-HTTPS / " + host, "Защищённый DNS", () -> NetworkDeadline.http(() -> {
                int id = (int) System.nanoTime(); byte[] query = DnsWire.query(id, "example.com", 1);
                HttpsURLConnection connection = (HttpsURLConnection) new URL("https://" + host + "/dns-query").openConnection();
                try {
                    connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
                    connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST"); connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/dns-message");
                    connection.setRequestProperty("Accept", "application/dns-message");
                    connection.setFixedLengthStreamingMode(query.length);
                    try (java.io.OutputStream stream = connection.getOutputStream()) { stream.write(query); }
                    if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
                    try (java.io.InputStream stream = connection.getInputStream(); java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream()) {
                        byte[] buffer = new byte[1024]; int count;
                        while (data.size() < 4096 && (count = stream.read(buffer, 0, Math.min(buffer.length, 4096 - data.size()))) > 0) data.write(buffer, 0, count);
                        return DnsWire.describe(data.toByteArray(), id);
                    }
                } finally { connection.disconnect(); }
            }, timeout * 3));
        }
        for (String address : Arrays.asList("1.1.1.1", "8.8.8.8", "77.88.8.8")) {
            check("icmp_series_" + address, "Серия ping / " + address, "Повторные замеры", () -> {
                InetAddress target = NetworkDeadline.resolve(address, timeout)[0];
                List<Long> samples = new ArrayList<>();
                for (int i = 0; i < 5; i++) { guard(); long start = System.nanoTime(); if (target.isReachable(800)) samples.add(elapsed(start)); }
                if (samples.isEmpty()) throw new java.io.IOException("Echo-ответы отсутствуют; ICMP может фильтроваться при рабочем интернете");
                return "received=" + samples.size() + "/5; minMs=" + Collections.min(samples) + "; maxMs=" + Collections.max(samples) + "; samplesMs=" + samples;
            });
        }
    }

    private String dns(String address, int type, boolean tcp) throws Exception {
        int id = (int) System.nanoTime(); byte[] query = DnsWire.query(id, "example.com", type);
        InetAddress server = NetworkDeadline.resolve(address, timeout)[0]; byte[] response;
        if (tcp) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(server, 53), timeout); socket.setSoTimeout(timeout);
                response = exchange(socket, query);
            }
        } else {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(server, 53); socket.setSoTimeout(timeout);
                socket.send(new DatagramPacket(query, query.length));
                byte[] bytes = new byte[4096]; DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                socket.receive(packet); response = Arrays.copyOf(bytes, packet.getLength());
            }
        }
        return DnsWire.describe(response, id);
    }

    private SSLSocket secureSocket(String host, int port, String protocol) throws Exception {
        Socket transport = new Socket();
        try {
            transport.connect(new InetSocketAddress(NetworkDeadline.resolve(host, timeout)[0], port), timeout);
            SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(transport, host, port, true);
            try {
                socket.setSoTimeout(timeout);
                if (protocol != null) socket.setEnabledProtocols(new String[]{protocol});
                SSLParameters parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(parameters);
                socket.startHandshake(); return socket;
            } catch (Exception error) { socket.close(); throw error; }
        } catch (Exception error) { transport.close(); throw error; }
    }

    private static byte[] exchange(Socket socket, byte[] query) throws Exception {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream()); out.writeShort(query.length); out.write(query); out.flush();
        DataInputStream in = new DataInputStream(socket.getInputStream()); int length = in.readUnsignedShort();
        if (length < 12 || length > 4096) throw new java.io.IOException("Invalid DNS frame length");
        byte[] response = new byte[length]; in.readFully(response); return response;
    }

    private void check(String id, String name, String category, Callable<String> action) {
        guard(); long start = System.nanoTime(); Map<String, String> metrics = new LinkedHashMap<>();
        NetworkCheckResult.Status status; String summary;
        try {
            String detail = action.call(); metrics.put("detail", detail); status = detail.contains("rcode=") && (!detail.contains("rcode=0;") || detail.contains("truncated=true"))
                ? NetworkCheckResult.Status.WARNING : NetworkCheckResult.Status.OK;
            summary = status == NetworkCheckResult.Status.OK ? "Ответ получен; подробности в замере." : "DNS ответил ошибкой или неполным сообщением; транспорт доступен.";
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (Exception | LinkageError error) {
            status = NetworkCheckResult.Status.WARNING; summary = "Проверка не дала успешного ответа; это не доказывает блокировку.";
            metrics.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        metrics.put("durationMs", String.valueOf(elapsed(start))); metrics.put("measuredAt", java.time.Instant.now().toString());
        output.accept(new NetworkCheckResult(id, name, category, status, summary, metrics));
    }
    private void guard() { if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new CancellationException("Deep scan budget exhausted"); }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
