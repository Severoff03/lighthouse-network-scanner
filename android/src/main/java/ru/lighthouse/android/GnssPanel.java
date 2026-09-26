package ru.lighthouse.android;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.*;
import android.content.Context;
import android.location.Location;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Sky/status UI based on measured GnssStatus values, with inline satellite details. */
final class GnssPanel extends LinearLayout {
    private final Activity activity;private final GnssSurvey survey;private final int foreground,muted,surface,edge;private final boolean terminal,dark;
    private final LinearLayout body,sorts;private final ScrollView scroll;private final EditText search;private final TextView status;
    private final Button graphButton,skyButton,positionButton,listButton,levelSort;private final Sky sky;private final SignalBars bars;private final TextView fixBadge,coordinates;
    private final SensorManager sensors;private final Sensor rotationVector,accelerationSensor;
    private final Set<Integer> hiddenSystems=new HashSet<>();
    private final Map<String,float[]> smoothSky=new HashMap<>();
    private boolean active,ascending,sensorActive,headingKnown;private int page,coordinateFormat;private float heading;private float acceleration=Float.NaN;
    private GnssSurvey.Satellite detailSnapshot;
    private final SensorEventListener orientation=new SensorEventListener(){
        public void onSensorChanged(SensorEvent event){
            if(event.sensor.getType()==Sensor.TYPE_LINEAR_ACCELERATION){float[] v=event.values;acceleration=(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);return;}
            if(event.sensor.getType()!=Sensor.TYPE_ROTATION_VECTOR)return;
            float[] matrix=new float[9],angles=new float[3];SensorManager.getRotationMatrixFromVector(matrix,event.values);SensorManager.getOrientation(matrix,angles);
            float measured=(float)((Math.toDegrees(angles[0])+360)%360);
            if(!headingKnown){heading=measured;headingKnown=true;}else heading=(heading+shortestAngle(measured-heading)*.14f+360)%360;
            sky.invalidate();
        }
        public void onAccuracyChanged(Sensor sensor,int accuracy){}
    };
    private final Map<String,Double> preferredCarrier=new HashMap<>();
    GnssPanel(Activity activity,boolean dark){
        super(activity);this.activity=activity;this.dark=dark;survey=new GnssSurvey(activity);terminal=TerminalTheme.enabled(activity);foreground=terminal?TerminalTheme.TEXT:dark?0xffe4e8ed:0xff192028;muted=terminal?TerminalTheme.MUTED:dark?0xff97a2ae:0xff546370;surface=terminal?TerminalTheme.SURFACE:dark?0xff161922:Color.WHITE;edge=terminal?TerminalTheme.BORDER:dark?0xff35404d:0xffd2dae2;setOrientation(VERTICAL);
        sensors=activity.getSystemService(SensorManager.class);rotationVector=sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);accelerationSensor=sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        coordinateFormat=activity.getSharedPreferences("settings",Context.MODE_PRIVATE).getInt("coordinateFormat",0);
        search=new EditText(activity);search.setSingleLine(true);search.setTextColor(foreground);search.setHintTextColor(muted);search.setTextSize(14);search.setHint(local("System or satellite number","Система или номер спутника"));
        LinearLayout modes=new LinearLayout(activity);
        graphButton=button(local("Signals","Сигналы"),()->switchPage(0));skyButton=button(local("Sky","Небосвод"),()->switchPage(1));positionButton=button(local("Position","Позиция"),()->switchPage(2));listButton=button(local("List","Список"),()->switchPage(3));
        for(Button option:new Button[]{graphButton,skyButton,positionButton,listButton})modes.addView(option,new LayoutParams(0,dp(44),1));addView(modes);
        addView(search,new LayoutParams(-1,dp(46)));
        sorts=new LinearLayout(activity);levelSort=button("Уровень ↓",()->{ascending=!ascending;refresh();});sorts.addView(levelSort,new LayoutParams(-1,dp(44)));addView(sorts);
        status=text("Ожидание GPS",13);addView(status);scroll=new ScrollView(activity);body=new LinearLayout(activity);body.setOrientation(VERTICAL);scroll.addView(body);addView(scroll,new LayoutParams(-1,0,1));sky=new Sky();bars=new SignalBars();
        LinearLayout fixRow=new LinearLayout(activity);fixRow.setGravity(Gravity.CENTER_VERTICAL);fixRow.setPadding(dp(12),dp(8),dp(12),dp(8));
        GradientDrawable fixBg=new GradientDrawable();fixBg.setColor(surface);fixBg.setCornerRadius(terminal?0:dp(12));fixBg.setStroke(dp(1),edge);fixRow.setBackground(fixBg);
        fixBadge=text(local("GNSS · No fix","GNSS · Нет фикса"),17);coordinates=text(local("Accuracy —\nAccel —","Точность —\nУскорение —"),13);coordinates.setGravity(Gravity.END);coordinates.setTextIsSelectable(true);
        fixRow.addView(fixBadge,new LayoutParams(0,-2,1));fixRow.addView(coordinates,new LayoutParams(0,-2,1));addView(fixRow,1,new LayoutParams(-1,dp(58)));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){if(page==3&&detailSnapshot==null)refresh();}public void afterTextChanged(Editable e){}});
    }
    void setActive(boolean value){if(active==value){if(active)survey.start();return;}active=value;if(value)survey.start();else survey.stop();syncSensor();if(value)refresh();}
    @Override protected void onDetachedFromWindow(){setActive(false);super.onDetachedFromWindow();}
    void showHome(){page=0;detailSnapshot=null;scroll.scrollTo(0,0);syncSensor();refresh();}
    private void switchPage(int value){page=value;detailSnapshot=null;scroll.scrollTo(0,0);syncSensor();refresh();}
    private void syncSensor(){boolean needed=active&&(rotationVector!=null||accelerationSensor!=null);if(needed&&!sensorActive){if(rotationVector!=null)sensors.registerListener(orientation,rotationVector,SensorManager.SENSOR_DELAY_UI);if(accelerationSensor!=null)sensors.registerListener(orientation,accelerationSensor,SensorManager.SENSOR_DELAY_UI);sensorActive=true;}else if(!needed&&sensorActive){sensors.unregisterListener(orientation);sensorActive=false;headingKnown=false;heading=0;acceleration=Float.NaN;}}
    private static float shortestAngle(float difference){return (difference+540)%360-180;}
    void refresh(){
        if(!active)return;survey.start();long now=SystemClock.elapsedRealtime();boolean fresh=survey.freshSatellites(),fix=survey.freshLocation();int dimension=survey.dimension();
        Set<String> visible=new HashSet<>(),used=new HashSet<>();if(fresh)for(GnssSurvey.Satellite s:survey.satellites){visible.add(s.constellation+":"+s.svid);if(s.used)used.add(s.constellation+":"+s.svid);}
        String state=fix&&dimension!=1?(dimension==2?local("2D fix","2D фикс"):dimension==3?local("3D fix","3D фикс"):local("Fix · type unknown","Фикс · тип неизвестен")):local("No fix","Нет фикса");
        status.setText(UiLanguage.text(local("In view ","Видно ")+visible.size()+"  ·  "+local("In use ","Используется ")+used.size()+(survey.error.isEmpty()?"":"  ·  "+survey.error)));
        Button[] modes={graphButton,skyButton,positionButton,listButton};for(int i=0;i<modes.length;i++)modes[i].setAlpha(page==i?1:.55f);
        search.setVisibility(page==3&&detailSnapshot==null?VISIBLE:GONE);sorts.setVisibility(page==3&&detailSnapshot==null?VISIBLE:GONE);
        levelSort.setText(local("Signal ","Сигнал ")+(ascending?"↑":"↓"));
        fixBadge.setText("GNSS · "+state);
        coordinates.setText((fix&&dimension!=1&&survey.location.hasAccuracy()?local("Accuracy ±","Точность ±")+format(survey.location.getAccuracy())+" m":local("Accuracy —","Точность —"))+"\n"+(Float.isFinite(acceleration)?local("Accel ","Ускорение ")+format(acceleration)+" m/s²":local("Accel —","Ускорение —")));
        Map<String,GnssSurvey.Satellite> unique=new LinkedHashMap<>();
        if(!fresh)preferredCarrier.clear();
        if(fresh)for(GnssSurvey.Satellite satellite:survey.satellites){
            String key=satellite.constellation+":"+satellite.svid;GnssSurvey.Satellite old=unique.get(key);
            Double preferred=preferredCarrier.get(key);
            boolean sameCarrier=preferred!=null&&Double.doubleToLongBits(preferred)==Double.doubleToLongBits(satellite.frequency);
            boolean oldSame=old!=null&&preferred!=null&&Double.doubleToLongBits(preferred)==Double.doubleToLongBits(old.frequency);
            if(old==null||sameCarrier&&!oldSame||sameCarrier==oldSame&&satellite.cn0>old.cn0)unique.put(key,satellite);
        }
        preferredCarrier.keySet().retainAll(unique.keySet());
        for(Map.Entry<String,GnssSurvey.Satellite> entry:unique.entrySet())preferredCarrier.put(entry.getKey(),entry.getValue().frequency);
        List<GnssSurvey.Satellite> values=new ArrayList<>(unique.values());
        values.sort(Comparator.comparing(GnssSurvey.Satellite::title));
        if(detailSnapshot!=null)return;
        if(page==0&&body.getChildCount()>0&&body.getChildAt(0) instanceof HorizontalScrollView){
            bars.values=values;bars.getLayoutParams().width=Math.max(dp(320),dp(37)*Math.max(1,values.size()));bars.requestLayout();bars.invalidate();return;
        }
        int y=scroll.getScrollY();
        body.removeAllViews();
        if(page==0){
            HorizontalScrollView horizontal=new HorizontalScrollView(activity);horizontal.setHorizontalScrollBarEnabled(true);
            if(bars.getParent() instanceof ViewGroup)((ViewGroup)bars.getParent()).removeView(bars);
            bars.values=values;horizontal.addView(bars,new android.widget.FrameLayout.LayoutParams(Math.max(dp(320),dp(37)*Math.max(1,values.size())),dp(310)));
            body.addView(horizontal);bars.invalidate();body.addView(new SignalLegend());
        }else if(page==1){
            List<GnssSurvey.Satellite> shown=new ArrayList<>();for(GnssSurvey.Satellite s:values)if(!hiddenSystems.contains(s.constellation))shown.add(s);
            sky.values=shown;if(sky.getParent() instanceof ViewGroup)((ViewGroup)sky.getParent()).removeView(sky);
            body.addView(text(headingKnown?local("Phone-up sky · tap empty area to filter","Небосвод по телефону · нажмите на пустое место для фильтра"):local("North-up sky · tap empty area to filter","Север сверху · нажмите на пустое место для фильтра"),13));
            body.addView(sky,new LayoutParams(-1,dp(345)));sky.invalidate();
            body.addView(new SignalLegend());if(rotationVector==null)body.addView(text(local("Orientation sensor unavailable; north stays at the top.","Датчик ориентации недоступен; север остаётся сверху."),12));
        }else if(page==2){
            LinearLayout formats=new LinearLayout(activity);String[] names={local("Decimal","Десятичный"),"DMS",local("Deg Min","Град. мин.")};for(int i=0;i<names.length;i++){int chosen=i;Button choice=button(names[i],()->{coordinateFormat=chosen;activity.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putInt("coordinateFormat",chosen).apply();refresh();});choice.setAlpha(coordinateFormat==i?1f:.55f);formats.addView(choice,new LayoutParams(0,dp(42),1));}body.addView(formats);
            LinearLayout position=card();position.addView(text(local("WGS84 · GPS provider","WGS84 · источник GPS"),14));
            if(fix&&dimension!=1){Location l=survey.location;TextView latitude=text(local("Latitude  ","Широта  ")+coordinate(l.getLatitude(),true),20),longitude=text(local("Longitude  ","Долгота  ")+coordinate(l.getLongitude(),false),20);latitude.setTextIsSelectable(true);longitude.setTextIsSelectable(true);position.addView(latitude);position.addView(longitude);
                position.addView(text(local("Age ","Возраст ")+Math.max(0,now-l.getElapsedRealtimeNanos()/1_000_000)/1000+" s",12));
                if(l.hasAccuracy())position.addView(text(local("Horizontal accuracy ±","Горизонтальная точность ±")+format(l.getAccuracy())+" m",14));
                if(l.hasAltitude())position.addView(text(local("Ellipsoid altitude ","Высота над эллипсоидом ")+format(l.getAltitude())+" m",12));
                if(Double.isFinite(survey.msl)&&now-survey.altitudeAt<GnssSurvey.FRESH_MS)position.addView(text(local("Mean sea level altitude ","Высота над уровнем моря ")+format(survey.msl)+" m (NMEA)",12));
                if(l.hasSpeed())position.addView(text(local("Speed ","Скорость ")+format(l.getSpeed())+" m/s",12));
                if(l.hasBearing())position.addView(text(local("Course ","Курс ")+format(l.getBearing())+"°",12));
                if(l.isFromMockProvider())position.addView(text(local("Android reports a mock location","Android сообщает о поддельном местоположении"),13));
            }else position.addView(text(local("No fresh satellite position. Network coordinates are not substituted.","Нет свежих координат GNSS. Сетевые координаты не подставляются."),14));
            if(dimension>1&&survey.dop!=null&&now-survey.dopAt<GnssSurvey.FRESH_MS)position.addView(text("PDOP "+format(survey.dop[0])+" · HDOP "+format(survey.dop[1])+" · VDOP "+format(survey.dop[2]),12));body.addView(position);
            body.addView(text(local("Position schematic · latitude / longitude grid","Схема положения · широта / долгота"),13));body.addView(new PositionMap(fix&&dimension!=1?survey.location:null),new LayoutParams(-1,dp(230)));
        }else{
            String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);
            values.sort((a,b)->ascending?Float.compare(a.cn0,b.cn0):Float.compare(b.cn0,a.cn0));boolean any=false;
            for(GnssSurvey.Satellite s:values)if(s.title().toLowerCase(Locale.ROOT).contains(query)){any=true;LinearLayout card=card();TextView title=text(s.title()+" · "+format(s.cn0)+" dB-Hz",17);title.setTextColor(signalColor(s.cn0));card.addView(title);card.addView(text(s.used?local("Used in fix","Участвует в фиксе"):local("Visible","Видим"),12));card.setOnClickListener(v->open(s));body.addView(card);}
            if(!any)body.addView(text(local("No satellites match the current search.","Поиск не нашёл спутников."),14));
        }
        scroll.post(()->scroll.scrollTo(0,y));UiLanguage.apply(this);
    }
    private void satelliteDetails(GnssSurvey.Satellite s){LinearLayout card=card();TextView heading=text(s.title(),24);heading.setTextColor(signalColor(s.cn0));card.addView(heading);card.addView(text(format(s.cn0)+" dB-Hz",26));card.addView(text(s.used?local("Used in position fix","Участвует в определении координат"):local("Visible, not used in fix","Видим, но не участвует в фиксе"),16));card.addView(text(local("System  ","Система  ")+GnssSurvey.constellationName(s.constellation),16));card.addView(text("SVID  "+s.svid,17));card.addView(text(local("Carrier  ","Несущая  ")+(Double.isFinite(s.frequency)?format(s.frequency)+" MHz":local("not reported","нет данных")),15));card.addView(text(local("Azimuth  ","Азимут  ")+format(s.azimuth)+"° · "+local("Elevation  ","Высота  ")+format(s.elevation)+"°",14));card.addView(text(local("Ephemeris  ","Эфемериды  ")+(s.ephemeris?local("yes","да"):local("no","нет"))+" · "+local("Almanac  ","Альманах  ")+(s.almanac?local("yes","да"):local("no","нет")),12));if(Double.isFinite(s.baseband))card.addView(text(local("Baseband C/N₀  ","Базовый C/N₀  ")+format(s.baseband)+" dB-Hz",12));body.addView(card);}
    private void open(GnssSurvey.Satellite s){detailSnapshot=s;body.removeAllViews();body.addView(button(local("‹ Back","‹ Назад"),()->{detailSnapshot=null;refresh();}));satelliteDetails(s);scroll.scrollTo(0,0);}
    private void showConstellationFilter(){
        String[] names={"GPS","GLONASS","Galileo","BeiDou","QZSS","SBAS","NavIC"};int[] ids={1,3,6,5,4,2,7};boolean[] chosen=new boolean[ids.length];
        for(int i=0;i<ids.length;i++)chosen[i]=!hiddenSystems.contains(ids[i]);
        ChoiceOverlay.show(activity,sky,dark,local("Constellations","Системы спутников"),names,chosen,false,index->{
            if(!hiddenSystems.add(ids[index]))hiddenSystems.remove(ids[index]);chosen[index]=!hiddenSystems.contains(ids[index]);refresh();
        });
    }
    private int signalColor(float cn0){if(!Float.isFinite(cn0)||cn0<0)return muted;return Color.HSVToColor(new float[]{Math.min(120f,Math.max(0f,cn0/45f*120f)),.83f,.92f});}
    private String coordinate(double value,boolean latitude){String hemisphere=latitude?(value<0?"S":"N"):(value<0?"W":"E");double magnitude=Math.abs(value);if(coordinateFormat==0)return String.format(Locale.ROOT,"%.6f° %s",magnitude,hemisphere);int degrees=(int)Math.floor(magnitude);double minutes=(magnitude-degrees)*60;if(coordinateFormat==2)return String.format(Locale.ROOT,"%d° %.4f′ %s",degrees,minutes,hemisphere);int whole=(int)Math.floor(minutes);return String.format(Locale.ROOT,"%d° %d′ %.2f″ %s",degrees,whole,(minutes-whole)*60,hemisphere);}
    private String format(double n){return Double.isFinite(n)?String.format(Locale.ROOT,"%.1f",n):"—";}
    private static String local(String english,String russian){return UiLanguage.isRussian()?russian:english;}
    private LinearLayout card(){LinearLayout box=new LinearLayout(activity);box.setOrientation(VERTICAL);box.setPadding(dp(14),dp(12),dp(14),dp(12));GradientDrawable b=new GradientDrawable();b.setColor(surface);b.setCornerRadius(terminal?0:dp(12));b.setStroke(dp(1),edge);box.setBackground(b);LayoutParams p=new LayoutParams(-1,-2);p.bottomMargin=dp(10);box.setLayoutParams(p);return box;}
    private TextView text(String s,int size){TextView t=new TextView(activity);t.setText(UiLanguage.text(s));t.setTextSize(size);t.setTextColor(size<=12?muted:foreground);t.setPadding(0,dp(4),0,dp(4));if(terminal)t.setTypeface(Typeface.MONOSPACE);return t;}
    private Button button(String s,Runnable action){Button b=new Button(activity);b.setText(UiLanguage.text(s));b.setAllCaps(false);b.setTextColor(foreground);b.setTextSize(12);if(terminal){b.setTypeface(Typeface.MONOSPACE);b.setBackground(TerminalTheme.panel(activity,surface));}b.setOnClickListener(v->action.run());return b;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private final class SignalBars extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);List<GnssSurvey.Satellite> values=Collections.emptyList();
        SignalBars(){super(activity);setContentDescription(local("Satellite signal levels in dB-Hz","Уровни сигналов спутников в dB-Hz"));}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float left=dp(8),right=getWidth()-dp(8),top=dp(20),bottom=getHeight()-dp(31);
            paint.setTypeface(Typeface.MONOSPACE);paint.setTextSize(dp(10));
            for(int level:new int[]{0,15,30,45,60}){float y=bottom-level/60f*(bottom-top);paint.setColor(edge);canvas.drawLine(left,y,right,y,paint);paint.setColor(muted);canvas.drawText(""+level,left,y-dp(2),paint);}
            if(values.isEmpty())return;float slot=(right-left)/values.size();
            for(int i=0;i<values.size();i++){GnssSurvey.Satellite satellite=values.get(i);if(!Float.isFinite(satellite.cn0)||satellite.cn0<0)continue;
                float x=left+i*slot+slot*.18f,y=bottom-Math.min(60,satellite.cn0)/60f*(bottom-top-dp(12));
                paint.setColor(signalColor(satellite.cn0));canvas.drawRect(x,y,x+slot*.64f,bottom,paint);
                if(satellite.used){paint.setColor(0xff23b269);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));canvas.drawRect(x,y,x+slot*.64f,bottom,paint);paint.setStyle(Paint.Style.FILL);}
                paint.setColor(foreground);paint.setTextSize(dp(10));canvas.drawText(format(satellite.cn0),x,y-dp(3),paint);
                paint.setColor(muted);paint.setTextSize(dp(9));canvas.drawText(""+satellite.svid,x,bottom+dp(15),paint);
            }
        }
    }
    private final class SignalLegend extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        SignalLegend(){super(activity);setLayoutParams(new LayoutParams(-1,dp(58)));setContentDescription(local("C/N₀ color scale, 0 to 45 dB-Hz","Цветовая шкала C/N₀ от 0 до 45 dB-Hz"));}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float left=dp(14),right=getWidth()-dp(14),top=dp(7),bottom=dp(22);
            for(int i=0;i<90;i++){paint.setColor(signalColor(i/2f));if(terminal){paint.setTypeface(Typeface.MONOSPACE);paint.setTextSize(dp(13));canvas.drawText("#",left+(right-left)*i/90f,top+dp(13),paint);}else canvas.drawRect(left+(right-left)*i/90f,top,left+(right-left)*(i+1)/90f,bottom,paint);}
            paint.setTypeface(Typeface.MONOSPACE);paint.setColor(muted);paint.setTextSize(dp(10));for(int db:new int[]{0,10,20,30,40,45}){float x=left+(right-left)*db/45f;paint.setTextAlign(db==45?Paint.Align.RIGHT:Paint.Align.LEFT);canvas.drawText(""+db,x,bottom+dp(17),paint);}paint.setTextAlign(Paint.Align.LEFT);canvas.drawText("C/N₀ dB-Hz",left,bottom+dp(30),paint);
        }
    }
    private final class PositionMap extends View {
        private final Location point;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        PositionMap(Location location){super(activity);point=location==null?null:new Location(location);setContentDescription(local("Schematic coordinate grid with measured GNSS position","Схема координат с измеренным положением GNSS"));}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f;paint.setColor(surface);canvas.drawRect(0,0,getWidth(),getHeight(),paint);paint.setColor(edge);paint.setStrokeWidth(dp(1));
            if(terminal){paint.setTypeface(Typeface.MONOSPACE);paint.setTextSize(dp(10));for(float y=dp(16);y<getHeight();y+=dp(17))for(float x=dp(10);x<getWidth();x+=dp(16))canvas.drawText("·",x,y,paint);}
            else for(int i=1;i<5;i++){float x=getWidth()*i/5f,y=getHeight()*i/5f;canvas.drawLine(x,0,x,getHeight(),paint);canvas.drawLine(0,y,getWidth(),y,paint);}paint.setColor(muted);paint.setTextSize(dp(12));paint.setTypeface(Typeface.MONOSPACE);canvas.drawText("N",cx-dp(4),dp(18),paint);
            if(point==null){canvas.drawText(local("No GNSS fix","Нет фикса GNSS"),dp(14),cy,paint);return;}paint.setColor(0xff23b269);if(terminal){paint.setTextSize(dp(18));canvas.drawText("[+]",cx-dp(16),cy+dp(5),paint);}else{paint.setStrokeWidth(dp(2));canvas.drawLine(cx-dp(16),cy,cx+dp(16),cy,paint);canvas.drawLine(cx,cy-dp(16),cx,cy+dp(16),paint);paint.setStyle(Paint.Style.STROKE);canvas.drawCircle(cx,cy,dp(10),paint);paint.setStyle(Paint.Style.FILL);}paint.setColor(foreground);paint.setTextSize(dp(12));canvas.drawText(String.format(Locale.ROOT,"%.6f°, %.6f°",point.getLatitude(),point.getLongitude()),dp(12),getHeight()-dp(14),paint);
        }
    }
    private final class Sky extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);List<GnssSurvey.Satellite> values=Collections.emptyList();final List<PointF> points=new ArrayList<>();
        Sky(){super(activity);setContentDescription(local("Sky view. Tap a satellite for details or empty space for constellation filters.","Небосвод. Нажмите на спутник для подробностей или на пустое место для фильтра систем."));}
        @Override protected void onDraw(Canvas c){super.onDraw(c);points.clear();float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y)-dp(30);
            if(terminal){c.drawColor(surface);p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.MONOSPACE);p.setTextSize(dp(12));p.setColor(edge);for(float at=x-r;at<x+r;at+=dp(22))c.drawText("·",at,y,p);for(float at=y-r;at<y+r;at+=dp(22))c.drawText("·",x,at,p);c.drawText("+",x,y,p);}
            else{p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(edge);for(int i=1;i<=3;i++)c.drawCircle(x,y,r*i/3,p);c.drawLine(x-r,y,x+r,y,p);c.drawLine(x,y-r,x,y+r,p);p.setStyle(Paint.Style.FILL);}
            p.setColor(muted);p.setTextSize(dp(13));String[] directions={"N","E","S","W"};for(int i=0;i<4;i++){double angle=Math.toRadians(i*90-(headingKnown?heading:0));float dx=x+(float)Math.sin(angle)*(r+dp(17)),dy=y-(float)Math.cos(angle)*(r+dp(17));c.drawText(directions[i],dx-dp(5),dy+dp(5),p);}
            Set<String> current=new HashSet<>();
            for(GnssSurvey.Satellite s:values){if(!Float.isFinite(s.azimuth)||!Float.isFinite(s.elevation)||s.elevation<0||s.elevation>90){points.add(null);continue;}current.add(s.id());float[] steady=smoothSky.computeIfAbsent(s.id(),key->new float[]{s.azimuth,s.elevation});steady[0]+=shortestAngle(s.azimuth-steady[0])*.18f;steady[1]+=(s.elevation-steady[1])*.18f;double a=Math.toRadians(steady[0]-(headingKnown?heading:0));float distance=r*(90-steady[1])/90;PointF at=new PointF(x+(float)Math.sin(a)*distance,y-(float)Math.cos(a)*distance);points.add(at);p.setColor(signalColor(s.cn0));p.setStyle(Paint.Style.FILL);
                if(terminal){p.setTextSize(dp(10));p.setTextAlign(Paint.Align.CENTER);c.drawText("["+s.svid+"]",at.x,at.y+dp(3),p);p.setTextAlign(Paint.Align.LEFT);continue;}
                c.drawCircle(at.x,at.y,dp(12),p);if(s.used){p.setColor(0xff23b269);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));c.drawCircle(at.x,at.y,dp(15),p);p.setStyle(Paint.Style.FILL);}p.setColor(Color.BLACK);p.setTextSize(dp(10));p.setTextAlign(Paint.Align.CENTER);c.drawText(""+s.svid,at.x,at.y+dp(3),p);p.setTextAlign(Paint.Align.LEFT);}smoothSky.keySet().retainAll(current);
        }
        @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN)return true;if(e.getAction()==MotionEvent.ACTION_UP){performClick();int nearest=-1;double distance=dp(22);for(int i=0;i<points.size();i++){PointF point=points.get(i);if(point!=null){double delta=Math.hypot(e.getX()-point.x,e.getY()-point.y);if(delta<distance){distance=delta;nearest=i;}}}if(nearest>=0)open(values.get(nearest));else showConstellationFilter();return true;}return super.onTouchEvent(e);}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
