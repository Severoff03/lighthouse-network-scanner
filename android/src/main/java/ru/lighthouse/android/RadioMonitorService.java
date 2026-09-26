package ru.lighthouse.android;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.concurrent.*;

public final class RadioMonitorService extends Service {
    static volatile boolean running;
    static volatile String lastError = "";
    static volatile String lastExport = "";
    static volatile boolean exporting;
    private ScheduledExecutorService scheduler;
    private PowerManager.WakeLock wake;
    private RadioCsv csv;
    private GnssMonitor gnss;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("stop".equals(intent.getAction())) { finishSession(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("radio_monitor", "Radio monitoring", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RadioMonitorService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(this, "radio_monitor").setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Lighthouse • Radio monitoring").setContentText("Wi-Fi / Bluetooth / Cell / GNSS")
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Stop and save", stop).build()).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(42, notification);
            boolean wifi = intent.getBooleanExtra("wifi", true), bt = intent.getBooleanExtra("bt", true), cell = intent.getBooleanExtra("cell", true);
            boolean gps = intent.getBooleanExtra("gps", true);
            csv = new RadioCsv(getNoBackupFilesDir());
            RadioRuntime runtime = RadioRuntime.get(this); runtime.monitoring(true, wifi, bt, cell);
            running = true; exporting=false;lastError = "";lastExport="";
            if(gps){gnss=new GnssMonitor(this,csv);gnss.start();}
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lighthouse:RadioMonitor");
            wake.acquire();
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> {
                try { synchronized (RadioCsv.class) { csv.record(runtime.snapshot(), wifi, bt, cell); } }
                catch (Exception error) { lastError = "CSV write failed: " + error.getClass().getSimpleName(); finishSession(); }
            }, 0, 120, TimeUnit.SECONDS);
        } catch (Exception error) { lastError = "Мониторинг не запущен: " + error.getClass().getSimpleName(); stopSelf(); }
        return START_NOT_STICKY;
    }
    private synchronized void finishSession(){
        if(exporting)return;
        exporting=true;
        if(scheduler!=null)scheduler.shutdownNow();
        new Thread(()->{
            try{
                if(scheduler!=null)scheduler.awaitTermination(10,TimeUnit.SECONDS);
                GnssMonitor pending=gnss;gnss=null;if(pending!=null)pending.stopAndFlush();
                RadioRuntime.get(this).monitoring(false,true,true,true);
                if(csv!=null)synchronized(RadioCsv.class){lastExport=RadioExport.save(this,csv.directory());lastError="";}
            }catch(Exception error){lastError="Download export failed: "+error.getMessage();}
            finally{running=false;exporting=false;stopSelf();}
        },"lighthouse-radio-export").start();
    }
    @Override public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        if(gnss!=null&&!exporting){GnssMonitor pending=gnss;gnss=null;new Thread(pending::stopAndFlush,"lighthouse-gnss-stop").start();}
        if (wake != null && wake.isHeld()) wake.release();
        RadioRuntime.get(this).monitoring(false, true, true, true);
        running = false; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
