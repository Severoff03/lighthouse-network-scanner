package ru.lighthouse.core;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tests a user-configured Telegram SOCKS5, HTTP CONNECT or MTProto proxy without logging secrets. */
public final class TelegramProxyCheck {
    private static final String TARGET = "api.telegram.org";
    private TelegramProxyCheck() { }

    public static NetworkCheckResult test(String configuration, int timeoutMs) {
        return test("Прокси Telegram", "telegram_proxy", configuration, timeoutMs);
    }

    public static List<NetworkCheckResult> testAll(List<NamedConfiguration> configurations, int timeoutMs) {
        List<NetworkCheckResult> values = new ArrayList<>();
        int working = 0, configured = 0;
        if (configurations != null) for (int i = 0; i < Math.min(12, configurations.size()); i++) {
            NamedConfiguration item = configurations.get(i);
            if (item == null || item.value.isBlank()) continue;
            configured++;
            String name = item.name.isBlank() ? "Прокси Telegram " + configured : item.name;
            NetworkCheckResult check = test(name, "telegram_proxy_" + configured, item.value, timeoutMs);
            values.add(check);
            if (check.status == NetworkCheckResult.Status.OK) working++;
        }
        NetworkCheckResult.Status status = configured == 0 || working > 0
            ? NetworkCheckResult.Status.OK : NetworkCheckResult.Status.WARNING;
        String summary = configured == 0
            ? "Прокси Telegram не настроены."
            : working > 0 ? "Работает хотя бы один прокси Telegram: " + working + " из " + configured + "."
            : "Ни один из настроенных прокси Telegram не прошёл проверку.";
        values.add(new NetworkCheckResult("telegram_proxy_summary", "Итог проверки прокси Telegram",
            "Чаты и мессенджеры", status, summary, map("configured", String.valueOf(configured),
                "working", String.valueOf(working), "secretsLogged", "false")));
        return values;
    }

    private static NetworkCheckResult test(String name, String id, String configuration, int timeoutMs) {
        if (configuration == null || configuration.isBlank()) return result(id, name, NetworkCheckResult.Status.OK,
            "Прокси Telegram не настроен. Укажите ссылку socks5://, http:// или tg://proxy в настройках.", map("configured", "false"));
        long started = System.nanoTime();
        try {
            ProxyConfig proxy = ProxyConfig.parse(configuration.trim());
            if (proxy.mtproto) {
                try (Socket socket = connect(proxy.host, proxy.port, timeoutMs)) { /* Endpoint reachability only. */ }
                return result(id, name, NetworkCheckResult.Status.OK,
                    "Узел MTProto-прокси доступен по TCP. Проверка секрета требует полного протокола Telegram и не выполняется.",
                    metrics(proxy, elapsed(started), "tcp_endpoint_only"));
            }
            try (Socket tunnel = proxy.socks ? socks(proxy, timeoutMs) : http(proxy, timeoutMs)) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(tunnel, TARGET, 443, true);
                SSLParameters parameters = tls.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters); tls.setSoTimeout(timeoutMs); tls.startHandshake(); tls.close();
            }
            return result(id, name, NetworkCheckResult.Status.OK, "Прокси создал защищённый туннель до Telegram API.",
                metrics(proxy, elapsed(started), "telegram_tls_ok"));
        } catch (Exception error) {
            Map<String, String> metrics = map("configured", "true", "error", error.getClass().getSimpleName());
            return result(id, name, NetworkCheckResult.Status.WARNING,
                "Прокси Telegram не прошёл проверку: " + error.getClass().getSimpleName() + ".", metrics);
        }
    }

    private static Socket socks(ProxyConfig proxy, int timeout) throws Exception {
        Socket socket = connect(proxy.host, proxy.port, timeout); socket.setSoTimeout(timeout);
        try {
            InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
            boolean auth = proxy.user != null;
            out.write(auth ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0}); out.flush();
            int version = in.read(), method = in.read();
            if (version != 5 || method < 0 || method == 0xff) throw new java.io.IOException("SOCKS5 authentication rejected");
            if (method == 2) {
                byte[] user = bytes(proxy.user), pass = bytes(proxy.password);
                if (user.length > 255 || pass.length > 255) throw new java.io.IOException("SOCKS credentials too long");
                out.write(1); out.write(user.length); out.write(user); out.write(pass.length); out.write(pass); out.flush();
                if (in.read() != 1 || in.read() != 0) throw new java.io.IOException("SOCKS credentials rejected");
            } else if (method != 0) throw new java.io.IOException("Unsupported SOCKS authentication");
            byte[] target = TARGET.getBytes(StandardCharsets.US_ASCII);
            out.write(new byte[]{5, 1, 0, 3, (byte) target.length}); out.write(target); out.write(443 >>> 8); out.write(443 & 255); out.flush();
            if (in.read() != 5 || in.read() != 0) throw new java.io.IOException("SOCKS tunnel rejected");
            in.read(); int addressType = in.read();
            int addressLength = addressType == 1 ? 4 : addressType == 4 ? 16 : addressType == 3 ? in.read() : -1;
            if (addressLength < 0) throw new java.io.IOException("Invalid SOCKS response");
            readFully(in, addressLength + 2); return socket;
        } catch (Exception error) { socket.close(); throw error; }
    }

    private static Socket http(ProxyConfig proxy, int timeout) throws Exception {
        Socket socket = connect(proxy.host, proxy.port, timeout); socket.setSoTimeout(timeout);
        try {
            StringBuilder request = new StringBuilder("CONNECT ").append(TARGET).append(":443 HTTP/1.1\r\nHost: ")
                .append(TARGET).append(":443\r\nProxy-Connection: Keep-Alive\r\n");
            if (proxy.user != null) request.append("Proxy-Authorization: Basic ")
                .append(Base64.getEncoder().encodeToString((proxy.user + ":" + proxy.password).getBytes(StandardCharsets.UTF_8))).append("\r\n");
            request.append("\r\n"); socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            InputStream input = socket.getInputStream(); StringBuilder header = new StringBuilder();
            while (header.length() < 16384) { int value = input.read(); if (value < 0) break; header.append((char) value);
                if (header.toString().endsWith("\r\n\r\n")) break; }
            String first = header.toString().split("\\r?\\n", 2)[0];
            if (!first.matches("HTTP/\\d(?:\\.\\d)? 200(?: .*)?")) throw new java.io.IOException("HTTP CONNECT rejected");
            return socket;
        } catch (Exception error) { socket.close(); throw error; }
    }

    private static Socket connect(String host, int port, int timeout) throws Exception {
        Socket socket = new Socket(); socket.connect(new InetSocketAddress(host, port), timeout); return socket;
    }
    private static void readFully(InputStream input, int count) throws Exception { for (int i = 0; i < count; i++) if (input.read() < 0) throw new java.io.IOException("Truncated proxy response"); }
    private static byte[] bytes(String value) { return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8); }
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000L; }
    private static Map<String, String> metrics(ProxyConfig proxy, long ms, String coverage) {
        return map("configured", "true", "type", proxy.type, "proxyHost", proxy.host, "proxyPort", String.valueOf(proxy.port),
            "target", TARGET + ":443", "durationMs", String.valueOf(ms), "coverage", coverage, "secretsLogged", "false");
    }
    private static NetworkCheckResult result(String id, String name, NetworkCheckResult.Status status, String summary, Map<String, String> metrics) {
        return new NetworkCheckResult(id, name, "Чаты и мессенджеры", status, summary, metrics);
    }
    private static Map<String, String> map(String... pairs) { Map<String, String> value = new LinkedHashMap<>(); for (int i = 0; i + 1 < pairs.length; i += 2) value.put(pairs[i], pairs[i + 1]); return value; }

    private static final class ProxyConfig {
        final String type, host, user, password; final int port; final boolean socks, mtproto;
        ProxyConfig(String type, String host, int port, String user, String password, boolean socks, boolean mtproto) {
            this.type = type; this.host = host; this.port = port; this.user = user; this.password = password; this.socks = socks; this.mtproto = mtproto;
        }
        static ProxyConfig parse(String value) throws Exception {
            URI uri = URI.create(value); String scheme = lower(uri.getScheme());
            if (("http".equals(scheme) || "https".equals(scheme)) && "t.me".equalsIgnoreCase(uri.getHost())
                && "/proxy".equalsIgnoreCase(uri.getPath())) {
                Map<String, String> query = query(uri.getRawQuery());
                return validated("MTProto", query.get("server"), integer(query.get("port")), null, "", false, true);
            }
            if ("tg".equals(scheme)) {
                Map<String, String> query = query(uri.getRawQuery()); boolean socks = "socks".equalsIgnoreCase(uri.getHost());
                boolean mtproto = "proxy".equalsIgnoreCase(uri.getHost());
                if (!socks && !mtproto) throw new IllegalArgumentException("Unsupported Telegram proxy link");
                return validated(socks ? "SOCKS5" : "MTProto", query.get("server"), integer(query.get("port")),
                    query.get("user"), query.get("pass"), socks, mtproto);
            }
            if (!"socks5".equals(scheme) && !"http".equals(scheme)) throw new IllegalArgumentException("Unsupported proxy scheme");
            String user = null, password = "";
            if (uri.getRawUserInfo() != null) { String[] p = uri.getRawUserInfo().split(":", 2); user = decode(p[0]); if (p.length > 1) password = decode(p[1]); }
            return validated("socks5".equals(scheme) ? "SOCKS5" : "HTTP CONNECT", uri.getHost(), uri.getPort(), user, password,
                "socks5".equals(scheme), false);
        }
        private static ProxyConfig validated(String type, String host, int port, String user, String password, boolean socks, boolean mtproto) {
            if (host == null || host.isBlank() || host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0 || port < 1 || port > 65535)
                throw new IllegalArgumentException("Invalid proxy address");
            return new ProxyConfig(type, host, port, user, password == null ? "" : password, socks, mtproto);
        }
        private static Map<String, String> query(String value) { Map<String, String> result = new LinkedHashMap<>(); if (value != null) for (String part : value.split("&")) { String[] p = part.split("=", 2); result.put(decode(p[0]), p.length > 1 ? decode(p[1]) : ""); } return result; }
        private static int integer(String value) { try { return Integer.parseInt(value); } catch (Exception error) { return -1; } }
        private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
        private static String lower(String value) { return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT); }
    }
}
