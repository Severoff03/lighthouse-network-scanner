package ru.lighthouse.desktop;

import ru.lighthouse.core.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ru.lighthouse.desktop.DesktopStyle.*;

/** Presentation only: no network, filesystem, preferences or native window access. */
final class DesktopDashboard extends JPanel {
    final ActionButton scan = new ActionButton("Начать сканирование", true);
    final ActionButton export = new ActionButton("Экспорт лога", false);
    final ActionButton settings = new ActionButton("Настройки", false);
    final JTextField search = new SearchField();
    final JComboBox<String> category = new JComboBox<>();
    final JComboBox<String> state = new JComboBox<>(new String[]{"Все статусы", "Работают", "Ограничены", "Не работают"});
    final ResultsModel model = new ResultsModel();
    final JTable table = new JTable(model);
    final TableRowSorter<ResultsModel> sorter = new TableRowSorter<>(model);
    private final JLabel title = label("Узнайте, как работает ваша сеть", 25, true, "text");
    private final JTextArea subtitle = paragraph("Проверим сервисы, DNS и маршруты. Результаты помогут понять, где возникли проблемы.");
    private final JLabel stage = label("Готово к сканированию", 12, false, "muted");
    private final JLabel timing = label("", 12, false, "muted");
    private final JLabel profileLabel = label("", 12, false, "muted");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel shown = label("", 12, false, "muted");
    private final JLabel emptyTitle = label("Здесь появятся результаты", 19, true, "text");
    private final JLabel emptyHint = label("Нажмите «Начать сканирование» — сервисы будут появляться по мере проверки.", 13, false, "muted");
    private final JPanel serviceBody = transparent(new CardLayout());
    private final JPanel pages = transparent(new CardLayout());
    private final JPanel categoryGrid = transparent(new GridLayout(0, 2, 12, 12));
    private final JPanel statusContent = new WidthPanel(new BorderLayout());
    private final JTextArea details = paragraph("Выберите сервис в таблице, чтобы увидеть адрес, HTTP-код и подробности замеров.");
    private final JScrollPane detailScroll = scroll(details);
    private final Surface serviceContext = new Surface(new BorderLayout(10, 6), 14);
    private final JLabel serviceContextTitle = label("", 18, true, "text");
    private final JTextArea serviceContextText = paragraph("");
    private final JTextArea diagnostics = paragraph("");
    private final JTextArea security = paragraph("");
    private final JPanel conclusions = new WidthPanel(new BorderLayout());
    private final Surface hero = new Surface(new BorderLayout(0, 10), 16);
    private final List<JLabel> filterLabels = new ArrayList<>();
    private final ActionButton[] tabs = new ActionButton[5];
    private final StatCard[] stats = new StatCard[4];
    private final Timer clock = new Timer(1000, e -> updateClock());
    private DesktopStyle palette;
    private ScanReport report;
    private ScanReport.Level level;
    private ScanProfile profile = ScanProfile.DEEP;
    private int expected = TargetCatalog.defaults().size();
    private long startedNanos;
    private boolean scanning;
    private boolean compact;
    private String currentPage = "categories";
    private ProbeResult.Status activeStatus;
    private List<NamedConfiguration> telegramProxies = List.of();
    private List<NamedConfiguration> vpnProfiles = List.of();

    DesktopDashboard(String version, Image logo, boolean dark) {
        super(new BorderLayout(0, 14));
        setBorder(BorderFactory.createEmptyBorder(18, 24, 12, 24));
        setFont(font(Font.PLAIN, 13));
        JPanel top = transparent(new BorderLayout(0, 14));
        JPanel brand = transparent(new BorderLayout(12, 0));
        if (logo != null) brand.add(new JLabel(new ImageIcon(logo.getScaledInstance(44, 44, Image.SCALE_SMOOTH))), BorderLayout.WEST);
        JPanel brandText = transparent(null);
        brandText.setLayout(new BoxLayout(brandText, BoxLayout.Y_AXIS));
        brandText.add(label("Lighthouse", 21, true, "text"));
        brandText.add(label("Диагностика доступности интернета", 12, false, "muted"));
        brand.add(brandText, BorderLayout.CENTER);
        JPanel header = transparent(new BorderLayout());
        JPanel headerActions = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        headerActions.add(scan); headerActions.add(export); headerActions.add(settings);
        header.add(brand, BorderLayout.WEST); header.add(headerActions, BorderLayout.EAST);
        top.add(header, BorderLayout.NORTH);

        JPanel overview = transparent(new BorderLayout(0, 12));
        JPanel heroText = transparent(new BorderLayout(0, 5));
        heroText.add(title, BorderLayout.NORTH); heroText.add(subtitle, BorderLayout.CENTER);
        subtitle.setRows(1);
        hero.add(heroText, BorderLayout.CENTER);
        JPanel activity = transparent(new BorderLayout(0, 6));
        JPanel activityLabels = transparent(new BorderLayout(12, 0));
        activityLabels.add(stage, BorderLayout.CENTER); activityLabels.add(timing, BorderLayout.EAST);
        activity.add(activityLabels, BorderLayout.NORTH);
        progress.setPreferredSize(new Dimension(100, 3)); progress.setBorderPainted(false);
        progress.setUI(new javax.swing.plaf.basic.BasicProgressBarUI());
        activity.add(progress, BorderLayout.SOUTH); hero.add(activity, BorderLayout.SOUTH);
        overview.add(hero, BorderLayout.NORTH);
        JPanel counters = transparent(new GridLayout(1, 4, 12, 0));
        String[] statNames = {"Работают", "Ограничены", "Не работают", "Проверено сервисов"};
        for (int i = 0; i < stats.length; i++) {
            final int filter = i < 3 ? i + 1 : 0;
            final int kind = i;
            stats[i] = new StatCard(statNames[i], i);
            stats[i].addActionListener(e -> {
                search.setText(""); category.setSelectedIndex(0); state.setSelectedIndex(filter);
                if (kind < 3) showStatusPage(ProbeResult.Status.values()[kind]); else selectPage("services");
            });
            counters.add(stats[i]);
        }
        overview.add(counters, BorderLayout.CENTER); top.add(overview, BorderLayout.CENTER); add(top, BorderLayout.NORTH);

        JPanel workspace = transparent(new BorderLayout(0, 10));
        JPanel navigation = transparent(new BorderLayout(10, 0));
        JPanel buttons = transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        String[] names = {"Аналитика", "Диагностика", "Безопасность", "Рекомендации", "Все сервисы"};
        String[] ids = {"categories", "diagnostics", "security", "conclusions", "services"};
        for (int i = 0; i < tabs.length; i++) {
            final String id = ids[i];
            tabs[i] = new ActionButton(names[i], false);
            tabs[i].addActionListener(e -> selectPage(id)); buttons.add(tabs[i]);
        }
        navigation.add(buttons, BorderLayout.WEST); navigation.add(profileLabel, BorderLayout.EAST);
        workspace.add(navigation, BorderLayout.NORTH);
        pages.add(buildServices(), "services");
        JPanel categories = new WidthPanel(new BorderLayout()); categories.add(categoryGrid, BorderLayout.NORTH);
        pages.add(scroll(categories), "categories");
        Surface diagnosticCard = new Surface(new BorderLayout(0, 12), 18);
        diagnosticCard.add(label("Сеть и дополнительные проверки", 17, true, "text"), BorderLayout.NORTH);
        diagnostics.setFont(font(Font.PLAIN, 14)); diagnosticCard.add(scroll(diagnostics), BorderLayout.CENTER);
        pages.add(diagnosticCard, "diagnostics");
        Surface securityCard = new Surface(new BorderLayout(0, 12), 18);
        securityCard.add(label("Фоновая сетевая активность", 17, true, "text"), BorderLayout.NORTH);
        security.setFont(font(Font.PLAIN, 14)); securityCard.add(scroll(security), BorderLayout.CENTER);
        pages.add(securityCard, "security");
        pages.add(scroll(conclusions), "conclusions");
        JPanel statusPage = new WidthPanel(new BorderLayout()); statusPage.add(statusContent, BorderLayout.NORTH);
        pages.add(scroll(statusPage), "status"); workspace.add(pages, BorderLayout.CENTER); add(workspace, BorderLayout.CENTER);

        JPanel footer = transparent(new BorderLayout(16, 0));
        footer.add(label("Недоступность сервиса сама по себе не доказывает блокировку.", 11, false, "muted"), BorderLayout.WEST);
        footer.add(label("Lighthouse " + version + "  /  Разработчик Mothman", 11, false, "muted"), BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        export.setEnabled(false); export.setToolTipText("Сохранить полный JSON-лог для последующего анализа");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        category.addActionListener(e -> filter()); state.addActionListener(e -> filter());
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) updateDetails(); });
        sorter.addRowSorterListener(e -> updateShown());
        configureProfile(ScanProfile.DEEP); updateStats(); updateShown(); showPendingPages(); selectPage("categories"); setDark(dark);
    }

    void setBypassConfigurations(List<NamedConfiguration> proxies, List<NamedConfiguration> vpns) {
        telegramProxies = proxies == null ? List.of() : new ArrayList<>(proxies);
        vpnProfiles = vpns == null ? List.of() : new ArrayList<>(vpns);
    }

    private JPanel buildServices() {
        Surface card = new Surface(new BorderLayout(0, 12), 16);
        JPanel filters = transparent(new BorderLayout(12, 0));
        search.setToolTipText("Поиск по названию, категории или домену; Ctrl+F");
        search.getAccessibleContext().setAccessibleName("Поиск сервиса");
        JPanel searchGroup = transparent(new BorderLayout(0, 5));
        JLabel searchLabel = label("ПОИСК СЕРВИСА ИЛИ ДОМЕНА", 10, true, "muted");
        filterLabels.add(searchLabel);
        searchLabel.setLabelFor(search); searchGroup.add(searchLabel, BorderLayout.NORTH); searchGroup.add(search, BorderLayout.CENTER);
        filters.add(searchGroup, BorderLayout.CENTER);
        category.addItem("Все категории");
        category.addItem(ReportAnalytics.NEWLY_BLOCKED);
        TargetCatalog.defaults().stream().map(t -> t.category).distinct().forEach(category::addItem);
        category.setPreferredSize(new Dimension(238, 38)); state.setPreferredSize(new Dimension(162, 38));
        category.getAccessibleContext().setAccessibleName("Категория сервисов");
        state.getAccessibleContext().setAccessibleName("Статус доступности");
        JPanel choices = transparent(new BorderLayout(10, 0));
        choices.add(labeled("КАТЕГОРИЯ", category), BorderLayout.CENTER);
        choices.add(labeled("СТАТУС", state), BorderLayout.EAST);
        filters.add(choices, BorderLayout.EAST);
        JPanel serviceTop = transparent(new BorderLayout(0, 10));
        JPanel contextHeading = transparent(new BorderLayout(10, 0));
        contextHeading.add(serviceContextTitle, BorderLayout.CENTER);
        ActionButton backToAnalytics = new ActionButton("← К аналитике", false);
        backToAnalytics.addActionListener(e -> selectPage("categories")); contextHeading.add(backToAnalytics, BorderLayout.EAST);
        serviceContext.add(contextHeading, BorderLayout.NORTH); serviceContext.add(serviceContextText, BorderLayout.CENTER);
        serviceContext.setVisible(false); serviceTop.add(serviceContext, BorderLayout.NORTH); serviceTop.add(filters, BorderLayout.CENTER);
        card.add(serviceTop, BorderLayout.NORTH);

        table.setRowSorter(sorter); sorter.setSortsOnUpdates(true);
        table.setRowHeight(42); table.setShowGrid(false); table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFont(font(Font.PLAIN, 13)); table.setDefaultRenderer(Object.class, new ResultRenderer());
        table.setDefaultRenderer(Long.class, new ResultRenderer());
        table.setDefaultRenderer(ProbeResult.Status.class, new ResultRenderer());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(100, 34));
        table.getTableHeader().setDefaultRenderer(new HeaderRenderer());
        int[] widths = {165, 224, 150, 80, 80, 88, 88};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            table.getColumnModel().getColumn(i).setMinWidth(i < 3 ? 100 : 58);
        }
        table.setToolTipText("Выберите строку для подробностей; нажмите заголовок для сортировки");
        JScrollPane tableScroll = scroll(table);
        tableScroll.setColumnHeaderView(table.getTableHeader());
        serviceBody.add(tableScroll, "table");
        JPanel empty = transparent(new GridBagLayout());
        JPanel message = transparent(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.gridy = 0; c.insets = new Insets(5, 0, 5, 0);
        message.add(new SignalMark(), c); c.gridy++; message.add(emptyTitle, c); c.gridy++; message.add(emptyHint, c);
        empty.add(message); serviceBody.add(empty, "empty"); card.add(serviceBody, BorderLayout.CENTER);
        JPanel bottom = transparent(new BorderLayout(0, 7));
        bottom.add(shown, BorderLayout.NORTH); details.setRows(2);
        detailScroll.setPreferredSize(new Dimension(100, 22));
        bottom.add(detailScroll, BorderLayout.CENTER); card.add(bottom, BorderLayout.SOUTH);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control F"), "search");
        getActionMap().put("search", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                selectPage("services"); search.requestFocusInWindow(); search.selectAll();
            }
        });
        search.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "clear");
        search.getActionMap().put("clear", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { search.setText(""); }
        });
        return card;
    }

    private JPanel labeled(String text, JComponent child) {
        JPanel panel = transparent(new BorderLayout(0, 5)); JLabel name = label(text, 10, true, "muted");
        filterLabels.add(name);
        name.setLabelFor(child); panel.add(name, BorderLayout.NORTH); panel.add(child, BorderLayout.CENTER); return panel;
    }

    void setDark(boolean dark) {
        palette = new DesktopStyle(dark); palette.apply(this); setBackground(palette.background);
        progress.setBackground(palette.raised); progress.setForeground(palette.accent);
        applySignalColors();
        table.getTableHeader().repaint(); revalidate(); repaint();
    }

    boolean isDark() { return palette.dark; }
    @Override public void doLayout() {
        boolean small = getHeight() > 0 && getHeight() < 740;
        if (small != compact) {
            compact = small;
            subtitle.setVisible(!small);
            title.setFont(font(Font.BOLD, small ? 21 : 25));
            hero.setBorder(BorderFactory.createEmptyBorder(small ? 10 : 16, 16, small ? 10 : 16, 16));
            setBorder(BorderFactory.createEmptyBorder(small ? 12 : 18, 24, 12, 24));
            ((BorderLayout) getLayout()).setVgap(small ? 10 : 14);
            for (JLabel label : filterLabels) label.setVisible(!small);
            for (StatCard stat : stats) stat.setPreferredSize(new Dimension(160, small ? 54 : 76));
            table.setRowHeight(small ? 34 : 42);
        }
        super.doLayout();
    }
    void configureProfile(ScanProfile value) {
        profile = value;
        profileLabel.setText(value == ScanProfile.DEEP ? "Глубокий · до 5 минут" : "Быстрый · 1 проход");
        profileLabel.setToolTipText("Глубину проверки можно изменить в настройках");
    }

    void beginScan(ScanProfile value, int total) {
        configureProfile(value); expected = total; scanning = true; report = null; level = null;
        model.clear(); table.clearSelection(); search.setText(""); category.setSelectedIndex(0); state.setSelectedIndex(0);
        scan.setText("Остановить сканирование"); export.setEnabled(false); settings.setEnabled(false);
        title.setText("Сканирование сети"); applySignalColors();
        subtitle.setText(value == ScanProfile.DEEP ? "Несколько проходов помогут отличить устойчивый сбой от случайного. Можно продолжать просматривать результаты."
            : "Проверяем доступность сервисов за один проход. Результаты появляются по мере готовности.");
        setStage("Собираем информацию о подключении…"); progress.setValue(0); progress.setIndeterminate(true);
        startedNanos = System.nanoTime(); updateClock(); clock.start();
        updateStats(); showPendingPages(); updateDetails(); filter(); selectPage("categories");
    }

    void setStage(String value) { stage.setText(value); stage.setToolTipText(value); }

    void savingReport() {
        scan.setEnabled(false); scan.setText("Сохраняем результат…");
        setStage("Сохраняем диагностический лог…");
    }

    void accept(List<ProbeResult> results) {
        if (!scanning) return;
        for (ProbeResult result : results) model.upsert(result);
        updateStats(); updateShown(); updateDetails();
        if ("categories".equals(currentPage)) showCategories();
        if ("status".equals(currentPage) && activeStatus != null) showStatusPage(activeStatus);
    }

    void finish(ScanReport value, Path log, String saveWarning) {
        stopActivity(); report = value; level = value.level; expected = value.expectedTargets;
        model.replace(value.results); configureProfile(value.profile); updateStats(); updateShown(); updateDetails();
        title.setText(levelName(value.level)); applySignalColors();
        List<String> overview = ReportAnalytics.plainOverview(value);
        subtitle.setText(overview.isEmpty() ? "Проверка завершена." : overview.get(0));
        String finished = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(value.finishedAt);
        setStage(saveWarning != null ? saveWarning : (value.complete ? "Сканирование завершено" : "Частичный результат")
            + " в " + finished + (log == null ? " · лог не сохранён" : " · лог сохранён"));
        long seconds = Math.max(0, Duration.between(value.startedAt, value.finishedAt).getSeconds());
        timing.setText(duration(seconds) + "  ·  " + value.results.size() + " / " + expected + " сервисов");
        // This line represents coverage only after completion, never fictitious scan progress.
        progress.setValue(expected == 0 ? 0 : Math.min(100, value.results.size() * 100 / expected));
        export.setEnabled(log != null); showCategories(); showDiagnostics(value); showSecurity(value); showConclusions(value, log, saveWarning);
        if ("status".equals(currentPage) && activeStatus != null) showStatusPage(activeStatus);
        if (value.assessment.state == NetworkAssessment.State.RED) {
            search.setText(""); category.setSelectedIndex(0); state.setSelectedIndex(1); selectPage("services");
            setStage("Признаки белых списков · показаны ответившие сервисы · " + (log == null ? "лог не сохранён" : "лог сохранён"));
        }
    }

    void cancelScan() {
        stopActivity(); level = null; title.setText("Сканирование остановлено"); applySignalColors();
        subtitle.setText("Полученные замеры оставлены в таблице. Это неполный результат: общий вывод о сети не сформирован.");
        setStage("Проверка остановлена · лог этой проверки не сохранён"); updateShown(); showCategories();
        diagnostics.setText("Проверка остановлена. Дополнительная диагностика не завершена.");
        security.setText("Проверка остановлена. Снимок процессов не завершён.");
        setConclusionNotice("Сканирование остановлено", "Для итоговых выводов и экспорта лога запустите новую проверку и дождитесь завершения.");
    }

    void failScan(String message) {
        stopActivity(); title.setText("Не удалось завершить проверку"); title.setForeground(palette.bad);
        subtitle.setText("Это ошибка диагностики, а не подтверждение отсутствия интернета. Можно запустить проверку повторно.");
        setStage("Ошибка сканирования"); details.setText(message); updateShown(); showCategories();
        diagnostics.setText("Диагностика прервана:\n\n" + message);
        security.setText("Диагностика фоновой активности прервана:\n\n" + message);
        setConclusionNotice("Нет итогового отчёта", "Проверка прервана. Текст ошибки доступен во вкладке «Диагностика».");
    }

    void dispose() { clock.stop(); progress.setIndeterminate(false); }

    private void stopActivity() {
        scanning = false; clock.stop(); progress.setIndeterminate(false);
        scan.setText("Начать сканирование"); scan.setEnabled(true); settings.setEnabled(true);
    }

    private void updateClock() {
        long seconds = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000_000L);
        timing.setText(duration(seconds) + "  ·  " + model.getRowCount() + " / " + expected + " сервисов");
    }

    private void updateStats() {
        int[] counts = new int[3];
        for (ProbeResult result : model.results) counts[NetworkAssessment.observedStatus(result).ordinal()]++;
        for (int i = 0; i < 3; i++) stats[i].setValue(model.getRowCount() == 0 && !scanning && report == null ? "—" : String.valueOf(counts[i]), "");
        stats[3].setValue(String.valueOf(model.getRowCount()), " / " + expected);
        if (scanning) updateClock();
    }

    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        String selectedCategory = (String) category.getSelectedItem();
        int selectedStatus = state.getSelectedIndex();
        sorter.setRowFilter(new RowFilter<>() {
            @Override public boolean include(Entry<? extends ResultsModel, ? extends Integer> entry) {
                ProbeResult r = model.results.get(entry.getIdentifier());
                boolean categoryMatches = category.getSelectedIndex() == 0
                    || ReportAnalytics.NEWLY_BLOCKED.equals(selectedCategory) && report != null && report.newlyUnavailable.contains(r.target.name)
                    || r.target.category.equals(selectedCategory);
                return categoryMatches
                    && (selectedStatus == 0 || NetworkAssessment.observedStatus(r).ordinal() == selectedStatus - 1)
                    && (r.target.name + " " + r.target.host + " " + r.target.category).toLowerCase(Locale.ROOT).contains(query);
            }
        });
        updateServiceContext(selectedCategory); updateShown(); updateDetails();
    }

    private void updateServiceContext(String selectedCategory) {
        if (category.getSelectedIndex() == 0 || selectedCategory == null) { serviceContext.setVisible(false); return; }
        int total = 0, up = 0, unstable = 0, down = 0;
        for (ProbeResult result : model.results) {
            boolean matches = ReportAnalytics.NEWLY_BLOCKED.equals(selectedCategory)
                ? report != null && report.newlyUnavailable.contains(result.target.name)
                : selectedCategory.equals(result.target.category);
            if (!matches) continue;
            total++; ProbeResult.Status status = NetworkAssessment.observedStatus(result);
            if (status == ProbeResult.Status.AVAILABLE) up++; else if (status == ProbeResult.Status.DEGRADED) unstable++; else down++;
        }
        serviceContextTitle.setText(ReportAnalytics.categoryIcon(selectedCategory) + "  " + selectedCategory);
        serviceContextText.setText(ReportAnalytics.categoryDescription(selectedCategory) + "\n\nРаботают: " + up
            + "  ·  Ограничены: " + unstable + "  ·  Не работают: " + down + "  ·  Всего: " + total);
        serviceContext.setVisible(true); serviceContext.revalidate();
    }

    private void updateShown() {
        int visible = table.getRowCount(), total = model.getRowCount();
        shown.setText(total == 0 ? "Замеров пока нет · DNS / TCP / HTTPS и ping" : "Показано " + visible + " из " + total
            + " сервисов · время в мс · «—» — нет успешного замера");
        ((CardLayout) serviceBody.getLayout()).show(serviceBody, visible == 0 ? "empty" : "table");
        emptyTitle.setText(total > 0 ? "Ничего не найдено" : scanning ? "Первые результаты уже в пути" : "Здесь появятся результаты");
        emptyHint.setText(total > 0 ? "Попробуйте другой запрос, категорию или статус." : scanning
            ? "Проверяем подключение и ждём ответы сервисов. Это может занять несколько минут."
            : "Нажмите «Начать сканирование», чтобы проверить сеть.");
    }

    private void updateDetails() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= table.getRowCount()) {
            details.setText("Выберите сервис в таблице, чтобы увидеть адрес, HTTP-код и подробности замеров.");
            detailScroll.setPreferredSize(new Dimension(100, 22)); detailScroll.revalidate(); return;
        }
        int modelRow = table.convertRowIndexToModel(row);
        if (modelRow < 0 || modelRow >= model.results.size()) return;
        ProbeResult r = model.results.get(modelRow);
        StringBuilder text = new StringBuilder(r.target.name).append("  ·  ").append(r.target.host)
            .append("  ·  итог: ").append(statusName(NetworkAssessment.observedStatus(r))).append("  ·  замеров: ").append(r.attempts())
            .append("\nIP: ").append(blank(r.resolvedIp)).append("  ·  HTTP: ").append(r.httpCode < 0 ? "—" : r.httpCode)
            .append("  ·  ").append(r.target.probeKind == ServiceTarget.ProbeKind.DNS ? "DNS-запрос" : "TCP-соединение")
            .append(": ").append(ms(r.tcpMs));
        if (r.error != null && !r.error.isBlank()) text.append("\nПодробности: ").append(r.error);
        if (!r.samples.isEmpty()) {
            for (int i = 0; i < r.samples.size(); i++) {
                ProbeResult sample = r.samples.get(i);
                text.append("\nЗамер ").append(i + 1).append(": ").append(statusName(sample.status))
                    .append(" · ping ").append(ms(sample.pingMs)).append(" · DNS ").append(ms(sample.dnsMs))
                    .append(" · TCP/DNS ").append(ms(sample.tcpMs)).append(" · HTTPS ").append(ms(sample.httpsMs));
            }
        }
        details.setText(text.toString()); details.setCaretPosition(0);
        detailScroll.setPreferredSize(new Dimension(100, 36)); detailScroll.revalidate();
    }

    void selectPage(String id) {
        currentPage = id;
        String[] ids = {"categories", "diagnostics", "security", "conclusions", "services"};
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(ids[i].equals(id));
        if ("categories".equals(id)) showCategories();
        ((CardLayout) pages.getLayout()).show(pages, id);
    }

    private void showPendingPages() {
        diagnostics.setText(scanning ? "Расширенные данные о подключении, DNS, маршрутах и протоколах появятся после завершения сканирования."
            : "Запустите сканирование, чтобы получить информацию о подключении и дополнительные сетевые проверки.");
        security.setText(scanning ? "Снимки процессов с сетевыми соединениями появятся после завершения сканирования."
            : "Запустите сканирование, чтобы увидеть приложения и процессы с фоновой сетевой активностью.");
        setConclusionNotice(scanning ? "Собираем данные" : "От измерений — к понятным выводам",
            scanning ? "Итоговая оценка будет доступна после проверки. Текущие результаты можно посмотреть во вкладках «Сервисы» и «Категории»."
                : "После сканирования здесь появятся объяснение состояния сети, изменения доступности и рекомендации.");
    }

    private void showCategories() {
        categoryGrid.removeAll();
        List<ReportAnalytics.CategorySummary> values = ReportAnalytics.byCategory(model.results);
        if (values.isEmpty()) {
            Surface empty = new Surface(new BorderLayout(0, 8), 20);
            empty.add(label("Категории сервисов", 18, true, "text"), BorderLayout.NORTH);
            empty.add(paragraph("Чаты, банки, облака, игры и другие категории появятся по мере получения замеров."), BorderLayout.CENTER);
            categoryGrid.add(empty);
        }
        if (report != null) {
            Surface card = new Surface(new BorderLayout(0, 8), 16);
            card.add(label(ReportAnalytics.categoryIcon(ReportAnalytics.NEWLY_BLOCKED) + "  " + ReportAnalytics.NEWLY_BLOCKED, 15, true, "text"), BorderLayout.NORTH);
            if (report.newlyUnavailable.isEmpty()) {
                card.add(paragraph("Новых недоступных сервисов не обнаружено. Список формируется при сравнении полных сканов одной сети."), BorderLayout.CENTER);
            } else {
                card.add(paragraph(report.newlyUnavailable.size() + " сервисов стали недоступны по сравнению с предыдущим полным сканом этой сети. Это признак для проверки, а не доказательство блокировки."), BorderLayout.CENTER);
                ActionButton open = new ActionButton("Показать сервисы", false);
                open.addActionListener(e -> openCategory(ReportAnalytics.NEWLY_BLOCKED));
                card.add(open, BorderLayout.SOUTH);
            }
            categoryGrid.add(card);
        }
        for (ReportAnalytics.CategorySummary value : values) {
            Surface card = new Surface(new BorderLayout(0, 8), 16);
            card.add(label(ReportAnalytics.categoryIcon(value.category) + "  " + value.category, 15, true, "text"), BorderLayout.NORTH);
            JTextArea caption = paragraph(value.available + " из " + value.total + " доступны  ·  " + value.readableStatus()
                + "\nОграничены: " + value.degraded + "  ·  Не работают: " + value.unavailable);
            card.add(caption, BorderLayout.CENTER);
            ActionButton open = new ActionButton("Показать сервисы", false);
            open.addActionListener(e -> openCategory(value.category));
            card.add(open, BorderLayout.SOUTH); categoryGrid.add(card);
        }
        if (palette != null) palette.apply(categoryGrid);
        categoryGrid.revalidate(); categoryGrid.repaint();
    }

    private void openCategory(String name) {
        search.setText(""); category.setSelectedItem(name); state.setSelectedIndex(0); selectPage("services");
        serviceContext.revalidate(); serviceContext.repaint();
    }

    private void showStatusPage(ProbeResult.Status status) {
        activeStatus = status; search.setText(""); category.setSelectedIndex(0); state.setSelectedIndex(status.ordinal() + 1);
        List<ProbeResult> matching = new ArrayList<>();
        for (ProbeResult result : model.results) if (NetworkAssessment.observedStatus(result) == status) matching.add(result);
        Map<String, List<ProbeResult>> groups = new LinkedHashMap<>();
        for (ProbeResult result : matching) groups.computeIfAbsent(result.target.category, ignored -> new ArrayList<>()).add(result);

        JPanel body = new WidthPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.gridy = 0; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 0, 12, 0);
        Surface heading = new Surface(new BorderLayout(12, 8), 18);
        JPanel headingText = transparent(new GridLayout(0, 1, 0, 5));
        headingText.add(label(pluralStatus(status) + " · " + matching.size(), 23, true, "text"));
        headingText.add(label("Сервисы сгруппированы по категориям.", 13, false, "muted"));
        heading.add(headingText, BorderLayout.CENTER);
        ActionButton back = new ActionButton("← К аналитике", false); back.addActionListener(e -> selectPage("categories"));
        heading.add(back, BorderLayout.EAST); body.add(heading, c); c.gridy++;
        if (groups.isEmpty()) {
            body.add(textCard("Нет сервисов", "В текущих результатах нет сервисов с выбранным состоянием."), c);
        } else for (Map.Entry<String, List<ProbeResult>> group : groups.entrySet()) {
            Surface card = new Surface(new BorderLayout(0, 8), 16);
            card.add(label(ReportAnalytics.categoryIcon(group.getKey()) + "  " + group.getKey() + " · " + group.getValue().size(), 17, true, "text"), BorderLayout.NORTH);
            StringBuilder lines = new StringBuilder();
            group.getValue().sort(java.util.Comparator.comparing(value -> value.target.name, String.CASE_INSENSITIVE_ORDER));
            for (ProbeResult result : group.getValue()) {
                if (lines.length() > 0) lines.append('\n');
                lines.append("• ").append(result.target.name).append("  ·  ").append(result.target.host)
                    .append("  ·  ").append(result.httpsMs >= 0 ? result.httpsMs + " мс" : result.tcpMs >= 0 ? result.tcpMs + " мс" : "нет ответа");
            }
            card.add(paragraph(lines.toString()), BorderLayout.CENTER); body.add(card, c); c.gridy++;
        }
        statusContent.removeAll(); statusContent.add(body, BorderLayout.NORTH);
        if (palette != null) palette.apply(statusContent);
        statusContent.revalidate(); statusContent.repaint(); selectPage("status");
    }

    private static String pluralStatus(ProbeResult.Status status) {
        return switch (status) { case AVAILABLE -> "Работают"; case DEGRADED -> "Ограничены"; case UNAVAILABLE -> "Не работают"; };
    }

    private void showDiagnostics(ScanReport r) {
        StringBuilder text = new StringBuilder("ПОДКЛЮЧЕНИЕ\n\n");
        text.append("Устройство: ").append(r.device.deviceName).append("\nСистема: ").append(r.device.os)
            .append("\nСеть: ").append(blank(r.networkOrigin)).append("\nПодключение: ").append(blank(r.device.networkName))
            .append("\nВнешний IP: ").append(blank(r.publicIp)).append("\nЛокальный IP: ").append(blank(r.device.localIp))
            .append("\nMAC: ").append(blank(r.device.macAddress)).append("\nVPN: ")
            .append(r.device.vpnDetected ? "обнаружен" : "не обнаружен (не гарантирует отсутствие)")
            .append("\nСистемы обхода: ").append(blank(r.device.circumvention)).append("\n");
        List<NetworkCheckResult> checks = new ArrayList<>(r.device.observations);
        checks.addAll(r.networkChecks);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (NetworkCheckResult check : checks) {
            if (!seen.add(check.id)) continue;
            if ("Безопасность устройства".equals(check.category)) continue;
            text.append("\n\n").append(check.name)
                .append("  ·  ").append(check.status == NetworkCheckResult.Status.OK ? "ОК" : check.status == NetworkCheckResult.Status.WARNING ? "Внимание" : "Сбой")
                .append("\n").append(check.summary).append("\n");
            check.metrics.forEach((key, value) -> text.append("  ").append(key).append(": ").append(value).append("\n"));
        }
        diagnostics.setText(text.toString()); diagnostics.setCaretPosition(0);
    }

    private void showSecurity(ScanReport r) {
        StringBuilder text = new StringBuilder("ПРОЦЕССЫ И СЕТЕВЫЕ СОЕДИНЕНИЯ\n\n")
            .append("Lighthouse показывает наблюдаемую сетевую активность, но не объявляет программу шпионской автоматически.\n")
            .append("Фоновые службы, обновления и синхронизация обычно создают соединения.\n");
        List<NetworkCheckResult> checks = new ArrayList<>(r.device.observations); checks.addAll(r.networkChecks);
        java.util.Set<String> seen = new java.util.HashSet<>(); int count = 0;
        for (NetworkCheckResult check : checks) {
            if (!"Безопасность устройства".equals(check.category) || !seen.add(check.id)) continue;
            count++; text.append("\n\n").append(check.name)
                .append("  ·  ").append(check.status == NetworkCheckResult.Status.WARNING ? "Проверить" : "Наблюдение")
                .append("\n").append(check.summary).append('\n');
            check.metrics.forEach((key, value) -> text.append("  ").append(key).append(": ").append(value).append('\n'));
        }
        if (count == 0) text.append("\nСведения о процессах недоступны.");
        security.setText(text.toString()); security.setCaretPosition(0);
    }

    private void setConclusionNotice(String heading, String body) {
        JPanel content = transparent(new GridLayout(0, 1, 0, 12)); content.add(textCard(heading, body));
        conclusions.removeAll(); conclusions.add(content, BorderLayout.NORTH);
        if (palette != null) palette.apply(conclusions); conclusions.revalidate(); conclusions.repaint();
    }

    private void showConclusions(ScanReport r, Path log, String warning) {
        JPanel content = transparent(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 0, 12, 0);
        if (r.assessment.state == NetworkAssessment.State.RED) {
            content.add(textCard("Доступны при текущих ограничениях", ReportAnalytics.availableServicesText(r)), c); c.gridy++;
        }
        content.add(bypassCard(r.networkChecks), c); c.gridy++;
        content.add(textCard("Что можно сделать", r.recommendations.isEmpty() ? "Дополнительных рекомендаций нет." : bullets(r.recommendations)), c); c.gridy++;
        content.add(textCard("Что это значит", bullets(ReportAnalytics.plainOverview(r))), c); c.gridy++;
        String changes = "Новые сбои: " + (r.newlyUnavailable.isEmpty() ? "не обнаружены" : String.join(", ", r.newlyUnavailable))
            + "\nВосстановились: " + (r.recovered.isEmpty() ? "не обнаружены" : String.join(", ", r.recovered))
            + "\nСравнение с сохранённым результатом; смена сети или VPN может повлиять на картину.";
        content.add(textCard("Изменения доступности", changes), c); c.gridy++;
        content.add(textCard("Отчёт для последующего анализа", (log == null ? "Лог не сохранён." : "Сохранён: " + log)
            + (warning == null ? "" : "\n" + warning)
            + "\nЛог содержит IP, сведения об устройстве и сети. Перед передачей другому человеку проверьте его содержимое."), c);
        conclusions.removeAll(); conclusions.add(content, BorderLayout.NORTH);
        palette.apply(conclusions); conclusions.revalidate(); conclusions.repaint();
    }

    private Surface textCard(String heading, String body) {
        Surface card = new Surface(new BorderLayout(0, 10), 20);
        card.add(label(heading, 17, true, "text"), BorderLayout.NORTH);
        card.add(paragraph(body), BorderLayout.CENTER); return card;
    }

    private Surface bypassCard(List<NetworkCheckResult> checks) {
        Surface card = new Surface(new BorderLayout(0, 10), 20);
        card.add(label("Методы обхода", 17, true, "text"), BorderLayout.NORTH);
        JPanel rows = transparent(new GridLayout(0, 1, 0, 8));
        List<NetworkCheckResult> values = BypassAnalytics.results(checks);
        boolean mass = BypassAnalytics.massVpnUnavailable(checks);
        if (values.isEmpty()) rows.add(paragraph("Настроенные методы обхода не проверялись."));
        for (NetworkCheckResult check : values) {
            JPanel row = transparent(new BorderLayout(10, 2));
            boolean highlighted = mass && check.id.matches("vpn_profile_\\d+")
                && check.status == NetworkCheckResult.Status.OK;
            Color signal = check.status == NetworkCheckResult.Status.OK ? palette.good
                : check.status == NetworkCheckResult.Status.WARNING ? palette.warning : palette.bad;
            JLabel dot = label("●", 18, true, "text"); dot.setForeground(signal); row.add(dot, BorderLayout.WEST);
            JPanel words = transparent(new GridLayout(0, 1, 0, 2));
            JLabel name = label((highlighted ? "РАБОТАЕТ · " : "") + check.name, 14, true, "text");
            if (highlighted) name.setForeground(palette.good);
            JTextArea summary = paragraph(check.summary); words.add(name); words.add(summary); row.add(words, BorderLayout.CENTER);
            row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(highlighted ? palette.good : new Color(palette.muted.getRed(), palette.muted.getGreen(), palette.muted.getBlue(), 70), highlighted ? 2 : 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            NamedConfiguration configuration = configurationFor(check.id);
            if (configuration != null) installCopy(row, configuration);
            rows.add(row);
        }
        card.add(rows, BorderLayout.CENTER); return card;
    }

    private NamedConfiguration configurationFor(String checkId) {
        List<NamedConfiguration> source;
        String prefix;
        if (checkId != null && checkId.startsWith("telegram_proxy_")) {
            source = telegramProxies; prefix = "telegram_proxy_";
        } else if (checkId != null && checkId.startsWith("vpn_profile_")) {
            source = vpnProfiles; prefix = "vpn_profile_";
        } else return null;
        try {
            int wanted = Integer.parseInt(checkId.substring(prefix.length()));
            int current = 0;
            for (NamedConfiguration item : source) if (item != null && !item.value.isBlank() && ++current == wanted) return item;
        } catch (RuntimeException ignored) { }
        return null;
    }

    private void installCopy(Component component, NamedConfiguration configuration) {
        if (component instanceof JComponent target) {
            target.setToolTipText("Удерживайте или нажмите правой кнопкой, чтобы скопировать ссылку");
            JPopupMenu menu = new JPopupMenu(); JMenuItem copy = new JMenuItem("Скопировать ссылку");
            copy.addActionListener(e -> copyConfiguration(configuration)); menu.add(copy); target.setComponentPopupMenu(menu);
            Timer hold = new Timer(650, e -> copyConfiguration(configuration)); hold.setRepeats(false);
            target.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) hold.start();
                }
                @Override public void mouseReleased(java.awt.event.MouseEvent event) { hold.stop(); }
                @Override public void mouseExited(java.awt.event.MouseEvent event) { hold.stop(); }
            });
        }
        if (component instanceof Container container)
            for (Component child : container.getComponents()) installCopy(child, configuration);
    }

    private void copyConfiguration(NamedConfiguration configuration) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
            new java.awt.datatransfer.StringSelection(configuration.value), null);
        stage.setText("Ссылка «" + configuration.name + "» скопирована");
    }

    private Color levelColor() {
        if (palette == null) return Color.GRAY;
        if (level == null || level == ScanReport.Level.INCOMPLETE) return palette.text;
        return switch (level) {
            case NORMAL -> palette.good;
            case DEGRADED, SEVERE_RESTRICTIONS -> palette.warning;
            case NO_CONNECTION -> Color.WHITE;
            default -> palette.bad;
        };
    }
    private void applySignalColors() {
        boolean black = level == ScanReport.Level.NO_CONNECTION;
        hero.putClientProperty("blackout", black);
        Color signal = null;
        if (level == ScanReport.Level.NORMAL) signal = new Color(14, 82, 57);
        else if (level == ScanReport.Level.DEGRADED || level == ScanReport.Level.SEVERE_RESTRICTIONS) signal = new Color(94, 68, 13);
        else if (level == ScanReport.Level.ALLOWLIST_SUSPECTED) signal = new Color(105, 24, 38);
        else if (black) signal = Color.BLACK;
        hero.putClientProperty("signalColor", signal);
        Color onSignal = level == null || level == ScanReport.Level.INCOMPLETE ? palette.text : Color.WHITE;
        title.setForeground(onSignal); subtitle.setForeground(level == null || level == ScanReport.Level.INCOMPLETE ? palette.text : new Color(230, 235, 240));
        stage.setForeground(level == null || level == ScanReport.Level.INCOMPLETE ? palette.muted : new Color(205, 214, 220));
        timing.setForeground(level == null || level == ScanReport.Level.INCOMPLETE ? palette.muted : new Color(205, 214, 220));
        progress.setForeground(level == null || level == ScanReport.Level.INCOMPLETE ? palette.accent : levelColor());
        hero.repaint();
    }
    static String levelName(ScanReport.Level level) {
        return NetworkAssessment.stateTitle(level);
    }
    static String statusName(ProbeResult.Status status) {
        return switch (status) { case AVAILABLE -> "Работает"; case DEGRADED -> "Ограничен"; case UNAVAILABLE -> "Не работает"; };
    }
    private static String blank(String value) { return value == null || value.isBlank() ? "—" : value; }
    private static String ms(long value) { return value < 0 ? "—" : value + " мс"; }
    private static String duration(long seconds) { return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    private static String bullets(List<String> items) { return "• " + String.join("\n\n• ", items); }

    static final class ResultsModel extends AbstractTableModel {
        private final String[] columns = {"Сервис", "Категория", "Статус", "Ping", "DNS", "TCP/DNS", "HTTPS"};
        final List<ProbeResult> results = new ArrayList<>();
        private final Map<String, Integer> indexes = new LinkedHashMap<>();
        void clear() { results.clear(); indexes.clear(); fireTableDataChanged(); }
        void replace(List<ProbeResult> values) {
            results.clear(); indexes.clear();
            for (ProbeResult r : values) {
                Integer row = indexes.get(r.target.id);
                if (row == null) { indexes.put(r.target.id, results.size()); results.add(r); }
                else results.set(row, r);
            }
            fireTableDataChanged();
        }
        void upsert(ProbeResult r) {
            Integer index = indexes.get(r.target.id);
            if (index == null) { int row = results.size(); indexes.put(r.target.id, row); results.add(r); fireTableRowsInserted(row, row); }
            else { results.set(index, r); fireTableRowsUpdated(index, index); }
        }
        @Override public int getRowCount() { return results.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column < 2 ? String.class : column == 2 ? ProbeResult.Status.class : Long.class; }
        @Override public Object getValueAt(int row, int column) {
            ProbeResult r = results.get(row);
            return switch (column) {
                case 0 -> r.target.name; case 1 -> r.target.category; case 2 -> NetworkAssessment.observedStatus(r);
                case 3 -> number(r.pingMs); case 4 -> number(r.dnsMs); case 5 -> number(r.tcpMs); default -> number(r.httpsMs);
            };
        }
        private Long number(long value) { return value < 0 ? null : value; }
    }

    private final class ResultRenderer extends DefaultTableCellRenderer {
        private ProbeResult.Status badge;
        ResultRenderer() { putClientProperty("html.disable", true); }
        @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            setFont(font(column == 0 ? Font.BOLD : Font.PLAIN, 13));
            badge = value instanceof ProbeResult.Status status ? status : null;
            setHorizontalAlignment(column >= 3 ? CENTER : LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            setText(badge != null ? "" : value == null ? "—" : String.valueOf(value));
            if (palette != null) {
                setBackground(selected ? palette.raised : palette.surface);
                setForeground(column == 1 || value == null ? palette.muted : palette.text);
                if (focus) setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette.accent),
                    BorderFactory.createEmptyBorder(0, 9, 0, 9)));
            }
            setToolTipText(value == null ? "Успешного замера нет" : badge == null ? String.valueOf(value) : statusName(badge));
            return this;
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (palette == null) return;
            Graphics2D g = (Graphics2D) graphics.create(); smooth(g);
            if (badge != null) {
                Color color = badge == ProbeResult.Status.AVAILABLE ? palette.good : badge == ProbeResult.Status.DEGRADED ? palette.warning : palette.bad;
                String text = statusName(badge); int w = Math.min(getWidth() - 10, g.getFontMetrics().stringWidth(text) + 28);
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 24));
                g.fillRoundRect(6, (getHeight() - 26) / 2, w, 26, 12, 12);
                g.setColor(color); g.fillOval(14, getHeight() / 2 - 3, 6, 6);
                g.drawString(text, 26, (getHeight() - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent());
            }
            g.setColor(palette.border); g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1); g.dispose();
        }
    }

    private final class HeaderRenderer extends DefaultTableCellRenderer {
        HeaderRenderer() { putClientProperty("html.disable", true); }
        @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            String arrow = "";
            if (!sorter.getSortKeys().isEmpty() && sorter.getSortKeys().get(0).getColumn() == column)
                arrow = sorter.getSortKeys().get(0).getSortOrder() == SortOrder.ASCENDING ? " ↑" : " ↓";
            setText(value + arrow); setFont(font(Font.BOLD, 12)); setHorizontalAlignment(column >= 3 ? CENTER : LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            if (palette != null) { setBackground(palette.surface); setForeground(palette.muted); }
            return this;
        }
    }

    private final class StatCard extends ActionButton {
        private final String caption;
        private final int kind;
        private String value = "—", suffix = "";
        StatCard(String caption, int kind) {
            super("", false); this.caption = caption; this.kind = kind;
            setPreferredSize(new Dimension(160, 76)); setToolTipText("Показать: " + caption.toLowerCase(Locale.ROOT));
        }
        void setValue(String value, String suffix) {
            this.value = value; this.suffix = suffix;
            getAccessibleContext().setAccessibleName(caption + ": " + value + suffix); repaint();
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); if (palette == null) return;
            Graphics2D g = (Graphics2D) graphics.create(); smooth(g);
            Color color = kind == 0 ? palette.good : kind == 1 ? palette.warning : kind == 2 ? palette.bad : palette.text;
            int valueSize = getHeight() < 65 ? 24 : 29;
            g.setFont(font(Font.BOLD, valueSize)); int width = g.getFontMetrics().stringWidth(value);
            g.setFont(font(Font.PLAIN, 14)); int suffixWidth = g.getFontMetrics().stringWidth(suffix);
            int x = (getWidth() - width - suffixWidth) / 2;
            int baseline = getHeight() / 2 + 1;
            g.setFont(font(Font.BOLD, valueSize)); g.setColor(color); g.drawString(value, x, baseline);
            g.setFont(font(Font.PLAIN, 14)); g.setColor(palette.muted); g.drawString(suffix, x + width, baseline);
            g.setFont(font(Font.PLAIN, 12)); g.drawString(caption, (getWidth() - g.getFontMetrics().stringWidth(caption)) / 2, baseline + (getHeight() < 65 ? 18 : 22)); g.dispose();
        }
    }

    private final class SignalMark extends JComponent {
        SignalMark() { setPreferredSize(new Dimension(72, 58)); }
        @Override protected void paintComponent(Graphics graphics) {
            if (palette == null) return;
            Graphics2D g = (Graphics2D) graphics.create(); smooth(g); g.setStroke(new BasicStroke(2));
            g.setColor(palette.border); g.drawOval(12, 3, 48, 48); g.drawOval(22, 13, 28, 28);
            g.setColor(palette.accent); g.drawLine(36, 27, 52, 11); g.fillOval(32, 23, 8, 8); g.dispose();
        }
    }

    private final class SearchField extends JTextField {
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (palette == null || !getText().isEmpty() || hasFocus()) return;
            Graphics2D g = (Graphics2D) graphics.create(); smooth(g); g.setColor(palette.muted);
            g.setFont(getFont());
            g.drawString("Поиск сервиса…", getInsets().left,
                (getHeight() - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent());
            g.dispose();
        }
    }
}
