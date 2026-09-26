package ru.lighthouse.android;

import ru.lighthouse.core.NetworkCheckResult;
import ru.lighthouse.core.ProbeResult;
import ru.lighthouse.core.ZapretCheck;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Best-effort, read-only lookup of Zapret native engines visible to an unprivileged Android app. */
final class AndroidZapretDiagnostics {
    private AndroidZapretDiagnostics() { }

    static NetworkCheckResult collect(List<ProbeResult> results) {
        List<String> found = new ArrayList<>();
        for (String engine : new String[]{"nfqws", "tpws", "winws", "winws2"}) if (pid(engine)) found.add(engine);
        NetworkCheckResult result = ZapretCheck.evaluate(!found.isEmpty(), String.join(", ", found), results);
        if (found.isEmpty()) {
            java.util.Map<String, String> metrics = new java.util.LinkedHashMap<>(result.metrics);
            metrics.put("androidVisibility", "root_processes_may_be_hidden");
            return new NetworkCheckResult(result.id, result.name, result.category, result.status,
                "Zapret не обнаружен среди доступных приложению процессов. Android может скрывать root-процессы, поэтому отсутствие не доказано.", metrics);
        }
        return result;
    }

    private static boolean pid(String name) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/pidof", name).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[128]; int count;
            while ((count = process.getInputStream().read(buffer)) >= 0 && output.size() < 1024) output.write(buffer, 0, count);
            return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
                && !new String(output.toByteArray(), StandardCharsets.US_ASCII).trim().isEmpty();
        } catch (Exception ignored) { return false; }
        finally { if (process != null) process.destroyForcibly(); }
    }
}
