package ru.lighthouse.android;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Translates application-owned labels; device observations and editable data remain untouched. */
final class UiLanguage {
    private static final List<String[]> translations=new ArrayList<>();
    private static boolean russian;
    private UiLanguage(){}
    static void init(Context context){
        russian="ru".equals(context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("language","en"));
        if(!translations.isEmpty())return;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getAssets().open("ui_language.tsv"),StandardCharsets.UTF_8))){
            String line;while((line=reader.readLine())!=null){int tab=line.indexOf("\\t");if(tab>0)translations.add(new String[]{unescape(line.substring(0,tab)),unescape(line.substring(tab+2))});}
            translations.sort(Comparator.comparingInt((String[] pair)->pair[0].length()).reversed());
        }catch(Exception error){throw new IllegalStateException("UI language catalogue unavailable",error);}
    }
    static boolean isRussian(){return russian;}
    private static String unescape(String value){return value.replace("\\\\n","\n").replace("\\n","\n");}
    static String text(String source){
        if(source==null)return null;
        String result=source;
        if(russian){for(String[] pair:translations)if(result.equals(pair[1]))return pair[0];}
        else{boolean hasRussian=false;for(int i=0;i<source.length();i++)if(source.charAt(i)>='А'&&source.charAt(i)<='я'||source.charAt(i)=='Ё'||source.charAt(i)=='ё'){hasRussian=true;break;}
            if(!hasRussian)return source;
            for(String[] pair:translations)if(result.contains(pair[0]))result=result.replace(pair[0],pair[1]);}
        return result;
    }
    static void apply(View view){
        if(Boolean.TRUE.equals(view.getTag()))return;
        if(view instanceof EditText){EditText input=(EditText)view;CharSequence hint=input.getHint();if(hint!=null)input.setHint(text(hint.toString()));return;}
        if(view instanceof TextView){TextView label=(TextView)view;String original=label.getText().toString(),translated=text(original);if(!original.equals(translated))label.setText(translated);
            CharSequence description=label.getContentDescription();if(description!=null&&!description.toString().equals(text(description.toString())))label.setContentDescription(text(description.toString()));}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)apply(group.getChildAt(i));}
    }
}
