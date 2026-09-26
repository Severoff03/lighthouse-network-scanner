package ru.lighthouse.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Indirectly verifies an already-running Zapret installation without changing its configuration. */
public final class ZapretCheck {
    private ZapretCheck() { }

    public static NetworkCheckResult evaluate(boolean detected, String evidence, List<ProbeResult> results) {
        int tested = 0, working = 0;
        if (results != null) for (ProbeResult result : results) {
            String id = result.target.id;
            if (!(id.startsWith("youtube") || id.startsWith("discord"))) continue;
            tested++;
            if (NetworkAssessment.observedStatus(result) == ProbeResult.Status.AVAILABLE) working++;
        }
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("detected", String.valueOf(detected));
        metrics.put("evidence", evidence == null || evidence.isBlank() ? "none" : evidence);
        metrics.put("testTargets", String.valueOf(tested)); metrics.put("workingTargets", String.valueOf(working));
        metrics.put("verification", "indirect_process_and_service_reachability");
        if (!detected) return new NetworkCheckResult("zapret_status", "Zapret", "Методы обхода", NetworkCheckResult.Status.OK,
            "Запущенный Zapret не обнаружен. Проверка не запускала и не изменяла сторонние программы.", metrics);
        if (tested > 0 && working == tested) return new NetworkCheckResult("zapret_status", "Zapret", "Методы обхода", NetworkCheckResult.Status.OK,
            "Zapret обнаружен; все контрольные узлы YouTube и Discord отвечают. Это косвенный признак работоспособности текущей стратегии.", metrics);
        if (working > 0) return new NetworkCheckResult("zapret_status", "Zapret", "Методы обхода", NetworkCheckResult.Status.WARNING,
            "Zapret обнаружен, но контрольные узлы отвечают только частично. Возможно, стратегия подходит не всем протоколам или доменам.", metrics);
        return new NetworkCheckResult("zapret_status", "Zapret", "Методы обхода", NetworkCheckResult.Status.WARNING,
            "Zapret обнаружен, но контрольные узлы YouTube и Discord не ответили. Текущая стратегия, вероятно, не помогает этой сети.", metrics);
    }
}
