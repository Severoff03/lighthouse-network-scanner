package ru.lighthouse.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;

import java.util.ArrayList;
import java.util.List;

import ru.lighthouse.core.NamedConfiguration;
import ru.lighthouse.core.ScanProfile;

/** A persistent tab, not a dialog. It owns only the settings form, never the scan. */
final class SettingsPage extends ScrollView {
    interface Listener {
        void themeChanged(String theme);
        void save(String theme, String language, ScanProfile profile, boolean radio, String detector404Token,
                  List<NamedConfiguration> telegramProxies, List<NamedConfiguration> vpnProfiles);
        void requestPermissions();
        void requestUsageAccess();
        void rememberBaseline();
        void exportErrors();
        void checkForUpdates();
        void installUpdate();
    }

    private static final int ACCENT = Color.rgb(38, 198, 218);
    private final int surface, primary, secondary;
    private final Context controlsContext;
    private final Spinner theme, language, depth;
    private final CheckBox radio;
    private final EditText detectorToken;
    private final LinearLayout proxyRows, vpnRows;
    private final List<ConfigRow> proxyFields = new ArrayList<>(), vpnFields = new ArrayList<>();
    private final Button permissions, usageAccess, baseline, save, addProxy, addVpn;
    private final Button checkUpdates, installUpdate;
    private final TextView scanNotice, saveHint, usageStatus, updateStatus;

    SettingsPage(Context context, boolean dark, String savedTheme, ScanProfile profile, boolean radioEnabled,
                 boolean usageGranted, String savedDetectorToken, List<NamedConfiguration> savedTelegramProxies,
                 List<NamedConfiguration> savedVpnProfiles, String version, boolean updatesConfigured, Listener listener) {
        super(context);
        controlsContext = new ContextThemeWrapper(context, dark
            ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        boolean terminal=TerminalTheme.enabled(context);
        surface = terminal ? TerminalTheme.SURFACE : dark ? Color.rgb(22, 25, 34) : Color.WHITE;
        primary = terminal ? TerminalTheme.TEXT : dark ? Color.WHITE : Color.rgb(22, 30, 46);
        secondary = terminal ? TerminalTheme.MUTED : dark ? Color.rgb(169, 176, 194) : Color.rgb(91, 102, 126);
        setFillViewport(true);
        LinearLayout root = column(); root.setPadding(dp(18), dp(12), dp(18), dp(18));
        root.addView(text("Настройки", 26, primary, true));
        TextView description = text("Оформление и параметры диагностики", 14, secondary, false);
        description.setPadding(0, dp(6), 0, dp(18)); root.addView(description);
        scanNotice = text("Сканирование продолжается. Изменить параметры можно после его завершения.", 14, primary, false);
        scanNotice.setPadding(dp(16), dp(14), dp(16), dp(14)); scanNotice.setBackground(round(surface));
        scanNotice.setVisibility(GONE); root.addView(scanNotice, spaced());

        LinearLayout appearance = card(root, "Оформление");
        appearance.addView(text("Тема приложения", 13, secondary, false));
        theme = spinner(new String[]{"Чёрная", "Светлая", "Системная", "Watch Dogs // Terminal"}, "Тема приложения");
        theme.setSelection("light".equals(savedTheme) ? 1 : "system".equals(savedTheme) ? 2 : "watchdogs".equals(savedTheme) ? 3 : 0);
        theme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String chosen=selectedTheme();
                if(!chosen.equals(context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("theme","dark")))
                    listener.themeChanged(chosen);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        appearance.addView(theme, control());
        appearance.addView(text("Системная тема следует оформлению устройства.", 12, secondary, false));
        appearance.addView(text("Language / Язык",13,secondary,false));
        language=spinner(new String[]{"English","Русский"},"Language / Язык");
        language.setSelection(UiLanguage.isRussian()?1:0);appearance.addView(language,control());

        LinearLayout updates = card(root, "Обновления");
        updates.addView(text("Lighthouse проверяет последний GitHub Release при запуске и периодически в фоне. При недоступности GitHub проверяется локальный Mothman. APK скачивается после подтверждения и передаётся системному установщику Android.", 13, secondary, false));
        updateStatus = text(updatesConfigured ? "Проверка ещё не выполнялась" : "Обновления не настроены для этой сборки.", 13, secondary, true);
        updateStatus.setPadding(0, dp(8), 0, 0); updates.addView(updateStatus);
        checkUpdates = button("Проверить наличие обновлений", false);
        checkUpdates.setOnClickListener(v -> listener.checkForUpdates()); updates.addView(checkUpdates, control());
        installUpdate = button("Обновить приложение", true);
        installUpdate.setVisibility(GONE); installUpdate.setOnClickListener(v -> listener.installUpdate());
        updates.addView(installUpdate, control());

        LinearLayout scanning = card(root, "Сканирование");
        scanning.addView(text("Глубина диагностики", 13, secondary, false));
        depth = spinner(new String[]{"Глубокая · 2 прохода", "Быстрая · 1 проход"}, "Глубина диагностики");
        depth.setSelection(profile == ScanProfile.DEEP ? 0 : 1); scanning.addView(depth, control());
        scanning.addView(text("Глубокая проверка занимает до 5 минут. Повторные замеры помогают оценить стабильность сети.", 13, secondary, false));
        radio = new CheckBox(controlsContext); radio.setText("Радиодиагностика"); radio.setTextSize(15); radio.setTextColor(primary);
        radio.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[0]}, new int[]{terminal?TerminalTheme.ACCENT:ACCENT, secondary}));
        radio.setChecked(radioEnabled); scanning.addView(radio, control());
        scanning.addView(text("Cell ID, Wi-Fi, Bluetooth, GPS и координаты по GPS/сотовой сети в глубоком скане. Эти данные раскрывают местоположение и попадут только в локальный лог. Текущие измерения доступны во вкладке Radio.", 13, secondary, false));
        LinearLayout permissionsSection=card(root,"Permissions");
        permissionsSection.addView(text("Manage access to Wi-Fi, Bluetooth, Cell and location observations.",13,secondary,false));
        permissions = button("Доступ к радиоданным", false);
        permissions.setOnClickListener(v -> listener.requestPermissions()); permissionsSection.addView(permissions, control());

        LinearLayout security = card(root, "Безопасность устройства");
        security.addView(text("Показывает приложения, передававшие данные в фоне за последние 24 часа. Это повод для ручной проверки, а не автоматический диагноз шпионского ПО. Адреса серверов Android не раскрывает.", 13, secondary, false));
        usageStatus = text("", 13, secondary, true); usageStatus.setPadding(0, dp(8), 0, 0); security.addView(usageStatus);
        usageAccess = button("Доступ к статистике приложений", false);
        usageAccess.setOnClickListener(v -> listener.requestUsageAccess()); security.addView(usageAccess, control());
        updateUsageAccess(usageGranted);

        LinearLayout external = card(root, "Detector404 API");
        external.addView(text("Добавляет агрегированные сообщения о сбоях к локальным измерениям. Нужен личный токен из профиля Detector404; он хранится только до закрытия приложения и в лог не записывается.", 13, secondary, false));
        detectorToken = new EditText(controlsContext); detectorToken.setHint("Токен API"); detectorToken.setText(savedDetectorToken);
        detectorToken.setTextColor(primary); detectorToken.setHintTextColor(secondary); detectorToken.setSingleLine(true);
        detectorToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        detectorToken.setContentDescription("Токен Detector404 API"); external.addView(detectorToken, control());

        LinearLayout proxy = card(root, "Прокси Telegram");
        proxy.addView(text("Добавьте до 12 прокси. SOCKS5/HTTP проверяются туннелем до Telegram API; для MTProto — доступность узла. Встроенные публичные прокси не являются доверенными: оператор видит ваш IP. Удерживайте строку, чтобы скопировать ссылку.", 13, secondary, false));
        proxyRows = column();
        addProxy = button("Добавить прокси", false);
        addProxy.setOnClickListener(v -> showAddDialog(proxyRows, proxyFields, true, "Прокси"));
        proxy.addView(addProxy, control());
        proxy.addView(proxyRows, control());
        if (savedTelegramProxies != null) for (NamedConfiguration item : savedTelegramProxies) addRow(proxyRows, proxyFields, item);

        LinearLayout vpn = card(root, "Тестирование VPN");
        vpn.addView(text("Добавьте VPN-сервер или ссылку-подписку. Lighthouse проверяет доступность, но не импортирует ключи и не включает VPN. Публичные подписки ведут к узлам неизвестных операторов; не используйте их для банкинга. Удерживайте строку для копирования.", 13, secondary, false));
        vpnRows = column();
        addVpn = button("Добавить VPN-сервер", false);
        addVpn.setOnClickListener(v -> showAddDialog(vpnRows, vpnFields, false, "VPN"));
        vpn.addView(addVpn, control());
        vpn.addView(vpnRows, control());
        if (savedVpnProfiles != null) for (NamedConfiguration item : savedVpnProfiles) addRow(vpnRows, vpnFields, item);

        LinearLayout normal = card(root, "Обычный уровень сети");
        normal.addView(text("Первый полный скан с обычной доступностью запоминается автоматически. Эталон можно заменить последним завершённым сканом — кроме отсутствия интернета или признаков белых списков.", 13, secondary, false));
        baseline = button("Запомнить последний скан", false);
        baseline.setOnClickListener(v -> listener.rememberBaseline()); normal.addView(baseline, control());

        LinearLayout errors = card(root, "Журнал ошибок");
        errors.addView(text("Сохраните журнал, если приложение работает неправильно. Перед передачей проверьте его содержимое.", 13, secondary, false));
        Button export = button("Сохранить журнал ошибок", false);
        export.setOnClickListener(v -> listener.exportErrors()); errors.addView(export, control());

        save = button("Сохранить настройки", true);
        save.setOnClickListener(v -> listener.save(selectedTheme(), selectedLanguage(), selectedProfile(), radio.isChecked(),
            detectorToken.getText().toString().trim(), collect(proxyFields), collect(vpnFields)));
        root.addView(save, control());
        saveHint = text("Тема применяется сразу и сохраняется. Остальные параметры сохраняются кнопкой выше.", 12, secondary, false);
        saveHint.setPadding(0, dp(10), 0, dp(12)); root.addView(saveHint);
        TextView footer = text("Lighthouse " + version + "\nmade by Mothman", 12, secondary, false);
        footer.setGravity(Gravity.CENTER); footer.setPadding(0, dp(12), 0, dp(8)); root.addView(footer);
        addView(root);
    }

    String selectedTheme() { return theme.getSelectedItemPosition() == 1 ? "light" : theme.getSelectedItemPosition() == 2 ? "system" : theme.getSelectedItemPosition() == 3 ? "watchdogs" : "dark"; }
    String selectedLanguage(){return language.getSelectedItemPosition()==1?"ru":"en";}
    void restoreLanguage(String value){language.setSelection("ru".equals(value)?1:0);}
    ScanProfile selectedProfile() { return depth.getSelectedItemPosition() == 0 ? ScanProfile.DEEP : ScanProfile.QUICK; }
    boolean selectedRadio() { return radio.isChecked(); }

    void restoreDraft(String value, ScanProfile profile, boolean collectRadio) {
        theme.setSelection("light".equals(value) ? 1 : "system".equals(value) ? 2 : "watchdogs".equals(value) ? 3 : 0);
        depth.setSelection(profile == ScanProfile.DEEP ? 0 : 1); radio.setChecked(collectRadio);
    }

    void updateAvailability(boolean scanning, boolean canRememberBaseline) {
        scanNotice.setVisibility(scanning ? VISIBLE : GONE);
        for (View control : new View[]{theme, language, depth, radio, permissions, usageAccess, detectorToken, addProxy, addVpn, save}) {
            control.setEnabled(!scanning); control.setAlpha(scanning ? .55f : 1f);
        }
        for (ConfigRow row : proxyFields) row.enabled(!scanning);
        for (ConfigRow row : vpnFields) row.enabled(!scanning);
        baseline.setEnabled(!scanning && canRememberBaseline);
        baseline.setAlpha(baseline.isEnabled() ? 1f : .55f);
    }

    void updateUsageAccess(boolean granted) {
        usageStatus.setText(UiLanguage.text(granted ? "Доступ предоставлен" : "Доступ не предоставлен"));
        usageStatus.setTextColor(granted ? Color.rgb(53, 211, 153) : Color.rgb(255, 183, 77));
    }

    void showSaved() { saveHint.setText(UiLanguage.text("Настройки сохранены. Результаты предыдущей проверки остались во вкладке «Сканирование».")); }

    void updateChecking() {
        checkUpdates.setEnabled(false); checkUpdates.setAlpha(.55f);
        installUpdate.setVisibility(GONE); updateStatus.setText(UiLanguage.text("Проверяем наличие новой версии…"));
        updateStatus.setTextColor(secondary);
    }

    void updateAvailable(String version) {
        checkUpdates.setEnabled(true); checkUpdates.setAlpha(1f);
        installUpdate.setVisibility(VISIBLE); updateStatus.setText(UiLanguage.text("Доступна " + version + ". Нажмите «Обновить приложение», чтобы скачать APK."));
        updateStatus.setTextColor(Color.rgb(53, 211, 153));
    }

    void updateNoUpdate() {
        checkUpdates.setEnabled(true); checkUpdates.setAlpha(1f);
        installUpdate.setVisibility(GONE); updateStatus.setText(UiLanguage.text("Установлена последняя доступная версия."));
        updateStatus.setTextColor(Color.rgb(53, 211, 153));
    }

    void updateDownloadProgress(int percent) {
        checkUpdates.setEnabled(false); checkUpdates.setAlpha(.55f); installUpdate.setVisibility(GONE);
        updateStatus.setText(UiLanguage.text("Загрузка обновления… " + percent + "%")); updateStatus.setTextColor(secondary);
    }

    void updateReady() {
        checkUpdates.setEnabled(true); checkUpdates.setAlpha(1f);
        updateStatus.setText(UiLanguage.text("APK передан системному установщику.")); updateStatus.setTextColor(Color.rgb(53, 211, 153));
    }

    void updateError(String message) {
        checkUpdates.setEnabled(true); checkUpdates.setAlpha(1f); updateStatus.setText(message); updateStatus.setTextColor(Color.rgb(255, 183, 77));
    }

    private void showAddDialog(LinearLayout container, List<ConfigRow> rows, boolean secret, String prefix) {
        if (rows.size() >= 12) { Toast.makeText(getContext(), UiLanguage.text("Можно добавить не больше 12 записей"), Toast.LENGTH_SHORT).show(); return; }
        LinearLayout form = column(); form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = edit("Название", false);
        EditText value = edit(secret ? "socks5://…  http://…  tg://proxy?…" : "tcp://host:port или https://…", secret);
        form.addView(name, control()); form.addView(value, control());
        AlertDialog dialog = new AlertDialog.Builder(controlsContext)
            .setTitle(UiLanguage.text("Добавить " + prefix.toLowerCase(java.util.Locale.ROOT)))
            .setView(form).setNegativeButton(UiLanguage.text("Отмена"), null).setPositiveButton(UiLanguage.text("Добавить"), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String enteredName = name.getText().toString().trim();
            String enteredValue = value.getText().toString().trim();
            if (enteredName.isBlank()) { name.setError("Укажите название"); return; }
            if (enteredValue.isBlank()) { value.setError("Укажите ссылку или адрес"); return; }
            addRow(container, rows, new NamedConfiguration(enteredName, enteredValue)); dialog.dismiss();
        }));
        dialog.show();
    }

    private void addRow(LinearLayout container, List<ConfigRow> rows, NamedConfiguration saved) {
        if (saved == null || rows.size() >= 12) return;
        LinearLayout holder = new LinearLayout(getContext()); holder.setGravity(Gravity.CENTER_VERTICAL);
        holder.setPadding(dp(12), dp(10), dp(8), dp(10));
        GradientDrawable outline = round(surface); outline.setStroke(dp(1), secondary); holder.setBackground(outline);
        LinearLayout description = column(); TextView savedName=text(saved.name,15,primary,true);savedName.setTag(Boolean.TRUE);description.addView(savedName);
        TextView preview=text(saved.safePreview(),12,secondary,false);preview.setTag(Boolean.TRUE);description.addView(preview);
        holder.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        Button remove = button("", false); remove.setContentDescription("Удалить " + saved.name);
        remove.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_delete, 0, 0, 0);
        ConfigRow row = new ConfigRow(holder, saved, remove);
        holder.setContentDescription(saved.name + ". Удерживайте, чтобы скопировать ссылку.");
        holder.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(saved.name, saved.value));
            Toast.makeText(getContext(), UiLanguage.text("Ссылка скопирована"), Toast.LENGTH_SHORT).show();
            return true;
        });
        remove.setOnClickListener(v -> new AlertDialog.Builder(controlsContext)
            .setTitle(UiLanguage.text("Удалить «") + saved.name + "»?")
            .setMessage(UiLanguage.text("Настройка будет удалена из текущего списка."))
            .setNegativeButton(UiLanguage.text("Отмена"), null)
            .setPositiveButton(UiLanguage.text("Удалить"), (dialog, which) -> { rows.remove(row); container.removeView(holder); })
            .show());
        holder.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48))); rows.add(row);
        LinearLayout.LayoutParams params = control(); params.bottomMargin = dp(10); container.addView(holder, params);
    }

    private EditText edit(String hint, boolean secret) {
        EditText value = new EditText(controlsContext); value.setHint(hint); value.setTextColor(primary);
        value.setHintTextColor(secondary); value.setSingleLine(true);
        value.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        return value;
    }

    private List<NamedConfiguration> collect(List<ConfigRow> rows) {
        List<NamedConfiguration> values = new ArrayList<>();
        for (ConfigRow row : rows) values.add(row.configuration);
        return values;
    }

    private static final class ConfigRow {
        final View holder; final NamedConfiguration configuration; final Button remove;
        ConfigRow(View holder, NamedConfiguration configuration, Button remove) {
            this.holder = holder; this.configuration = configuration; this.remove = remove;
        }
        void enabled(boolean enabled) {
            holder.setAlpha(enabled ? 1f : .55f); remove.setEnabled(enabled);
        }
    }

    private Spinner spinner(String[] values, String description) {
        Spinner value = new Spinner(controlsContext, Spinner.MODE_DROPDOWN);
        String[] displayed=new String[values.length];for(int i=0;i<values.length;i++)displayed[i]=UiLanguage.text(values[i]);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(controlsContext, android.R.layout.simple_spinner_item, displayed) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View row=super.getView(position,convertView,parent);if(TerminalTheme.enabled(getContext()))TerminalTheme.apply(row);return row;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View row=super.getDropDownView(position,convertView,parent);if(TerminalTheme.enabled(getContext()))TerminalTheme.apply(row);return row;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        value.setAdapter(adapter); value.setContentDescription(description); value.setMinimumHeight(dp(48));
        value.setBackgroundTintList(ColorStateList.valueOf(secondary));if(TerminalTheme.enabled(getContext()))value.setPopupBackgroundDrawable(TerminalTheme.panel(getContext(),surface));return value;
    }

    private LinearLayout column() { LinearLayout value = new LinearLayout(getContext()); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout value = column(); value.setPadding(dp(16), dp(16), dp(16), dp(16)); value.setBackground(round(surface));
        TextView heading = text(title, 18, primary, true); heading.setPadding(0, 0, 0, dp(12));
        LinearLayout body = column();
        boolean expanded = "Оформление".equals(title) || "Сканирование".equals(title);
        body.setVisibility(expanded ? VISIBLE : GONE);
        heading.setText(UiLanguage.text(title + (expanded ? " −" : " +")));
        heading.setOnClickListener(v -> { boolean open = body.getVisibility() != VISIBLE;
            body.setVisibility(open ? VISIBLE : GONE); heading.setText(UiLanguage.text(title + (open ? " −" : " +"))); });
        value.addView(heading); value.addView(body); root.addView(value, spaced()); return body;
    }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(getContext()); view.setText(UiLanguage.text(value)); view.setTextSize(sp); view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL)); view.setLineSpacing(0, 1.1f); return view;
    }
    private Button button(String label, boolean primaryAction) {
        Button value = new Button(controlsContext); value.setText(UiLanguage.text(label)); value.setAllCaps(false); value.setTextSize(14);
        value.setTypeface(null, Typeface.BOLD); value.setMinHeight(dp(48)); value.setMinimumHeight(dp(48));
        value.setPadding(dp(12), dp(10), dp(12), dp(10)); value.setTextColor(primaryAction ? (TerminalTheme.enabled(getContext())?TerminalTheme.BACKGROUND:Color.rgb(5, 31, 38)) : primary);
        GradientDrawable shape = round(primaryAction ? (TerminalTheme.enabled(getContext())?TerminalTheme.ACCENT:ACCENT) : surface);
        if (!primaryAction) shape.setStroke(dp(1), secondary);
        value.setBackground(shape); return value;
    }
    private GradientDrawable round(int color) {
        if(TerminalTheme.enabled(getContext()))return TerminalTheme.panel(getContext(),color);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(4)); return shape;
    }
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(14); return params;
    }
    private LinearLayout.LayoutParams control() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(8); params.bottomMargin = dp(8); return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
