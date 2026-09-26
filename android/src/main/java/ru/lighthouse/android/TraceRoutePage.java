package ru.lighthouse.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Runs a real traceroute/tracepath utility. Never infers hops from ordinary ping responses. */
final class TraceRoutePage extends LinearLayout {
    private static final Pattern HOP = Pattern.compile("^\\s*\\d+\\s+.+");
    private static final Pattern TRACEPATH_HOP = Pattern.compile("^\\s*\\d+\\??:\\s+(?:[0-9A-Fa-f:.]+|no reply)(?:\\s+.*)?$");
    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int ink, muted, surface;
    private final EditText target;
    private final TextView state;
    private final LinearLayout hops;
    private final Button action,rootAction;
    private volatile Process process;
    private volatile boolean cancelled;
    private Thread worker;

    TraceRoutePage(Activity context, boolean dark, Runnable back) {
        super(context); activity=context;
        boolean terminal=TerminalTheme.enabled(context);
        ink=terminal?TerminalTheme.TEXT:dark?Color.WHITE:0xff16202e;
        muted=terminal?TerminalTheme.MUTED:dark?0xffa9b0c2:0xff5b667e;
        surface=terminal?TerminalTheme.SURFACE:dark?0xff161922:Color.WHITE;
        setOrientation(VERTICAL); setPadding(dp(16),dp(14),dp(16),dp(8));
        addView(button(UiLanguage.isRussian()?"← Инструменты":"← Tools",back),new LayoutParams(-1,dp(46)));
        addView(text("Trace",23,ink));
        addView(text("Route to an IP address or URL. Traceroute and tracepath are tried without root first. Only measured hops are shown; * means no reply.",12,muted));
        target=new EditText(context);target.setSingleLine(true);target.setTextColor(ink);target.setHintTextColor(muted);
        target.setHint(UiLanguage.text("IP address or URL"));target.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        addView(target,new LayoutParams(-1,dp(52)));
        action=button("Start trace",this::toggle);addView(action,new LayoutParams(-1,dp(50)));
        rootAction=button("Use root traceroute",this::startRoot);rootAction.setVisibility(GONE);addView(rootAction,new LayoutParams(-1,dp(46)));
        state=text("Ready",13,muted);addView(state);
        ScrollView scroll=new ScrollView(context);hops=new LinearLayout(context);hops.setOrientation(VERTICAL);
        scroll.addView(hops);addView(scroll,new LayoutParams(-1,0,1));
    }

    private void toggle(){
        if(worker!=null&&worker.isAlive()){cancelled=true;Process running=process;if(running!=null)running.destroyForcibly();worker.interrupt();action.setText(UiLanguage.text("Start trace"));state.setText(UiLanguage.text("Cancelled"));return;}
        String input=target.getText().toString().trim();
        final String host;
        try {host=host(input);}catch(IllegalArgumentException error){state.setText(UiLanguage.text(error.getMessage()));return;}
        start(host,false);
    }
    private void startRoot(){
        if(worker!=null&&worker.isAlive())return;
        try{start(host(target.getText().toString().trim()),true);}
        catch(IllegalArgumentException error){state.setText(UiLanguage.text(error.getMessage()));}
    }
    private void start(String host,boolean useRoot){
        cancelled=false;hops.removeAllViews();rootAction.setVisibility(GONE);action.setText(UiLanguage.text("Stop trace"));state.setText(UiLanguage.text("Resolving host…"));
        worker=new Thread(()->trace(host,useRoot),"lighthouse-trace");worker.start();
    }

    private static String host(String input){
        if(input.isEmpty())throw new IllegalArgumentException("Enter an IP address or URL");
        if(!input.contains("://")&&input.indexOf(':')>=0&&!input.contains("/")&&!input.startsWith("[")){
            try{InetAddress address=InetAddress.getByName(input);if(!(address instanceof Inet4Address))return address.getHostAddress();}
            catch(Exception ignored){throw new IllegalArgumentException("Invalid IP address or URL");}
        }
        try{
            URI uri=URI.create(input.contains("://")?input:"https://"+input);
            String host=uri.getHost();
            if(host==null||host.isBlank()||uri.getUserInfo()!=null||host.length()>253)throw new IllegalArgumentException("Invalid IP address or URL");
            return host;
        }catch(IllegalArgumentException error){throw new IllegalArgumentException("Invalid IP address or URL");}
    }

    private void trace(String host,boolean useRoot){
        try{
            InetAddress[] addresses=InetAddress.getAllByName(host);
            InetAddress destination=null;
            for(InetAddress address:addresses)if(address instanceof Inet4Address){destination=address;break;}
            if(destination==null&&addresses.length>0)destination=addresses[0];
            if(destination==null)throw new IOException("No address returned by DNS");
            String ip=destination.getHostAddress();
            if(!ip.matches("[0-9A-Fa-f:.]+"))throw new IOException("Scoped addresses are not supported by this traceroute backend");
            postIfActive(()->state.setText((UiLanguage.isRussian()?"Трассировка ":"Tracing ")+host+" → "+ip));
            String[] args={"-n","-m","30","-q","3","-w","2",ip};
            String[] rootCommands={"traceroute "+String.join(" ",args),"toybox traceroute "+String.join(" ",args),
                "busybox traceroute "+String.join(" ",args),"/data/data/com.termux/files/usr/bin/traceroute "+String.join(" ",args)};
            String[][] candidates=useRoot
                ?new String[][]{{"su","-c",rootCommands[0]},{"su","-c",rootCommands[1]},{"su","-c",rootCommands[2]},{"su","-c",rootCommands[3]}}
                :new String[][]{withCommand("/system/bin/traceroute",args),withCommand("/system/bin/toybox","traceroute",args),
                    withCommand("/system/xbin/busybox","traceroute",args),withCommand("traceroute",args)};
            for(String[] command:candidates){
                if(cancelled)return;
                if(runTraceroute(command,false))return;
            }
            if(!useRoot){
                String[] tracepathArgs={"-n","-m","30",ip};
                for(String[] command:new String[][]{withCommand("/system/bin/tracepath",tracepathArgs),withCommand("tracepath",tracepathArgs)}){
                    if(cancelled)return;
                    if(runTraceroute(command,true))return;
                }
            }
            postIfActive(()->{state.setText(UiLanguage.text(useRoot
                ?"Root traceroute is unavailable. Grant root access and install a traceroute-capable binary."
                :"No usable traceroute or tracepath utility was found without root. Root is optional if a compatible unprivileged utility is available."));
                rootAction.setVisibility(VISIBLE);
            });
        }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        catch(Exception error){if(!cancelled)postIfActive(()->{state.setText((UiLanguage.isRussian()?"Ошибка трассировки: ":"Trace failed: ")
            +(error.getMessage()==null?error.getClass().getSimpleName():UiLanguage.text(error.getMessage())));
            if(useRoot)rootAction.setVisibility(VISIBLE);
        });}
        finally{process=null;postIfActive(()->{if(!cancelled)action.setText(UiLanguage.text("Start trace"));});}
    }
    private static String[] withCommand(String binary,String[] args){String[] command=new String[args.length+1];command[0]=binary;System.arraycopy(args,0,command,1,args.length);return command;}
    private static String[] withCommand(String binary,String subcommand,String[] args){String[] command=new String[args.length+2];command[0]=binary;command[1]=subcommand;System.arraycopy(args,0,command,2,args.length);return command;}
    private boolean runTraceroute(String[] command,boolean tracepath) throws InterruptedException,IOException {
        final Process running;
        try{running=new ProcessBuilder(command).redirectErrorStream(true).start();}
        catch(IOException missing){return false;}
        process=running;int observations=0;StringBuilder error=new StringBuilder();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(running.getInputStream(),StandardCharsets.UTF_8))){
            String line;
            while(!cancelled&&(line=reader.readLine())!=null){
                if((tracepath?TRACEPATH_HOP:HOP).matcher(line).matches()){
                    observations++;String measured=line;
                    postIfActive(()->hops.addView(text(measured,15,measured.contains("*")?muted:ink)));
                }else if(error.length()<1000)error.append(line).append(' ');
            }
            running.waitFor();
        }catch(IOException failure){if(!cancelled)error.append(failure.getMessage());}
        finally{running.destroy();process=null;}
        if(cancelled)return true;
        if(observations>0){postIfActive(()->state.setText(UiLanguage.text("Trace finished. Missing replies do not prove that a hop is absent.")));return true;}
        if("su".equals(command[0])&&error.toString().toLowerCase(java.util.Locale.ROOT).matches(".*(denied|not granted|not allowed).*"))
            throw new IOException("Root access was denied");
        return false;
    }

    private void postIfActive(Runnable callback){ui.post(()->{if(!cancelled&&isAttachedToWindow())callback.run();});}
    private Button button(String label,Runnable click){Button button=new Button(activity);button.setText(UiLanguage.text(label));button.setAllCaps(false);button.setTextColor(ink);if(TerminalTheme.enabled(activity)){button.setTypeface(Typeface.MONOSPACE);button.setBackground(TerminalTheme.panel(activity,surface));}button.setOnClickListener(v->click.run());return button;}
    private TextView text(String label,int size,int color){TextView view=new TextView(activity);view.setText(UiLanguage.text(label));view.setTextColor(color);view.setTextSize(size);view.setPadding(0,dp(5),0,dp(5));if(TerminalTheme.enabled(activity))view.setTypeface(Typeface.MONOSPACE);return view;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onDetachedFromWindow(){cancelled=true;Process running=process;if(running!=null)running.destroyForcibly();if(worker!=null)worker.interrupt();super.onDetachedFromWindow();}
}
