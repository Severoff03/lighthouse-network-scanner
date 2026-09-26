package ru.lighthouse.desktop;

import ru.lighthouse.core.NetworkCheckResult;
import ru.lighthouse.core.ProbeResult;
import ru.lighthouse.core.ZapretCheck;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only detection of a running Zapret engine/service. */
final class DesktopZapretDiagnostics {
    private static final Pattern SERVICE = Pattern.compile("(?is)SERVICE_NAME:\\s*([^\\r\\n]+).*?STATE\\s*:\\s*4\\s+RUNNING");
    private DesktopZapretDiagnostics() { }

    static NetworkCheckResult collect(List<ProbeResult> results) {
        Set<String> evidence = new LinkedHashSet<>();
        try {
            ProcessHandle.allProcesses().forEach(process -> process.info().command().ifPresent(command -> {
                String name = fileName(command).toLowerCase(Locale.ROOT);
                if (engine(name)) evidence.add(name);
            }));
        } catch (Exception | LinkageError ignored) { }
        if (isWindows()) {
            String tasks = command("tasklist", "/FO", "CSV", "/NH").toLowerCase(Locale.ROOT);
            for (String name : List.of("winws.exe", "winws2.exe", "nfqws.exe", "tpws.exe", "zapret.exe"))
                if (tasks.contains("\"" + name + "\"")) evidence.add(name);
            Matcher services = SERVICE.matcher(command("sc.exe", "query", "type=", "service", "state=", "all"));
            while (services.find()) {
                String name = services.group(1).trim();
                if (engine(name.toLowerCase(Locale.ROOT))) evidence.add("служба " + name);
            }
        }
        return ZapretCheck.evaluate(!evidence.isEmpty(), String.join(", ", evidence), results);
    }

    private static boolean engine(String name) {
        return name.matches(".*(?:^|[\\/\\\\._-])(winws2?|nfqws|tpws|zapret)(?:\\.exe)?$")
            || name.matches("^(winws2?|nfqws|tpws|zapret)(?:\\.exe)?$");
    }
    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    private static String command(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = process.getInputStream().read(buffer)) >= 0 && output.size() < 262144)
                output.write(buffer, 0, Math.min(count, 262144 - output.size()));
            if (!process.waitFor(5, TimeUnit.SECONDS)) return "";
            return output.toString(Charset.forName("IBM866"));
        } catch (Exception ignored) { return ""; }
        finally { if (process != null) process.destroyForcibly(); }
    }
}
