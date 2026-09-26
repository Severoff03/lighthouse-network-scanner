package ru.lighthouse.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** TCP-positive local discovery: reports only addresses with an open tested port. */
final class IpScannerPage extends LinearLayout {
    private static final int[] PORTS={80,443,22,445,53,8080};
    private final Activity activity;private final int ink,surface,muted;
    private final EditText manual;private final TextView progress;private final LinearLayout results;
    private final Button action;private final Handler main=new Handler(Looper.getMainLooper());
    private ExecutorService workers;private volatile boolean cancelled;
    IpScannerPage(Activity activity,boolean dark,Runnable back){super(activity);this.activity=activity;boolean terminal=TerminalTheme.enabled(activity);ink=terminal?TerminalTheme.TEXT:dark?Color.WHITE:0xff16202e;surface=terminal?TerminalTheme.SURFACE:dark?0xff161922:Color.WHITE;muted=terminal?TerminalTheme.MUTED:dark?0xffa9b0c2:0xff5b667e;setOrientation(VERTICAL);setPadding(dp(16),dp(14),dp(16),dp(8));
        Button backButton=button(local("← Tools","← Инструменты"),back);addView(backButton,new LayoutParams(-1,dp(46)));
        addView(text(local("IP scanner","IP-сканер"),23,ink),new LayoutParams(-1,dp(48)));
        addView(text(local("Active Wi-Fi / Ethernet: scan the local /24 around each address. Add a private CIDR (/22–/32) for another subnet. Only confirmed open TCP ports are listed.","Активные Wi-Fi / Ethernet: сканирование локальной /24 для каждого адреса. Добавьте частную подсеть CIDR (/22–/32). Показываются только подтверждённые открытые TCP-порты."),12,muted));
        manual=new EditText(activity);manual.setSingleLine(true);manual.setTextColor(ink);manual.setHintTextColor(muted);manual.setHint("IPv4 CIDR");manual.setText("192.168.0.0/24");addView(manual,new LayoutParams(-1,dp(50)));
        action=button(local("Scan local networks","Сканировать локальные сети"),this::toggle);addView(action,new LayoutParams(-1,dp(50)));
        progress=text(local("Ready","Готово"),13,muted);addView(progress);
        ScrollView scroll=new ScrollView(activity);results=new LinearLayout(activity);results.setOrientation(VERTICAL);scroll.addView(results);addView(scroll,new LayoutParams(-1,0,1));
    }
    private Button button(String label,Runnable click){Button b=new Button(activity);b.setText(label);b.setAllCaps(false);b.setTextColor(ink);if(TerminalTheme.enabled(activity)){b.setTypeface(Typeface.MONOSPACE);b.setBackground(TerminalTheme.panel(activity,surface));}b.setOnClickListener(v->click.run());return b;}
    private TextView text(String value,int sp,int color){TextView t=new TextView(activity);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));if(TerminalTheme.enabled(activity))t.setTypeface(Typeface.MONOSPACE);return t;}
    private int dp(int px){return Math.round(px*getResources().getDisplayMetrics().density);}
    private static String local(String en,String ru){return UiLanguage.isRussian()?ru:en;}
    private void toggle(){if(workers!=null){cancelled=true;workers.shutdownNow();workers=null;action.setText(local("Scan local networks","Сканировать локальные сети"));progress.setText(local("Cancelled","Отменено"));return;}
        ConnectivityManager manager=activity.getSystemService(ConnectivityManager.class);if(manager==null){progress.setText(local("Network service unavailable","Сетевая служба недоступна"));return;}
        List<Network> routes=new ArrayList<>();LinkedHashMap<String,Network> targets=new LinkedHashMap<>();
        try{
            for(Network network:manager.getAllNetworks()){
                NetworkCapabilities capabilities=manager.getNetworkCapabilities(network);
                if(capabilities==null||capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||!(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))continue;
                routes.add(network);LinkProperties properties=manager.getLinkProperties(network);if(properties==null)continue;
                for(LinkAddress address:properties.getLinkAddresses())if(address.getAddress() instanceof Inet4Address){
                    int prefix=Math.max(24,address.getPrefixLength());
                    for(String ip:hosts(address.getAddress().getHostAddress()+"/"+prefix))
                        if(!ip.equals(address.getAddress().getHostAddress()))targets.putIfAbsent(ip,network);
                }
            }
            String extra=manual.getText().toString().trim();if(!extra.isEmpty()){
                if(routes.isEmpty())throw new IllegalArgumentException(local("Connect to Wi-Fi or Ethernet first","Сначала подключитесь к Wi-Fi или Ethernet"));
                for(String ip:hosts(extra))targets.putIfAbsent(ip,routes.get(0));
            }
            if(targets.isEmpty())throw new IllegalArgumentException(local("No scannable Wi-Fi / Ethernet subnet","Нет доступной подсети Wi-Fi / Ethernet"));
            if(targets.size()>2048)throw new IllegalArgumentException(local("Too many addresses; select narrower CIDR ranges","Слишком много адресов; выберите более узкий диапазон CIDR"));
        }catch(IllegalArgumentException error){progress.setText(error.getMessage());return;}
        results.removeAllViews();cancelled=false;workers=Executors.newFixedThreadPool(24);action.setText(local("Cancel scan","Отменить сканирование"));
        AtomicInteger done=new AtomicInteger(),found=new AtomicInteger();int total=targets.size();progress.setText("0 / "+total);
        ExecutorService pool=workers;
        for(Map.Entry<String,Network> entry:targets.entrySet())pool.execute(()->{
            if(cancelled)return;String host=entry.getKey();List<Integer> open=new ArrayList<>();
            for(int port:PORTS){if(cancelled||Thread.currentThread().isInterrupted())return;
                try(Socket socket=new Socket()){entry.getValue().bindSocket(socket);socket.connect(new InetSocketAddress(host,port),300);open.add(port);}catch(java.io.IOException ignored){}
            }
            int finished=done.incrementAndGet();if(!open.isEmpty())found.incrementAndGet();
            main.post(()->{if(cancelled)return;if(!open.isEmpty()){
                LinearLayout row=new LinearLayout(activity);row.setOrientation(VERTICAL);row.setPadding(dp(12),dp(10),dp(12),dp(10));GradientDrawable bg=new GradientDrawable();bg.setColor(surface);bg.setCornerRadius(TerminalTheme.enabled(activity)?0:dp(10));row.setBackground(bg);
                TextView title=text(host,17,ink);title.setTypeface(null,Typeface.BOLD);row.addView(title);row.addView(text(local("Open TCP: ","Открытые TCP: ")+open.toString(),12,muted));results.addView(row);
            }progress.setText(finished+" / "+total+" · "+found.get()+local(" confirmed"," подтверждено"));if(finished==total){action.setText(local("Scan local networks","Сканировать локальные сети"));pool.shutdown();if(workers==pool)workers=null;}});
        });
    }
    static List<String> hosts(String cidr){
        String[] pieces=cidr.split("/",-1);if(pieces.length!=2)throw new IllegalArgumentException(local("Enter an IPv4 CIDR such as 192.168.1.0/24","Введите IPv4 CIDR, например 192.168.1.0/24"));
        String[] octets=pieces[0].split("\\.",-1);if(octets.length!=4)throw new IllegalArgumentException(local("Invalid IPv4 address","Неверный адрес IPv4"));
        long address=0;for(String part:octets){if(!part.matches("(0|[1-9][0-9]{0,2})"))throw new IllegalArgumentException(local("Invalid IPv4 address","Неверный адрес IPv4"));int n=Integer.parseInt(part);if(n>255)throw new IllegalArgumentException(local("Invalid IPv4 address","Неверный адрес IPv4"));address=(address<<8)|n;}
        int prefix;try{prefix=Integer.parseInt(pieces[1]);}catch(NumberFormatException e){throw new IllegalArgumentException(local("Invalid prefix","Неверный префикс"));}
        if(prefix<22||prefix>32)throw new IllegalArgumentException(local("CIDR must be /22 to /32 (at most 1024 addresses)","CIDR должен быть от /22 до /32 (не более 1024 адресов)"));
        int first=(int)(address>>>24),second=(int)((address>>>16)&255);
        if(!(first==10||first==172&&second>=16&&second<=31||first==192&&second==168||first==169&&second==254))throw new IllegalArgumentException(local("Only local/private IPv4 ranges are allowed","Разрешены только локальные/частные диапазоны IPv4"));
        long mask=prefix==32?0xffffffffL:(0xffffffffL << (32-prefix))&0xffffffffL;
        long start=address&mask,end=start+(1L<<(32-prefix))-1;
        List<String> result=new ArrayList<>();for(long ip=start;ip<=end;ip++){
            if(prefix<=30&&(ip==start||ip==end))continue;
            result.add((ip>>>24)+"."+((ip>>>16)&255)+"."+((ip>>>8)&255)+"."+(ip&255));
        }return result;
    }
    @Override protected void onDetachedFromWindow(){cancelled=true;if(workers!=null)workers.shutdownNow();super.onDetachedFromWindow();}
}
