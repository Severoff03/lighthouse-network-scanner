package ru.lighthouse.desktop;

import ru.lighthouse.core.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Offline interaction and render checks. Never calls the scanner or saves real device data. */
public final class DesktopDashboardTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path output = Path.of("build", "desktop-ui-check", "previews"); Files.createDirectories(output);
        Image logo;
        try (var in = DesktopDashboardTest.class.getResourceAsStream("/lighthouse-logo.png")) {
            logo = in == null ? null : ImageIO.read(in);
        }
        final Image brand = logo;
        SwingUtilities.invokeAndWait(() -> {
            DesktopDashboard view = new DesktopDashboard("· демо", brand, true);
            try {
                check(view.model.getRowCount() == 0 && !view.export.isEnabled(), "empty state");
                render(view, output.resolve("desktop-dark-empty.png"), 1200, 820);
                List<ProbeResult> samples = sampleResults();
                view.beginScan(ScanProfile.DEEP, samples.size());
                check(!view.settings.isEnabled() && !view.export.isEnabled(), "scan controls");
                view.accept(samples.subList(0, 6));
                check(view.model.getRowCount() == 6, "streaming rows");
                view.setStage("Повторные проверки · проход 2 из 3");
                render(view, output.resolve("desktop-scanning.png"), 1200, 820);
                ProbeResult repeated = ProbeResult.combine(samples.get(0), result(samples.get(0).target, ProbeResult.Status.DEGRADED, 10));
                view.accept(List.of(repeated));
                check(view.model.getRowCount() == 6 && view.model.results.get(0).attempts() == 2, "repeat replaces same service");
                view.state.setSelectedIndex(2);
                check(view.table.getRowCount() == 3, "status filter and updated status");
                view.search.setText("Vk");
                check(view.table.getRowCount() == 1, "case insensitive search");
                view.search.setText("[invalid(regex");
                check(view.table.getRowCount() == 0, "search is literal, not regex");
                view.search.setText(""); view.state.setSelectedIndex(0);
                view.category.setSelectedItem(TargetCatalog.BANKING);
                check(view.table.getRowCount() == 1, "category filter");
                view.category.setSelectedIndex(0);
                view.sorter.setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.ASCENDING)));
                long last = Long.MIN_VALUE;
                for (int i = 0; i < view.table.getRowCount(); i++) {
                    Object n = view.table.getValueAt(i, 3);
                    if (n != null) { check((Long) n >= last, "numeric latency sort"); last = (Long) n; }
                }
                view.table.setRowSelectionInterval(0, 0);
                view.cancelScan();
                check(view.model.getRowCount() == 6 && view.settings.isEnabled() && !view.export.isEnabled(), "cancel retains partial rows");
                view.accept(samples); check(view.model.getRowCount() == 6, "late results ignored after cancel");
                view.beginScan(ScanProfile.DEEP, TargetCatalog.defaults().size());
                check(view.model.getRowCount() == 0 && view.table.getRowCount() == 0, "restart clears previous results");
                view.sorter.setSortKeys(List.of());
                List<ProbeResult> allSamples = allResults();
                ScanReport report = report(allSamples, ScanReport.Level.DEGRADED, true);
                view.savingReport(); check(!view.scan.isEnabled(), "save phase prevents cancellation race");
                view.finish(report, Path.of("example-log.json"), null);
                check(view.export.isEnabled() && view.settings.isEnabled() && view.scan.isEnabled(), "finished controls");
                check(view.model.getRowCount() == allSamples.size(), "final snapshot authoritative");
                view.search.setText("test"); view.category.setSelectedItem(TargetCatalog.BANKING);
                JButton unavailable = findStat(view, "Не работают:");
                check(unavailable != null, "status card is keyboard-accessible button");
                unavailable.doClick(0);
                check(view.search.getText().isEmpty() && view.category.getSelectedIndex() == 0, "status card resets narrower filters");
                check(view.table.getRowCount() == allSamples.stream().filter(r -> r.status == ProbeResult.Status.UNAVAILABLE).count(), "status card matches total");
                view.state.setSelectedIndex(0);
                view.table.setRowSelectionInterval(2, 2);
                render(view, output.resolve("desktop-dark-results.png"), 1200, 820);
                view.setDark(false);
                render(view, output.resolve("desktop-light-results.png"), 1200, 820);
                check(!view.isDark(), "light theme");
                view.setDark(true);
                render(view, output.resolve("desktop-compact.png"), 944, 641);
                check(((JViewport) view.table.getParent()).getExtentSize().height >= view.table.getRowHeight() * 3,
                    "compact layout leaves at least three service rows visible");
                for (String page : List.of("categories", "diagnostics", "security", "conclusions")) {
                    view.selectPage(page);
                    render(view, output.resolve("desktop-" + page + ".png"), 1200, 820);
                    render(view, output.resolve("desktop-compact-" + page + ".png"), 944, 641);
                }
                view.selectPage("services"); view.search.setText("no such service");
                render(view, output.resolve("desktop-no-matches.png"), 1200, 820);
                view.search.setText("");
                for (ScanReport.Level level : ScanReport.Level.values()) {
                    view.finish(report(stateResults(level), level, level != ScanReport.Level.INCOMPLETE), null, null);
                    check(!view.export.isEnabled(), "missing log disables export for " + level);
                    if (level == ScanReport.Level.ALLOWLIST_SUSPECTED)
                        check(view.state.getSelectedIndex() == 1, "red opens the available services filter");
                    if (level == ScanReport.Level.NO_CONNECTION) {
                        check(view.model.results.stream().noneMatch(NetworkAssessment::accessible), "black preview has no available services");
                        view.setDark(false);
                        render(view, output.resolve("desktop-black-light.png"), 1200, 820);
                        view.setDark(true);
                    }
                    render(view, output.resolve("state-" + level + ".png"), 944, 641);
                    if (level == ScanReport.Level.ALLOWLIST_SUSPECTED) {
                        view.selectPage("conclusions"); render(view, output.resolve("desktop-red-available.png"), 1200, 820);
                        view.selectPage("services");
                    }
                }
                view.beginScan(ScanProfile.QUICK, TargetCatalog.defaults().size()); view.failScan("Тестовая ошибка");
                check(view.settings.isEnabled() && !view.export.isEnabled(), "error controls");
                view.beginScan(ScanProfile.QUICK, TargetCatalog.defaults().size()); view.accept(List.of(result(
                    new ServiceTarget("unsafe", "<html><b>Literal name</b>", "test", "example.invalid", "/", "test"),
                    ProbeResult.Status.AVAILABLE, 15)));
                Component renderer = view.table.prepareRenderer(view.table.getCellRenderer(0, 0), 0, 0);
                check(Boolean.TRUE.equals(((JComponent) renderer).getClientProperty("html.disable")), "external text is not HTML");
                view.cancelScan();
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { view.dispose(); }
        });
        System.out.println("Desktop dashboard: " + assertions + " checks passed. Offline previews: " + output);
    }

    private static List<ProbeResult> sampleResults() {
        String[] names = {"VK", "Telegram", "YouTube", "Сбербанк", "Google Drive", "Discord"};
        String[] categories = {TargetCatalog.SOCIAL, TargetCatalog.CHATS, TargetCatalog.MEDIA, TargetCatalog.BANKING, TargetCatalog.FILES, TargetCatalog.CHATS};
        List<ProbeResult> rows = new ArrayList<>();
        for (int i = 0; i < names.length; i++) rows.add(result(new ServiceTarget("fixture-" + i, names[i], categories[i],
            "example-" + i + ".invalid", "/", "test"), ProbeResult.Status.values()[i % 3], new long[]{100, 20, -1, 9, 1000, -1}[i]));
        return rows;
    }

    private static List<ProbeResult> allResults() {
        List<ProbeResult> rows = new ArrayList<>(); int i = 0;
        for (ServiceTarget target : TargetCatalog.defaults()) {
            ProbeResult.Status status = i % 9 == 1 ? ProbeResult.Status.DEGRADED : i % 11 == 3 ? ProbeResult.Status.UNAVAILABLE : ProbeResult.Status.AVAILABLE;
            rows.add(result(target, status, status == ProbeResult.Status.UNAVAILABLE ? -1 : 12 + (i * 7) % 160)); i++;
        }
        return rows;
    }

    private static List<ProbeResult> stateResults(ScanReport.Level level) {
        List<ProbeResult> rows = new ArrayList<>(); int i = 0;
        for (ServiceTarget target : TargetCatalog.defaults()) {
            if (level == ScanReport.Level.INCOMPLETE && i >= 5) break;
            boolean down = level == ScanReport.Level.NO_CONNECTION
                || level == ScanReport.Level.ALLOWLIST_SUSPECTED && !"RU".equals(target.region)
                || (level == ScanReport.Level.DEGRADED || level == ScanReport.Level.SEVERE_RESTRICTIONS) && i % 2 == 0;
            rows.add(result(target, down ? ProbeResult.Status.UNAVAILABLE : ProbeResult.Status.AVAILABLE, down ? -1 : 24)); i++;
        }
        return rows;
    }

    private static ProbeResult result(ServiceTarget target, ProbeResult.Status status, long ping) {
        long https = status == ProbeResult.Status.UNAVAILABLE ? -1 : ping + 24;
        int code = status == ProbeResult.Status.AVAILABLE ? 200 : status == ProbeResult.Status.DEGRADED ? 503 : -1;
        return new ProbeResult(target, status, 8, ping, ping < 0 ? -1 : ping + 4, https,
            code, "192.0.2.1", ping < 0 ? "Тестовый тайм-аут" : "");
    }

    private static ScanReport report(List<ProbeResult> results, ScanReport.Level level, boolean complete) {
        DeviceInfo device = new DeviceInfo("Демонстрационный ПК", "Windows · тестовые данные", "192.0.2.2", "00:00:00:00:00:00", "Example Wi-Fi", false, "не обнаружены");
        NetworkCheckResult dns = new NetworkCheckResult("demo_dns", "DNS-серверы", TargetCatalog.DNS,
            NetworkCheckResult.Status.OK, "Серверы отвечают на тестовые запросы.", Map.of("Задержка", "24 мс"));
        NetworkCheckResult process = new NetworkCheckResult("process_traffic_demo", "example.exe", "Безопасность устройства",
            NetworkCheckResult.Status.WARNING, "Фоновый процесс имеет внешнее TCP-соединение; требуется ручная проверка.",
            Map.of("pid", "4242", "remoteEndpoints", "192.0.2.40:443", "withoutVisibleWindow", "true"));
        Instant start = Instant.parse("2026-08-28T10:00:00Z");
        return new ScanReport("offline-ui-fixture", start, start.plusSeconds(154), device, "192.0.2.3", "Демонстрационная сеть",
            level, results, List.of(dns, process), List.of("Пример сервиса"), List.of("Пример восстановления"),
            List.of("Проверьте доступность нужных сервисов в другой сети.", "Повторите сканирование, чтобы проверить устойчивость результата."),
            ScanProfile.DEEP, TargetCatalog.defaults().size(), complete);
    }

    private static void render(DesktopDashboard view, Path path, int width, int height) throws Exception {
        view.setSize(width, height);
        // Off-screen Swing needs explicit recursive layout (including wrapped text and scroll viewports).
        for (int i = 0; i < 4; i++) layout(view);
        check(view.table.getColumnModel().getTotalColumnWidth() > 0, "table layout");
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); DesktopStyle.smooth(g); view.printAll(g); g.dispose(); ImageIO.write(image, "png", path.toFile());
    }
    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container container) layout(container);
    }
    private static JButton findStat(Container root, String prefix) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && button.getAccessibleContext().getAccessibleName().startsWith(prefix)) return button;
            if (child instanceof Container nested) { JButton found = findStat(nested, prefix); if (found != null) return found; }
        }
        return null;
    }
    private static void check(boolean passed, String message) {
        assertions++; if (!passed) throw new AssertionError(message);
    }
}
