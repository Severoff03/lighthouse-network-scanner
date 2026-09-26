package ru.lighthouse.android;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import java.util.*;
import ru.lighthouse.core.GnssNmea;

/** Main-thread, visible-navigation-only GPS_PROVIDER survey. No fused/network coordinates. */
final class GnssSurvey {
    static final long FRESH_MS=15000;
    static final class Satellite {
        final int svid, constellation;final float cn0,azimuth,elevation;
        final boolean used,ephemeris,almanac;final double frequency,baseband;
        Satellite(GnssStatus s,int i){
            svid=s.getSvid(i);constellation=s.getConstellationType(i);cn0=s.getCn0DbHz(i);
            azimuth=s.getAzimuthDegrees(i);elevation=s.getElevationDegrees(i);used=s.usedInFix(i);
            ephemeris=s.hasEphemerisData(i);almanac=s.hasAlmanacData(i);
            frequency=s.hasCarrierFrequencyHz(i)?s.getCarrierFrequencyHz(i)/1e6:Double.NaN;
            baseband=Build.VERSION.SDK_INT>=30&&s.hasBasebandCn0DbHz(i)?s.getBasebandCn0DbHz(i):Double.NaN;
        }
        String id(){return constellation+":"+svid;}
        String title(){return constellationName(constellation)+" "+svid;}
    }
    private final Context context;
    private final LocationManager manager;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean running;
    List<Satellite> satellites=Collections.emptyList();
    Location location;
    long observed, dopAt, altitudeAt;
    double[] dop;double msl=Double.NaN;
    String error="";
    private final Map<String,long[]> dimensions=new HashMap<>();
    GnssSurvey(Context context){this.context=context;manager=context.getSystemService(LocationManager.class);}
    private final GnssStatus.Callback status=new GnssStatus.Callback(){
        @Override public void onSatelliteStatusChanged(GnssStatus data){if(!running)return;List<Satellite> next=new ArrayList<>();for(int i=0;i<data.getSatelliteCount();i++)next.add(new Satellite(data,i));satellites=next;observed=SystemClock.elapsedRealtime();}
        @Override public void onStopped(){clear();}
    };
    private final LocationListener locations=new LocationListener(){
        @Override public void onLocationChanged(Location value){if(running&&LocationManager.GPS_PROVIDER.equals(value.getProvider()))location=new Location(value);}
        @Override public void onProviderDisabled(String provider){if(LocationManager.GPS_PROVIDER.equals(provider)){clear();error="GPS выключен в настройках устройства";}}
        @Override public void onProviderEnabled(String provider){error="";}
        @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    };
    private final OnNmeaMessageListener nmea=(message,timestamp)->{
        if(!running)return;
        long now=SystemClock.elapsedRealtime();String[] fields=GnssNmea.fields(message);int mode=GnssNmea.fixDimension(message);
        if(mode>0&&fields!=null){
            String key=fields[0]+(fields.length>18?":"+fields[18]:"");dimensions.put(key,new long[]{now,mode});
            dop=GnssNmea.dop(message);dopAt=now;
        }
        double altitude=GnssNmea.altitudeMeanSeaLevel(message);if(Double.isFinite(altitude)){msl=altitude;altitudeAt=now;}
    };
    void start(){
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){if(running)stop();error="Разрешите точное местоположение для GNSS";return;}
        try{
            if(manager==null||!manager.getAllProviders().contains(LocationManager.GPS_PROVIDER)){error="GNSS-приёмник недоступен";return;}
            if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)){error="Включите геолокацию / GPS";return;}
            if(running)return;
            running=true;error="";
            boolean supported=manager.registerGnssStatusCallback(status,handler);
            boolean nmeaSupported=manager.addNmeaListener(nmea,handler);
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,locations,Looper.getMainLooper());
            if(!supported)error="ОС не предоставила список спутников";
            else if(!nmeaSupported)error="NMEA недоступен: тип 2D/3D не определяется";
        }catch(RuntimeException failure){stop();error="GNSS: "+failure.getClass().getSimpleName();}
    }
    void stop(){
        running=false;
        if(manager!=null){try{manager.removeUpdates(locations);}catch(RuntimeException ignored){}try{manager.unregisterGnssStatusCallback(status);}catch(RuntimeException ignored){}try{manager.removeNmeaListener(nmea);}catch(RuntimeException ignored){}}
        clear();
    }
    private void clear(){satellites=Collections.emptyList();location=null;observed=0;dimensions.clear();dop=null;msl=Double.NaN;}
    boolean freshSatellites(){return observed>0&&SystemClock.elapsedRealtime()-observed<=FRESH_MS;}
    boolean freshLocation(){if(location==null)return false;long age=SystemClock.elapsedRealtime()-location.getElapsedRealtimeNanos()/1_000_000;return age>=0&&age<=FRESH_MS;}
    int dimension(){
        long now=SystemClock.elapsedRealtime();dimensions.values().removeIf(v->now-v[0]>FRESH_MS);
        int mode=0;for(long[] value:dimensions.values()){if(mode!=0&&mode!=value[1])return 0;mode=(int)value[1];}return mode;
    }
    static String constellationName(int value){return switch(value){case GnssStatus.CONSTELLATION_GPS->"GPS";case GnssStatus.CONSTELLATION_GLONASS->"GLONASS";case GnssStatus.CONSTELLATION_GALILEO->"Galileo";case GnssStatus.CONSTELLATION_BEIDOU->"BeiDou";case GnssStatus.CONSTELLATION_QZSS->"QZSS";case GnssStatus.CONSTELLATION_SBAS->"SBAS";case GnssStatus.CONSTELLATION_IRNSS->"NavIC";default->"GNSS ("+value+")";};}
}
