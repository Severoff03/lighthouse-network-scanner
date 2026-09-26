package ru.lighthouse.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Four user-facing states. Insufficient measurements have no state, not a fifth alarm color. */
public final class NetworkAssessment {
    public enum State { GREEN, YELLOW, RED, BLACK }
    public final ScanReport.Level level;
    public final State state;
    public final String explanation;
    public final boolean baselineCompared;
    public final int comparedServices;
    public final List<String> newlyAffected;

    private NetworkAssessment(ScanReport.Level level, String explanation, boolean baselineCompared,
                              int comparedServices, List<String> newlyAffected) {
        this.level = level;
        state = stateFor(level);
        this.explanation = explanation;
        this.baselineCompared = baselineCompared;
        this.comparedServices = comparedServices;
        this.newlyAffected = Collections.unmodifiableList(new ArrayList<>(newlyAffected));
    }

    public static State stateFor(ScanReport.Level level) {
        switch (level) {
            case NORMAL: return State.GREEN;
            case DEGRADED: case SEVERE_RESTRICTIONS: return State.YELLOW;
            case ALLOWLIST_SUSPECTED: return State.RED;
            case NO_CONNECTION: return State.BLACK;
            default: return null;
        }
    }

    public static NetworkAssessment legacy(ScanReport.Level level) {
        return new NetworkAssessment(level, "Оценка без сохранённого эталона доступности.", false, 0, Collections.emptyList());
    }

    public static NetworkAssessment assess(List<ProbeResult> results, Map<String, ProbeResult.Status> baseline,
                                            int expectedTargets, boolean complete) {
        return assess(results, baseline, expectedTargets, complete, false);
    }

    public static NetworkAssessment assess(List<ProbeResult> results, Map<String, ProbeResult.Status> baseline,
                                            int expectedTargets, boolean complete, boolean externalControlResponded) {
        int total = 0, ruTotal = 0, ruUp = 0, foreignTotal = 0, foreignUp = 0, responses = 0, transports = 0, affected = 0, compared = 0;
        List<String> newlyAffected = new ArrayList<>();
        for (ProbeResult result : results) {
            if (result.target.probeKind != ServiceTarget.ProbeKind.HTTPS) continue;
            total++;
            boolean accessible = accessible(result);
            if (!accessible) affected++;
            if (hasWebResponse(result)) responses++;
            if (result.tcpMs >= 0 || result.pingMs >= 0) transports++;
            else for (ProbeResult sample : result.samples) if (sample.tcpMs >= 0 || sample.pingMs >= 0) { transports++; break; }
            if ("RU".equals(result.target.region)) { ruTotal++; if (accessible) ruUp++; }
            else { foreignTotal++; if (accessible) foreignUp++; }
            ProbeResult.Status before = baseline.get(result.target.id);
            if (before != null) {
                compared++;
                if (before == ProbeResult.Status.AVAILABLE && !accessible) newlyAffected.add(result.target.name);
            }
        }
        boolean comparable = compared >= 12 && compared >= Math.ceil(total * .8);
        if (!complete || results.size() < expectedTargets || total < 12 || ruTotal < 3 || foreignTotal < 3)
            return new NetworkAssessment(ScanReport.Level.INCOMPLETE,
                "Нужен завершённый скан с достаточным числом российских и зарубежных сервисов. Непроверенные узлы не считаются заблокированными.",
                comparable, compared, newlyAffected);

        if (responses == 0 && transports == 0 && !externalControlResponded)
            return new NetworkAssessment(ScanReport.Level.NO_CONNECTION,
                "Не получено ни одного ответа веб-сервисов, в том числе российских. Это может быть блокировка, авария или отсутствие подключения; причина не установлена.",
                comparable, compared, newlyAffected);

        if (responses == 0)
            return new NetworkAssessment(ScanReport.Level.DEGRADED,
                "Веб-сервисы не ответили, но транспортные или дополнительные внешние проверки работают. Это серьёзное нарушение доступности, однако полное отключение интернета не подтверждается.",
                comparable, compared, newlyAffected);

        double ruShare = (double) ruUp / ruTotal;
        double foreignShare = (double) foreignUp / foreignTotal;
        // A regional difference alone is not proof of a government allowlist.
        if (ruTotal >= 5 && foreignTotal >= 5 && ruShare >= .60 && foreignShare <= .15)
            return new NetworkAssessment(ScanReport.Level.ALLOWLIST_SUSPECTED,
                "Признаки белых списков: доступны " + ruUp + " из " + ruTotal + " российских и " + foreignUp + " из " + foreignTotal
                    + " зарубежных сервисов. Это диагностическая гипотеза, а не подтверждение причины ограничения.",
                comparable, compared, newlyAffected);

        if (comparable) {
            int threshold = Math.max(3, (int) Math.ceil(compared * .05));
            if (newlyAffected.size() >= threshold)
                return new NetworkAssessment(ScanReport.Level.DEGRADED,
                    "По сравнению с обычным уровнем появились проблемы у " + newlyAffected.size() + " сервисов из " + compared
                        + " сопоставимых. Сбой сервиса и блокировка не различаются достоверно одним сканом.", true, compared, newlyAffected);
            return new NetworkAssessment(ScanReport.Level.NORMAL,
                "Доступность близка к сохранённому обычному уровню. Привычные недоступные сервисы не повышают тревогу. Новых проблем: "
                    + newlyAffected.size() + ".", true, compared, newlyAffected);
        }
        return new NetworkAssessment(ScanReport.Level.NORMAL,
            "Обычный уровень этой сети ещё не был сохранён, поэтому необычность ограничений не определяется по одному скану. Текущий полный результат станет отправной точкой для последующих сравнений.",
            false, compared, newlyAffected);
    }

    /** A majority of successful HTTPS samples proves stable reachability; high ping alone never changes the state. */
    public static boolean accessible(ProbeResult result) {
        if (result.target.probeKind != ServiceTarget.ProbeKind.HTTPS) return false;
        int attempts = result.samples.isEmpty() ? 1 : result.samples.size();
        int successful = successfulWebSamples(result);
        return successful >= attempts / 2 + 1;
    }

    /** Status used by summaries. DNS, ping or TCP without an HTTP response is not working web access. */
    public static ProbeResult.Status observedStatus(ProbeResult result) {
        int attempts = result.samples.isEmpty() ? 1 : result.samples.size();
        if (result.target.probeKind == ServiceTarget.ProbeKind.HTTPS) {
            int successful = successfulWebSamples(result);
            if (successful >= attempts / 2 + 1) return ProbeResult.Status.AVAILABLE;
            // Only an intermittent successful HTTPS response is limited service access.
            // DNS, ICMP, TCP and HTTP error pages cannot establish usable access.
            if (successful > 0) return ProbeResult.Status.DEGRADED;
            return ProbeResult.Status.UNAVAILABLE;
        }
        int successful = 0;
        if (result.samples.isEmpty()) successful = result.status == ProbeResult.Status.AVAILABLE ? 1 : 0;
        else for (ProbeResult sample : result.samples) if (sample.status == ProbeResult.Status.AVAILABLE) successful++;
        if (successful >= attempts / 2 + 1) return ProbeResult.Status.AVAILABLE;
        if (successful > 0) return ProbeResult.Status.DEGRADED;
        return result.status;
    }

    private static int successfulWebSamples(ProbeResult result) {
        if (result.samples.isEmpty()) return successfulWebResponse(result) ? 1 : 0;
        int successful = 0;
        for (ProbeResult sample : result.samples) if (successfulWebResponse(sample)) successful++;
        return successful;
    }
    private static boolean successfulWebResponse(ProbeResult result) {
        return result.httpsMs >= 0 && result.httpCode >= 200 && result.httpCode < 400;
    }
    private static boolean hasWebResponse(ProbeResult result) {
        if (result.httpsMs >= 0 && result.httpCode >= 100) return true;
        for (ProbeResult sample : result.samples) if (sample.httpsMs >= 0 && sample.httpCode >= 100) return true;
        return false;
    }

    private static boolean hasTransportResponse(ProbeResult result) {
        if (result.tcpMs >= 0 || result.pingMs >= 0) return true;
        for (ProbeResult sample : result.samples) if (sample.tcpMs >= 0 || sample.pingMs >= 0) return true;
        return false;
    }

    public static boolean hasExternalResponse(List<NetworkCheckResult> checks, String publicIp) {
        if (publicIp != null && !publicIp.isEmpty() && !"unavailable".equals(publicIp) && !"unknown".equals(publicIp)) return true;
        for (NetworkCheckResult check : checks) {
            if (check.id.startsWith("tcp_") && check.metrics.containsKey("successful")) {
                try { if (Integer.parseInt(check.metrics.get("successful")) > 0) return true; }
                catch (NumberFormatException ignored) { }
            }
            if (check.status == NetworkCheckResult.Status.OK && ("ipv6".equals(check.id)
                || check.id.startsWith("address_")
                || check.id.startsWith("doh_") || check.id.startsWith("dot_") || check.id.startsWith("icmp_series_"))) return true;
        }
        return false;
    }

    public static List<ProbeResult> availableServices(ScanReport report) {
        List<ProbeResult> values = new ArrayList<>();
        for (ProbeResult result : report.results) if (accessible(result)) values.add(result);
        return values;
    }

    public static String stateTitle(ScanReport.Level level) {
        State state = stateFor(level);
        if (state == null) return "Данных недостаточно";
        switch (state) {
            case GREEN: return "Обычный уровень доступности";
            case YELLOW: return "Необычные ограничения";
            case RED: return "Признаки белых списков";
            default: return "Интернет-сервисы недоступны";
        }
    }
}
