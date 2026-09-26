package ru.lighthouse.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import ru.lighthouse.core.GnssNmea;

/** Records actual GNSS callbacks while explicit foreground monitoring is active. */
final class GnssMonitor {
    private final Context context;private RadioCsv csv;private final LocationManager manager;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(256),r->{Thread t=new Thread(r,"lighthouse-gnss-csv");t.setDaemon(true);return t;});
    private final AtomicInteger dropped=new AtomicInteger();private volatile boolean active;
    private volatile int dimension;private volatile long dimensionAt;
    GnssMonitor(Context context,RadioCsv csv){this.context=context;this.csv=csv;manager=context.getSystemService(LocationManager.class);}
    private void write(IoAction action){try{writer.execute(()->{try{action.run();}catch(IOException e){RadioMonitorService.lastError="GNSS CSV: "+e.getClass().getSimpleName();}});}catch(RejectedExecutionException e){dropped.incrementAndGet();}}
    private final GnssStatus.Callback status=new GnssStatus.Callback(){
        @Override public void onStarted(){write(()->csv.gnssEvent("receiver_started",""));}
        @Override public void onStopped(){write(()->csv.gnssEvent("receiver_stopped",""));}
        @Override public void onFirstFix(int ttffMillis){write(()->csv.gnssEvent("first_fix","TTFF_ms="+ttffMillis));}
        @Override public void onSatelliteStatusChanged(GnssStatus snapshot){if(active)write(()->csv.gnssSatellites(snapshot));}
    };
    private final OnNmeaMessageListener nmea=(sentence,time)->{if(!active)return;boolean valid=GnssNmea.fields(sentence)!=null;int mode=GnssNmea.fixDimension(sentence);
        if(mode>0){dimension=mode;dimensionAt=SystemClock.elapsedRealtime();}
        write(()->csv.gnssNmea(sentence,valid));
    };
    private final LocationListener fixes=new LocationListener(){
        @Override public void onLocationChanged(Location location){if(!active||!LocationManager.GPS_PROVIDER.equals(location.getProvider()))return;
            Location copy=new Location(location);int mode=SystemClock.elapsedRealtime()-dimensionAt<=GnssSurvey.FRESH_MS?dimension:0;
            write(()->csv.gnssFix(copy,mode));}
        @Override public void onProviderDisabled(String name){write(()->csv.gnssEvent("provider_disabled",name));}
        @Override public void onProviderEnabled(String name){write(()->csv.gnssEvent("provider_enabled",name));}
        @Override public void onStatusChanged(String name,int status,Bundle extras){}
    };
    void start(){if(manager==null||context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){write(()->csv.gnssEvent("unavailable","Fine location permission missing"));return;}
        try{if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)){write(()->csv.gnssEvent("unavailable","GPS provider disabled"));return;}
            active=true;boolean satellites=manager.registerGnssStatusCallback(status,main);boolean sentences=manager.addNmeaListener(nmea,main);
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,fixes,Looper.getMainLooper());
            write(()->csv.gnssEvent("monitor_started","GnssStatus="+satellites+", NMEA="+sentences));
        }catch(RuntimeException error){active=false;write(()->csv.gnssEvent("start_failed",error.getClass().getSimpleName()));}
    }
    void stopAndFlush(){active=false;if(manager!=null){try{manager.removeUpdates(fixes);}catch(RuntimeException ignored){}try{manager.removeNmeaListener(nmea);}catch(RuntimeException ignored){}try{manager.unregisterGnssStatusCallback(status);}catch(RuntimeException ignored){}}
        write(()->csv.gnssEvent("monitor_stopped","dropped_events="+dropped.get()));writer.shutdown();try{writer.awaitTermination(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    private interface IoAction{void run()throws IOException;}
}
