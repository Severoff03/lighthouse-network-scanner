package ru.lighthouse.desktop;

import ru.lighthouse.core.NetworkCheckResult;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Read-only snapshot of established Windows TCP connections. */
final class DesktopNetworkActivity {
    private static final class Activity {
        final long pid; final String name, executable; final Set<String> remotes = new LinkedHashSet<>(); int connections;
        Activity(long pid, String name, String executable) { this.pid = pid; this.name = name; this.executable = executable; }
    }

    private DesktopNetworkActivity() { }

    static List<NetworkCheckResult> capture(String phase) {
        List<NetworkCheckResult> result = new ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            result.add(check("process_traffic_" + phase, "Сетевая активность процессов", NetworkCheckResult.Status.WARNING,
                "Список процессов с удалёнными соединениями сейчас поддерживается только на Windows.", new LinkedHashMap<>()));
            return result;
        }
        Process process = null;
        try {
            process = new ProcessBuilder("netstat", "-ano", "-p", "tcp").redirectErrorStream(true).start();
            Map<Long, Activity> rows = new LinkedHashMap<>();
            Set<Long> visibleWindows = visibleWindowProcesses();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.InputStream input = process.getInputStream();
            Thread reader = new Thread(() -> {
                try { byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) != -1) {
                    synchronized (bytes) { if (bytes.size() < 262144) bytes.write(buffer, 0, Math.min(count, 262144 - bytes.size())); }
                } } catch (java.io.IOException ignored) { }
            }, "lighthouse-netstat-reader"); reader.setDaemon(true); reader.start();
            if (!process.waitFor(6, TimeUnit.SECONDS)) throw new java.io.IOException("netstat timeout");
            reader.join(500);
            String output; synchronized (bytes) { output = bytes.toString(Charset.defaultCharset()); }
            for (String line : output.split("\\r?\\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 5 || !"TCP".equalsIgnoreCase(parts[0]) || !"ESTABLISHED".equalsIgnoreCase(parts[3])) continue;
                String remote = parts[2]; if (loopback(remote)) continue;
                long pid; try { pid = Long.parseLong(parts[4]); } catch (NumberFormatException ignored) { continue; }
                Activity activity = rows.computeIfAbsent(pid, DesktopNetworkActivity::process);
                activity.connections++;
                if (activity.remotes.size() < 100) activity.remotes.add(remote);
            }
            Map<String, String> summary = new LinkedHashMap<>(); summary.put("phase", phase);
            summary.put("processes", String.valueOf(rows.size()));
            long background = rows.keySet().stream().filter(pid -> !visibleWindows.contains(pid)).count();
            summary.put("withoutVisibleWindow", String.valueOf(background));
            result.add(check("process_traffic_summary_" + phase, "Процессы с внешними TCP-соединениями",
                NetworkCheckResult.Status.OK, "Обнаружено процессов: " + rows.size()
                    + ", без видимого окна: " + background
                    + ". Наличие соединения нормально для браузеров, обновлений и синхронизации и не доказывает шпионскую активность.", summary));
            for (Activity activity : rows.values()) {
                boolean backgroundProcess = !visibleWindows.contains(activity.pid);
                String lowPath = activity.executable.toLowerCase(java.util.Locale.ROOT);
                boolean attention = backgroundProcess && (lowPath.contains("\\appdata\\local\\temp\\") || "недоступно".equals(lowPath));
                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("phase", phase); metrics.put("pid", String.valueOf(activity.pid));
                metrics.put("executable", activity.executable); metrics.put("remoteEndpoints", String.join("; ", activity.remotes));
                metrics.put("connections", String.valueOf(activity.connections));
                metrics.put("withoutVisibleWindow", String.valueOf(backgroundProcess));
                metrics.put("visibilityLimitation", "MainWindowHandle heuristic; services and tray apps normally have no visible window");
                result.add(check("process_traffic_" + phase + "_" + activity.pid, activity.name,
                    attention ? NetworkCheckResult.Status.WARNING : NetworkCheckResult.Status.OK,
                    "Активных внешних TCP-соединений: " + activity.connections
                        + (backgroundProcess ? ". Процесс работает без видимого окна." : ". У процесса есть видимое окно.")
                        + (attention ? " Путь запуска требует ручной проверки." : " Неизвестное имя или адрес следует проверить вручную."), metrics));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException();
        } catch (Exception error) {
            Map<String, String> metrics = new LinkedHashMap<>(); metrics.put("error", error.toString()); metrics.put("phase", phase);
            result.add(check("process_traffic_error_" + phase, "Сетевая активность процессов", NetworkCheckResult.Status.WARNING,
                "Не удалось получить список установленных TCP-соединений.", metrics));
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
        return result;
    }

    private static Activity process(long pid) {
        String command = ProcessHandle.of(pid).flatMap(handle -> handle.info().command()).orElse("недоступно");
        String name = "PID " + pid;
        try { if (!"недоступно".equals(command)) name = Path.of(command).getFileName().toString(); }
        catch (RuntimeException ignored) { }
        return new Activity(pid, name, command);
    }

    private static Set<Long> visibleWindowProcesses() {
        Set<Long> result = new LinkedHashSet<>(); Process process = null;
        try {
            process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Get-Process | Where-Object {$_.MainWindowHandle -ne 0} | ForEach-Object {$_.Id}")
                .redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) return result;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line; while ((line = reader.readLine()) != null) try { result.add(Long.parseLong(line.trim())); } catch (NumberFormatException ignored) { }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
        catch (Exception ignored) { }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
        return result;
    }

    private static boolean loopback(String endpoint) {
        String low = endpoint.toLowerCase(java.util.Locale.ROOT);
        return low.startsWith("127.") || low.startsWith("[::1]") || low.startsWith("0.0.0.0") || low.startsWith("[::]");
    }

    private static NetworkCheckResult check(String id, String name, NetworkCheckResult.Status status,
                                            String summary, Map<String, String> metrics) {
        return new NetworkCheckResult(id, name, "Безопасность устройства", status, summary, metrics);
    }
}
