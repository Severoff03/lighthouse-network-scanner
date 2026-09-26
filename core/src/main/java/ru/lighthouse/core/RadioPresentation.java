package ru.lighthouse.core;

import java.util.*;

/** Pure presentation rules; unknown measurements remain unknown. */
public final class RadioPresentation {
    private RadioPresentation() {}
    public static String value(Map<String,String> m,String... keys) {
        for(String key:keys){String v=m.get(key);if(v!=null&&!v.isBlank()&&!v.equals("null")&&!v.equals("unavailable"))return v;}
        return "—";
    }
    public static double number(Map<String,String> m,String key){return GnssNmea.number(m.get(key));}
    public static double signal(NetworkCheckResult r){return GnssNmea.number(value(r.metrics,"rssiDbm","dbm"));}
    public static String identity(NetworkCheckResult r) {
        Map<String,String> m=r.metrics;
        if(m.containsKey("bssid"))return m.get("bssid").matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")?"wifi:"+m.get("bssid").toLowerCase(Locale.ROOT):r.id;
        if(m.containsKey("address"))return "bt:"+m.get("address");
        if(m.containsKey("cellId")&&!value(m,"cellId").equals("—"))return "cell:"+value(m,"technology")+":"+value(m,"mcc")+":"+value(m,"mnc")+":"+value(m,"tac","lac")+":"+value(m,"cellId")+":"+value(m,"pci","psc","bsic")+":"+value(m,"systemId")+":"+value(m,"networkId");
        return r.id;
    }
    public static boolean matches(NetworkCheckResult r,String query) {
        String q=query.trim().toLowerCase(Locale.ROOT);if(q.isEmpty())return true;
        String hay=(r.name+" "+value(r.metrics,"ssid","name")+" "+value(r.metrics,"operator")+" "+value(r.metrics,"technology","protocol")+" "+value(r.metrics,"cellId")+" "+value(r.metrics,"mcc")+"-"+value(r.metrics,"mnc")+" "+value(r.metrics,"bssid","address")).toLowerCase(Locale.ROOT);
        if(hay.contains(q))return true;
        String mac=value(r.metrics,"bssid","address").replace(":","").replace("-","").toLowerCase(Locale.ROOT);
        String compact=q.replace(":","").replace("-","");return compact.matches("[0-9a-f]{2,12}")&&mac.contains(compact);
    }
    public static int wifiBand(NetworkCheckResult r) {
        double f=number(r.metrics,"frequencyMHz");return f>=2400&&f<2500?0:f>=4900&&f<5925?1:f>=5925&&f<=7125?2:-1;
    }
    public static String wifiProtocol(NetworkCheckResult r) {
        String reported=r.metrics.get("protocol");
        if(reported==null)return "Unknown";
        return switch(reported){
            case "802.11 legacy" -> "Legacy";
            case "802.11n" -> "N";
            case "802.11ac" -> "AC";
            case "802.11ax" -> "AX";
            case "802.11be" -> "BE";
            case "802.11ad" -> "AD";
            default -> "Unknown";
        };
    }
    public static boolean matchesWifiProtocol(NetworkCheckResult r,String selected) {
        return "All".equals(selected)||wifiProtocol(r).equals(selected);
    }
    public static Comparator<NetworkCheckResult> order(boolean frequency,boolean ascending) {
        return (a,b)->{
            double x=frequency?number(a.metrics,"frequencyMHz"):signal(a), y=frequency?number(b.metrics,"frequencyMHz"):signal(b);
            int c=Double.isNaN(x)?(Double.isNaN(y)?0:1):Double.isNaN(y)?-1:(ascending?Double.compare(x,y):Double.compare(y,x));
            return c!=0?c:identity(a).compareTo(identity(b));
        };
    }
}
