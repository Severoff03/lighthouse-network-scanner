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
    static final int BACKGROUND = Color.rgb(3, 10, 14);
    static final int SURFACE = Color.rgb(9, 25, 30);
    static final int TEXT = Color.rgb(185, 245, 227);
    static final int MUTED = Color.rgb(111, 170, 160);
    static final int ACCENT = Color.rgb(46, 225, 191);
    static final int BORDER = Color.rgb(38, 106, 105);
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
