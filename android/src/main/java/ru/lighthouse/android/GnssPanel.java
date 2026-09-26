package ru.lighthouse.android;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Sky/status UI based on measured GnssStatus values, with inline satellite details. */
final class GnssPanel extends LinearLayout {
    private final Activity activity;private final GnssSurvey survey;private final int foreground,muted,surface,edge;private final boolean terminal;
    private final LinearLayout body,sorts;private final ScrollView scroll;private final EditText search;private final TextView status;
    private final Button graphButton,listButton,levelSort;private final Sky sky;private final SignalBars bars;private final TextView fixBadge,coordinates;
    private boolean active,list,ascending;private String detail;
    private final Map<String,Double> preferredCarrier=new HashMap<>();
    GnssPanel(Activity activity,boolean dark){
        super(activity);this.activity=activity;survey=new GnssSurvey(activity);terminal=TerminalTheme.enabled(activity);foreground=terminal?TerminalTheme.TEXT:dark?0xffe4e8ed:0xff192028;muted=terminal?TerminalTheme.MUTED:dark?0xff97a2ae:0xff546370;surface=terminal?TerminalTheme.SURFACE:dark?0xff161922:Color.WHITE;edge=terminal?TerminalTheme.BORDER:dark?0xff35404d:0xffd2dae2;setOrientation(VERTICAL);
        search=new EditText(activity);search.setSingleLine(true);search.setTextColor(foreground);search.setHintTextColor(muted);search.setTextSize(14);search.setHint("Система или номер спутника");
        LinearLayout modes=new LinearLayout(activity);graphButton=button("Небосвод",()->{list=false;detail=null;refresh();});listButton=button("Список",()->{list=true;detail=null;refresh();});modes.addView(graphButton,new LayoutParams(0,dp(44),1));modes.addView(listButton,new LayoutParams(0,dp(44),1));addView(modes);
        addView(search,new LayoutParams(-1,dp(46)));
        sorts=new LinearLayout(activity);levelSort=button("Уровень ↓",()->{ascending=!ascending;refresh();});sorts.addView(levelSort,new LayoutParams(-1,dp(44)));addView(sorts);
        status=text("Ожидание GPS",13);addView(status);scroll=new ScrollView(activity);body=new LinearLayout(activity);body.setOrientation(VERTICAL);scroll.addView(body);addView(scroll,new LayoutParams(-1,0,1));sky=new Sky();bars=new SignalBars();
        LinearLayout fixRow=new LinearLayout(activity);fixRow.setGravity(Gravity.CENTER_VERTICAL);fixRow.setPadding(dp(12),dp(8),dp(12),dp(8));
        GradientDrawable fixBg=new GradientDrawable();fixBg.setColor(surface);fixBg.setCornerRadius(terminal?0:dp(12));fixBg.setStroke(dp(1),edge);fixRow.setBackground(fixBg);
        fixBadge=text("No fix",17);coordinates=text("—",17);coordinates.setGravity(Gravity.END);coordinates.setTextIsSelectable(true);
        fixRow.addView(fixBadge,new LayoutParams(0,-2,1));fixRow.addView(coordinates,new LayoutParams(0,-2,2));addView(fixRow,new LayoutParams(-1,dp(58)));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){detail=null;refresh();}public void afterTextChanged(Editable e){}});
    }
    void setActive(boolean value){if(active==value){if(active)survey.start();return;}active=value;if(value)survey.start();else survey.stop();}
    @Override protected void onDetachedFromWindow(){setActive(false);super.onDetachedFromWindow();}
    void refresh(){
        if(!active)return;survey.start();long now=SystemClock.elapsedRealtime();boolean fresh=survey.freshSatellites(),fix=survey.freshLocation();int dimension=survey.dimension();
        Set<String> visible=new HashSet<>(),used=new HashSet<>();if(fresh)for(GnssSurvey.Satellite s:survey.satellites){if(Float.isFinite(s.cn0)&&s.cn0>0)visible.add(s.constellation+":"+s.svid);if(s.used)used.add(s.constellation+":"+s.svid);}
        String state=fix&&dimension!=1?(dimension==2?"2D fix":dimension==3?"3D fix":"Fix · 2D/3D не сообщён"):(dimension==1?"Нет fix (NMEA)":"Ожидание fix");
        status.setText(state+" · видны "+visible.size()+" · в решении "+used.size()+(survey.error.isEmpty()?"":"\n"+survey.error));
        graphButton.setAlpha(!list?1:.7f);listButton.setAlpha(list?1:.7f);
        search.setVisibility(list&&detail==null?VISIBLE:GONE);sorts.setVisibility(list&&detail==null?VISIBLE:GONE);
        levelSort.setText("Уровень "+(ascending?"↑":"↓"));
        fixBadge.setText(state);coordinates.setText(fix&&dimension!=1?String.format(Locale.ROOT,"%.6f, %.6f",survey.location.getLatitude(),survey.location.getLongitude()):"Coordinates —");
        Map<String,GnssSurvey.Satellite> unique=new LinkedHashMap<>();
        if(!fresh)preferredCarrier.clear();
        if(fresh)for(GnssSurvey.Satellite satellite:survey.satellites){
            if(!Float.isFinite(satellite.cn0)||satellite.cn0<=0)continue;
            String key=satellite.constellation+":"+satellite.svid;GnssSurvey.Satellite old=unique.get(key);
            Double preferred=preferredCarrier.get(key);
            boolean sameCarrier=preferred!=null&&Double.doubleToLongBits(preferred)==Double.doubleToLongBits(satellite.frequency);
            boolean oldSame=old!=null&&preferred!=null&&Double.doubleToLongBits(preferred)==Double.doubleToLongBits(old.frequency);
            if(old==null||sameCarrier&&!oldSame||sameCarrier==oldSame&&satellite.cn0>old.cn0)unique.put(key,satellite);
        }
        preferredCarrier.keySet().retainAll(unique.keySet());
        for(Map.Entry<String,GnssSurvey.Satellite> entry:unique.entrySet())preferredCarrier.put(entry.getKey(),entry.getValue().frequency);
        List<GnssSurvey.Satellite> values=new ArrayList<>();String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for(GnssSurvey.Satellite satellite:unique.values())if(!list||satellite.title().toLowerCase(Locale.ROOT).contains(q))values.add(satellite);
        values.sort((a,b)->list?(ascending?Float.compare(a.cn0,b.cn0):Float.compare(b.cn0,a.cn0)):a.title().compareTo(b.title()));
        int y=scroll.getScrollY();
        boolean keepGraph=detail==null&&!list&&body.getChildCount()>=2&&body.getChildAt(0)==sky&&body.getChildAt(1)==bars;
        if(keepGraph){while(body.getChildCount()>2)body.removeViewAt(2);}else body.removeAllViews();
        if(detail!=null){body.addView(button("‹ Назад",()->{detail=null;refresh();}));GnssSurvey.Satellite found=null;for(GnssSurvey.Satellite s:values)if(s.id().equals(detail))found=s;if(found==null)body.addView(text("Свежие данные спутника больше не доступны",14));else satelliteDetails(found);}
        else{
            if(list){if(values.isEmpty())body.addView(text("Спутники не получены или скрыты фильтром",14));for(GnssSurvey.Satellite s:values){LinearLayout card=card();TextView title=text(s.title()+" · "+format(s.cn0)+" dB-Hz",17);title.setTextColor(color(s));card.addView(title);card.addView(text((s.used?"Используется в решении":"Виден")+"",12));card.setOnClickListener(v->open(s));body.addView(card);}}
            else{sky.values=values;if(!keepGraph){if(sky.getParent() instanceof ViewGroup)((ViewGroup)sky.getParent()).removeView(sky);body.addView(sky,new LayoutParams(-1,dp(235)));}sky.invalidate();bars.values=values;if(!keepGraph){if(bars.getParent() instanceof ViewGroup)((ViewGroup)bars.getParent()).removeView(bars);body.addView(bars,new LayoutParams(-1,dp(125)));}bars.invalidate();}
            LinearLayout position=card();position.addView(text("Fix details",16));
            if(fix&&dimension!=1){Location l=survey.location;TextView coordinates=text(String.format(Locale.ROOT,"%.6f, %.6f",l.getLatitude(),l.getLongitude()),20);coordinates.setTextIsSelectable(true);position.addView(coordinates);position.addView(text("WGS84 · GPS_PROVIDER · возраст "+Math.max(0,now-l.getElapsedRealtimeNanos()/1_000_000)/1000+(UiLanguage.isRussian()?" с":" s"),12));
                if(l.hasAccuracy())position.addView(text("Горизонтальная точность ±"+format(l.getAccuracy())+" м",14));
                if(l.hasAltitude())position.addView(text("Высота над эллипсоидом "+format(l.getAltitude())+" м",12));
                if(Double.isFinite(survey.msl)&&now-survey.altitudeAt<GnssSurvey.FRESH_MS)position.addView(text("Высота над уровнем моря "+format(survey.msl)+" м (NMEA)",12));
                if(l.hasVerticalAccuracy())position.addView(text("Вертикальная точность ±"+format(l.getVerticalAccuracyMeters())+" м",12));
                if(l.hasSpeed())position.addView(text("Скорость "+format(l.getSpeed())+" м/с",12));
                if(l.hasBearing())position.addView(text("Курс движения "+format(l.getBearing())+"°",12));
                if(l.isFromMockProvider())position.addView(text("ТЕСТОВОЕ местоположение от Android",15));
            }else position.addView(text("Свежего спутникового решения нет. Координаты сети не подставляются.",14));
            if(dimension>1&&survey.dop!=null&&now-survey.dopAt<GnssSurvey.FRESH_MS)position.addView(text("PDOP "+format(survey.dop[0])+" · HDOP "+format(survey.dop[1])+" · VDOP "+format(survey.dop[2]),12));
            body.addView(position);
        }scroll.post(()->scroll.scrollTo(0,y));UiLanguage.apply(this);
    }
    private void satelliteDetails(GnssSurvey.Satellite s){LinearLayout card=card();card.addView(text(s.title(),24));card.addView(text(format(s.cn0)+" dB-Hz",26));card.addView(text(s.used?"Используется при расчёте координат":"Наблюдается приёмником",16));card.addView(text("Система  "+GnssSurvey.constellationName(s.constellation),16));card.addView(text("SVID  "+s.svid,17));card.addView(text("Несущая  "+format(s.frequency)+" MHz",15));card.addView(text("Азимут  "+format(s.azimuth)+"° · высота  "+format(s.elevation)+"°",14));card.addView(text("Эфемериды  "+(s.ephemeris?(UiLanguage.isRussian()?"есть":"yes"):(UiLanguage.isRussian()?"нет":"no"))+" · альманах  "+(s.almanac?(UiLanguage.isRussian()?"есть":"yes"):(UiLanguage.isRussian()?"нет":"no")),12));card.addView(text("Baseband C/N₀  "+format(s.baseband)+" dB-Hz",12));card.addView(text("Источник: Android GnssStatus. C/N₀ — отношение мощности несущей к спектральной плотности шума, не RSSI.",11));body.addView(card);}
    private void open(GnssSurvey.Satellite s){detail=s.id();scroll.scrollTo(0,0);refresh();}
    private int color(GnssSurvey.Satellite s){return Color.HSVToColor(new float[]{Math.floorMod((s.constellation*61+s.svid*137),360),.65f,.85f});}
    private String format(double n){return Double.isFinite(n)?String.format(Locale.ROOT,"%.1f",n):"—";}
    private LinearLayout card(){LinearLayout box=new LinearLayout(activity);box.setOrientation(VERTICAL);box.setPadding(dp(14),dp(12),dp(14),dp(12));GradientDrawable b=new GradientDrawable();b.setColor(surface);b.setCornerRadius(terminal?0:dp(12));b.setStroke(dp(1),edge);box.setBackground(b);LayoutParams p=new LayoutParams(-1,-2);p.bottomMargin=dp(10);box.setLayoutParams(p);return box;}
    private TextView text(String s,int size){TextView t=new TextView(activity);t.setText(UiLanguage.text(s));t.setTextSize(size);t.setTextColor(size<=12?muted:foreground);t.setPadding(0,dp(4),0,dp(4));if(terminal)t.setTypeface(Typeface.MONOSPACE);return t;}
    private Button button(String s,Runnable action){Button b=new Button(activity);b.setText(UiLanguage.text(s));b.setAllCaps(false);b.setTextColor(foreground);b.setTextSize(12);if(terminal){b.setTypeface(Typeface.MONOSPACE);b.setBackground(TerminalTheme.panel(activity,surface));}b.setOnClickListener(v->action.run());return b;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private final class SignalBars extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);List<GnssSurvey.Satellite> values=Collections.emptyList();
        SignalBars(){super(activity);setContentDescription("Satellite signal levels in dB-Hz");}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float left=dp(8),right=getWidth()-dp(8),top=dp(12),bottom=getHeight()-dp(26);
            paint.setColor(muted);paint.setTextSize(dp(10));canvas.drawText("C/N₀ · dB-Hz",left,top,paint);
            if(values.isEmpty())return;float slot=(right-left)/values.size();
            for(int i=0;i<values.size();i++){GnssSurvey.Satellite satellite=values.get(i);if(!Float.isFinite(satellite.cn0)||satellite.cn0<0)continue;
                float x=left+i*slot+slot*.18f,y=bottom-Math.min(60,satellite.cn0)/60f*(bottom-top-dp(12));
                if(terminal){paint.setTypeface(Typeface.MONOSPACE);paint.setColor(satellite.used?0xff23b269:color(satellite));paint.setTextSize(dp(9));for(float at=bottom;at>y;at-=dp(10))canvas.drawText("#",x,at,paint);if(slot>=dp(23)){paint.setColor(muted);canvas.drawText(""+satellite.svid,x,bottom+dp(15),paint);}continue;}
                paint.setColor(color(satellite));canvas.drawRect(x,y,x+slot*.64f,bottom,paint);
                if(satellite.used){paint.setColor(0xff23b269);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));canvas.drawRect(x,y,x+slot*.64f,bottom,paint);paint.setStyle(Paint.Style.FILL);}
                if(slot>=dp(23)){paint.setColor(muted);paint.setTextSize(dp(9));canvas.drawText(""+satellite.svid,x,bottom+dp(15),paint);}
            }
        }
    }
    private final class Sky extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);List<GnssSurvey.Satellite> values=Collections.emptyList();final List<PointF> points=new ArrayList<>();
        Sky(){super(activity);setContentDescription("Небосвод: север сверху, зенит в центре. Список доступен отдельной кнопкой.");}
        @Override protected void onDraw(Canvas c){super.onDraw(c);points.clear();float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y)-dp(30);
            if(terminal){c.drawColor(surface);p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.MONOSPACE);p.setTextSize(dp(12));p.setColor(edge);for(float at=x-r;at<x+r;at+=dp(22))c.drawText("·",at,y,p);for(float at=y-r;at<y+r;at+=dp(22))c.drawText("·",x,at,p);c.drawText("+",x,y,p);}
            else{p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(edge);for(int i=1;i<=3;i++)c.drawCircle(x,y,r*i/3,p);c.drawLine(x-r,y,x+r,y,p);c.drawLine(x,y-r,x,y+r,p);p.setStyle(Paint.Style.FILL);}
            p.setColor(muted);p.setTextSize(dp(13));c.drawText("N",x-dp(5),y-r-dp(10),p);c.drawText("S",x-dp(5),y+r+dp(20),p);c.drawText("W",x-r-dp(22),y+dp(5),p);c.drawText("E",x+r+dp(8),y+dp(5),p);
            for(GnssSurvey.Satellite s:values){if(!Float.isFinite(s.azimuth)||!Float.isFinite(s.elevation)||s.elevation<0||s.elevation>90){points.add(null);continue;}double a=Math.toRadians(s.azimuth);float distance=r*(90-s.elevation)/90;PointF at=new PointF(x+(float)Math.sin(a)*distance,y-(float)Math.cos(a)*distance);points.add(at);p.setColor(s.used?0xff23b269:color(s));p.setStyle(Paint.Style.FILL);
                if(terminal){p.setTextSize(dp(10));p.setTextAlign(Paint.Align.CENTER);c.drawText("["+s.svid+"]",at.x,at.y+dp(3),p);p.setTextAlign(Paint.Align.LEFT);continue;}
                c.drawCircle(at.x,at.y,dp(12),p);if(s.used){p.setColor(0xff23b269);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));c.drawCircle(at.x,at.y,dp(15),p);p.setStyle(Paint.Style.FILL);}p.setColor(Color.WHITE);p.setTextSize(dp(10));p.setTextAlign(Paint.Align.CENTER);c.drawText(""+s.svid,at.x,at.y+dp(3),p);p.setTextAlign(Paint.Align.LEFT);}
        }
        @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN)return true;if(e.getAction()==MotionEvent.ACTION_UP){performClick();List<GnssSurvey.Satellite> hits=new ArrayList<>();for(int i=0;i<points.size();i++){PointF p=points.get(i);if(p!=null&&Math.hypot(e.getX()-p.x,e.getY()-p.y)<dp(22))hits.add(values.get(i));}if(hits.size()==1)open(hits.get(0));else if(hits.size()>1){list=true;refresh();}return true;}return super.onTouchEvent(e);}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
