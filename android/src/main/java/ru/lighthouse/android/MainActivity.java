package ru.lighthouse.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.provider.Settings;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.widget.GridLayout;

import ru.lighthouse.core.DeviceInfo;
import ru.lighthouse.core.DiagnosticScanner;
import ru.lighthouse.core.History;
import ru.lighthouse.core.JsonLog;
import ru.lighthouse.core.NetworkCheckResult;
import ru.lighthouse.core.ProbeResult;
import ru.lighthouse.core.ReportAnalytics;
import ru.lighthouse.core.ScanReport;
import ru.lighthouse.core.TargetCatalog;
import ru.lighthouse.core.ScanProfile;
import ru.lighthouse.core.ScanArchive;
import ru.lighthouse.core.NetworkAssessment;
import ru.lighthouse.core.NetworkBaseline;
import ru.lighthouse.core.NamedConfiguration;
import ru.lighthouse.core.BypassAnalytics;
import ru.lighthouse.core.TelegramProxyCheck;
import ru.lighthouse.core.VpnEndpointCheck;
import ru.lighthouse.core.DefaultConnections;

import java.io.File;
import java.net.Inet4Address;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int SAVE_LOG = 1001;
    private static final int SAVE_ERROR = 1002;
    private static final int RADIO_PERMISSIONS = 1003;
    private boolean scanAfterPermission;
    private static final int CYAN = Color.rgb(88, 151, 208);
    private static final int PURPLE = Color.rgb(124, 77, 255);
    private static final int GREEN = Color.rgb(53, 211, 153);
    private static final int AMBER = Color.rgb(255, 183, 77);
    private static final int RED = Color.rgb(255, 91, 105);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService archiveExecutor = Executors.newSingleThreadExecutor();
    private UpdateManager updateManager;
    private ConnectivityManager.NetworkCallback updateNetworkCallback;
    private final Runnable updateTimer = new Runnable() { public void run() {
        if (!alive) return; checkAutomaticallyIfDue(); ui.postDelayed(this,UpdateManager.AUTOMATIC_CHECK_INTERVAL_MS);
    }};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Future<?> activeScan;
    private int scanGeneration;
    private boolean scanning;
    private boolean radioScanning;
    private boolean radioSniffAfterPermission;
    private long scanStarted;
    private String scanPhase = "", latestService = "";
    private final Map<String, ProbeResult> currentResults = new LinkedHashMap<>();
    private int completedRequests;
    private final List<ru.lighthouse.core.ServiceTarget> targets = TargetCatalog.defaults();
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!alive || !scanning) return;
            refreshProgress();
            ui.postDelayed(this, 1000);
        }
    };
    private TextView headline, details, emptyState, overview, recommendations, categoryTitle, recommendationsTitle, bypassTitle, vpnTitle;
    private TextView availableCount, degradedCount, unavailableCount;
    private LinearLayout heroCard, diagnosticsList, bypassList, vpnList;
    private GridLayout categoryTiles;
    private Button scan, save, settings, radioScan;
    private Button scanTab, historyTab, settingsTab, radioTab;
    private RadioPage liveRadioPage;
    private ScrollView controlPage;
    private FrameLayout toolsPage;
    private LinearLayout toolsHome;
    private IpScannerPage ipScannerPage;
    private TraceRoutePage tracePage;
    private static final int EXPORT_RADIO = 1005;
    private ScrollView scanPage, categoryPage;
    private LinearLayout categoryPageContent;
    private HistoryPage historyPage;
    private SettingsPage settingsPage;
    private String currentTab = "scan";
    private ProgressBar progress;
    private boolean dark,terminal;private TextView terminalHeader;
    private int background, surface, primary, secondary;
    private int checkedCount, available, degraded, unavailable;
    private volatile boolean alive = true;
    private byte[] lastLog;
    private byte[] pendingExportLog;
    private ScanReport lastReport;
    private TextView availableInRestrictions;
    private String lastName = "lighthouse-log.json";
    private volatile String detector404Token = "";
    private volatile List<NamedConfiguration> telegramProxies = DefaultConnections.telegramProxies();
    private volatile List<NamedConfiguration> vpnProfiles = DefaultConnections.vpnSubscriptions();
    private volatile UpdateManager.UpdateInfo pendingUpdate;
    private boolean awaitingInstallPermission;
    private String automaticDialogVersion = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiLanguage.init(this);
        updateManager = new UpdateManager(this);
        pendingUpdate = UpdateManager.loadPending(this);
        terminal=TerminalTheme.enabled(this);dark = isDark();
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        setPalette();
        applySystemBars();
        View content = buildUi();
        UiLanguage.apply(content);if(terminal)TerminalTheme.apply(content);
        setContentView(content);
        ui.post(new Runnable(){private int frame;@Override public void run(){if(!alive)return;if(terminal&&terminalHeader!=null){String[] cursor={"|","/","-","\\"};terminalHeader.setText("[ "+cursor[frame++%cursor.length]+" ]  LIGHTHOUSE // "+(UiLanguage.isRussian()?"РАДИО + СЕТЬ":"RADIO + NETWORK"));}ui.postDelayed(this,700);}});
        content.requestApplyInsets();
        bindActions();
        scheduleBackgroundUpdateCheck();
        ui.postDelayed(updateTimer,3000);
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        updateNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { ui.postDelayed(MainActivity.this::checkAutomaticallyIfDue,3000); }
        };
        if(connectivity!=null)connectivity.registerNetworkCallback(new android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),updateNetworkCallback);
        requestNotificationPermission();
        if (state != null) {
            String draftProfile = state.getString("draftProfile");
            if (draftProfile != null) {
                try { settingsPage.restoreDraft(getSharedPreferences("settings",MODE_PRIVATE).getString("theme","dark"), ScanProfile.valueOf(draftProfile), state.getBoolean("draftRadio", radioEnabled()));
                    settingsPage.restoreLanguage(state.getString("draftLanguage",getSharedPreferences("settings",MODE_PRIVATE).getString("language","en"))); }
                catch (IllegalArgumentException ignored) { /* Corrupt transient state falls back to persisted settings. */ }
            }
            String restoredTab = state.getString("currentTab", state.getBoolean("settingsOpen", false) ? "settings" : "scan");
            if ("settings".equals(restoredTab)) showSettingsTab();
            else if ("history".equals(restoredTab)) showHistoryTab();
            else if ("radio".equals(restoredTab)) showRadioTab();
            else if ("tools".equals(restoredTab) || "control".equals(restoredTab)) showToolsTab();
        }
    }

    private void bindActions() {
        scan.setOnClickListener(v -> { if (scanning) cancelScan(); else startScan(); });
        save.setOnClickListener(v -> saveLog());
        settings.setOnClickListener(v -> showSettingsTab());
    }

    @Override protected void onResume() {
        super.onResume();
        RadioRuntime.get(this).foreground(true);
        if (settingsPage != null) settingsPage.updateUsageAccess(AndroidAppTraffic.allowed(this));
        if (awaitingInstallPermission && canInstallPackages()) {
            awaitingInstallPermission = false;
            downloadPendingUpdate();
        } else if (pendingUpdate != null && !pendingUpdate.displayVersion().equals(automaticDialogVersion) && !isFinishing()) {
            automaticDialogVersion = pendingUpdate.displayVersion();
            showUpdateDialog(pendingUpdate);
        } else ui.postDelayed(this::checkAutomaticallyIfDue,3000);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(18));
        root.setBackgroundColor(background);
        root.addView(buildHeader());
        Button history = actionButton("История проверок", surface, primary);
        history.setOnClickListener(v -> showHistoryTab()); root.addView(history);
        root.addView(buildHero(), margins(-1, -2, 0, 0, 16, 0, 14));
        root.addView(buildActions(), margins(-1, dp(54), 0, 0, 0, 0, 16));
        root.addView(buildStats(), margins(-1, dp(82), 0, 0, 0, 0, 20));

        categoryTitle = text("Аналитика по категориям", 19, primary, Typeface.BOLD);
        root.addView(categoryTitle, margins(-1, -2, 0, 2, 0, 2, 9));
        categoryTiles = new GridLayout(this); categoryTiles.setColumnCount(2); categoryTiles.setVisibility(View.GONE);
        root.addView(categoryTiles, margins(-1, -2, 0, 0, 0, 0, 18));
        emptyState = text("Результатов пока нет.",
            14, secondary, Typeface.NORMAL);
        emptyState.setGravity(Gravity.CENTER); emptyState.setPadding(dp(18), dp(22), dp(18), dp(22));
        emptyState.setBackground(round(surface, 18)); root.addView(emptyState, margins(-1, -2, 0, 0, 0, 0, 18));

        vpnTitle = text("Доступность VPN-адресов", 19, primary, Typeface.BOLD);
        vpnTitle.setVisibility(View.GONE);
        root.addView(vpnTitle, margins(-1, -2, 0, 2, 0, 2, 9));
        vpnList = new LinearLayout(this); vpnList.setOrientation(LinearLayout.VERTICAL); vpnList.setVisibility(View.GONE);
        root.addView(vpnList, margins(-1, -2, 0, 0, 0, 0, 20));

        root.addView(text("Диагностика сети и безопасности", 19, primary, Typeface.BOLD), margins(-1, -2, 0, 2, 0, 2, 9));
        diagnosticsList = new LinearLayout(this); diagnosticsList.setOrientation(LinearLayout.VERTICAL);
        TextView diagnosticsHint = text("Здесь появятся проверки стабильности, IPv6, DNS и защищённых соединений.",
            14, secondary, Typeface.NORMAL);
        diagnosticsHint.setGravity(Gravity.CENTER); diagnosticsHint.setPadding(dp(18), dp(20), dp(18), dp(20));
        diagnosticsHint.setBackground(round(surface, 18)); diagnosticsList.addView(diagnosticsHint);
        root.addView(diagnosticsList, margins(-1, -2, 0, 0, 0, 0, 20));

        bypassTitle = text("Методы обхода  ⌄", 19, primary, Typeface.BOLD); bypassTitle.setVisibility(View.GONE);
        bypassTitle.setOnClickListener(v -> bypassList.setVisibility(bypassList.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        root.addView(bypassTitle, margins(-1, -2, 0, 2, 0, 2, 9));
        bypassList = new LinearLayout(this); bypassList.setOrientation(LinearLayout.VERTICAL); bypassList.setVisibility(View.GONE);
        root.addView(bypassList, margins(-1, -2, 0, 0, 0, 0, 20));

        recommendationsTitle = text("Рекомендации  ⌄", 19, primary, Typeface.BOLD); recommendationsTitle.setVisibility(View.GONE);
        recommendationsTitle.setOnClickListener(v -> recommendations.setVisibility(recommendations.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        root.addView(recommendationsTitle, margins(-1, -2, 0, 2, 0, 2, 9));
        recommendations = text("", 14, primary, Typeface.NORMAL);
        recommendations.setLineSpacing(0, 1.14f);
        recommendations.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        recommendations.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        recommendations.setLinkTextColor(CYAN);
        recommendations.setPadding(dp(16), dp(15), dp(16), dp(15));
        recommendations.setBackground(round(surface, 18));
        recommendations.setVisibility(View.GONE);
        root.addView(recommendations, margins(-1, -2, 0, 0, 12, 0, 12));


        overview = text("После проверки здесь появится короткое объяснение результата без технических терминов.",
            14, primary, Typeface.NORMAL);
        overview.setLineSpacing(0, 1.14f); overview.setPadding(dp(16), dp(15), dp(16), dp(15));
        overview.setBackground(round(surface, 18));
        
        availableInRestrictions = text("", 14, primary, Typeface.NORMAL);
        availableInRestrictions.setTextIsSelectable(true); availableInRestrictions.setLineSpacing(0, 1.12f);
        availableInRestrictions.setPadding(dp(16), dp(15), dp(16), dp(15));
        availableInRestrictions.setBackground(round(surface, 18)); availableInRestrictions.setVisibility(View.GONE);
        root.addView(availableInRestrictions, margins(-1, -2, 0, 0, 0, 0, 20));

        TextView footer = text("LIGHTHOUSE  " + appVersion() + "   •   MOTHMAN", 11, secondary, Typeface.BOLD);
        footer.setGravity(Gravity.CENTER); footer.setLetterSpacing(.10f);
        footer.setPadding(0, dp(22), 0, dp(6)); 

        scanPage = new ScrollView(this);
        scanPage.setFillViewport(true); scanPage.setBackgroundColor(background); scanPage.addView(root);
        categoryPageContent = new LinearLayout(this); categoryPageContent.setOrientation(LinearLayout.VERTICAL);
        categoryPageContent.setPadding(dp(18), dp(14), dp(18), dp(22));
        categoryPage = new ScrollView(this); categoryPage.setFillViewport(true); categoryPage.setBackgroundColor(background);
        categoryPage.addView(categoryPageContent); categoryPage.setVisibility(View.GONE);
        settingsPage = new SettingsPage(this, dark,
            getSharedPreferences("settings", MODE_PRIVATE).getString("theme", "dark"), scanProfile(), radioEnabled(),
            AndroidAppTraffic.allowed(this), detector404Token, telegramProxies, vpnProfiles, appVersion(),
            updateManager.isConfigured(),
            new SettingsPage.Listener() {
                @Override public void themeChanged(String theme) { applyTheme(theme); }
                @Override public void languageChanged(String language) { applyLanguage(language); }
                @Override public void saveOpenCellIdKey(String key) {
                    getSharedPreferences("tower-map",MODE_PRIVATE).edit().putString("key",key).apply();TowerMap.clearCache();
                    Toast.makeText(MainActivity.this,UiLanguage.text("Ключ OpenCellID сохранён"),Toast.LENGTH_SHORT).show();
                }
                @Override public void save(String theme, String language, ScanProfile profile, boolean radio, String detectorToken,
                                           List<NamedConfiguration> proxies, List<NamedConfiguration> vpns) {
                    saveSettings(theme, language, profile, radio, detectorToken, proxies, vpns);
                }
                @Override public void requestPermissions() { askRadioPermissions(false); }
                @Override public void requestUsageAccess() {
                    try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
                    catch (Exception error) { Toast.makeText(MainActivity.this, UiLanguage.text("Системные настройки недоступны"), Toast.LENGTH_LONG).show(); }
                }
                @Override public void rememberBaseline() { confirmRememberBaseline(); }
                @Override public void exportErrors() { saveErrorLog(); }
                @Override public void checkForUpdates() { MainActivity.this.checkForUpdates(true); }
                @Override public void installUpdate() { installPendingUpdate(); }
            });
        settingsPage.setBackgroundColor(background); settingsPage.setVisibility(View.GONE);
        if (pendingUpdate != null) settingsPage.updateAvailable(pendingUpdate.displayVersion());
        historyPage = new HistoryPage(this, dark, new HistoryPage.Listener() {
            @Override public void refresh(int limit) { refreshHistory(limit); }
            @Override public void export(String relativeLog) { exportStoredLog(relativeLog); }
        });
        historyPage.setBackgroundColor(background); historyPage.setVisibility(View.GONE);
        liveRadioPage = new RadioPage(this, dark, () -> askRadioPermissions(false)); liveRadioPage.setVisibility(View.GONE);
        controlPage = buildControlPage(); controlPage.setVisibility(View.GONE);
        toolsPage = new FrameLayout(this);toolsPage.setVisibility(View.GONE);
        toolsHome=new LinearLayout(this);toolsHome.setOrientation(LinearLayout.VERTICAL);toolsHome.setPadding(dp(18),dp(24),dp(18),dp(12));
        toolsHome.addView(text("Tools",28,primary,Typeface.BOLD));
        Button controlTool=actionButton("Control  ›",surface,primary);controlTool.setOnClickListener(v->showTool("control"));toolsHome.addView(controlTool,margins(-1,dp(80),0,0,12,0,0));
        Button ipTool=actionButton("IP scanner  ›",surface,primary);ipTool.setOnClickListener(v->showTool("ip"));toolsHome.addView(ipTool,margins(-1,dp(80),0,0,12,0,0));
        Button traceTool=actionButton("Trace  ›",surface,primary);traceTool.setOnClickListener(v->showTool("trace"));toolsHome.addView(traceTool,margins(-1,dp(80),0,0,12,0,0));
        toolsPage.addView(toolsHome,new FrameLayout.LayoutParams(-1,-1));
        toolsPage.addView(controlPage,new FrameLayout.LayoutParams(-1,-1));
        ipScannerPage=new IpScannerPage(this,dark,()->showTool("home"));ipScannerPage.setVisibility(View.GONE);toolsPage.addView(ipScannerPage,new FrameLayout.LayoutParams(-1,-1));
        tracePage=new TraceRoutePage(this,dark,()->showTool("home"));tracePage.setVisibility(View.GONE);toolsPage.addView(tracePage,new FrameLayout.LayoutParams(-1,-1));
        installMonitorSettings();
        FrameLayout pages = new FrameLayout(this);
        pages.addView(liveRadioPage, new FrameLayout.LayoutParams(-1,-1));
        pages.addView(toolsPage, new FrameLayout.LayoutParams(-1,-1));
        pages.addView(scanPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(categoryPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(historyPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL); shell.setBackgroundColor(background);
        if(terminal){terminalHeader=text("[ | ]  LIGHTHOUSE // "+(UiLanguage.isRussian()?"РАДИО + СЕТЬ":"RADIO + NETWORK"),11,TerminalTheme.ACCENT,Typeface.BOLD);terminalHeader.setPadding(dp(12),dp(6),dp(12),dp(6));terminalHeader.setBackground(TerminalTheme.panel(this,TerminalTheme.SURFACE));shell.addView(terminalHeader,new LinearLayout.LayoutParams(-1,dp(32)));}else terminalHeader=null;
        shell.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        shell.addView(buildTabs(), new LinearLayout.LayoutParams(-1, dp(64)));
        applySafeArea(shell);
        return shell;
    }

    private View buildTabs() {
        LinearLayout tabs = new LinearLayout(this); tabs.setBackgroundColor(surface);
        scanTab = actionButton(terminal?(UiLanguage.isRussian()?"[ СЕТЬ ]":"[ NET ]"):UiLanguage.isRussian()?"Сеть":"Network",surface,primary); radioTab = actionButton(terminal?(UiLanguage.isRussian()?"[ РАДИО ]":"[ RADIO ]"):"Radio",surface,primary);
        historyTab = actionButton(terminal?(UiLanguage.isRussian()?"[ ИНСТР ]":"[ TOOLS ]"):"Tool",surface,primary);settingsTab = actionButton(terminal?(UiLanguage.isRussian()?"[ НАСТР ]":"[ CONFIG ]"):"Settings",surface,primary);
        if(!terminal){setTabIcon(scanTab,R.drawable.nav_network);setTabIcon(radioTab,R.drawable.nav_radio);
        setTabIcon(historyTab,R.drawable.nav_tools);setTabIcon(settingsTab,R.drawable.nav_settings);}
        scanTab.setOnClickListener(v -> showScanTab());radioTab.setOnClickListener(v -> showRadioTab());
        historyTab.setOnClickListener(v -> showToolsTab());settingsTab.setOnClickListener(v -> showSettingsTab());
        for (Button tab : new Button[]{scanTab,radioTab,historyTab,settingsTab}) {
            tab.setTextSize(10);tab.setPadding(0,dp(5),0,dp(5));tab.setMinWidth(0);tab.setSingleLine(true);
            tab.setGravity(Gravity.CENTER);tab.setCompoundDrawablePadding(dp(2));
            tabs.addView(tab,new LinearLayout.LayoutParams(0,-1,1));
        }
        styleTab(scanTab,true); return tabs;
    }
    private void selectPage(String name) {
        currentTab = name;
        scanPage.setVisibility("scan".equals(name) ? View.VISIBLE : View.GONE);
        historyPage.setVisibility("history".equals(name) ? View.VISIBLE : View.GONE);
        categoryPage.setVisibility(View.GONE);
        settingsPage.setVisibility("settings".equals(name) ? View.VISIBLE : View.GONE);
        liveRadioPage.setVisibility("radio".equals(name) ? View.VISIBLE : View.GONE);
        toolsPage.setVisibility("tools".equals(name) ? View.VISIBLE : View.GONE);
        styleTab(scanTab,"scan".equals(name)||"history".equals(name)); styleTab(radioTab,"radio".equals(name));
        styleTab(historyTab,"tools".equals(name)); styleTab(settingsTab,"settings".equals(name));
    }
    private void showScanTab() { selectPage("scan"); }
    private void showHistoryTab() { selectPage("history"); historyPage.requestRefresh(); }
    private void showSettingsTab() { selectPage("settings"); settingsPage.updateAvailability(scanning,canRememberBaseline()); }
    private void showRadioTab(){liveRadioPage.showLanding();selectPage("radio");}
    private void showToolsTab(){showTool("home");selectPage("tools");}
    private void showTool(String tool){toolsHome.setVisibility("home".equals(tool)?View.VISIBLE:View.GONE);controlPage.setVisibility("control".equals(tool)?View.VISIBLE:View.GONE);ipScannerPage.setVisibility("ip".equals(tool)?View.VISIBLE:View.GONE);tracePage.setVisibility("trace".equals(tool)?View.VISIBLE:View.GONE);}


    private void styleTab(Button tab, boolean selected) {
        tab.setBackground(round(selected ? (terminal?TerminalTheme.ACCENT:CYAN) : surface, 17));
        int color = selected ? (terminal?TerminalTheme.BACKGROUND:Color.rgb(5, 31, 38)) : primary;
        tab.setTextColor(color);
        Drawable icon = tab.getCompoundDrawables()[1]; if(icon != null) icon.setTint(color);
        tab.setAlpha(tab.isEnabled() ? (selected ? 1f : .82f) : .35f); tab.setSelected(selected);
    }

    private void setTabIcon(Button tab,int resource) {
        Drawable icon = getDrawable(resource).mutate();
        icon.setBounds(0,0,dp(21),dp(21)); icon.setTint(primary);
        tab.setCompoundDrawables(null,icon,null,null);
    }

    private Runnable returnFromProbeDetails;
    private Runnable returnToResults;

    private void showProbeDetails(ProbeResult result) {
        boolean fromResults = categoryPage.getVisibility() == View.VISIBLE;
        int previousScroll = fromResults ? categoryPage.getScrollY() : 0;
        Runnable back = fromResults && returnToResults != null ? returnToResults : this::showScanTab;
        returnFromProbeDetails = () -> { back.run(); if (fromResults) categoryPage.post(() -> categoryPage.scrollTo(0,previousScroll)); };
        categoryPageContent.removeAllViews();
        Button close = actionButton("←  Назад к сервисам", surface, primary);
        close.setOnClickListener(v -> leaveProbeDetails());
        categoryPageContent.addView(close, margins(-1,dp(48),0,0,0,0,16));
        ProbeResult.Status observed = NetworkAssessment.observedStatus(result);
        int tone = observed == ProbeResult.Status.AVAILABLE ? GREEN : observed == ProbeResult.Status.DEGRADED ? AMBER : RED;
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text(result.target.name,25,primary,Typeface.BOLD),new LinearLayout.LayoutParams(0,-2,1));
        header.addView(text(shortStatus(observed),14,tone,Typeface.BOLD));
        categoryPageContent.addView(header,margins(-1,-2,0,0,0,0,14));
        LinearLayout identity = detailCard();
        identity.addView(text(result.target.host+":"+result.target.port,16,primary,Typeface.BOLD));
        identity.addView(text(result.target.probeKind+" · "+result.target.category,12,secondary,Typeface.NORMAL));
        categoryPageContent.addView(identity,margins(-1,-2,0,0,0,0,10));
        List<ProbeResult> samples = result.samples.isEmpty() ? java.util.Collections.singletonList(result) : result.samples;
        for (ProbeResult sample : samples) {
            LinearLayout item = detailCard();
            item.addView(text(java.time.Instant.ofEpochMilli(sample.measuredAtMillis).toString(),12,secondary,Typeface.NORMAL));
            item.addView(text(timing(sample),18,primary,Typeface.BOLD));
            item.addView(text("IP: "+(sample.resolvedIp == null ? "не получен" : sample.resolvedIp),13,primary,Typeface.NORMAL));
            if (sample.httpCode >= 0) item.addView(text("HTTP: "+sample.httpCode,13,primary,Typeface.NORMAL));
            if(observed==ProbeResult.Status.UNAVAILABLE&&(sample.tcpMs>=0||sample.pingMs>=0)
                &&!(sample.httpsMs>=0&&sample.httpCode>=200&&sample.httpCode<400))
                item.addView(text("Transport answered, but this web endpoint did not return a successful HTTP response.",12,secondary,Typeface.NORMAL));
            if (sample.probeDetail != null && !sample.probeDetail.isBlank()) item.addView(text(sample.probeDetail,13,primary,Typeface.NORMAL));
            if (sample.error != null && !sample.error.isBlank()) item.addView(text(sample.error,12,tone,Typeface.NORMAL));
            categoryPageContent.addView(item,margins(-1,-2,0,0,0,0,9));
        }
        TextView note = text("Ping, DNS и HTTPS проверяются отдельно. Отсутствие ответа на один протокол не доказывает, что сервис полностью выключен. Сертификат сервера в проверке доступности не проверяется.",12,secondary,Typeface.NORMAL);
        categoryPageContent.addView(note,margins(-1,-2,0,0,0,0,10));
        currentTab="category";scanPage.setVisibility(View.GONE);categoryPage.setVisibility(View.VISIBLE);
        historyPage.setVisibility(View.GONE);settingsPage.setVisibility(View.GONE);
        categoryPage.post(() -> categoryPage.scrollTo(0,0));
    }

    private LinearLayout detailCard() {
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(round(surface,18));return card;
    }
    private void leaveProbeDetails(){Runnable back=returnFromProbeDetails;returnFromProbeDetails=null;if(back!=null)back.run();else showScanTab();}

    private ScrollView buildControlPage() {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(18),dp(16),dp(18),dp(16));
        Button toolBack=actionButton("←  Tools",surface,primary);toolBack.setOnClickListener(v->showTool("home"));content.addView(toolBack);
        content.addView(text("Control",24,primary,Typeface.BOLD));
        content.addView(text("Управление радиомодулями",14,secondary,Typeface.NORMAL));
        for (String label : new String[]{"Wi-Fi: принудительный стандарт и диапазон", "Bluetooth: принудительный протокол"}) {
            TextView item = text(label + "\nНедоступно через публичный Android API для обычного приложения",14,secondary,Typeface.NORMAL);
            item.setPadding(0,dp(16),0,dp(12)); content.addView(item);
        }
        content.addView(text(UiLanguage.isRussian()
            ? "Android показывает обслуживающую и соседние соты, но не позволяет обычному приложению выбрать Cell ID или автоматически переключать GSM/4G/5G. Выбор оператора — это выбор сети, а не вышки. Откройте системные параметры сети, если телефон позволяет вручную выбрать режим, затем вернитесь в Radio для новых измерений."
            : "Android reports serving and neighboring cells, but a regular app cannot select a Cell ID or automatically cycle GSM/4G/5G. Operator selection chooses a network, not a tower. Open mobile network settings if your phone offers manual modes, then return to Radio for new measurements.",13,secondary,Typeface.NORMAL));
        for (String[] entry : new String[][]{{"Системные настройки Wi-Fi",Settings.ACTION_WIFI_SETTINGS},{"Системные настройки Bluetooth",Settings.ACTION_BLUETOOTH_SETTINGS},{"Выбор оператора",Settings.ACTION_NETWORK_OPERATOR_SETTINGS},{"Параметры мобильной сети",Settings.ACTION_DATA_ROAMING_SETTINGS}}) {
            Button button = actionButton(entry[0],surface,primary);
            button.setOnClickListener(v -> { try { startActivity(new Intent(entry[1])); } catch (Exception error) { Toast.makeText(this,UiLanguage.text("Экран недоступен на этом устройстве"),Toast.LENGTH_LONG).show(); } });
            content.addView(button,margins(-1,dp(56),0,0,8,0,0));
        }
        content.addView(text("Изменения в системных настройках сохраняются после закрытия Lighthouse.",12,secondary,Typeface.NORMAL));
        ScrollView scroll = new ScrollView(this); scroll.addView(content); return scroll;
    }

    private void installMonitorSettings() {
        LinearLayout root=(LinearLayout)settingsPage.getChildAt(0);
        LinearLayout monitoring=new LinearLayout(this);monitoring.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView light=text("●",22,GREEN,Typeface.BOLD);heading.addView(light);
        heading.addView(text(UiLanguage.isRussian()?"Радиомониторинг":"Radio monitoring",18,primary,Typeface.BOLD));monitoring.addView(heading);
        Button start=actionButton("Start monitoring",surface,primary);start.setOnClickListener(v->configureMonitor());monitoring.addView(start);
        Button stop=actionButton("Stop monitoring",surface,primary);
        stop.setOnClickListener(v->startService(new Intent(this,RadioMonitorService.class).setAction("stop")));monitoring.addView(stop);
        TextView monitorState=text("",12,secondary,Typeface.NORMAL);monitoring.addView(monitorState);
        Runnable update=new Runnable(){public void run(){if(!alive)return;boolean running=RadioMonitorService.running;
            start.setVisibility(running?View.GONE:View.VISIBLE);stop.setVisibility(running?View.VISIBLE:View.GONE);
            stop.setEnabled(!RadioMonitorService.exporting);light.setVisibility(running?View.VISIBLE:View.GONE);
            monitorState.setText(RadioMonitorService.exporting?(UiLanguage.isRussian()?"Сохранение в Downloads…":"Saving to Downloads…"):
                running?UiLanguage.text("Recording"):!RadioMonitorService.lastError.isEmpty()?UiLanguage.text(RadioMonitorService.lastError):
                !RadioMonitorService.lastExport.isEmpty()?(UiLanguage.isRussian()?"Сохранено в Downloads: ":"Saved to Downloads: ")+RadioMonitorService.lastExport:"");
            ui.postDelayed(this,2000);
        }};ui.post(update);
        root.addView(monitoring,2);
    }

    private void configureMonitor() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { askRadioPermissions(false); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1004); return;
        }
        if(Build.VERSION.SDK_INT<=28&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},1006);return;
        }
        boolean[] selected = {true,true,true,true};
        new AlertDialog.Builder(this).setTitle(UiLanguage.text("Что отслеживать?")).setMultiChoiceItems(new String[]{"WiFi","Bluetooth","Cell","GPS"},selected,(d,i,value) -> selected[i]=value)
            .setNegativeButton(UiLanguage.text("Отмена"),null).setPositiveButton(UiLanguage.text("Далее"),(d,w) -> {
                if (!selected[0] && !selected[1] && !selected[2] && !selected[3]) { Toast.makeText(this,UiLanguage.text("Выберите хотя бы один модуль"),Toast.LENGTH_LONG).show(); return; }
                new AlertDialog.Builder(this).setTitle(UiLanguage.text("Начать мониторинг?"))
                    .setMessage(UiLanguage.text("Вы уверены, что хотите начать мониторинг? Расход вашей батареи существенно увеличится. Выбранные радиоданные и перемещение будут записываться локально каждые 120 секунд. Android может задерживать измерения; возраст данных сохраняется. При выключенном экране Android приостанавливает общий BLE-поиск."))
                    .setNegativeButton(UiLanguage.text("Отмена"),null).setPositiveButton(UiLanguage.text("Начать"),(dialog,which) -> {
                        Intent intent = new Intent(this,RadioMonitorService.class).putExtra("wifi",selected[0]).putExtra("bt",selected[1]).putExtra("cell",selected[2]).putExtra("gps",selected[3]);
                        try { startForegroundService(intent); } catch (RuntimeException error) { Toast.makeText(this,UiLanguage.text("Мониторинг не запущен: ") + error.getClass().getSimpleName(),Toast.LENGTH_LONG).show(); }
                    }).show();
            }).show();
    }

    private void exportRadio(android.net.Uri destination) {
        archiveExecutor.execute(() -> {
            try (java.io.OutputStream raw = getContentResolver().openOutputStream(destination);
                 java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(raw)) {
                java.nio.file.Path base = new File(getNoBackupFilesDir(),"radio-logs").toPath();
                if (!java.nio.file.Files.isDirectory(base)) throw new java.io.IOException("Нет записанных журналов");
                synchronized (RadioCsv.class) {
                    try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(base)) {
                        for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.filter(java.nio.file.Files::isRegularFile)::iterator) {
                            zip.putNextEntry(new java.util.zip.ZipEntry(base.relativize(file).toString().replace('\\','/')));
                            java.nio.file.Files.copy(file,zip); zip.closeEntry();
                        }
                    }
                }
                ui.post(() -> Toast.makeText(this,UiLanguage.text("CSV сохранены"),Toast.LENGTH_SHORT).show());
            } catch (Exception error) { ui.post(() -> Toast.makeText(this,UiLanguage.text("Ошибка экспорта: ") + error.getMessage(),Toast.LENGTH_LONG).show()); }
        });
    }

    private void applySafeArea(View content) {
        // Keep the scroll viewport outside system bars/cutouts, including in landscape.
        // Padding is replaced on every dispatch, never added to the previous insets.
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout = insets.getDisplayCutout();
                    if (cutout != null) {
                        left = Math.max(left, cutout.getSafeInsetLeft());
                        top = Math.max(top, cutout.getSafeInsetTop());
                        right = Math.max(right, cutout.getSafeInsetRight());
                        bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                    }
                }
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        if(terminal){header.addView(text("[//]",21,TerminalTheme.ACCENT,Typeface.BOLD),new LinearLayout.LayoutParams(dp(48),dp(48)));}
        else{ImageView logo = new ImageView(this);
            logo.setImageResource(R.drawable.lighthouse_logo);
            logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            logo.setContentDescription("Логотип Lighthouse");
            header.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));}
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL); names.setPadding(dp(12), 0, 0, 0);
        names.addView(text("Lighthouse", 23, primary, Typeface.BOLD));
        names.addView(text("Состояние интернета", 13, secondary, Typeface.NORMAL));
        header.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        settings = new Button(this); settings.setText(terminal?"[> ]":"⚙"); settings.setTextSize(terminal?14:22); settings.setGravity(Gravity.CENTER);
        settings.setContentDescription("Настройки"); settings.setPadding(0, 0, 0, 0);
        settings.setBackground(round(surface, 15)); settings.setTextColor(primary);
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return header;
    }

    private View buildHero() {
        heroCard = new LinearLayout(this); heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setPadding(dp(18), dp(17), dp(18), dp(16)); setHeroGradient(null);
        TextView kicker = text("СОСТОЯНИЕ СЕТИ", 11, secondary, Typeface.BOLD); kicker.setLetterSpacing(.12f);
        headline = text("Готово к проверке", 24, primary, Typeface.BOLD); headline.setPadding(0, dp(6), 0, dp(5));
        details = text("Проверка DNS / TCP / HTTPS", 14,
            secondary, Typeface.NORMAL); details.setLineSpacing(0, 1.12f);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(TargetCatalog.defaults().size()); progress.setProgressTintList(ColorStateList.valueOf(CYAN));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(75, 255, 255, 255)));
        progress.setVisibility(View.GONE);
        heroCard.addView(kicker); heroCard.addView(headline); heroCard.addView(details);
        heroCard.addView(progress, margins(-1, dp(8), 0, 0, 13, 0, 0));
        return heroCard;
    }

    private View buildActions() {
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        scan = actionButton("Начать скан", terminal?TerminalTheme.ACCENT:CYAN, terminal?TerminalTheme.BACKGROUND:Color.rgb(5, 31, 38));
        save = actionButton("Выгрузить лог", surface, primary); save.setEnabled(false); save.setAlpha(.45f);
        radioScan = actionButton("Радио", surface, primary);
        radioScan.setContentDescription("Снимок сотовых вышек, Wi-Fi и Bluetooth");
        radioScan.setOnClickListener(v -> requestOrStartRadioSnapshot());
        actions.addView(scan, margins(0, -1, 1, 0, 0, 5, 0));
        actions.addView(save, margins(0, -1, 1, 5, 0, 0, 0));
        
        return actions;
    }

    private Button actionButton(String label, int color, int textColor) {
        Button value = new Button(this); value.setText(UiLanguage.text(label)); value.setTextSize(14); value.setAllCaps(false);
        value.setTypeface(null, Typeface.BOLD); value.setTextColor(textColor); value.setBackground(round(color, 17));
        value.setPadding(dp(8), 0, dp(8), 0); return value;
    }

    private View buildStats() {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        availableCount = stat(row, "Работают", GREEN, 0);
        degradedCount = stat(row, "Ограничены", AMBER, 1);
        unavailableCount = stat(row, "Нет ответа", RED, 2);
        return row;
    }

    private TextView stat(LinearLayout row, String label, int color, int position) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER);
        card.setPadding(dp(5), dp(11), dp(5), dp(10)); card.setBackground(round(surface, 17));
        ProbeResult.Status filter = ProbeResult.Status.values()[position];
        card.setClickable(true); card.setFocusable(true);
        TypedValue ripple = new TypedValue();
        if (!terminal && getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true) && ripple.resourceId != 0)
            card.setForeground(getDrawable(ripple.resourceId));
        card.setContentDescription("Показать сервисы: " + label.toLowerCase(java.util.Locale.ROOT));
        card.setOnClickListener(view -> showStatusResults(filter));
        TextView count = text("0", 22, color, Typeface.BOLD);
        count.setGravity(Gravity.CENTER);
        TextView caption = text(label, 11, secondary, Typeface.NORMAL); caption.setGravity(Gravity.CENTER);
        card.addView(count); card.addView(caption);
        row.addView(card, margins(0, -1, 1, position == 0 ? 0 : 4, 0, position == 2 ? 0 : 4, 0));
        return count;
    }

    private void showStatusResults(ProbeResult.Status status) {
        List<ProbeResult> source = lastReport == null ? new ArrayList<>(currentResults.values()) : lastReport.results;
        List<ProbeResult> filtered = new ArrayList<>();
        for (ProbeResult result : source)
            if (NetworkAssessment.observedStatus(result) == status) filtered.add(result);
        showResultsPage(pluralStatus(status), "", filtered, true,
            status == ProbeResult.Status.AVAILABLE ? GREEN : status == ProbeResult.Status.DEGRADED ? AMBER : RED);
    }

    private void startScan() {
        if (scanning || !alive) return;
        if (scanProfile() == ScanProfile.DEEP && radioEnabled()
            && !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("radioAsked", false)) {
            askRadioPermissions(true); return;
        }
        final ScanProfile profile = scanProfile();
        final boolean collectRadio = profile == ScanProfile.DEEP && radioEnabled();
        final int generation = ++scanGeneration;
        scanning = true; scanStarted = SystemClock.elapsedRealtime(); scanPhase = "Проверка сервисов"; latestService = "";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lastLog = null;
        lastReport = null; availableInRestrictions.setVisibility(View.GONE);
        scan.setText(UiLanguage.text("Отменить")); radioScan.setEnabled(false); radioScan.setAlpha(.45f); save.setEnabled(false); save.setAlpha(.45f);
        settingsPage.updateAvailability(true, false);
        progress.setMax(targets.size() * profile.passes); progress.setProgress(0); progress.setVisibility(terminal?View.GONE:View.VISIBLE);
        checkedCount = available = degraded = unavailable = 0;
        currentResults.clear(); completedRequests = 0;
        updateCounts(); emptyState.setVisibility(View.GONE); recommendations.setVisibility(View.GONE);
        recommendationsTitle.setVisibility(View.GONE);
        bypassList.removeAllViews(); bypassList.setVisibility(View.GONE); bypassTitle.setVisibility(View.GONE);
        vpnList.removeAllViews(); vpnList.setVisibility(View.GONE); vpnTitle.setVisibility(View.GONE);
        categoryTiles.removeAllViews(); categoryTiles.setVisibility(View.GONE); categoryTitle.setVisibility(View.GONE);
        diagnosticsList.removeAllViews();
        overview.setText(UiLanguage.text(profile == ScanProfile.DEEP
            ? "Глубокий скан: 2 прохода и до 5 минут. Состояние радио не равно доступности интернета. Cell ID и BSSID в логе могут раскрывать местоположение."
            : "Быстрый скан: один проход. Медленные запросы ограничены тайм-аутом."));
        headline.setText(UiLanguage.text(getString(R.string.scan_in_progress)));
        ui.post(heartbeat);
        setHeroGradient(null);
        activeScan = executor.submit(() -> {
            try (NetworkTimeline timeline = new NetworkTimeline(this)) {
                java.nio.file.Path history = new File(getFilesDir(), "last-state.tsv").toPath();
                postScanUi(generation, () -> { scanPhase = "Снимок Wi-Fi, модема и сетей"; refreshProgress(); });
                List<NetworkCheckResult> observations = collectRadio
                    ? new RadioDiagnostics(this, "start", true).collect() : java.util.Collections.emptyList();
                DeviceInfo device = collectDevice(observations);
                ScanReport measured = new DiagnosticScanner(3500, 6, profile).scan(device, targets,
                    ScanArchive.loadPrevious(privateStorage(), device), NetworkBaseline.load(getFilesDir().toPath(), device), r -> {
                        CrashDiagnostics.phase("Получен результат: " + r.target.name);
                        postScanUi(generation, () -> showProbe(r));
                    }, phase -> {
                        CrashDiagnostics.phase(phase);
                        postScanUi(generation, () -> { scanPhase = phase; refreshProgress(); });
                    });
                if (Thread.currentThread().isInterrupted() || !alive) return;
                postScanUi(generation, () -> { scanPhase = "Повторный снимок сетевого окружения"; refreshProgress(); });
                List<NetworkCheckResult> checks = new ArrayList<>(measured.networkChecks);
                if (collectRadio) checks.addAll(new RadioDiagnostics(this, "end", false).collect());
                checks.addAll(timeline.snapshot());
                postScanUi(generation, () -> { scanPhase = "Анализ фонового трафика приложений"; refreshProgress(); });
                checks.addAll(AndroidAppTraffic.collect(this));
                checks.add(ru.lighthouse.core.Detector404Client.fetch(detector404Token, 6000));
                postScanUi(generation, () -> { scanPhase = "Проверка прокси и VPN"; refreshProgress(); });
                checks.addAll(TelegramProxyCheck.testAll(new ArrayList<>(telegramProxies), 5000));
                checks.addAll(VpnEndpointCheck.testAll(new ArrayList<>(vpnProfiles), 5000, measured.device.vpnDetected));
                checks.add(AndroidZapretDiagnostics.collect(measured.results));
                List<String> finalRecommendations = BypassAnalytics.enrichRecommendations(measured.level, measured.recommendations, checks);
                ScanReport report = new ScanReport(measured.scanId, measured.startedAt, java.time.Instant.now(), measured.device,
                    measured.publicIp, measured.networkOrigin, measured.level, measured.results, checks,
                    measured.newlyUnavailable, measured.recovered, finalRecommendations, measured.profile, measured.expectedTargets, measured.complete, measured.assessment);
                CrashDiagnostics.phase("Сохранение отчёта");
                postScanUi(generation, () -> { scanPhase = "Сохранение отчёта"; refreshProgress(); });
                byte[] encoded = JsonLog.encode(report);
                String filename = "lighthouse-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneId.systemDefault()).format(report.finishedAt) + ".json";
                boolean saved = true;
                java.nio.file.Path privateRoot = privateStorage();
                java.nio.file.Path logPath = privateRoot.resolve("logs").resolve(filename);
                try { java.nio.file.Files.createDirectories(logPath.getParent()); java.nio.file.Files.write(logPath, encoded); }
                catch (Exception error) { saved = false; CrashDiagnostics.record("Автосохранение JSON", error); }
                try { History.save(history, report); }
                catch (Exception | LinkageError error) { saved = false; CrashDiagnostics.record("Сохранение истории", error); }
                try { NetworkBaseline.rememberFirstNormal(getFilesDir().toPath(), report); }
                catch (Exception error) { saved = false; CrashDiagnostics.record("Сохранение обычного уровня", error); }
                try { ScanArchive.record(privateRoot, report, "logs/" + filename); }
                catch (Exception error) { saved = false; CrashDiagnostics.record("Сохранение архива сканов", error); }
                // Analytics and string construction stay off the UI thread.
                String summary = String.join("\n\n", ReportAnalytics.plainOverview(report));
                boolean savedLocally = saved;
                postScanUi(generation, () -> {
                    lastLog = encoded; lastName = filename;
                    CrashDiagnostics.phase("Показ результатов");
                    finishScan(report, summary);
                    if ("history".equals(currentTab)) historyPage.requestRefresh();
                    if (!savedLocally) Toast.makeText(this, UiLanguage.text("Автосохранение не удалось. Сохраните лог кнопкой."), Toast.LENGTH_LONG).show();
                });
            } catch (CancellationException cancelled) {
                // onDestroy/cancelScan already restores the UI; no failed report is saved.
            } catch (Exception | LinkageError error) {
                CrashDiagnostics.record("Сканирование", error);
                postScanUi(generation, () -> showScanError(error));
            }
        });
    }

    private void requestOrStartRadioSnapshot() {
        if (!alive || scanning || radioScanning) return;
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION); missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!missing.isEmpty()) { radioSniffAfterPermission = true; askRadioPermissions(false); return; }
        startRadioSnapshot();
    }

    private void startRadioSnapshot() {
        if (!alive || scanning || radioScanning) return;
        radioScanning = true; radioScan.setEnabled(false); radioScan.setText(UiLanguage.text("Снимаем…"));
        progress.setIndeterminate(false); progress.setMax(5); progress.setProgress(0); progress.setVisibility(terminal?View.GONE:View.VISIBLE);
        details.setText(UiLanguage.text("Радиоснимок: подготовка"));
        Toast.makeText(this, UiLanguage.text("Снимаем вышки, Wi‑Fi и Bluetooth…"), Toast.LENGTH_SHORT).show();
        executor.submit(() -> {
            List<NetworkCheckResult> snapshot;
            try { snapshot = new RadioDiagnostics(this, "manual", true, (completed, total, phase) -> ui.post(() -> {
                    if (!alive || !radioScanning) return;
                    progress.setMax(total); progress.setProgress(completed); details.setText(UiLanguage.text("Радиоснимок: " + phase + "\n" + completed + " из " + total + " этапов"));
                })).collect(); }
            catch (Exception | LinkageError error) { CrashDiagnostics.record("Радиоснимок", error); snapshot = new ArrayList<>(); }
            List<NetworkCheckResult> result = snapshot;
            ui.post(() -> {
                radioScanning = false; radioScan.setEnabled(true); radioScan.setText(UiLanguage.text("Радио")); progress.setVisibility(View.GONE);
                if (!alive) return;
                showNetworkChecks(result);
                Toast.makeText(this, UiLanguage.text("Радиоснимок готов: ") + result.size() + UiLanguage.text(" наблюдений"), Toast.LENGTH_LONG).show();
            });
        });
    }

    private void postScanUi(int generation, Runnable action) {
        ui.post(() -> {
            if (!alive || !scanning || generation != scanGeneration) return;
            try { action.run(); }
            catch (RuntimeException | LinkageError error) {
                CrashDiagnostics.record("Интерфейс результатов", error);
                showScanError(error);
            }
        });
    }

    private void refreshProgress() {
        long seconds = (SystemClock.elapsedRealtime() - scanStarted) / 1000;
        String text = checkedCount + " из " + targets.size() + " сервисов  •  " + seconds + " с\n" + scanPhase
            + "\nЗамеров: " + completedRequests;
        if (!latestService.isEmpty()) text += "\nПоследний ответ: " + latestService;
        details.setText(UiLanguage.text(text));
    }

    private void resetScanControls() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scanning = false; ui.removeCallbacks(heartbeat);
        scan.setEnabled(true); scan.setText(UiLanguage.text("Начать скан")); scan.setAlpha(1f);
        radioScan.setEnabled(true); radioScan.setAlpha(1f);
        settingsPage.updateAvailability(false, canRememberBaseline());
        progress.setVisibility(View.GONE);
        save.setEnabled(lastLog != null); save.setAlpha(lastLog == null ? .45f : 1f);
    }

    private void cancelScan() {
        scanGeneration++;
        if (activeScan != null) activeScan.cancel(true);
        resetScanControls();
        headline.setText(UiLanguage.text("Сканирование отменено"));
        details.setText(UiLanguage.text("Получено ответов: " + checkedCount + ". Можно начать повторную проверку."));
        CrashDiagnostics.phase("Отменено пользователем");
    }

    private void showScanError(Throwable error) {
        scanGeneration++;
        if (activeScan != null) activeScan.cancel(true);
        resetScanControls();
        headline.setText(UiLanguage.text(lastLog == null ? "Проверка не завершена" : "Отчёт сохранён"));
        details.setText(UiLanguage.text("Ошибка: " + error.getClass().getSimpleName() + ". Настройки → Журнал ошибок."));
        if (lastLog != null) overview.setText(UiLanguage.text("Не удалось показать часть аналитики. JSON-лог можно сохранить кнопкой."));
    }

    private void showProbe(ProbeResult result) {
        currentResults.put(result.target.id, result); checkedCount = currentResults.size();
        available = degraded = unavailable = completedRequests = 0;
        for (ProbeResult value : currentResults.values()) {
            completedRequests += value.attempts();
            ProbeResult.Status observed = NetworkAssessment.observedStatus(value);
            if (observed == ProbeResult.Status.AVAILABLE) available++;
            else if (observed == ProbeResult.Status.DEGRADED) degraded++; else unavailable++;
        }
        progress.setProgress(completedRequests);
        latestService = result.target.name;
        updateCounts(); refreshProgress();
    }

    private View probeRow(ProbeResult result) {
        ProbeResult.Status observed = NetworkAssessment.observedStatus(result);
        int color = observed == ProbeResult.Status.AVAILABLE ? GREEN
            : observed == ProbeResult.Status.DEGRADED ? AMBER : RED;
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(11), dp(13), dp(11)); row.setBackground(round(surface, 16));
        TextView dot = text("●", 18, color, Typeface.BOLD); dot.setGravity(Gravity.CENTER);
        row.addView(dot, new LinearLayout.LayoutParams(dp(30), -2));
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(result.target.name, 15, primary, Typeface.BOLD));
        String detail = result.target.category + "  •  " + timing(result) + "  •  замеров: " + result.attempts();
        if (result.status == ProbeResult.Status.DEGRADED && observed == ProbeResult.Status.AVAILABLE)
            detail += "  •  ответы менялись";
        names.addView(text(detail, 12, secondary, Typeface.NORMAL));
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        TextView status = text(shortStatus(observed), 12, color, Typeface.BOLD); status.setGravity(Gravity.END);
        row.addView(status, new LinearLayout.LayoutParams(dp(96), -2));
        row.setOnClickListener(v -> showProbeDetails(result)); return row;
    }

    private void finishScan(ScanReport report, String summary) {
        lastReport = report;
        headline.setText(UiLanguage.text(levelName(report.level)));
        details.setText(UiLanguage.text("Работают " + available + " из " + report.results.size() + "  •  " + report.networkOrigin));
        setHeroGradient(report.level);
        overview.setText(UiLanguage.text(summary));
        if (report.assessment.state == NetworkAssessment.State.RED) {
            availableInRestrictions.setText(UiLanguage.text("Доступны при текущих ограничениях\n\n" + ReportAnalytics.availableServicesText(report)));
            availableInRestrictions.setVisibility(View.VISIBLE);
        } else availableInRestrictions.setVisibility(View.GONE);
        showNetworkChecks(report.networkChecks);
        showVpnChecks(report.networkChecks);
        showBypassChecks(report.networkChecks);
        showCategoryTiles(report);
        StringBuilder value = new StringBuilder();
        for (String item : report.recommendations) value.append("•  ").append(item).append("\n\n");
        if (!report.newlyUnavailable.isEmpty()) value.append("Стали недоступны: ").append(String.join(", ", report.newlyUnavailable)).append('\n');
        if (!report.recovered.isEmpty()) value.append("Снова доступны: ").append(String.join(", ", report.recovered)).append('\n');
        recommendations.setText(UiLanguage.text(value.toString().trim())); recommendations.setVisibility(View.GONE);
        recommendationsTitle.setVisibility(View.VISIBLE);
        resetScanControls();
        CrashDiagnostics.phase("Сканирование завершено");
    }

    private void showNetworkChecks(List<NetworkCheckResult> checks) {
        diagnosticsList.removeAllViews();
        Map<String, List<NetworkCheckResult>> groups = new LinkedHashMap<>();
        for (NetworkCheckResult check : checks) {
            if ("Методы обхода".equals(check.category) || "VPN".equals(check.category)
                || check.id.startsWith("radio_") || check.id.startsWith("before_") || check.id.startsWith("after_")) continue;
            groups.computeIfAbsent(check.category, ignored -> new ArrayList<>()).add(check);
        }
        for (Map.Entry<String, List<NetworkCheckResult>> group : groups.entrySet()) {
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setBackground(round(surface, 16));
            TextView title = text(group.getKey() + "  ·  " + group.getValue().size() + "  ⌄", 16, primary, Typeface.BOLD);
            title.setPadding(dp(14), dp(16), dp(14), dp(16));
            LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setVisibility(View.GONE);
            title.setOnClickListener(view -> {
                if (body.getChildCount() == 0) {
                    for (int i = 0; i < Math.min(32, group.getValue().size()); i++) body.addView(networkCheckRow(group.getValue().get(i)));
                    if (group.getValue().size() > 32) {
                        int[] shown = {32}; Button more = actionButton("Показать ещё", surface, primary);
                        more.setOnClickListener(button -> {
                            int end = Math.min(shown[0] + 32, group.getValue().size());
                            for (int i = shown[0]; i < end; i++) body.addView(networkCheckRow(group.getValue().get(i)), body.getChildCount() - 1);
                            shown[0] = end; if (shown[0] >= group.getValue().size()) body.removeView(more);
                        }); body.addView(more);
                    }
                }
                body.setVisibility(body.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            });
            card.addView(title); card.addView(body); diagnosticsList.addView(card, margins(-1, -2, 0, 0, 0, 0, 8));
        }
        if (checks.isEmpty()) {
            TextView unavailable = text("Дополнительные проверки не завершились.", 14, secondary, Typeface.NORMAL);
            unavailable.setPadding(dp(15), dp(15), dp(15), dp(15)); unavailable.setBackground(round(surface, 16));
            diagnosticsList.addView(unavailable);
        }
    }

    private void showVpnChecks(List<NetworkCheckResult> checks) {
        vpnList.removeAllViews();
        NetworkCheckResult overall = null;
        int count = 0;
        for (NetworkCheckResult check : checks) {
            if ("vpn_profiles_summary".equals(check.id)) overall = check;
            else if (check.id.matches("vpn_profile_\\d+")) {
                vpnList.addView(vpnCheckRow(check), margins(-1, -2, 0, 0, 0, 0, 8));
                count++;
            }
        }
        if (overall == null) {
            vpnTitle.setVisibility(View.GONE); vpnList.setVisibility(View.GONE);
            return;
        }
        if (count == 0) {
            TextView empty = text("VPN-адреса для проверки не добавлены. Добавьте их в Settings → VPN tests.",
                13, secondary, Typeface.NORMAL);
            empty.setPadding(dp(14), dp(14), dp(14), dp(14)); empty.setBackground(round(surface, 16));
            vpnList.addView(empty);
        } else {
            TextView hint = text("Проверяется отклик адреса или порта. Работа VPN-туннеля этим не подтверждается.",
                12, secondary, Typeface.NORMAL);
            vpnList.addView(hint, 0, margins(-1, -2, 0, 0, 0, 0, 9));
        }
        vpnTitle.setVisibility(View.VISIBLE); vpnList.setVisibility(View.VISIBLE);
        UiLanguage.apply(vpnTitle); UiLanguage.apply(vpnList);
    }

    private View vpnCheckRow(NetworkCheckResult check) {
        String coverage = check.metrics.get("coverage");
        String state = "https_endpoint".equals(coverage) ? "HTTPS-адрес доступен"
            : "https_response".equals(coverage) ? "HTTP-ошибка или редирект"
            : "tcp_endpoint".equals(coverage) ? "TCP-порт отвечает"
            : "dns_only".equals(coverage) ? "Только DNS"
            : "Доступность не подтверждена";
        int color = check.status == NetworkCheckResult.Status.OK ? GREEN : AMBER;
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12)); row.setBackground(round(surface, 16));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        String displayName = check.name;
        for (NamedConfiguration bundled : DefaultConnections.vpnSubscriptions())
            if (bundled.name.equals(check.name)) { displayName = UiLanguage.text(check.name); break; }
        TextView name = text(displayName, 15, primary, Typeface.BOLD); name.setTag(Boolean.TRUE);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(text("● " + state, 12, color, Typeface.BOLD));
        row.addView(top);
        TextView summary = text(check.summary, 12, secondary, Typeface.NORMAL);
        row.addView(summary, margins(-1, -2, 0, 0, 5, 0, 0));
        TextView details = text("", 12, secondary, Typeface.NORMAL); details.setVisibility(View.GONE);
        details.setTextIsSelectable(true);
        row.addView(details, margins(-1, -2, 0, 0, 8, 0, 0));
        row.setOnClickListener(view -> {
            if (details.getVisibility() == View.VISIBLE) details.setVisibility(View.GONE);
            else {
                StringBuilder values = new StringBuilder();
                for (Map.Entry<String, String> metric : check.metrics.entrySet())
                    values.append(metric.getKey()).append(": ").append(metric.getValue()).append('\n');
                details.setText(UiLanguage.text(values.toString().trim())); details.setVisibility(View.VISIBLE);
            }
        });
        return row;
    }

    private void showBypassChecks(List<NetworkCheckResult> checks) {
        bypassList.removeAllViews();
        List<NetworkCheckResult> values = BypassAnalytics.results(checks);
        for (NetworkCheckResult check : values) {
            if ("vpn_profiles_summary".equals(check.id) || check.id.matches("vpn_profile_\\d+")) continue;
            View row = networkCheckRow(check);
            NamedConfiguration configuration = configurationFor(check.id);
            if (configuration != null) {
                row.setContentDescription(check.name + ". Удерживайте, чтобы скопировать ссылку.");
                row.setOnLongClickListener(view -> { copyConfiguration(configuration); return true; });
            }
            bypassList.addView(row, margins(-1, -2, 0, 0, 0, 0, 8));
        }
        boolean visible = bypassList.getChildCount() > 0;
        bypassTitle.setVisibility(visible ? View.VISIBLE : View.GONE);
        bypassList.setVisibility(View.GONE);
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
            for (NamedConfiguration item : source) if (item != null && !item.value.isBlank()) {
                if (++current == wanted) return item;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private void copyConfiguration(NamedConfiguration configuration) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(configuration.name, configuration.value));
        Toast.makeText(this, UiLanguage.text("Ссылка скопирована"), Toast.LENGTH_SHORT).show();
    }

    private View networkCheckRow(NetworkCheckResult check) {
        int color = check.status == NetworkCheckResult.Status.OK ? GREEN
            : check.status == NetworkCheckResult.Status.WARNING ? AMBER : RED;
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.TOP);
        row.setPadding(dp(14), dp(12), dp(14), dp(12)); row.setBackground(round(surface, 16));
        TextView dot = text("●", 18, color, Typeface.BOLD);
        row.addView(dot, new LinearLayout.LayoutParams(dp(28), -2));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        TextView checkName=text(check.name,15,primary,Typeface.BOLD);
        if(check.id.startsWith("telegram_proxy_")||check.id.startsWith("vpn_profile_"))checkName.setTag(Boolean.TRUE);
        content.addView(checkName);
        TextView summary = text(check.summary, 12, secondary, Typeface.NORMAL); summary.setLineSpacing(0, 1.1f);
        content.addView(summary); row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(view -> {
            if (content.getChildCount() == 2) {
                StringBuilder values = new StringBuilder();
                for (Map.Entry<String, String> metric : check.metrics.entrySet()) values.append(metric.getKey()).append(": ").append(metric.getValue()).append('\n');
                TextView metrics = text(values.toString().trim(), 12, secondary, Typeface.NORMAL); metrics.setTextIsSelectable(true); content.addView(metrics);
            } else { View metrics = content.getChildAt(2); metrics.setVisibility(metrics.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }
        });
        return row;
    }

    private void showCategoryTiles(ScanReport report) {
        categoryTiles.removeAllViews();
        List<ProbeResult> changed = ReportAnalytics.newlyBlocked(report);
        if (!changed.isEmpty()) addCategoryTile(ReportAnalytics.NEWLY_BLOCKED, changed.size(), 0, changed.size(), RED,
            () -> showCategoryResults(ReportAnalytics.NEWLY_BLOCKED, changed));
        for (ReportAnalytics.CategorySummary summary : ReportAnalytics.byCategory(report.results)) {
            int color = summary.health == ReportAnalytics.Health.HEALTHY ? GREEN
                : summary.health == ReportAnalytics.Health.DEGRADED ? AMBER : RED;
            List<ProbeResult> values = new ArrayList<>();
            for (ProbeResult result : report.results) if (summary.category.equals(result.target.category)) values.add(result);
            addCategoryTile(summary.category, summary.total, summary.available, summary.unavailable, color,
                () -> showCategoryResults(summary.category, values));
        }
        boolean visible = categoryTiles.getChildCount() > 0;
        categoryTiles.setVisibility(visible ? View.VISIBLE : View.GONE);
        categoryTitle.setVisibility(visible ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void addCategoryTile(String name, int total, int up, int down, int color, Runnable open) {
        LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(13), dp(14), dp(13)); tile.setBackground(round(surface, 18));
        tile.setClickable(true); tile.setFocusable(true); tile.setContentDescription("Открыть категорию " + name);
        TypedValue ripple = new TypedValue();
        if (!terminal && getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true) && ripple.resourceId != 0)
            tile.setForeground(getDrawable(ripple.resourceId));
        TextView icon = text(terminal?"[+]":ReportAnalytics.categoryIcon(name), 23, color, Typeface.BOLD);
        tile.addView(icon);
        TextView title = text(name, 14, primary, Typeface.BOLD); title.setMaxLines(2);
        title.setPadding(0, dp(5), 0, dp(5)); tile.addView(title);
        String summary = ReportAnalytics.NEWLY_BLOCKED.equals(name)
            ? total + " новых недоступных" : up + " из " + total + " работают" + (down > 0 ? " · " + down + " не работают" : "");
        tile.addView(text(summary, 12, secondary, Typeface.NORMAL)); tile.setOnClickListener(v -> open.run());
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0; params.height = -2; params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(0, 0, dp(8), dp(8)); categoryTiles.addView(tile, params);
    }

    private void showCategoryResults(String category, List<ProbeResult> values) {
        showResultsPage(ReportAnalytics.categoryIcon(category) + "  " + category,
            ReportAnalytics.categoryDescription(category), values, false);
    }

    private void showResultsPage(String title, String description, List<ProbeResult> source, boolean grouped) {
        showResultsPage(title,description,source,grouped,primary);
    }

    private void showResultsPage(String title, String description, List<ProbeResult> source, boolean grouped, int titleColor) {
        returnFromProbeDetails=null;
        returnToResults=()->showResultsPage(title,description,source,grouped,titleColor);
        categoryPageContent.removeAllViews();
        Button back = actionButton("←  Назад к аналитике", surface, primary);
        back.setOnClickListener(v -> { showScanTab(); scanPage.post(() -> scanPage.smoothScrollTo(0, categoryTiles.getTop())); });
        categoryPageContent.addView(back,margins(-1,dp(48),0,0,0,0,16));
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(title,26,primary,Typeface.BOLD),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(text(String.valueOf(source.size()),28,titleColor,Typeface.BOLD));
        categoryPageContent.addView(heading,margins(-1,-2,0,0,0,0,14));
        List<ProbeResult> ordered = new ArrayList<>(source);
        ordered.sort(java.util.Comparator.comparingInt((ProbeResult value) -> statusOrder(NetworkAssessment.observedStatus(value)))
            .thenComparing(value -> value.target.category, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(value -> value.target.name, String.CASE_INSENSITIVE_ORDER));
        if (ordered.isEmpty()) {
            TextView none = text("В этой группе пока нет результатов.", 14, secondary, Typeface.NORMAL);
            none.setGravity(Gravity.CENTER); none.setPadding(dp(16), dp(22), dp(16), dp(22)); none.setBackground(round(surface, 18));
            categoryPageContent.addView(none);
        } else for (ProbeResult result : ordered)
            categoryPageContent.addView(probeRow(result), margins(-1, -2, 0, 0, 0, 0, 7));
        currentTab = "category"; scanPage.setVisibility(View.GONE); categoryPage.setVisibility(View.VISIBLE);
        historyPage.setVisibility(View.GONE); settingsPage.setVisibility(View.GONE);
        styleTab(scanTab, true); styleTab(historyTab, false); styleTab(settingsTab, false);
        categoryPage.post(() -> categoryPage.scrollTo(0, 0));
    }

    private View categoryMetric(String label, int value, int color) {
        LinearLayout metric = new LinearLayout(this); metric.setOrientation(LinearLayout.VERTICAL); metric.setGravity(Gravity.CENTER);
        TextView count = text(String.valueOf(value), 22, color, Typeface.BOLD); count.setGravity(Gravity.CENTER);
        TextView caption = text(label, 11, secondary, Typeface.NORMAL); caption.setGravity(Gravity.CENTER);
        metric.addView(count); metric.addView(caption); return metric;
    }

    private int statusOrder(ProbeResult.Status status) {
        return status == ProbeResult.Status.UNAVAILABLE ? 0 : status == ProbeResult.Status.DEGRADED ? 1 : 2;
    }

    @Override public void onBackPressed() {
        if (returnFromProbeDetails != null) { leaveProbeDetails(); return; }
        if (categoryPage != null && categoryPage.getVisibility() == View.VISIBLE) { showScanTab(); return; }
        if ("radio".equals(currentTab) && liveRadioPage.onBack()) return;
        if ("tools".equals(currentTab) && toolsHome.getVisibility()!=View.VISIBLE){showTool("home");return;}
        if (!"scan".equals(currentTab)) { showScanTab(); return; }
        super.onBackPressed();
    }

    private void updateCounts() {
        availableCount.setText(String.valueOf(available)); degradedCount.setText(String.valueOf(degraded));
        unavailableCount.setText(String.valueOf(unavailable));
    }

    private void setHeroGradient(ScanReport.Level level) {
        GradientDrawable panel = round(surface,4);
        panel.setStroke(dp(1), secondary); heroCard.setBackground(panel);
    }

    private DeviceInfo collectDevice(List<NetworkCheckResult> observations) {
        String localIp = "unavailable", networkName = "unavailable";
        String validation = "unknown", metered = "unknown";
        boolean vpn = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm.getActiveNetwork(); NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            LinkProperties links = cm.getLinkProperties(network);
            if (caps != null) { vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                networkName = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Мобильная сеть"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Другая сеть";
                validation = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                    ? "captive_portal" : caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ? "validated" : "not_validated";
                metered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ? "no" : "yes";
            }
            if (links != null) for (LinkAddress address : links.getLinkAddresses())
                if (address.getAddress() instanceof Inet4Address) { localIp = address.getAddress().getHostAddress(); break; }
        } catch (Exception ignored) { }
        return new DeviceInfo(Build.MANUFACTURER + " " + Build.MODEL, "Android " + Build.VERSION.RELEASE, localIp,
            "unavailable: Android restricts device MAC", networkName, vpn,
            vpn ? "VPN transport detected" : "Не обнаружено (эвристика)", validation, metered, observations);
    }

    private void saveLog() {
        if (lastLog == null) return;
        beginLogExport(lastLog, lastName);
    }

    private void refreshHistory(int limit) {
        if (historyPage == null) return;
        historyPage.showLoading();
        archiveExecutor.submit(() -> {
            List<ScanArchive.Entry> scans = ScanArchive.loadScans(privateStorage(), limit);
            List<ScanArchive.Change> changes = ScanArchive.loadChanges(privateStorage(), Math.max(250, limit * 3));
            ui.post(() -> { if (alive && historyPage != null) historyPage.show(scans, changes); });
        });
    }

    private void exportStoredLog(String relativeLog) {
        archiveExecutor.submit(() -> {
            try {
                java.nio.file.Path source = ScanArchive.resolveLog(privateStorage(), relativeLog);
                byte[] bytes = java.nio.file.Files.readAllBytes(source);
                ui.post(() -> { if (alive) beginLogExport(bytes, source.getFileName().toString()); });
            } catch (Exception error) {
                ui.post(() -> { if (alive) Toast.makeText(this, UiLanguage.text("Лог отсутствует или повреждён"), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private void beginLogExport(byte[] bytes, String filename) {
        pendingExportLog = bytes;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, filename); intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, SAVE_LOG);
    }

    private void saveErrorLog() {
        try {
            if (CrashDiagnostics.read() == null) {
                Toast.makeText(this, UiLanguage.text("Ошибок пока не записано"), Toast.LENGTH_SHORT).show(); return;
            }
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "lighthouse-error.txt"); intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, SAVE_ERROR);
        } catch (Exception error) { Toast.makeText(this, UiLanguage.text("Не удалось открыть журнал ошибок"), Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_RADIO) { if (resultCode == RESULT_OK && data != null && data.getData() != null) exportRadio(data.getData()); return; }
        if ((requestCode == SAVE_LOG || requestCode == SAVE_ERROR) && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                byte[] bytes = requestCode == SAVE_ERROR ? CrashDiagnostics.read() : pendingExportLog;
                if (out == null || bytes == null) throw new java.io.IOException("Log unavailable");
                out.write(bytes); Toast.makeText(this, UiLanguage.text("Лог сохранён"), Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(this, UiLanguage.text("Не удалось сохранить лог"), Toast.LENGTH_LONG).show(); }
            finally { if (requestCode == SAVE_LOG) pendingExportLog = null; }
        }
        if (requestCode == SAVE_LOG && resultCode != RESULT_OK) pendingExportLog = null;
    }

    private void saveSettings(String theme, String language, ScanProfile profile, boolean radio, String detectorToken,
                              List<NamedConfiguration> proxies, List<NamedConfiguration> vpns) {
        if (scanning) return;
        boolean oldDark = dark,oldTerminal=terminal;
        boolean languageChanged=UiLanguage.isRussian()!="ru".equals(language);
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("theme", theme)
            .putString("language",language).putString("profile", profile == ScanProfile.DEEP ? "deep" : "quick").putBoolean("radio", radio).commit();
        detector404Token = detectorToken;
        telegramProxies = new ArrayList<>(proxies); vpnProfiles = new ArrayList<>(vpns);
        terminal=TerminalTheme.enabled(this);dark = isDark();
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        if(languageChanged){recreate();return;}
        if (oldDark != dark || oldTerminal != terminal) rebuildForTheme();
        else {
            if (lastReport == null) details.setText(UiLanguage.text(profile == ScanProfile.DEEP
                ? "Проверка DNS / TCP / HTTPS"
                : "Быстрый скан: один проход с ограниченными тайм-аутами."));
            settingsPage.showSaved(); settingsPage.updateAvailability(false, canRememberBaseline());
        }
    }

    private void checkAutomaticallyIfDue() {
        if (!alive || isFinishing()) return;
        if (!updateManager.isConfigured()) return;
        long now = System.currentTimeMillis();
        long last = getSharedPreferences("settings", MODE_PRIVATE).getLong("lastUpdateCheck", 0L);
        if (last > 0 && now - last < UpdateManager.AUTOMATIC_CHECK_INTERVAL_MS) return;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putLong("lastUpdateCheck", now).apply();
        checkForUpdates(false);
    }

    private void checkForUpdates(boolean manual) {
        if (!updateManager.isConfigured()) {
            if (manual) Toast.makeText(this, UiLanguage.text("Обновления не настроены для этой сборки"), Toast.LENGTH_LONG).show();
            if (settingsPage != null) settingsPage.updateError("Обновления не настроены для этой сборки.");
            return;
        }
        if (settingsPage != null) settingsPage.updateChecking();
        updateManager.check(new UpdateManager.Callback() {
            @Override public void onNoUpdate() {
                pendingUpdate = null;
                UpdateManager.clearPending(MainActivity.this);
                if (settingsPage != null) settingsPage.updateNoUpdate();
            }

            @Override public void onUpdateAvailable(UpdateManager.UpdateInfo update) {
                pendingUpdate = update;
                UpdateManager.savePending(MainActivity.this, update);
                if (settingsPage != null) settingsPage.updateAvailable(update.displayVersion());
                if (!manual && !update.displayVersion().equals(automaticDialogVersion) && !isFinishing()) {
                    automaticDialogVersion = update.displayVersion();
                    showUpdateDialog(update);
                }
            }

            @Override public void onError(String message) {
                if (settingsPage != null) settingsPage.updateError(message);
                if (manual) Toast.makeText(MainActivity.this, UiLanguage.text(message), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void scheduleBackgroundUpdateCheck() {
        if (!updateManager.isConfigured() || Build.VERSION.SDK_INT < 24) return;
        JobScheduler scheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(UpdateCheckJobService.JOB_ID,
            new ComponentName(this, UpdateCheckJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(UpdateManager.AUTOMATIC_CHECK_INTERVAL_MS)
            .setBackoffCriteria(30L * 60L * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        scheduler.schedule(job);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && updateManager.isConfigured()
            && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1004);
    }

    private void showUpdateDialog(UpdateManager.UpdateInfo update) {
        StringBuilder message = new StringBuilder("Установлена ").append(appVersion())
            .append(". Доступна ").append(update.displayVersion()).append(".");
        if (update.assetUrl.startsWith("http://")) message.append("\n\nИсточник: Mothman, локальная сеть. Манифест без доверенной цифровой подписи. Устанавливайте только с вашего сервера.");
        if (!update.notes.isEmpty()) {
            String notes = update.notes.length() > 700 ? update.notes.substring(0, 700) + "…" : update.notes;
            message.append("\n\n").append(notes);
        }
        int dialogTheme = dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
        new AlertDialog.Builder(this, dialogTheme).setTitle(UiLanguage.text("Доступно обновление"))
            .setMessage(UiLanguage.text(message.toString())).setPositiveButton(UiLanguage.text("Установить"), (dialog, which) -> installPendingUpdate())
            .setNegativeButton(UiLanguage.text("Отмена"), null).show();
    }

    private void installPendingUpdate() {
        if (pendingUpdate == null) {
            Toast.makeText(this, UiLanguage.text("Сначала проверьте наличие обновлений"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!canInstallPackages()) {
            awaitingInstallPermission = true;
            int dialogTheme = dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
            new AlertDialog.Builder(this, dialogTheme).setTitle(UiLanguage.text("Разрешите установку"))
                .setMessage(UiLanguage.text("Для обновления Android попросит разрешить установку приложений из этого источника один раз."))
                .setPositiveButton(UiLanguage.text("Открыть настройки"), (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception error) {
                        awaitingInstallPermission = false;
                        Toast.makeText(this, UiLanguage.text("Не удалось открыть настройки установки"), Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton(UiLanguage.text("Отмена"), (dialog, which) -> awaitingInstallPermission = false).show();
            return;
        }
        downloadPendingUpdate();
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void downloadPendingUpdate() {
        UpdateManager.UpdateInfo update = pendingUpdate;
        if (update == null) return;
        if (settingsPage != null) settingsPage.updateDownloadProgress(0);
        updateManager.download(update, new UpdateManager.Callback() {
            @Override public void onProgress(int percent) {
                if (settingsPage != null) settingsPage.updateDownloadProgress(percent);
            }

            @Override public void onDownloaded(File apk) {
                if (settingsPage != null) settingsPage.updateReady();
                openPackageInstaller(apk);
            }

            @Override public void onError(String message) {
                if (settingsPage != null) settingsPage.updateError(message);
                Toast.makeText(MainActivity.this, UiLanguage.text(message), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void openPackageInstaller(File apk) {
        try {
            Uri uri = Uri.parse("content://" + getPackageName() + ".updates/updates/lighthouse-update.apk");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, UiLanguage.text("Не удалось открыть установщик Android"), Toast.LENGTH_LONG).show();
        }
    }

    private void rebuildForTheme() {
        String draftLanguage=settingsPage==null?null:settingsPage.selectedLanguage();
        setPalette();
        applySystemBars();
        ScanReport report = lastReport;
        byte[] log = lastLog; String name = lastName;
        View content = buildUi(); UiLanguage.apply(content);if(terminal)TerminalTheme.apply(content); setContentView(content); content.requestApplyInsets(); bindActions();
        if(draftLanguage!=null)settingsPage.restoreLanguage(draftLanguage);
        lastReport = report; lastLog = log; lastName = name;
        if (report != null) {
            currentResults.clear(); available = degraded = unavailable = completedRequests = 0;
            for (ProbeResult result : report.results) {
                currentResults.put(result.target.id, result); completedRequests += result.attempts();
                ProbeResult.Status observed = NetworkAssessment.observedStatus(result);
                if (observed == ProbeResult.Status.AVAILABLE) available++;
                else if (observed == ProbeResult.Status.DEGRADED) degraded++; else unavailable++;
            }
            checkedCount = currentResults.size(); updateCounts();
            String summary = String.join("\n\n", ReportAnalytics.plainOverview(report));
            finishScan(report, summary);
        }
        if ("history".equals(currentTab)) showHistoryTab(); else showSettingsTab();
        settingsPage.showSaved();
    }

    private boolean canRememberBaseline() {
        return lastReport != null && lastReport.complete && lastReport.level != ScanReport.Level.NO_CONNECTION
            && lastReport.level != ScanReport.Level.ALLOWLIST_SUSPECTED && lastReport.level != ScanReport.Level.INCOMPLETE;
    }

    private void confirmRememberBaseline() {
        if (!canRememberBaseline() || scanning) return;
        int dialogTheme = dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
        new AlertDialog.Builder(this, dialogTheme).setTitle(UiLanguage.text("Обычный уровень доступности"))
            .setMessage(UiLanguage.text("Запомнить последний завершённый скан как обычное состояние этой сети? Последующие сканы будут сравниваться с ним."))
            .setPositiveButton(UiLanguage.text("Запомнить"), (confirmation, which) -> {
                ScanReport reference = lastReport;
                executor.submit(() -> {
                    try { NetworkBaseline.replace(getFilesDir().toPath(), reference);
                        ui.post(() -> { if (alive) Toast.makeText(this, UiLanguage.text("Эталон сохранён для следующих сканирований"), Toast.LENGTH_LONG).show(); });
                    } catch (Exception failure) {
                        ui.post(() -> { if (alive) Toast.makeText(this, UiLanguage.text("Не удалось сохранить эталон"), Toast.LENGTH_LONG).show(); });
                    }
                });
            }).setNegativeButton(UiLanguage.text("Отмена"), null).show();
    }

    private ScanProfile scanProfile() { return "quick".equals(getSharedPreferences("settings", MODE_PRIVATE).getString("profile", "deep")) ? ScanProfile.QUICK : ScanProfile.DEEP; }
    private java.nio.file.Path privateStorage() { return getNoBackupFilesDir().toPath(); }
    private boolean radioEnabled() { return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("radio", true); }

    private void askRadioPermissions(boolean startAfter) {
        int dialogTheme = dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
        new AlertDialog.Builder(this, dialogTheme).setTitle(UiLanguage.text("Cell ID, Wi-Fi и Bluetooth"))
            .setMessage(UiLanguage.text("Android требует точное местоположение для Cell ID, Wi-Fi и GPS, а доступ к телефону — для состояния SIM. На Android 12+ Bluetooth требует отдельные разрешения. GPS-координаты, Cell ID, SSID/BSSID и видимые Bluetooth-устройства попадут в локальный лог и могут рассказать о местоположении. IMEI, IMSI и номер телефона не считываются. Без разрешений остальные проверки продолжатся."))
            .setPositiveButton(UiLanguage.text("Разрешить"), (dialog, which) -> {
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("radioAsked", true).apply();
                List<String> missing = new ArrayList<>();
                // Android 12+ requires both location permissions in the same request,
                // including an upgrade from previously granted approximate location.
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                    missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
                }
                if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                    missing.add(Manifest.permission.READ_PHONE_STATE);
                if (Build.VERSION.SDK_INT >= 31) {
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                        missing.add(Manifest.permission.BLUETOOTH_SCAN);
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                        missing.add(Manifest.permission.BLUETOOTH_CONNECT);
                }
                scanAfterPermission = startAfter;
                if (missing.isEmpty()) { if (startAfter) startScan(); }
                else requestPermissions(missing.toArray(new String[0]), RADIO_PERMISSIONS);
            }).setNegativeButton(UiLanguage.text("Без разрешений"), (dialog, which) -> {
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("radioAsked", true).apply();
                if (startAfter) startScan();
                else if (radioSniffAfterPermission) { radioSniffAfterPermission = false; startRadioSnapshot(); }
            }).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RADIO_PERMISSIONS) {
            RadioRuntime.get(this).refreshPermissions();
            if (scanAfterPermission) { scanAfterPermission = false; startScan(); }
            if (radioSniffAfterPermission) { radioSniffAfterPermission = false; startRadioSnapshot(); }
        }
    }

    private boolean isDark() {
        String value = getSharedPreferences("settings", MODE_PRIVATE).getString("theme", "dark");
        if ("dark".equals(value)||"watchdogs".equals(value)) return true; if ("light".equals(value)) return false;
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(background); getWindow().setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Explicit edge-to-edge on older versions too; applySafeArea owns the offsets.
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
            if (controller != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
            }
        } else {
            int layout = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            getWindow().getDecorView().setSystemUiVisibility(layout | (dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        }
    }

    private String appVersion() {
        try { return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return ""; }
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this); view.setText(UiLanguage.text(value)); view.setTextSize(sp); view.setTextColor(color);
        view.setTypeface(Typeface.create(terminal?"monospace":"sans", style)); return view;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(terminal?0:dp(4));if(terminal)drawable.setStroke(dp(1),TerminalTheme.BORDER);
        return drawable;
    }

    private void applyTheme(String theme) {
        if(scanning)return;
        if(!getSharedPreferences("settings",MODE_PRIVATE).edit().putString("theme",theme).commit())return;
        terminal=TerminalTheme.enabled(this);dark=isDark();
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        rebuildForTheme();
    }
    private void applyLanguage(String language) {
        if(scanning)return;
        if(!getSharedPreferences("settings",MODE_PRIVATE).edit().putString("language",language).commit())return;
        UiLanguage.init(this);
        rebuildForTheme();
    }

    private void setPalette() {
        background=terminal?TerminalTheme.BACKGROUND:dark?Color.rgb(8,10,16):Color.rgb(240,244,251);
        surface=terminal?TerminalTheme.SURFACE:dark?Color.rgb(22,25,34):Color.WHITE;
        primary=terminal?TerminalTheme.TEXT:dark?Color.WHITE:Color.rgb(22,30,46);
        secondary=terminal?TerminalTheme.MUTED:dark?Color.rgb(169,176,194):Color.rgb(91,102,126);
    }

    private LinearLayout.LayoutParams margins(int width, int height, float weight,
                                              int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height, weight);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String timing(ProbeResult result) {
        String unit=UiLanguage.isRussian()?" мс":" ms";
        String ping = result.pingMs < 0 ? "Ping —" : "Ping " + result.pingMs + unit;
        if (result.target.probeKind == ru.lighthouse.core.ServiceTarget.ProbeKind.DNS)
            return ping + "  •  DNS " + (result.tcpMs < 0 ? "—" : result.tcpMs + unit);
        return ping + "  •  HTTPS " + (result.httpsMs < 0 ? "—" : result.httpsMs + unit)
            + "  •  TCP " + (result.tcpMs < 0 ? "—" : result.tcpMs + unit);
    }
    private static String shortStatus(ProbeResult.Status status) {
        return status == ProbeResult.Status.AVAILABLE ? "Работает"
            : status == ProbeResult.Status.DEGRADED ? "Ограничен" : "Не работает";
    }
    private static String pluralStatus(ProbeResult.Status status) {
        return status == ProbeResult.Status.AVAILABLE ? "Работают"
            : status == ProbeResult.Status.DEGRADED ? "Ограничены" : "Не работают";
    }
    private static String levelName(ScanReport.Level level) {
        return NetworkAssessment.stateTitle(level);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("settingsOpen", "settings".equals(currentTab));
        state.putString("currentTab", currentTab);
        if (settingsPage != null) {
            state.putString("draftProfile", settingsPage.selectedProfile().name());
            state.putBoolean("draftRadio", settingsPage.selectedRadio());
            state.putString("draftLanguage", settingsPage.selectedLanguage());
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onStop() { RadioRuntime.get(this).foreground(false); super.onStop(); }

    @Override protected void onDestroy() {
        alive = false; scanning = false; ui.removeCallbacksAndMessages(null);
        if(updateNetworkCallback!=null)try{getSystemService(ConnectivityManager.class).unregisterNetworkCallback(updateNetworkCallback);}catch(Exception ignored){}
        if (activeScan != null) activeScan.cancel(true);
        executor.shutdownNow(); archiveExecutor.shutdownNow(); updateManager.shutdown(); super.onDestroy();
    }
}
