package ru.lighthouse.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** App-styled, centered choices without a platform alert dialog. */
final class ChoiceOverlay {
    private ChoiceOverlay(){}

    static void show(Activity activity,View anchor,boolean dark,String title,String[] options,
                     boolean[] selected,boolean single,IntConsumer onChoice){
        boolean terminal=TerminalTheme.enabled(activity);
        int surface=terminal?TerminalTheme.SURFACE:dark?0xff161922:Color.WHITE;
        int ink=terminal?TerminalTheme.TEXT:dark?Color.WHITE:0xff192028;
        int border=terminal?TerminalTheme.BORDER:dark?0xff35404d:0xffd2dae2;
        int accent=terminal?TerminalTheme.ACCENT:0xff26c6da;
        int density=Math.max(1,Math.round(activity.getResources().getDisplayMetrics().density));
        FrameLayout shade=new FrameLayout(activity);shade.setBackgroundColor(terminal?0xc900061b:0x99000000);
        PopupWindow popup=new PopupWindow(shade,-1,-1,true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(16*density,14*density,16*density,16*density);
        GradientDrawable panelShape=new GradientDrawable();panelShape.setColor(surface);panelShape.setCornerRadius(terminal?0:14*density);panelShape.setStroke(density,border);panel.setBackground(panelShape);
        FrameLayout.LayoutParams placement=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);placement.leftMargin=20*density;placement.rightMargin=20*density;shade.addView(panel,placement);
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=new TextView(activity);name.setText(title);name.setTextColor(ink);name.setTextSize(18);name.setTypeface(terminal?Typeface.MONOSPACE:Typeface.DEFAULT,Typeface.BOLD);
        heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        Button close=new Button(activity);close.setText("×");close.setAllCaps(false);close.setTextColor(ink);close.setTextSize(23);close.setContentDescription(UiLanguage.isRussian()?"Закрыть":"Close");
        close.setBackground(new ColorDrawable(Color.TRANSPARENT));close.setOnClickListener(v->popup.dismiss());heading.addView(close,new LinearLayout.LayoutParams(48*density,48*density));
        panel.addView(heading);
        Button[] optionButtons=new Button[options.length];
        for(int i=0;i<options.length;i++){final int index=i;Button option=new Button(activity);optionButtons[i]=option;option.setText(options[i]);option.setAllCaps(false);option.setTextSize(14);
            if(terminal)option.setTypeface(Typeface.MONOSPACE);style(option,selected[i],terminal,surface,ink,border,accent,density);
            LinearLayout.LayoutParams row=new LinearLayout.LayoutParams(-1,46*density);row.topMargin=5*density;panel.addView(option,row);
            option.setOnClickListener(v->{onChoice.accept(index);if(single)popup.dismiss();else for(int j=0;j<optionButtons.length;j++)style(optionButtons[j],selected[j],terminal,surface,ink,border,accent,density);});
        }
        shade.setOnClickListener(v->popup.dismiss());panel.setOnClickListener(v->{});
        popup.showAtLocation(anchor,Gravity.CENTER,0,0);
    }
    private static void style(Button option,boolean selected,boolean terminal,int surface,int ink,int border,int accent,int density){
        GradientDrawable shape=new GradientDrawable();shape.setColor(selected?accent:surface);shape.setCornerRadius(terminal?0:7*density);shape.setStroke(density,selected?accent:border);
        option.setBackground(shape);option.setTextColor(selected?(terminal?TerminalTheme.BACKGROUND:0xff052025):ink);
    }
}
