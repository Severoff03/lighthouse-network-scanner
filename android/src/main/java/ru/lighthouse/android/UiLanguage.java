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
            String line;while((line=reader.readLine())!=null){int tab=line.indexOf("\\t");if(tab>0){String from=unescape(line.substring(0,tab));String word=from.trim();translations.add(new String[]{from,unescape(line.substring(tab+2)),String.valueOf(!word.isEmpty()&&word.codePoints().allMatch(Character::isLetter))});}}
            translations.sort(Comparator.comparingInt((String[] pair)->pair[0].length()).reversed());
        }catch(Exception error){throw new IllegalStateException("UI language catalogue unavailable",error);}
    }
    static boolean isRussian(){return russian;}
    private static String unescape(String value){return value.replace("\\\\n","\n").replace("\\n","\n").replace("\\s"," ");}
    static String text(String source){
        if(source==null)return null;
        String result=source;
        if(russian){for(String[] pair:translations)if(result.equals(pair[1]))return pair[0];}
        else{boolean hasRussian=false;for(int i=0;i<source.length();i++)if(source.charAt(i)>='А'&&source.charAt(i)<='я'||source.charAt(i)=='Ё'||source.charAt(i)=='ё'){hasRussian=true;break;}
            if(!hasRussian)return source;
            for(String[] pair:translations)if(result.contains(pair[0]))result="true".equals(pair[2])
                ?replaceWord(result,pair[0],pair[1]):result.replace(pair[0],pair[1]);}
        return result;
    }
    private static String replaceWord(String source,String from,String to){
        StringBuilder out=new StringBuilder();int cursor=0,at;
        String word=from.trim();int offset=from.indexOf(word);
        while((at=source.indexOf(from,cursor))>=0){int end=at+from.length(),wordStart=at+offset,wordEnd=wordStart+word.length();
            if((wordStart==0||!Character.isLetter(source.charAt(wordStart-1)))&&(wordEnd==source.length()||!Character.isLetter(source.charAt(wordEnd)))){
                out.append(source,cursor,at).append(to);cursor=end;
            }else{out.append(source,cursor,end);cursor=end;}
        }
        return out.append(source,cursor,source.length()).toString();
    }
    static void apply(View view){
        if(Boolean.TRUE.equals(view.getTag()))return;
        CharSequence description=view.getContentDescription();
        if(description!=null&&!description.toString().equals(text(description.toString())))view.setContentDescription(text(description.toString()));
        if(view instanceof EditText){EditText input=(EditText)view;CharSequence hint=input.getHint();if(hint!=null)input.setHint(text(hint.toString()));return;}
        if(view instanceof TextView){TextView label=(TextView)view;String original=label.getText().toString(),translated=text(original);if(!original.equals(translated))label.setText(translated);}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)apply(group.getChildAt(i));}
    }
}
