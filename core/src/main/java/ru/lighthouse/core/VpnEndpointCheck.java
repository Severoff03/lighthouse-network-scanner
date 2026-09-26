package ru.lighthouse.core;

import javax.net.ssl.HttpsURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Checks VPN server reachability without importing private keys or changing the system route. */
public final class VpnEndpointCheck {
    private VpnEndpointCheck() { }

    public static List<NetworkCheckResult> testAll(List<NamedConfiguration> configurations, int timeoutMs, boolean systemVpnActive) {
        List<NetworkCheckResult> values = new ArrayList<>();
        int reachable = 0, configured = 0;
        if (configurations != null) for (int i = 0; i < Math.min(12, configurations.size()); i++) {
            NamedConfiguration item = configurations.get(i);
            if (item == null || item.value.isBlank()) continue;
            configured++;
            NetworkCheckResult check = test(item.name.isBlank() ? "VPN " + configured : item.name,
                "vpn_profile_" + configured, item.value, timeoutMs);
            values.add(check);
            if (check.status == NetworkCheckResult.Status.OK) reachable++;
        }
        boolean massUnavailable = configured >= 3 && (configured - reachable) * 2 >= configured;
        NetworkCheckResult.Status status = configured == 0 || (reachable > 0 && !massUnavailable)
            ? NetworkCheckResult.Status.OK : NetworkCheckResult.Status.WARNING;
        String summary = configured == 0 ? "VPN-адреса для проверки не добавлены."
            : (massUnavailable ? "Большинство VPN-адресов не подтвердили доступность. " : "")
                + reachable + " из " + configured + " адресов ответили по HTTPS или TCP. "
                + (systemVpnActive ? "Системный VPN активен; результаты сервисов относятся к текущему туннелю."
                    : "Системный VPN не обнаружен; ответ адреса не подтверждает работу туннеля или ключа.");
        values.add(new NetworkCheckResult("vpn_profiles_summary", "Итог проверки VPN", "VPN", status, summary,
            map("configured", String.valueOf(configured), "reachable", String.valueOf(reachable),
                "systemVpnActive", String.valueOf(systemVpnActive), "privateKeysAccepted", "false")));
        return values;
    }

    private static NetworkCheckResult test(String name, String id, String configuration, int timeoutMs) {
        long started = System.nanoTime();
        try {
            URI uri = normalized(configuration);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isBlank() || host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Invalid VPN endpoint");
            if ("https".equals(scheme)) {
                HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
                connection.setConnectTimeout(timeoutMs); connection.setReadTimeout(timeoutMs);
                connection.setInstanceFollowRedirects(true); connection.setRequestMethod("GET");
                connection.setRequestProperty("Range", "bytes=0-0"); connection.setRequestProperty("User-Agent", "Lighthouse/diagnostic");
                int code = connection.getResponseCode(); connection.disconnect();
                boolean success = code >= 200 && code < 300;
                return result(id, name, success ? NetworkCheckResult.Status.OK : NetworkCheckResult.Status.WARNING,
                    success ? "HTTPS-адрес ответил успешно. Это не подтверждает работу VPN-туннеля."
                        : "HTTPS-адрес ответил HTTP " + code + "; доступность ресурса не подтверждена.",
                    metrics(scheme, host, port < 0 ? 443 : port, elapsed(started),
                        success ? "https_endpoint" : "https_response", String.valueOf(code)));
            }
            if ("tcp".equals(scheme) || "openvpn".equals(scheme) || "wireguard".equals(scheme)) {
                if (port < 1 || port > 65535) throw new IllegalArgumentException("Port required");
                if ("wireguard".equals(scheme) || ("openvpn".equals(scheme) && !tcpTransport(uri))) {
                    java.net.InetAddress.getAllByName(host);
                    return result(id, name, NetworkCheckResult.Status.WARNING,
                        "Адрес VPN разрешается через DNS, но UDP-туннель и ключ нельзя подтвердить без подключения.",
                        metrics(scheme, host, port, elapsed(started), "dns_only", "—"));
                }
                try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(host, port), timeoutMs); }
                return result(id, name, NetworkCheckResult.Status.OK,
                    "VPN-сервер доступен по TCP. Работа туннеля и ключа проверяется после включения VPN в системе.",
                    metrics(scheme, host, port, elapsed(started), "tcp_endpoint", "—"));
            }
            throw new IllegalArgumentException("Unsupported endpoint scheme");
        } catch (Exception error) {
            return result(id, name, NetworkCheckResult.Status.WARNING,
                "VPN-сервер не прошёл проверку: " + error.getClass().getSimpleName() + ".",
                map("configured", "true", "error", error.getClass().getSimpleName(), "secretsLogged", "false"));
        }
    }

    private static URI normalized(String value) {
        URI uri = URI.create(value.trim());
        if (uri.getScheme() == null) throw new IllegalArgumentException("Endpoint scheme required");
        return uri;
    }
    private static boolean tcpTransport(URI uri) {
        String query = uri.getRawQuery();
        return query != null && query.toLowerCase(Locale.ROOT).contains("transport=tcp");
    }
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000L; }
    private static Map<String, String> metrics(String scheme, String host, int port, long duration, String coverage, String http) {
        return map("configured", "true", "scheme", scheme, "host", host, "port", String.valueOf(port),
            "durationMs", String.valueOf(duration), "coverage", coverage, "httpCode", http, "secretsLogged", "false");
    }
    private static NetworkCheckResult result(String id, String name, NetworkCheckResult.Status status, String summary, Map<String, String> metrics) {
        return new NetworkCheckResult(id, name, "VPN", status, summary, metrics);
    }
    private static Map<String, String> map(String... pairs) {
        Map<String, String> value = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) value.put(pairs[i], pairs[i + 1]);
        return value;
    }
}
