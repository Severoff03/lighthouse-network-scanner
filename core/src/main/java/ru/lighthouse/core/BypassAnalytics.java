package ru.lighthouse.core;

import java.util.ArrayList;
import java.util.List;

/** Final-report presentation and recommendations for user-configured circumvention methods. */
public final class BypassAnalytics {
    private BypassAnalytics() { }

    public static List<NetworkCheckResult> results(List<NetworkCheckResult> checks) {
        List<NetworkCheckResult> values = new ArrayList<>();
        if (checks == null) return values;
        for (NetworkCheckResult check : checks) {
            if ("zapret_status".equals(check.id)
                || "telegram_proxy_summary".equals(check.id) || check.id.matches("telegram_proxy_\\d+")
                || "vpn_profiles_summary".equals(check.id) || check.id.matches("vpn_profile_\\d+")) values.add(check);
        }
        return values;
    }

    public static String summaryText(List<NetworkCheckResult> checks) {
        List<NetworkCheckResult> values = results(checks);
        if (values.isEmpty()) return "Настроенные методы обхода не проверялись.";
        StringBuilder text = new StringBuilder();
        for (NetworkCheckResult check : values) {
            if (text.length() > 0) text.append("\n\n");
            text.append(check.status == NetworkCheckResult.Status.OK ? "✓ " : "! ")
                .append(check.name).append(" — ").append(check.summary);
        }
        return text.toString();
    }

    /** A cautious signal: most configured endpoints failed, not proof of regulator blocking. */
    public static boolean massVpnUnavailable(List<NetworkCheckResult> checks) {
        int total = 0, failed = 0;
        if (checks != null) for (NetworkCheckResult check : checks) {
            if (!check.id.matches("vpn_profile_\\d+")) continue;
            total++;
            if (check.status != NetworkCheckResult.Status.OK) failed++;
        }
        return total >= 3 && failed * 2 >= total;
    }

    public static List<String> enrichRecommendations(ScanReport.Level level, List<String> original,
                                                      List<NetworkCheckResult> checks) {
        List<String> values = new ArrayList<>(original == null ? List.of() : original);
        boolean restrictions = level == ScanReport.Level.DEGRADED || level == ScanReport.Level.SEVERE_RESTRICTIONS
            || level == ScanReport.Level.ALLOWLIST_SUSPECTED;
        if (!restrictions) return values;

        NetworkCheckResult proxy = find(checks, "telegram_proxy_summary");
        NetworkCheckResult vpn = find(checks, "vpn_profiles_summary");
        NetworkCheckResult zapret = find(checks, "zapret_status");
        if (proxy != null && positive(proxy.metrics.get("working")))
            values.add("Работает хотя бы один настроенный прокси Telegram. Он помогает только Telegram и не открывает остальные сервисы.");
        if (vpn != null && positive(vpn.metrics.get("reachable")))
            values.add("Ответил хотя бы один настроенный VPN-адрес или адрес подписки. Это не доказывает доступность VPN-сервиса или работу туннеля.");
        if (zapret != null && "true".equals(zapret.metrics.get("detected"))) values.add(zapret.summary);

        if (level == ScanReport.Level.ALLOWLIST_SUSPECTED) {
            values.add("При белых списках обычные VPN, прокси, Tor и DPI-обходы могут не подключиться: сначала сохраните доступные каналы, SMS и телефоны, затем проверяйте методы через другую сеть.");
            values.add("Если разрешённый маршрут существует, по очереди проверьте доверенный VPN с маскировкой трафика, Tor Browser с мостами или Psiphon; универсальной гарантии нет.");
        } else {
            values.add("Для DPI-фильтрации можно отдельно проверить Zapret или GoodbyeDPI на ПК и ByeDPI на Android; они не скрывают IP и не заменяют VPN.");
            values.add("Для туннеля с маскировкой можно проверить AmneziaVPN/AmneziaWG, Outline либо другой доверенный VPN; сравните новый лог с прямым подключением.");
            values.add("Резервные варианты: Tor Browser с мостами и Psiphon. Загружайте программы только из официальных репозиториев или магазинов.");
        }
        values.add("Официальные источники: https://github.com/bol-van/zapret, https://github.com/ValdikSS/GoodbyeDPI, https://github.com/dovecoteescapee/ByeDPIAndroid, https://github.com/amnezia-vpn/amnezia-client, https://github.com/outline-vpn/outline-client и https://torproject.org.");
        return deduplicate(values);
    }

    private static NetworkCheckResult find(List<NetworkCheckResult> checks, String id) {
        if (checks != null) for (NetworkCheckResult check : checks) if (id.equals(check.id)) return check;
        return null;
    }
    private static boolean positive(String value) { try { return Integer.parseInt(value) > 0; } catch (Exception ignored) { return false; } }
    private static List<String> deduplicate(List<String> source) {
        List<String> result = new ArrayList<>();
        for (String value : source) if (value != null && !value.isBlank() && !result.contains(value)) result.add(value);
        return result;
    }
}
