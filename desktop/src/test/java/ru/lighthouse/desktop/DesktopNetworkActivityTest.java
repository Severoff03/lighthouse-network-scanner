package ru.lighthouse.desktop;

import ru.lighthouse.core.NetworkCheckResult;

import java.util.List;

/** Read-only smoke test for process/network correlation. */
public final class DesktopNetworkActivityTest {
    public static void main(String[] args) {
        List<NetworkCheckResult> checks = DesktopNetworkActivity.capture("test");
        if (checks.isEmpty()) throw new AssertionError("Process activity diagnostic returned no status");
        if (checks.stream().anyMatch(value -> !"Безопасность устройства".equals(value.category)))
            throw new AssertionError("Process activity must stay in the security category");
        if (checks.stream().noneMatch(value -> value.id.contains("process_traffic")))
            throw new AssertionError("Process activity summary missing");
        NetworkCheckResult zapret = DesktopZapretDiagnostics.collect(List.of());
        if (!"zapret_status".equals(zapret.id) || !"Методы обхода".equals(zapret.category))
            throw new AssertionError("Zapret diagnostic summary missing");
        System.out.println("Desktop process activity: " + checks.size() + " checks passed.");
    }
}
