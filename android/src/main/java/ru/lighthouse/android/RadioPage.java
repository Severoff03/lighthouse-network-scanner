package ru.lighthouse.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import ru.lighthouse.core.*;
import java.util.*;

final class RadioPage extends LinearLayout {
    private final Activity activity;
    private final int foreground,muted,surface,edge;private final boolean terminal;
    private static final int GREEN=0xff23b269;
    private final LinearLayout rows,bandBar,sortBar,radioControls,landing;
    private final HorizontalScrollView protocolScroll;
    private final ScrollView scroll;
    private final TextView status;
    private final Button map,graphButton,listButton,signalSort,frequencySort,access,hiddenButton;
    private final EditText search;
    private final Spectrum spectrum;
    private final GnssPanel navigation;
    private String selected="",detailId,wifiProtocolFilter="All";
    private final Map<String,Set<String>> hidden=new HashMap<>();
    private final Map<String,List<long[]>> bluetoothHistory=new HashMap<>();
    private List<NetworkCheckResult> overlaps=Collections.emptyList();
    private int band;
    private boolean list,sortFrequency,ascending,showingHidden;
    private final Map<String,Button> tabs=new LinkedHashMap<>();
    private final List<Button> bands=new ArrayList<>();
    private final Map<String,Button> protocolButtons=new LinkedHashMap<>();
    private final Map<String,Integer> colors=new LinkedHashMap<>();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){public void run(){syncNavigation();if(isShown())render();ui.postDelayed(this,2000);}};

    RadioPage(Activity context,boolean dark,Runnable permissions){
        super(context);activity=context;terminal=TerminalTheme.enabled(context);foreground=terminal?TerminalTheme.TEXT:dark?0xffe4e8ed:0xff192028;muted=terminal?TerminalTheme.MUTED:dark?0xff97a2ae:0xff546370;surface=terminal?TerminalTheme.SURFACE:dark?0xff161922:Color.WHITE;edge=terminal?TerminalTheme.BORDER:dark?0xff35404d:0xffd2dae2;
        setOrientation(VERTICAL);setPadding(dp(12),dp(8),dp(12),0);
        LinearLayout bar=new LinearLayout(context);
        for(String name:new String[]{"WiFi","Bluetooth","Cell","Навигация"}){
            Button b=button(name,()->selectMode(name));
            b.setTextSize(11);tabs.put(name,b);bar.addView(b,new LayoutParams(0,dp(46),1));
        }addView(bar);
        landing=new LinearLayout(context);landing.setOrientation(VERTICAL);addView(landing,new LayoutParams(-1,0,1));
        for(String[] pair:new String[][]{{"WiFi","Wi-Fi"},{"Bluetooth","Bluetooth"},{"Cell","Cell"},{"Навигация","Navigation"}}){
            String tileName=pair[0].equals("Навигация")&&UiLanguage.isRussian()?"Навигация":pair[1];
            Button tile=button(terminal?"[ "+tileName+" ]":tileName,()->selectMode(pair[0]));tile.setTextSize(19);tile.setGravity(Gravity.CENTER);
            GradientDrawable tileBg=new GradientDrawable();tileBg.setColor(surface);tileBg.setCornerRadius(terminal?0:dp(14));tileBg.setStroke(dp(1),edge);tile.setBackground(tileBg);
            LayoutParams tileSize=new LayoutParams(-1,0,1);tileSize.bottomMargin=dp(10);landing.addView(tile,tileSize);
        }
        radioControls=new LinearLayout(context);radioControls.setOrientation(VERTICAL);addView(radioControls);
        bandBar=new LinearLayout(context);
        for(int i=0;i<3;i++){final int value=i;Button b=button(new String[]{"2.4 GHz","5 GHz","6 GHz"}[i],()->{band=value;detailId=null;render();});bands.add(b);bandBar.addView(b,new LayoutParams(0,dp(44),1));}radioControls.addView(bandBar);
        protocolScroll=new HorizontalScrollView(context);protocolScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout protocolBar=new LinearLayout(context);protocolBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView protocolLabel=label("Protocol",12);protocolLabel.setPadding(dp(5),0,dp(9),0);protocolBar.addView(protocolLabel);
        for(String protocol:new String[]{"All","Legacy","N","AC","AX","BE","Unknown"}){
            Button chip=button(protocol.equals("Legacy")?"A/B/G":protocol,()->{wifiProtocolFilter=protocol;detailId=null;overlaps=Collections.emptyList();render();});
            chip.setTextSize(11);protocolButtons.put(protocol,chip);
            chip.setContentDescription(protocol.equals("Legacy")?"Wi-Fi 802.11a/b/g":protocol.equals("Unknown")?"Wi-Fi protocol not reported":protocol.equals("All")?"All Wi-Fi protocols":"Wi-Fi 802.11"+protocol.toLowerCase(Locale.ROOT));
            LayoutParams chipSize=new LayoutParams(dp(protocol.equals("Unknown")?86:protocol.equals("Legacy")?76:58),dp(40));
            chipSize.rightMargin=dp(5);protocolBar.addView(chip,chipSize);
        }
        protocolScroll.addView(protocolBar);radioControls.addView(protocolScroll,new LayoutParams(-1,dp(44)));
        search=new EditText(context);search.setSingleLine(true);search.setTextSize(14);search.setTextColor(foreground);search.setHintTextColor(muted);search.setHint("Поиск по имени или MAC");search.setContentDescription("Поиск радиоустройств");
        LinearLayout views=new LinearLayout(context);
        graphButton=button("График",()->{list=false;showingHidden=false;detailId=null;overlaps=Collections.emptyList();render();});
        listButton=button("Список",()->{list=true;showingHidden=false;detailId=null;overlaps=Collections.emptyList();render();});
        views.addView(graphButton,new LayoutParams(0,dp(44),1));views.addView(listButton,new LayoutParams(0,dp(44),1));radioControls.addView(views);
        radioControls.addView(search,new LayoutParams(-1,dp(48)));
        sortBar=new LinearLayout(context);
        signalSort=button("Уровень ↓",()->{ascending=sortFrequency?false:!ascending;sortFrequency=false;render();});
        frequencySort=button("Частота ↑",()->{ascending=sortFrequency?!ascending:true;sortFrequency=true;render();});
        sortBar.addView(signalSort,new LayoutParams(0,dp(44),1));sortBar.addView(frequencySort,new LayoutParams(0,dp(44),1));radioControls.addView(sortBar);
        hiddenButton=button("Hidden devices",()->showHidden());radioControls.addView(hiddenButton,new LayoutParams(-1,dp(42)));
        status=label("Ожидание измерений",12);radioControls.addView(status);
        LinearLayout actions=new LinearLayout(context);
        access=button("Permissions",permissions);actions.addView(access,new LayoutParams(0,dp(40),1));
        map=button("Карта сот",()->TowerMap.show(activity,RadioRuntime.get(activity).snapshot()));actions.addView(map,new LayoutParams(0,dp(40),1));addView(actions);
        scroll=new ScrollView(context);rows=new LinearLayout(context);rows.setOrientation(VERTICAL);scroll.addView(rows);addView(scroll,new LayoutParams(-1,0,1));spectrum=new Spectrum(context);
        navigation=new GnssPanel(context,dark);addView(navigation,new LayoutParams(-1,0,1));navigation.setVisibility(GONE);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){detailId=null;overlaps=Collections.emptyList();render();}public void afterTextChanged(Editable s){}});
        render();
    }
    private Button button(String title,Runnable click){Button b=new Button(activity);b.setText(UiLanguage.text(title));b.setAllCaps(false);b.setTextColor(foreground);b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(5),0,dp(5),0);if(terminal){b.setTypeface(Typeface.MONOSPACE);b.setBackground(TerminalTheme.panel(activity,surface));}b.setOnClickListener(v->click.run());return b;}
    void showLanding(){selected="";detailId=null;list=false;showingHidden=false;syncNavigation();render();}
    private void selectMode(String name){selected=name;list=false;sortFrequency=false;showingHidden=false;detailId=null;wifiProtocolFilter="All";overlaps=Collections.emptyList();search.setText("");scroll.scrollTo(0,0);syncNavigation();render();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();ui.post(tick);}
    @Override protected void onDetachedFromWindow(){ui.removeCallbacks(tick);if(navigation!=null)navigation.setActive(false);super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);syncNavigation();}
    @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);syncNavigation();}
    private void syncNavigation(){if(navigation!=null)navigation.setActive(isAttachedToWindow()&&isShown()&&getWindowVisibility()==VISIBLE&&selected.equals("Навигация"));}
    private List<NetworkCheckResult> entries(){
        Map<String,NetworkCheckResult> unique=new LinkedHashMap<>();
        for(NetworkCheckResult r:RadioRuntime.get(activity).snapshot()){
            Map<String,String> m=r.metrics;
            boolean belongs=selected.equals("WiFi")?r.category.contains("Wi-Fi")&&m.containsKey("observedElapsedMs")&&m.containsKey("bssid"):selected.equals("Bluetooth")?r.category.equals("Bluetooth")&&m.containsKey("address"):m.containsKey("cellId")&&m.containsKey("technology");
            if(!belongs)continue;
            String key=RadioPresentation.identity(r);NetworkCheckResult old=unique.get(key);
            if(old==null||connected(r)||!connected(old)&&age(m)<age(old.metrics))unique.put(key,r);
        }
        List<NetworkCheckResult> out=new ArrayList<>();
        for(NetworkCheckResult r:unique.values())if(!hidden.computeIfAbsent(selected,k->new HashSet<>()).contains(RadioPresentation.identity(r))
            && (!list||RadioPresentation.matches(r,search.getText().toString()))
            && (!selected.equals("WiFi")||RadioPresentation.wifiBand(r)==band&&RadioPresentation.matchesWifiProtocol(r,wifiProtocolFilter)))out.add(r);
        out.sort(RadioPresentation.order(sortFrequency,ascending));return out;
    }
    private void render(){
        try { renderContent(); }
        catch (RuntimeException failure) {
            CrashDiagnostics.record("Radio / " + selected, failure);
            rows.removeAllViews();
            status.setText((UiLanguage.isRussian()?"Ошибка отображения радио: ":"Radio display error: ") + failure.getClass().getSimpleName());
        }
    }
    private void renderContent(){
        if(navigation==null)return;
        boolean home=selected.isEmpty(),nav=selected.equals("Навигация");
        landing.setVisibility(home?VISIBLE:GONE);navigation.setVisibility(nav?VISIBLE:GONE);
        radioControls.setVisibility(home||nav?GONE:VISIBLE);scroll.setVisibility(home||nav?GONE:VISIBLE);
        map.setVisibility(selected.equals("Cell")?VISIBLE:GONE);
        access.setVisibility(needsPermissions()?VISIBLE:GONE);
        for(Map.Entry<String,Button> tab:tabs.entrySet())tab.getValue().setAlpha(selected.equals(tab.getKey())?1f:.5f);
        if(home)return;
        if(nav){navigation.refresh();return;}
        bandBar.setVisibility(selected.equals("WiFi")?VISIBLE:GONE);for(int i=0;i<bands.size();i++)bands.get(i).setAlpha(i==band?1:.5f);
        protocolScroll.setVisibility(selected.equals("WiFi")?VISIBLE:GONE);
        if(selected.equals("WiFi"))for(Map.Entry<String,Button> option:protocolButtons.entrySet())styleSwitch(option.getValue(),option.getKey().equals(wifiProtocolFilter));
        search.setHint(selected.equals("Cell")?"Оператор, технология или Cell ID":"Поиск по имени или MAC");
        styleSwitch(graphButton,!list);styleSwitch(listButton,list);
        search.setVisibility(list&&detailId==null&&!showingHidden?VISIBLE:GONE);
        sortBar.setVisibility(list&&detailId==null&&!showingHidden?VISIBLE:GONE);
        frequencySort.setVisibility(selected.equals("Bluetooth")?GONE:VISIBLE);
        hiddenButton.setVisibility(list&&!hidden.computeIfAbsent(selected,k->new HashSet<>()).isEmpty()?VISIBLE:GONE);
        hiddenButton.setText("Hidden · "+hidden.get(selected).size()+"  ›");
        signalSort.setText("Уровень "+(!sortFrequency?(ascending?"↑":"↓"):""));frequencySort.setText("Частота "+(sortFrequency?(ascending?"↑":"↓"):""));
        List<NetworkCheckResult> values=entries();if(selected.equals("Bluetooth"))rememberBluetooth(values);
        long fresh=values.stream().filter(r->fresh(r)&&signal(r)>=-140&&signal(r)<=20).count();
        status.setText(values.size()+" наблюдений · "+fresh+" свежих уровней"+(list&&sortFrequency&&values.stream().noneMatch(r->Double.isFinite(RadioPresentation.number(r.metrics,"frequencyMHz")))?" · частоты не сообщены ОС":"")+(RadioRuntime.get(activity).error.isEmpty()?"":" · ошибка измерения: "+RadioRuntime.get(activity).error));
        int y=scroll.getScrollY();rows.removeAllViews();
        if(showingHidden){rows.addView(button("‹ Back to list",()->{showingHidden=false;render();}));
            for(String id:new ArrayList<>(hidden.getOrDefault(selected,Collections.emptySet()))){Button show=button("Show  "+id,()->{hidden.get(selected).remove(id);if(hidden.get(selected).isEmpty())showingHidden=false;render();});rows.addView(show);}
        }else if(detailId!=null){NetworkCheckResult found=null;for(NetworkCheckResult r:values)if(RadioPresentation.identity(r).equals(detailId))found=r;
            rows.addView(button("‹ "+(list?"К списку":"К графику"),()->{detailId=null;overlaps=Collections.emptyList();render();}));
            if(found==null)rows.addView(label("Наблюдение больше не доступно. Вернитесь к графику.",14));else details(found);
        }else if(!overlaps.isEmpty()){
            rows.addView(button("‹ К графику",()->{overlaps=Collections.emptyList();render();}));rows.addView(label("В этой области несколько точек",16));
            for(NetworkCheckResult r:values)if(overlaps.stream().anyMatch(o->RadioPresentation.identity(o).equals(RadioPresentation.identity(r))))addRow(r);
        }else if(list){if(values.isEmpty())rows.addView(label("Нет наблюдений по выбранному фильтру",14));for(NetworkCheckResult r:values)addRow(r);}
        else{
            spectrum.values=values;spectrum.hit.clear();spectrum.targets.clear();spectrum.setContentDescription(UiLanguage.text("График "+selected+", "+fresh+" измерений. Для чтения всех устройств откройте список."));
            if(spectrum.getParent() instanceof ViewGroup)((ViewGroup)spectrum.getParent()).removeView(spectrum);
            rows.addView(spectrum,new LayoutParams(-1,dp(310)));spectrum.invalidate();
            if(fresh==0)rows.addView(label(values.isEmpty()
                ?selected.equals("WiFi")?"Нет сетей Wi-Fi для выбранного диапазона и протокола.":"Нет измерений. Проверьте разрешения и включение модуля."
                :"Свежий уровень сигнала отсутствует. Доступные сведения — в списке.",14));
        }
        scroll.post(()->scroll.scrollTo(0,y));
        UiLanguage.apply(this);
    }
    private String title(NetworkCheckResult r){return selected.equals("Cell")?RadioPresentation.value(r.metrics,"technology")+" · "+operator(r.metrics)+" · "+RadioPresentation.value(r.metrics,"cellId"):RadioPresentation.value(r.metrics,"ssid","name").equals("—")?r.name:RadioPresentation.value(r.metrics,"ssid","name");}
    private boolean needsPermissions(){
        if(activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return true;
        if(activity.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return true;
        return Build.VERSION.SDK_INT>=31&&(activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)!=android.content.pm.PackageManager.PERMISSION_GRANTED
            ||activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED);
    }
    private String operator(Map<String,String> m){String name=RadioPresentation.value(m,"operator");if(!name.equals("—"))return name;String mcc=RadioPresentation.value(m,"mcc"),mnc=RadioPresentation.value(m,"mnc");return !mcc.equals("—")&&!mnc.equals("—")?"PLMN "+mcc+"-"+mnc:"не сообщён";}
    private String power(NetworkCheckResult r){double n=RadioPresentation.signal(r);return Double.isFinite(n)?String.format(Locale.ROOT,"%.0f dBm",n):"Уровень —";}
    private String frequency(NetworkCheckResult r){String f=RadioPresentation.value(r.metrics,"frequencyMHz");return f.equals("—")?"Частота —":f+" MHz";}
    private boolean fresh(NetworkCheckResult r){long age=age(r.metrics);return age>=0&&age<=120000;}
    private boolean connected(NetworkCheckResult r){boolean observed=fresh(r);
        boolean currentBluetoothConnection=r.category.equals("Bluetooth")&&!r.metrics.containsKey("observedElapsedMs")
            &&"true".equals(r.metrics.get("connected"));
        return currentBluetoothConnection||observed&&("true".equals(r.metrics.get("connected"))||"true".equals(r.metrics.get("registered")));}
    private int color(NetworkCheckResult r){return colors.computeIfAbsent(RadioPresentation.identity(r),key->Color.HSVToColor(new float[]{(colors.size()*137.508f+205)%360,.66f,.85f}));}
    private LinearLayout card(NetworkCheckResult r){LinearLayout box=new LinearLayout(activity);box.setOrientation(VERTICAL);box.setPadding(dp(14),dp(12),dp(14),dp(12));GradientDrawable bg=new GradientDrawable();bg.setColor(surface);bg.setCornerRadius(terminal?0:dp(12));bg.setStroke(dp(connected(r)?2:1),connected(r)?GREEN:edge);box.setBackground(bg);LayoutParams p=new LayoutParams(-1,-2);p.bottomMargin=dp(10);box.setLayoutParams(p);return box;}
    private void addRow(NetworkCheckResult r){LinearLayout box=card(r);TextView heading=label("●  "+title(r),15);heading.setTag(Boolean.TRUE);heading.setTextColor(color(r));box.addView(heading);box.addView(label(power(r)+"  ·  "+frequency(r),14));box.addView(label(RadioPresentation.value(r.metrics,"bssid","address","cellId")+(connected(r)?" · подключено":"")+(!fresh(r)?" · устарело / возраст неизвестен":""),11));box.setOnClickListener(v->open(r));box.setFocusable(true);
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.addView(box,new LayoutParams(0,-2,1));
        if(list){Button hide=button("Hide",()->{hidden.computeIfAbsent(selected,k->new HashSet<>()).add(RadioPresentation.identity(r));render();});hide.setContentDescription("Hide "+title(r));row.addView(hide,new LayoutParams(dp(68),dp(50)));}
        rows.addView(row);
    }
    private void styleSwitch(Button button,boolean active){GradientDrawable shape=new GradientDrawable();shape.setColor(active?(terminal?TerminalTheme.ACCENT:foreground):surface);shape.setCornerRadius(terminal?0:dp(10));shape.setStroke(dp(2),active?foreground:muted);button.setBackground(shape);button.setTextColor(active?surface:foreground);button.setAlpha(1f);}
    private void showHidden(){showingHidden=true;scroll.scrollTo(0,0);render();}
    private void rememberBluetooth(List<NetworkCheckResult> values){long now=SystemClock.elapsedRealtime();for(NetworkCheckResult r:values){long at=RadioCsv.number(r.metrics.get("observedElapsedMs"),-1),level=signal(r);if(at<=0||now-at>120000||level < -140||level>20)continue;List<long[]> trail=bluetoothHistory.computeIfAbsent(RadioPresentation.identity(r),k->new ArrayList<>());if(trail.isEmpty()||trail.get(trail.size()-1)[0]!=at)trail.add(new long[]{at,level});}bluetoothHistory.values().forEach(trail->trail.removeIf(point->now-point[0]>120000));bluetoothHistory.entrySet().removeIf(e->e.getValue().isEmpty());}
    private void open(NetworkCheckResult r){detailId=RadioPresentation.identity(r);overlaps=Collections.emptyList();scroll.scrollTo(0,0);render();}
    private void details(NetworkCheckResult r){
        Map<String,String> m=r.metrics;LinearLayout top=card(r);TextView heading=label(selected.equals("Cell")?RadioPresentation.value(m,"technology"):title(r),23);heading.setTag(Boolean.TRUE);heading.setTypeface(null,Typeface.BOLD);top.addView(heading);
        if(selected.equals("Cell")){top.addView(label("Оператор  "+operator(m),17));top.addView(label("Технология  "+RadioPresentation.value(m,"technology"),15));top.addView(label("Cell ID  "+RadioPresentation.value(m,"cellId"),17));}
        top.addView(label(power(r),26));top.addView(label(connected(r)?selected.equals("Cell")?"Обслуживающая сота":"Подключено":fresh(r)?"Наблюдается":"Последнее наблюдение устарело",13));
        if(!selected.equals("Cell"))top.addView(label("Протокол  "+RadioPresentation.value(m,"protocol"),16));
        top.addView(label(frequency(r),16));if(!selected.equals("Cell"))top.addView(label("MAC  "+RadioPresentation.value(m,"bssid","address"),14));
        if(selected.equals("WiFi")){top.addView(label("Ширина  "+RadioPresentation.value(m,"bandwidthMHz")+" MHz",15));top.addView(label("Защита  "+RadioPresentation.value(m,"security"),14));}rows.addView(top);
        LinearLayout extra=card(r);extra.addView(label("Подробности измерения",14));
        Set<String> major=new HashSet<>(Arrays.asList("ssid","name","bssid","address","rssiDbm","dbm","protocol","technology","cellId","operator","frequencyMHz","security","bandwidthMHz","connected","registered","sim","simPresent","snapshot","privacy"));
        for(Map.Entry<String,String> e:m.entrySet())if(!major.contains(e.getKey())&&!e.getKey().toLowerCase(Locale.ROOT).contains("sim")
            && e.getValue()!=null&&!e.getValue().isBlank()&&!e.getValue().equals("unavailable")&&!e.getValue().equals("null")&&!e.getValue().equals("[]")){
            TextView t=label(fieldName(e.getKey())+"  "+e.getValue(),12);t.setTextColor(muted);t.setTextIsSelectable(true);extra.addView(t);}
        extra.addView(label("Возраст: "+(age(m)<0?"неизвестен":age(m)/1000+(UiLanguage.isRussian()?" с":" s")),12));rows.addView(extra);
    }
    private String fieldName(String key){return switch(key){case "centerFreq0MHz"->"Центр канала 1, MHz";case "centerFreq1MHz"->"Центр канала 2, MHz";case "observedElapsedMs"->"Время ОС, ms";case "source"->"Источник";case "bands"->"Диапазоны";case "bonded"->"Сопряжено";case "connectable"->"Допускает подключение";case "serviceUuids"->"Сервисы UUID";case "primaryPhy"->"Основной PHY (код Android)";case "secondaryPhy"->"Вторичный PHY (код Android)";default->key;};}
    static long signal(NetworkCheckResult r){double n=RadioPresentation.signal(r);return Double.isFinite(n)?(long)n:Long.MIN_VALUE;}
    static long age(Map<String,String> m){long at=RadioCsv.number(m.get("observedElapsedMs"),-1);return at<=0?-1:SystemClock.elapsedRealtime()-at;}
    private TextView label(String s,int size){TextView t=new TextView(activity);t.setText(UiLanguage.text(s));t.setTextSize(size);t.setTextColor(size<=12?muted:foreground);t.setPadding(0,dp(5),0,dp(5));if(terminal)t.setTypeface(Typeface.MONOSPACE);return t;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

    private final class Spectrum extends View {
        List<NetworkCheckResult> values=Collections.emptyList();final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final List<RectF> hit=new ArrayList<>();final List<NetworkCheckResult> targets=new ArrayList<>();
        Spectrum(Context c){super(c);setFocusable(true);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);hit.clear();targets.clear();float left=dp(40),right=getWidth()-dp(10),top=dp(24),bottom=getHeight()-dp(44);
            if(terminal){drawTerminal(c,left,right,top,bottom);return;}
            p.setTypeface(Typeface.MONOSPACE);p.setTextSize(dp(10));p.setStyle(Paint.Style.FILL);p.setColor(surface);c.drawRoundRect(new RectF(0,0,getWidth(),getHeight()),dp(12),dp(12),p);
            for(int dbm:new int[]{-20,-40,-60,-80,-100,-120,-140}){float y=bottom-(dbm+140)/160f*(bottom-top);p.setColor(edge);c.drawLine(left,y,right,y,p);p.setColor(muted);c.drawText(""+dbm,dp(2),y,p);}
            if(selected.equals("Bluetooth")){drawBluetooth(c,left,right,top,bottom);return;}
            boolean wifi=selected.equals("WiFi");double min=band==0?2400:band==1?4900:5925,max=band==0?2500:band==1?5925:7125;
            if(!wifi){min=Double.POSITIVE_INFINITY;max=Double.NEGATIVE_INFINITY;
                for(NetworkCheckResult r:values){double frequency=RadioPresentation.number(r.metrics,"frequencyMHz");if(Double.isFinite(frequency)){min=Math.min(min,frequency);max=Math.max(max,frequency);}}
                if(!Double.isFinite(min)){p.setColor(muted);c.drawText("MHz unavailable",left,top+dp(20),p);return;}
                double padding=Math.max(10,(max-min)*.07);min-=padding;max+=padding;
            }
            List<NetworkCheckResult> plotted=new ArrayList<>();for(NetworkCheckResult r:values)if(fresh(r)&&signal(r)>=-140&&signal(r)<=20)plotted.add(r);
            plotted.sort(Comparator.comparing(RadioPresentation::identity));
            c.save();c.clipRect(left,top,right,bottom);
            for(int i=0;i<plotted.size();i++){
                NetworkCheckResult r=plotted.get(i);float y=bottom-(signal(r)+140)/160f*(bottom-top);
                if(wifi){double f=RadioPresentation.number(r.metrics,"frequencyMHz"),width=RadioPresentation.number(r.metrics,"bandwidthMHz"),center=RadioPresentation.number(r.metrics,"centerFreq0MHz");
                    if("80+80".equals(r.metrics.get("bandwidthMHz"))){boolean drew=false;for(String key:new String[]{"centerFreq0MHz","centerFreq1MHz"}){double cf=RadioPresentation.number(r.metrics,key);if(cf>0){drew=true;rectangle(c,r,(float)(left+(cf-40-min)/(max-min)*(right-left)),(float)(left+(cf+40-min)/(max-min)*(right-left)),y,bottom,left,right);}}if(!drew){float x=(float)(left+(f-min)/(max-min)*(right-left));rectangle(c,r,x-dp(3),x+dp(3),y,bottom,left,right);}}
                    else{float x=(float)(left+(f-min)/(max-min)*(right-left));if(width>0){if(width<=20)center=f;if(center>0)rectangle(c,r,(float)(left+(center-width/2-min)/(max-min)*(right-left)),(float)(left+(center+width/2-min)/(max-min)*(right-left)),y,bottom,left,right);else rectangle(c,r,x-dp(3),x+dp(3),y,bottom,left,right);}else rectangle(c,r,x-dp(3),x+dp(3),y,bottom,left,right);}
                }else{double f=RadioPresentation.number(r.metrics,"frequencyMHz");if(!Double.isFinite(f))continue;float x=(float)(left+(f-min)/(max-min)*(right-left));rectangle(c,r,x-dp(4),x+dp(4),y,bottom,left,right);}
            }c.restore();p.setColor(muted);p.setStyle(Paint.Style.FILL);
            for(int i=0;i<=4;i++){float x=left+(right-left)*i/4;p.setTextAlign(i==4?Paint.Align.RIGHT:Paint.Align.LEFT);c.drawText(String.format(Locale.ROOT,"%.0f",min+(max-min)*i/4),x,bottom+dp(18),p);}p.setTextAlign(Paint.Align.LEFT);c.drawText("MHz",left,bottom+dp(34),p);
        }
        private void drawTerminal(Canvas c,float left,float right,float top,float bottom){
            c.drawColor(surface);p.setTypeface(Typeface.MONOSPACE);p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.LEFT);p.setTextSize(dp(10));
            if(right<=left||bottom<=top)return;
            for(int dbm:new int[]{-20,-40,-60,-80,-100,-120,-140}){
                float y=bottom-(dbm+140)/160f*(bottom-top);p.setColor(muted);c.drawText(""+dbm,dp(2),y,p);
                for(float x=left;x<right;x+=dp(14))c.drawText("·",x,y,p);
            }
            if(selected.equals("Bluetooth")){
                long now=SystemClock.elapsedRealtime();p.setColor(muted);c.drawText("[-120s]",left,bottom+dp(18),p);p.setTextAlign(Paint.Align.RIGHT);c.drawText("[now]",right,bottom+dp(18),p);p.setTextAlign(Paint.Align.LEFT);
                for(NetworkCheckResult r:values){List<long[]> trail=bluetoothHistory.get(RadioPresentation.identity(r));if(trail==null)continue;
                    float previousX=Float.NaN,previousY=Float.NaN;p.setColor(connected(r)?GREEN:color(r));
                    for(long[] sample:trail){float x=right-(now-sample[0])/120000f*(right-left),y=bottom-(sample[1]+140)/160f*(bottom-top);if(x<left||x>right)continue;
                        if(Float.isFinite(previousX)){int steps=Math.min(100,Math.max(1,(int)(Math.hypot(x-previousX,y-previousY)/dp(10))));for(int j=1;j<steps;j++){float part=j/(float)steps;c.drawText("·",previousX+(x-previousX)*part,previousY+(y-previousY)*part,p);}}
                        c.drawText("*",x,y,p);hit.add(new RectF(x-dp(15),y-dp(15),x+dp(15),y+dp(15)));targets.add(r);previousX=x;previousY=y;
                    }
                }
                return;
            }
            boolean wifi=selected.equals("WiFi");double min=band==0?2400:band==1?4900:5925,max=band==0?2500:band==1?5925:7125;
            if(!wifi){min=Double.POSITIVE_INFINITY;max=Double.NEGATIVE_INFINITY;for(NetworkCheckResult r:values){double f=RadioPresentation.number(r.metrics,"frequencyMHz");if(Double.isFinite(f)){min=Math.min(min,f);max=Math.max(max,f);}}if(!Double.isFinite(min)){p.setColor(muted);c.drawText("[ MHz: -- ]",left,top+dp(16),p);return;}double padding=Math.max(10,(max-min)*.07);min-=padding;max+=padding;}
            for(NetworkCheckResult r:values){if(!fresh(r)||signal(r)<-140||signal(r)>20)continue;double frequency=RadioPresentation.number(r.metrics,"frequencyMHz");if(!Double.isFinite(frequency))continue;
                float x=(float)(left+(frequency-min)/(max-min)*(right-left)),y=bottom-(signal(r)+140)/160f*(bottom-top);if(x<left||x>right)continue;
                p.setColor(connected(r)?GREEN:color(r));for(float at=y+dp(12);at<bottom;at+=dp(12))c.drawText("│",x,at,p);
                c.drawText(connected(r)?"[+]":"[•]",x-dp(8),y,p);
                if(wifi){if("80+80".equals(r.metrics.get("bandwidthMHz"))){for(String key:new String[]{"centerFreq0MHz","centerFreq1MHz"}){double center=RadioPresentation.number(r.metrics,key);if(center>0)terminalSpan(c,r,center,80,min,max,left,right,y,bottom);}}
                    else{double width=RadioPresentation.number(r.metrics,"bandwidthMHz"),center=RadioPresentation.number(r.metrics,"centerFreq0MHz");if(width>0){if(width<=20||!Double.isFinite(center)||center<=0)center=frequency;terminalSpan(c,r,center,width,min,max,left,right,y,bottom);}}}
                String name=title(r);if(name!=null&&!name.isEmpty()){p.setTextSize(dp(8));int count=p.breakText(name,true,Math.max(dp(18),right-x),null);c.drawText(name.substring(0,count),x,y-dp(8),p);p.setTextSize(dp(10));}
                hit.add(new RectF(x-dp(15),y-dp(16),x+dp(15),bottom));targets.add(r);
            }
            p.setColor(muted);for(int i=0;i<=4;i++){float x=left+(right-left)*i/4;p.setTextAlign(i==4?Paint.Align.RIGHT:Paint.Align.LEFT);c.drawText(String.format(Locale.ROOT,"%.0f",min+(max-min)*i/4),x,bottom+dp(18),p);}p.setTextAlign(Paint.Align.LEFT);c.drawText("[MHz]",left,bottom+dp(34),p);
        }
        private void terminalSpan(Canvas c,NetworkCheckResult r,double center,double width,double min,double max,float left,float right,float y,float bottom){
            float from=(float)(left+(center-width/2-min)/(max-min)*(right-left)),to=(float)(left+(center+width/2-min)/(max-min)*(right-left));
            from=Math.max(left,from);to=Math.min(right,to);if(to<=from)return;
            for(float at=from;at<to;at+=dp(9))c.drawText("═",at,y,p);
            hit.add(new RectF(from,y-dp(14),to,bottom));targets.add(r);
        }
        private void drawBluetooth(Canvas c,float left,float right,float top,float bottom){
            long now=SystemClock.elapsedRealtime();p.setColor(muted);c.drawText("−120 s",left,bottom+dp(20),p);p.setTextAlign(Paint.Align.RIGHT);c.drawText("now",right,bottom+dp(20),p);p.setTextAlign(Paint.Align.LEFT);
            for(NetworkCheckResult r:values){List<long[]> trail=bluetoothHistory.get(RadioPresentation.identity(r));if(trail==null||trail.isEmpty())continue;
                p.setColor(color(r));p.setStrokeWidth(dp(connected(r)?4:2));p.setStyle(Paint.Style.STROKE);Path path=new Path();boolean started=false;
                for(long[] sample:trail){float x=right-(now-sample[0])/120000f*(right-left),y=bottom-(sample[1]+140)/160f*(bottom-top);if(x<left||x>right)continue;
                    if(!started){path.moveTo(x,y);started=true;if(trail.size()==1)c.drawLine(x-dp(3),y,x+dp(3),y,p);}else path.lineTo(x,y);
                    hit.add(new RectF(x-dp(14),y-dp(14),x+dp(14),y+dp(14)));targets.add(r);
                }if(started)c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
            }
        }
        void rectangle(Canvas c,NetworkCheckResult r,float x1,float x2,float y,float bottom,float left,float right){
            RectF box=new RectF(Math.max(left,x1),y,Math.min(right,x2),bottom);if(box.width()<=0)return;
            p.setColor(color(r));p.setAlpha(115);p.setStyle(Paint.Style.FILL);c.drawRect(box,p);p.setAlpha(255);p.setColor(connected(r)?GREEN:color(r));p.setStrokeWidth(dp(connected(r)?3:1));p.setStyle(Paint.Style.STROKE);c.drawRect(box,p);p.setStyle(Paint.Style.FILL);
            RectF touch=new RectF(box);touch.inset(-dp(5),-dp(5));hit.add(touch);targets.add(r);
            if(box.width()>dp(30)){p.setTextSize(dp(9));p.setColor(foreground);String name=title(r);int n=p.breakText(name,true,box.width()-dp(4),null);c.save();c.clipRect(box);c.drawText(name.substring(0,n),box.left+dp(2),box.top+dp(12),p);c.restore();}
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN)return true;if(e.getAction()==MotionEvent.ACTION_UP){performClick();LinkedHashMap<String,NetworkCheckResult> found=new LinkedHashMap<>();for(int i=0;i<hit.size();i++)if(hit.get(i).contains(e.getX(),e.getY()))found.put(RadioPresentation.identity(targets.get(i)),targets.get(i));if(found.size()==1)open(found.values().iterator().next());else if(found.size()>1){overlaps=new ArrayList<>(found.values());render();}return true;}return super.onTouchEvent(e);}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
