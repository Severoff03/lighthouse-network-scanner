package ru.lighthouse.desktop;

import ru.lighthouse.core.*;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;

/** Desktop controller. Scanning and report persistence stay off the Swing event thread. */
public final class DesktopApp {
    private static final String VERSION = loadVersion();
    private final Preferences preferences = Preferences.userNodeForPackage(DesktopApp.class);
    private final Path dataDir = Path.of(System.getProperty("user.home"), ".lighthouse");
    private final JFrame frame = new JFrame("Lighthouse " + VERSION + " — состояние интернета");
    private final Image logo = loadLogo();
    private final DesktopDashboard dashboard = new DesktopDashboard(VERSION, logo, resolveDarkMode());
    private SwingWorker<CompletedScan, ProbeResult> currentWorker;
    private Path lastLog;
    private ScanReport lastReport;
    private final Object persistenceGate = new Object();
    private boolean savingReport;
    private final MothmanDesktop updater = new MothmanDesktop(frame,VERSION,() -> currentWorker != null || savingReport);
    private volatile String detector404Token = "";
    private volatile List<NamedConfiguration> telegramProxies = DefaultConnections.telegramProxies();
    private volatile List<NamedConfiguration> vpnProfiles = DefaultConnections.vpnSubscriptions();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { /* Custom components also work with the default look and feel. */ }
            new DesktopApp().show();
        });
    }

    private static String loadVersion() {
        try (java.io.InputStream in = DesktopApp.class.getResourceAsStream("/version.properties")) {
            java.util.Properties properties = new java.util.Properties();
            if (in != null) properties.load(in);
            return "v" + properties.getProperty("VERSION_NAME", "dev");
        } catch (Exception ignored) { return "vdev"; }
    }

    private static Image loadLogo() {
        try (java.io.InputStream in = DesktopApp.class.getResourceAsStream("/lighthouse-logo.png")) {
            return in == null ? null : javax.imageio.ImageIO.read(in);
        } catch (Exception ignored) { return null; }
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        if (logo != null) frame.setIconImage(logo);
        frame.setContentPane(dashboard);
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setMinimumSize(new Dimension(Math.min(960, usable.width), Math.min(680, usable.height)));
        frame.setSize(Math.min(1240, usable.width), Math.min(860, usable.height));
        frame.setLocationRelativeTo(null);
        dashboard.configureProfile(savedProfile());
        dashboard.setBypassConfigurations(telegramProxies, vpnProfiles);
        dashboard.scan.addActionListener(e -> {
            synchronized (persistenceGate) {
                if (currentWorker != null) {
                    if (!savingReport) currentWorker.cancel(true);
                    return;
                }
            }
            startScan();
        });
        dashboard.export.addActionListener(e -> exportLog());
        dashboard.settings.addActionListener(e -> showSettings());
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (currentWorker != null) currentWorker.cancel(true);
                updater.close();
                dashboard.dispose();
            }
        });
        frame.setVisible(true);
        updater.start();
    }

    private boolean resolveDarkMode() {
        String theme = preferences.get("theme", "dark");
        if ("dark".equals(theme)) return true;
        if ("light".equals(theme)) return false;
        Color system = UIManager.getColor("Panel.background");
        return system != null && system.getRed() + system.getGreen() + system.getBlue() < 330;
    }

    private ScanProfile savedProfile() {
        return "quick".equals(preferences.get("profile", "deep")) ? ScanProfile.QUICK : ScanProfile.DEEP;
    }

    private void showSettings() {
        JDialog dialog = new JDialog(frame, "Настройки Lighthouse", true);
        DesktopStyle dialogStyle = new DesktopStyle(dashboard.isDark());
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JPanel panel = DesktopStyle.transparent(new BorderLayout(0, 24));
        panel.setOpaque(true); panel.setBorder(BorderFactory.createEmptyBorder(24, 26, 24, 26));
        panel.add(DesktopStyle.label("Настройте под себя", 23, true, "text"), BorderLayout.NORTH);
        JPanel fields = new DesktopStyle.WidthPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 8, 0);
        JComboBox<String> theme = new JComboBox<>(new String[]{"Тёмная", "Светлая", "Системная"});
        String saved = preferences.get("theme", "dark");
        theme.setSelectedIndex("dark".equals(saved) ? 0 : "light".equals(saved) ? 1 : 2);
        JLabel themeLabel = DesktopStyle.label("Тема оформления", 14, true, "text"); themeLabel.setLabelFor(theme);
        fields.add(themeLabel, c); c.gridy++; fields.add(theme, c); c.gridy++;
        c.insets = new Insets(16, 0, 8, 0);
        JComboBox<String> depth = new JComboBox<>(new String[]{"Глубокая · 2 прохода · до 5 минут", "Быстрая · 1 проход"});
        depth.setSelectedIndex(savedProfile() == ScanProfile.DEEP ? 0 : 1);
        JLabel depthLabel = DesktopStyle.label("Глубина сканирования", 14, true, "text"); depthLabel.setLabelFor(depth);
        fields.add(depthLabel, c); c.gridy++; c.insets = new Insets(0, 0, 8, 0); fields.add(depth, c); c.gridy++;
        JTextArea description = DesktopStyle.paragraph("Повторные замеры помогают оценить стабильность. Проверки обращаются к внешним сервисам и расходуют трафик.");
        description.setRows(3); fields.add(description, c); panel.add(DesktopStyle.scroll(fields), BorderLayout.CENTER);
        c.gridy++;
        c.insets = new Insets(12, 0, 6, 0);
        JLabel detectorLabel = DesktopStyle.label("Токен Detector404 API", 14, true, "text");
        JPasswordField detectorTokenField = new JPasswordField(detector404Token);
        detectorTokenField.setToolTipText("Личный токен действует до закрытия Lighthouse и не записывается в лог или настройки на диске.");
        detectorLabel.setLabelFor(detectorTokenField); fields.add(detectorLabel, c); c.gridy++;
        c.insets = new Insets(0, 0, 8, 0); fields.add(detectorTokenField, c); c.gridy++;
        c.insets = new Insets(12, 0, 6, 0);
        fields.add(DesktopStyle.label("Прокси Telegram", 14, true, "text"), c); c.gridy++;
        JTextArea proxyHint = DesktopStyle.paragraph("До 12 SOCKS5, HTTP CONNECT или MTProto-прокси. Встроенные публичные узлы не являются доверенными: их оператор видит ваш IP. Двойной щелчок по строке копирует ссылку.");
        proxyHint.setRows(3); c.insets = new Insets(0, 0, 8, 0); fields.add(proxyHint, c); c.gridy++;
        ConfigEditor proxyEditor = new ConfigEditor(dialogStyle, "Прокси", "socks5://…  http://…  tg://proxy?…", true, telegramProxies);
        fields.add(proxyEditor, c); c.gridy++;
        c.insets = new Insets(16, 0, 6, 0);
        fields.add(DesktopStyle.label("Тестирование VPN", 14, true, "text"), c); c.gridy++;
        JTextArea vpnHint = DesktopStyle.paragraph("Добавьте VPN-адрес или ссылку-подписку. Lighthouse не импортирует ключи и не включает VPN. Публичные подписки ведут к узлам неизвестных операторов; не используйте их для банкинга. Двойной щелчок копирует ссылку.");
        vpnHint.setRows(4); c.insets = new Insets(0, 0, 8, 0); fields.add(vpnHint, c); c.gridy++;
        ConfigEditor vpnEditor = new ConfigEditor(dialogStyle, "VPN", "tcp://host:port или https://…", false, vpnProfiles);
        fields.add(vpnEditor, c); c.gridy++;
        DesktopStyle.ActionButton baseline = new DesktopStyle.ActionButton("Последний скан — обычный уровень", false);
        baseline.setEnabled(lastReport != null && lastReport.complete && lastReport.level != ScanReport.Level.NO_CONNECTION
            && lastReport.level != ScanReport.Level.ALLOWLIST_SUSPECTED && lastReport.level != ScanReport.Level.INCOMPLETE);
        baseline.setToolTipText("Первый полный скан с обычной доступностью запоминается автоматически. Позже эталон меняется только по вашему запросу.");
        baseline.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(dialog, "Запомнить последний завершённый скан как обычное состояние этой сети?\nПоследующие сканы будут сравниваться с ним.",
                "Обычный уровень доступности", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            try { NetworkBaseline.replace(dataDir, lastReport); JOptionPane.showMessageDialog(dialog, "Эталон сохранён для следующих сканирований."); }
            catch (Exception failure) { JOptionPane.showMessageDialog(dialog, "Не удалось сохранить эталон.", "Ошибка", JOptionPane.ERROR_MESSAGE); }
        });
        fields.add(baseline, c);
        c.gridy++; c.insets = new Insets(8, 0, 8, 0);
        DesktopStyle.ActionButton archive = new DesktopStyle.ActionButton("Архив логов и динамика", false);
        archive.setToolTipText("Логи хранятся локально и выгружаются только по вашему запросу.");
        archive.addActionListener(e -> showArchive());
        fields.add(archive, c); c.gridy++;
        JButton updates = new JButton("Проверить обновления");
        updates.addActionListener(e -> updater.check(true)); fields.add(updates,c);
        JPanel buttons = DesktopStyle.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        DesktopStyle.ActionButton cancel = new DesktopStyle.ActionButton("Отмена", false);
        DesktopStyle.ActionButton save = new DesktopStyle.ActionButton("Сохранить", true);
        cancel.addActionListener(e -> dialog.dispose());
        save.addActionListener(e -> {
            preferences.put("theme", theme.getSelectedIndex() == 0 ? "dark" : theme.getSelectedIndex() == 1 ? "light" : "system");
            preferences.put("profile", depth.getSelectedIndex() == 0 ? "deep" : "quick");
            detector404Token = new String(detectorTokenField.getPassword()).trim();
            telegramProxies = proxyEditor.values(); vpnProfiles = vpnEditor.values();
            dashboard.setBypassConfigurations(telegramProxies, vpnProfiles);
            dashboard.setDark(resolveDarkMode()); dashboard.configureProfile(savedProfile()); dialog.dispose();
        });
        buttons.add(cancel); buttons.add(save); panel.add(buttons, BorderLayout.SOUTH);
        dialogStyle.apply(panel);
        dialog.setContentPane(panel); dialog.getRootPane().setDefaultButton(save);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setSize(610, 700); dialog.setMinimumSize(new Dimension(560, 640));
        dialog.setLocationRelativeTo(frame); dialog.setVisible(true);
    }

    private void showArchive() {
        List<ScanArchive.Entry> entries = ScanArchive.loadScans(dataDir, Integer.MAX_VALUE);
        List<ScanArchive.Change> changes = ScanArchive.loadChanges(dataDir, Integer.MAX_VALUE);
        JDialog dialog = new JDialog(frame, "Архив Lighthouse", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JPanel root = DesktopStyle.transparent(new BorderLayout(0, 14));
        root.setOpaque(true); root.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        JPanel heading = DesktopStyle.transparent(new BorderLayout(8, 4));
        heading.add(DesktopStyle.label("Локальные логи и динамика", 22, true, "text"), BorderLayout.NORTH);
        heading.add(DesktopStyle.paragraph("Логи остаются на этом ПК и никуда не отправляются. Недоступность не доказывает блокировку: возможен обычный сбой."), BorderLayout.CENTER);
        root.add(heading, BorderLayout.NORTH);

        DefaultListModel<String> model = new DefaultListModel<>();
        DateTimeFormatter time = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
        for (ScanArchive.Entry entry : entries) model.addElement(time.format(entry.timestamp) + "  ·  "
            + NetworkAssessment.stateTitle(entry.level) + "  ·  " + entry.available + "/" + entry.total + " доступно"
            + (entry.complete ? "" : "  ·  неполный"));
        JList<String> scanList = new JList<>(model); scanList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        scanList.setFixedCellHeight(34); if (!entries.isEmpty()) scanList.setSelectedIndex(0);
        JScrollPane scansPane = new JScrollPane(scanList); scansPane.setBorder(BorderFactory.createTitledBorder("Сохранённые сканы"));

        StringBuilder dynamics = new StringBuilder();
        if (changes.isEmpty()) dynamics.append(entries.size() < 2
            ? "Для сравнения нужны два полных скана одной сети."
            : "Изменений между полными сканами не обнаружено.");
        for (ScanArchive.Change change : changes) dynamics.append(time.format(change.timestamp)).append("  ·  ")
            .append(change.description()).append(" — ").append(change.serviceName).append("\n")
            .append("    ").append(change.category).append('\n');
        JTextArea changeText = DesktopStyle.paragraph(dynamics.toString());
        changeText.setRows(12); changeText.setCaretPosition(0); changeText.setEditable(false);
        JScrollPane changesPane = new JScrollPane(changeText); changesPane.setBorder(BorderFactory.createTitledBorder("Динамика доступности"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scansPane, changesPane);
        split.setResizeWeight(.52); split.setBorder(null); root.add(split, BorderLayout.CENTER);

        JPanel buttons = DesktopStyle.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        DesktopStyle.ActionButton close = new DesktopStyle.ActionButton("Закрыть", false);
        DesktopStyle.ActionButton export = new DesktopStyle.ActionButton("Выгрузить выбранный лог", true);
        export.setEnabled(!entries.isEmpty());
        scanList.addListSelectionListener(e -> export.setEnabled(scanList.getSelectedIndex() >= 0));
        close.addActionListener(e -> dialog.dispose());
        export.addActionListener(e -> {
            int index = scanList.getSelectedIndex(); if (index < 0) return;
            try { exportLog(ScanArchive.resolveLog(dataDir, entries.get(index).logFile)); }
            catch (Exception failure) { JOptionPane.showMessageDialog(dialog, "Лог отсутствует или повреждён.", "Ошибка", JOptionPane.ERROR_MESSAGE); }
        });
        buttons.add(close); buttons.add(export); root.add(buttons, BorderLayout.SOUTH);
        new DesktopStyle(dashboard.isDark()).apply(root);
        dialog.setContentPane(root); dialog.setSize(820, 680); dialog.setMinimumSize(new Dimension(680, 540));
        dialog.setLocationRelativeTo(frame); dialog.setVisible(true);
    }

    private record CompletedScan(ScanReport report, Path log, String warning) {}

    private void startScan() {
        lastLog = null;
        lastReport = null;
        synchronized (persistenceGate) { savingReport = false; }
        ScanProfile profile = savedProfile();
        dashboard.beginScan(profile, TargetCatalog.defaults().size());
        SwingWorker<CompletedScan, ProbeResult> worker = new SwingWorker<>() {
            @Override protected CompletedScan doInBackground() {
                DiagnosticScanner scanner = new DiagnosticScanner(3500, 6, profile);
                DeviceInfo device = DesktopDevice.collect();
                ScanReport report = scanner.scan(device, TargetCatalog.defaults(),
                    ScanArchive.loadPrevious(dataDir, device), NetworkBaseline.load(dataDir, device), this::publish,
                    stage -> SwingUtilities.invokeLater(() -> {
                        if (!isCancelled() && currentWorker == this) dashboard.setStage(stage);
                    }));
                if (!isCancelled()) {
                    List<NetworkCheckResult> checks = new java.util.ArrayList<>(report.networkChecks);
                    checks.addAll(DesktopNetworkActivity.capture("end"));
                    checks.add(Detector404Client.fetch(detector404Token, 6000));
                    checks.addAll(TelegramProxyCheck.testAll(new ArrayList<>(telegramProxies), 5000));
                    checks.addAll(VpnEndpointCheck.testAll(new ArrayList<>(vpnProfiles), 5000, report.device.vpnDetected));
                    checks.add(DesktopZapretDiagnostics.collect(report.results));
                    List<String> recommendations = BypassAnalytics.enrichRecommendations(report.level, report.recommendations, checks);
                    report = new ScanReport(report.scanId, report.startedAt, java.time.Instant.now(), report.device,
                        report.publicIp, report.networkOrigin, report.level, report.results, checks,
                        report.newlyUnavailable, report.recovered, recommendations, report.profile,
                        report.expectedTargets, report.complete, report.assessment);
                }
                // Cancellation stops measurements, but must not race a committed report save.
                synchronized (persistenceGate) {
                    if (isCancelled()) throw new CancellationException();
                    savingReport = true;
                }
                SwingUtilities.invokeLater(() -> {
                    if (!isCancelled() && currentWorker == this) dashboard.savingReport();
                });
                Path log = null;
                String warning = null;
                Path target = null;
                try {
                    Files.createDirectories(dataDir.resolve("logs"));
                    String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                        .withZone(ZoneId.systemDefault()).format(report.finishedAt);
                    target = dataDir.resolve("logs/lighthouse-" + stamp + ".json");
                    Files.write(target, JsonLog.encode(report)); log = target;
                } catch (Exception e) {
                    warning = "Результат получен, но сохранить лог не удалось: " + e.getMessage();
                }
                if (log != null && !isCancelled()) {
                    StringBuilder issues = new StringBuilder();
                    try { History.save(dataDir.resolve("last-state.tsv"), report); }
                    catch (Exception e) { issues.append("последнее состояние; "); }
                    try { NetworkBaseline.rememberFirstNormal(dataDir, report); }
                    catch (Exception e) { issues.append("обычный уровень; "); }
                    try { ScanArchive.record(dataDir, report, dataDir.relativize(target).toString()); }
                    catch (Exception e) { issues.append("архив динамики; "); }
                    if (issues.length() > 0) warning = "Лог сохранён, но не обновлены: " + issues;
                }
                return new CompletedScan(report, log, warning);
            }
            @Override protected void process(List<ProbeResult> chunks) {
                if (!isCancelled() && currentWorker == this) dashboard.accept(chunks);
            }
            @Override protected void done() {
                if (currentWorker != this) return;
                try {
                    CompletedScan completed = get(); lastLog = completed.log(); lastReport = completed.report();
                    dashboard.finish(completed.report(), completed.log(), completed.warning());
                } catch (CancellationException cancelled) {
                    dashboard.cancelScan();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); dashboard.failScan("Ожидание результата было прервано.");
                } catch (ExecutionException failed) {
                    dashboard.failScan(String.valueOf(failed.getCause()));
                } finally {
                    currentWorker = null;
                    synchronized (persistenceGate) { savingReport = false; }
                }
            }
        };
        currentWorker = worker; worker.execute();
    }

    private void exportLog() {
        if (lastLog == null) return;
        exportLog(lastLog);
    }

    private void exportLog(Path source) {
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("Экспорт диагностического лога");
        chooser.setFileFilter(new FileNameExtensionFilter("Диагностический лог JSON", "json"));
        chooser.setSelectedFile(source.getFileName().toFile());
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path destination = chooser.getSelectedFile().toPath();
        if (!destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
            destination = destination.resolveSibling(destination.getFileName() + ".json");
        if (destination.toAbsolutePath().normalize().equals(source.toAbsolutePath().normalize())) return;
        if (Files.exists(destination) && JOptionPane.showConfirmDialog(frame, "Заменить существующий файл?",
            "Файл уже существует", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        final Path target = destination;
        dashboard.export.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING); return null;
            }
            @Override protected void done() {
                try {
                    get(); JOptionPane.showMessageDialog(frame, "Лог сохранён. Он содержит сведения об устройстве и сети.",
                        "Экспорт завершён", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(frame, "Не удалось экспортировать лог: " + e.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
                } finally { dashboard.export.setEnabled(currentWorker == null && lastLog != null); }
            }
        }.execute();
    }

    private static final class ConfigEditor extends JPanel {
        private final JPanel rows = DesktopStyle.transparent(new GridLayout(0, 1, 0, 8));
        private final List<ConfigRow> values = new ArrayList<>();
        private final String prefix, hint;
        private final boolean secret;
        private final DesktopStyle palette;

        ConfigEditor(DesktopStyle palette, String prefix, String hint, boolean secret, List<NamedConfiguration> saved) {
            super(new BorderLayout(0, 8)); setOpaque(false);
            this.palette = palette; this.prefix = prefix; this.hint = hint; this.secret = secret;
            if (saved != null) for (NamedConfiguration item : saved) addRow(item);
            add(rows, BorderLayout.CENTER);
            DesktopStyle.ActionButton add = new DesktopStyle.ActionButton("Добавить " + prefix.toLowerCase(java.util.Locale.ROOT), false);
            add.addActionListener(e -> showAddForm());
            add(add, BorderLayout.NORTH); palette.apply(this);
        }

        private void addRow(NamedConfiguration saved) {
            if (saved == null || values.size() >= 12) return;
            JPanel row = DesktopStyle.transparent(new BorderLayout(8, 0));
            row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(95, 112, 123, 100)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
            JPanel description = DesktopStyle.transparent(new GridLayout(0, 1, 0, 3));
            description.add(DesktopStyle.label(saved.name, 14, true, "text"));
            description.add(DesktopStyle.label(saved.safePreview(), 12, false, "muted"));
            row.add(description, BorderLayout.CENTER);
            DesktopStyle.ActionButton remove = new DesktopStyle.ActionButton("", false);
            remove.setIcon(new TrashIcon(palette.muted)); remove.setToolTipText("Удалить " + saved.name);
            remove.getAccessibleContext().setAccessibleName("Удалить " + saved.name);
            ConfigRow item = new ConfigRow(row, saved); values.add(item);
            row.setToolTipText("Двойной щелчок или правая кнопка: скопировать ссылку");
            JPopupMenu menu = new JPopupMenu();
            JMenuItem copy = new JMenuItem("Скопировать ссылку");
            copy.addActionListener(e -> copy(saved)); menu.add(copy);
            row.setComponentPopupMenu(menu); description.setComponentPopupMenu(menu);
            row.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (event.getButton() == java.awt.event.MouseEvent.BUTTON1 && event.getClickCount() == 2) copy(saved);
                }
            });
            remove.addActionListener(e -> {
                int answer = JOptionPane.showConfirmDialog(this, "Точно удалить «" + saved.name + "»?",
                    "Удаление настройки", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) return;
                values.remove(item); rows.remove(row); rows.revalidate(); rows.repaint();
            });
            row.add(remove, BorderLayout.EAST); rows.add(row); palette.apply(row); rows.revalidate(); rows.repaint();
        }

        private void showAddForm() {
            if (values.size() >= 12) {
                JOptionPane.showMessageDialog(this, "Можно добавить не больше 12 записей.", "Список заполнен", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JTextField name = new JTextField();
            JTextField endpoint = secret ? new JPasswordField() : new JTextField(); endpoint.setToolTipText(hint);
            JPanel form = DesktopStyle.transparent(new GridLayout(0, 1, 0, 6));
            form.add(DesktopStyle.label("Название", 13, true, "text")); form.add(name);
            form.add(DesktopStyle.label(secret ? "Ссылка прокси" : "Ссылка или адрес сервера", 13, true, "text")); form.add(endpoint);
            palette.apply(form);
            while (true) {
                int answer = JOptionPane.showConfirmDialog(this, form, "Добавить " + prefix.toLowerCase(java.util.Locale.ROOT),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                if (answer != JOptionPane.OK_OPTION) return;
                char[] secretValue = endpoint instanceof JPasswordField password ? password.getPassword() : null;
                String enteredEndpoint = (secretValue == null ? endpoint.getText() : new String(secretValue)).trim();
                if (secretValue != null) java.util.Arrays.fill(secretValue, '\0');
                String enteredName = name.getText().trim();
                if (!enteredName.isBlank() && !enteredEndpoint.isBlank()) {
                    addRow(new NamedConfiguration(enteredName, enteredEndpoint)); return;
                }
                JOptionPane.showMessageDialog(this, "Заполните название и ссылку.", "Не все поля заполнены", JOptionPane.WARNING_MESSAGE);
            }
        }

        List<NamedConfiguration> values() {
            List<NamedConfiguration> result = new ArrayList<>();
            for (ConfigRow row : values) result.add(row.configuration);
            return result;
        }

        private void copy(NamedConfiguration configuration) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new java.awt.datatransfer.StringSelection(configuration.value), null);
            JOptionPane.showMessageDialog(this, "Ссылка скопирована.", "Буфер обмена", JOptionPane.INFORMATION_MESSAGE);
        }

        private record ConfigRow(JPanel panel, NamedConfiguration configuration) { }
    }

    private static final class TrashIcon implements Icon {
        private final Color color;
        TrashIcon(Color color) { this.color = color; }
        @Override public int getIconWidth() { return 18; }
        @Override public int getIconHeight() { return 18; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create(); g.setColor(color); g.setStroke(new BasicStroke(1.8f));
            g.drawLine(x + 4, y + 5, x + 14, y + 5); g.drawLine(x + 7, y + 2, x + 11, y + 2);
            g.drawRoundRect(x + 5, y + 6, 8, 10, 2, 2); g.drawLine(x + 8, y + 8, x + 8, y + 14); g.drawLine(x + 10, y + 8, x + 10, y + 14);
            g.dispose();
        }
    }
}
