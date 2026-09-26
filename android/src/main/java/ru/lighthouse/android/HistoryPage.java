package ru.lighthouse.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import ru.lighthouse.core.NetworkAssessment;
import ru.lighthouse.core.ScanArchive;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Local scan archive. No data leaves the device until a user selects Export. */
final class HistoryPage extends ScrollView {
    interface Listener { void refresh(int limit); void export(String relativeLog); }

    private static final int ACCENT = Color.rgb(38, 198, 218);
    private final int surface, primary, secondary;
    private final Listener listener;
    private final LinearLayout scans, changes;
    private final TextView status;
    private int limit = 100;
    private final DateTimeFormatter time = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        .withZone(ZoneId.systemDefault());

    HistoryPage(Context context, boolean dark, Listener listener) {
        super(context); this.listener = listener;
        surface = dark ? Color.rgb(22, 25, 34) : Color.WHITE;
        primary = dark ? Color.WHITE : Color.rgb(22, 30, 46);
        secondary = dark ? Color.rgb(169, 176, 194) : Color.rgb(91, 102, 126);
        setFillViewport(true);
        LinearLayout root = column(); root.setPadding(dp(18), dp(12), dp(18), dp(18));
        root.addView(text("История", 26, primary, true));
        TextView privacy = text("Логи сохраняются автоматически только внутри приложения. Они никуда не отправляются без экспорта.", 14, secondary, false);
        privacy.setPadding(0, dp(6), 0, dp(14)); root.addView(privacy);
        Button refresh = button("Обновить историю"); refresh.setOnClickListener(v -> requestRefresh());
        root.addView(refresh, spaced());
        status = text("Загрузка…", 13, secondary, false); status.setPadding(0, 0, 0, dp(12)); root.addView(status);

        root.addView(text("Сохранённые сканы", 19, primary, true), spaced());
        scans = column(); root.addView(scans);
        root.addView(text("Динамика доступности", 19, primary, true), spaced());
        TextView warning = text("Недоступность сервиса сама по себе не доказывает блокировку: возможны авария, фильтрация или сбой сети.", 12, secondary, false);
        warning.setPadding(0, 0, 0, dp(10)); root.addView(warning);
        changes = column(); root.addView(changes);
        Button more = button("Показать ещё 100 сканов");
        more.setOnClickListener(v -> { limit += 100; requestRefresh(); }); root.addView(more, spaced());
        addView(root);
    }

    void requestRefresh() { listener.refresh(limit); }

    void showLoading() { status.setText("Загрузка…"); }

    void show(List<ScanArchive.Entry> entries, List<ScanArchive.Change> events) {
        scans.removeAllViews(); changes.removeAllViews();
        status.setText(entries.isEmpty() ? "Сканов пока нет." : "Сохранено сканов: " + entries.size());
        if (entries.isEmpty()) scans.addView(empty("После завершения скана здесь появится локальный лог."), spaced());
        for (ScanArchive.Entry entry : entries) scans.addView(scanCard(entry), spaced());
        if (events.isEmpty()) changes.addView(empty(entries.size() < 2
            ? "Для сравнения нужны два полных скана одной сети." : "Изменений между полными сканами не обнаружено."), spaced());
        for (ScanArchive.Change event : events) changes.addView(changeCard(event), spaced());
    }

    private LinearLayout scanCard(ScanArchive.Entry entry) {
        LinearLayout card = card();
        String title = NetworkAssessment.stateTitle(entry.level);
        card.addView(text(time.format(entry.timestamp) + "  ·  " + title, 15, stateColor(entry.level), true));
        card.addView(text(entry.available + " работают  ·  " + entry.degraded + " ограничены  ·  "
            + entry.unavailable + " не работают" + (entry.complete ? "" : "\nНеполный скан"), 13, secondary, false), control());
        Button export = button("Выгрузить лог"); export.setOnClickListener(v -> listener.export(entry.logFile));
        card.addView(export, control()); return card;
    }

    private LinearLayout changeCard(ScanArchive.Change event) {
        LinearLayout card = card();
        int color = event.after == ru.lighthouse.core.ProbeResult.Status.UNAVAILABLE ? Color.rgb(255, 91, 105)
            : event.after == ru.lighthouse.core.ProbeResult.Status.AVAILABLE ? Color.rgb(53, 211, 153) : Color.rgb(255, 183, 77);
        card.addView(text(event.description() + " · " + event.serviceName, 15, color, true));
        card.addView(text(event.category + "  ·  " + time.format(event.timestamp), 12, secondary, false), control());
        return card;
    }

    private int stateColor(ru.lighthouse.core.ScanReport.Level level) {
        NetworkAssessment.State state = NetworkAssessment.stateFor(level);
        if (state == NetworkAssessment.State.GREEN) return Color.rgb(53, 211, 153);
        if (state == NetworkAssessment.State.YELLOW) return Color.rgb(255, 183, 77);
        if (state == NetworkAssessment.State.RED) return Color.rgb(255, 91, 105);
        if (state == NetworkAssessment.State.BLACK) return darkText();
        return secondary;
    }

    private int darkText() { return primary == Color.WHITE ? Color.rgb(225, 225, 230) : Color.BLACK; }
    private TextView empty(String value) { TextView view = text(value, 13, secondary, false); view.setGravity(Gravity.CENTER); view.setPadding(dp(14), dp(18), dp(14), dp(18)); view.setBackground(round(surface)); return view; }
    private LinearLayout card() { LinearLayout value = column(); value.setPadding(dp(15), dp(14), dp(15), dp(14)); value.setBackground(round(surface)); return value; }
    private LinearLayout column() { LinearLayout value = new LinearLayout(getContext()); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private TextView text(String value, int sp, int color, boolean bold) { TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(sp); view.setTextColor(color); view.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL)); view.setLineSpacing(0, 1.1f); return view; }
    private Button button(String label) { Button value = new Button(getContext()); value.setText(label); value.setAllCaps(false); value.setTextSize(14); value.setTypeface(null, Typeface.BOLD); value.setTextColor(primary); GradientDrawable shape = round(surface); shape.setStroke(dp(1), ACCENT); value.setBackground(shape); value.setMinHeight(dp(46)); return value; }
    private GradientDrawable round(int color) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(17)); return shape; }
    private LinearLayout.LayoutParams spaced() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(14); return p; }
    private LinearLayout.LayoutParams control() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
