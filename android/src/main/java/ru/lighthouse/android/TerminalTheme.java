package ru.lighthouse.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Optional terminal presentation. Measurements and user-entered values are never altered. */
final class TerminalTheme {
    static final int BACKGROUND = Color.rgb(4, 9, 30);
    static final int SURFACE = Color.rgb(10, 22, 53);
    static final int TEXT = Color.rgb(220, 236, 255);
    static final int MUTED = Color.rgb(141, 173, 211);
    static final int ACCENT = Color.rgb(64, 205, 239);
    static final int BORDER = Color.rgb(49, 86, 139);
    private TerminalTheme() {}

    static boolean enabled(Context context) {
        return "watchdogs".equals(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme", "dark"));
    }

    static GradientDrawable panel(Context context, int fill) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill); shape.setCornerRadius(0);
        shape.setStroke(Math.max(1, Math.round(context.getResources().getDisplayMetrics().density)), BORDER);
        return shape;
    }

    static void apply(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTypeface(Typeface.MONOSPACE, text.getTypeface() != null && text.getTypeface().isBold() ? Typeface.BOLD : Typeface.NORMAL);
            text.setLetterSpacing(.035f);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
        }
    }
}
