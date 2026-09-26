package ru.lighthouse.android;

import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import ru.lighthouse.core.NetworkCheckResult;

final class RadioCsv {
    private static final String HEADER = "Дата;Время;Название;Протокол;MAC_CellID;Уровень_dBm;BW;Каналы_Частоты;Шифрование;Подключено_Зарегистрировано;MCC;MNC;TAC_LAC;SIM;Возраст_ms;Источник;Время_записи";
    private final File directory;
    RadioCsv(File root) throws IOException {
        directory = new File(root, "radio-logs/" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(LocalDateTime.now()));
        if (!directory.mkdirs()) throw new IOException("Не удалось создать журнал");
    }
    File directory(){return directory;}
    synchronized void gnssEvent(String event,String detail) throws IOException {
        String[] time=timestamp(System.currentTimeMillis());
        append("gps_events.csv","Date;Time;Event;Detail",time[0],time[1],event,detail);
    }
    synchronized void gnssNmea(String sentence,boolean valid) throws IOException {
        String[] time=timestamp(System.currentTimeMillis());
        append("gps_nmea.csv","Date;Time;Valid_checksum;Sentence",time[0],time[1],String.valueOf(valid),sentence);
    }
    synchronized void gnssSatellites(android.location.GnssStatus status) throws IOException {
        String[] time=timestamp(System.currentTimeMillis());
        String header="Date;Time;System;SVID;C_N0_dBHz;Azimuth_deg;Elevation_deg;Used_in_fix;Carrier_MHz;Ephemeris;Almanac";
        ensureHeader("gps_satellites.csv",header);
        for(int i=0;i<status.getSatelliteCount();i++)append("gps_satellites.csv",header,time[0],time[1],
            GnssSurvey.constellationName(status.getConstellationType(i)),String.valueOf(status.getSvid(i)),
            String.valueOf(status.getCn0DbHz(i)),String.valueOf(status.getAzimuthDegrees(i)),String.valueOf(status.getElevationDegrees(i)),
            String.valueOf(status.usedInFix(i)),status.hasCarrierFrequencyHz(i)?String.valueOf(status.getCarrierFrequencyHz(i)/1e6):"",
            String.valueOf(status.hasEphemerisData(i)),String.valueOf(status.hasAlmanacData(i)));
    }
    synchronized void gnssFix(android.location.Location location,int dimension) throws IOException {
        String[] time=timestamp(System.currentTimeMillis());
        append("gps_fixes.csv","Date;Time;Latitude;Longitude;Fix_dimension_NMEA;Horizontal_accuracy_m;Altitude_ellipsoid_m;Vertical_accuracy_m;Speed_mps;Mock;Provider;Fix_UTC",
            time[0],time[1],String.valueOf(location.getLatitude()),String.valueOf(location.getLongitude()),
            dimension==2||dimension==3?String.valueOf(dimension):"",
            location.hasAccuracy()?String.valueOf(location.getAccuracy()):"",
            location.hasAltitude()?String.valueOf(location.getAltitude()):"",
            android.os.Build.VERSION.SDK_INT>=26&&location.hasVerticalAccuracy()?String.valueOf(location.getVerticalAccuracyMeters()):"",
            location.hasSpeed()?String.valueOf(location.getSpeed()):"",String.valueOf(location.isFromMockProvider()),
            location.getProvider(),java.time.Instant.ofEpochMilli(location.getTime()).toString());
    }
    void record(List<NetworkCheckResult> data, boolean wifi, boolean bt, boolean cell) throws IOException {
        long now = SystemClock.elapsedRealtime();
        String[] time = timestamp(System.currentTimeMillis());
        for (String module : new String[]{"wifi", "bluetooth", "cell"}) {
            if (!(module.equals("wifi") ? wifi : module.equals("bluetooth") ? bt : cell)) continue;
            ensureHeader(module + ".csv", HEADER);
            int count = 0;
            for (NetworkCheckResult r : data) {
                Map<String,String> m = r.metrics;
                boolean matches = module.equals("wifi") ? m.containsKey("bssid") && m.containsKey("observedElapsedMs")
                    : module.equals("bluetooth") ? m.containsKey("address") : m.containsKey("cellId");
                if (!matches) continue;
                long observed = number(m.get("observedElapsedMs"), -1), age = now - observed;
                // Never turn cached/unknown-age entries into a new measurement.
                if (observed <= 0 || age < 0 || age > 120_000) continue;
                String[] measured = timestamp(System.currentTimeMillis() - age);
                append(module + ".csv", HEADER,
                    measured[0], measured[1], value(m, "ssid", "name", "operator"), value(m, "protocol", "technology"),
                    value(m, "bssid", "address", "cellId"), value(m, "rssiDbm", "dbm"),
                    !clean(m.get("bandwidthMHz")).isEmpty() ? m.get("bandwidthMHz") + " MHz" : !clean(m.get("bandwidthKHz")).isEmpty() ? m.get("bandwidthKHz") + " kHz" : "",
                    frequencies(m), clean(m.get("security")), value(m, "connected", "registered"), clean(m.get("mcc")), clean(m.get("mnc")),
                    value(m, "tac", "lac"), clean(m.get("sim")), String.valueOf(age), value(m, "source", "protocol"), String.join(" ", time));
                count++;
            }
            StringBuilder diagnostics = new StringBuilder();
            for (NetworkCheckResult r : data) if (r.status != NetworkCheckResult.Status.OK
                && (module.equals("wifi") ? r.category.contains("Wi-Fi") : module.equals("bluetooth") ? r.category.contains("Bluetooth") : r.category.contains("Сотов")))
                diagnostics.append(r.summary).append(' ');
            append("status.csv", "Дата;Время;Модуль;Свежих_записей;Диагностика", time[0], time[1], module, String.valueOf(count), diagnostics.toString());
        }
        NetworkCheckResult fix = null;
        for (String suffix : new String[]{"network_location", "gps_fix"}) {
            for (NetworkCheckResult r : data) if (r.id.endsWith(suffix) && r.metrics.containsKey("latitude")
                && RadioPage.age(r.metrics) >= 0 && RadioPage.age(r.metrics) <= 120_000
                && !"true".equals(r.metrics.get("mockLocation"))) { fix = r; break; }
            if (fix != null) break;
        }
        append("movement.csv", "Дата;Время;Координаты;Источник;Точность_m;Возраст_ms", time[0], time[1],
            fix == null ? "" : fix.metrics.get("latitude") + "," + fix.metrics.get("longitude"),
            fix == null ? "" : clean(fix.metrics.get("provider")), fix == null ? "" : clean(fix.metrics.get("accuracyMeters")),
            fix == null ? "" : String.valueOf(RadioPage.age(fix.metrics)));
    }
    static String frequencies(Map<String,String> m) {
        for (String key : new String[]{"frequencyMHz", "earfcn", "nrarfcn", "arfcn", "uarfcn"})
            if (!clean(m.get(key)).isEmpty()) return key + "=" + m.get(key);
        return "";
    }
    private void ensureHeader(String file, String header) throws IOException {
        File target = new File(directory,file);
        if (!target.exists()) try (Writer out = new OutputStreamWriter(new FileOutputStream(target),StandardCharsets.UTF_8)) { out.write("\ufeff" + header + "\r\n"); }
    }
    private void append(String file, String header, String... fields) throws IOException {
        File target = new File(directory, file); boolean fresh = !target.exists();
        try (Writer out = new OutputStreamWriter(new FileOutputStream(target, true), StandardCharsets.UTF_8)) {
            if (fresh) out.write("\ufeff" + header + "\r\n");
            for (int i = 0; i < fields.length; i++) { if (i > 0) out.write(';'); out.write(escape(fields[i])); }
            out.write("\r\n");
        }
    }
    static String escape(String text) { return "\"" + (text == null ? "" : text).replace("\r"," ").replace("\n"," ").replace("\"", "\"\"") + "\""; }
    static String clean(String s) { return s == null || s.equals("unavailable") || s.equals("null") ? "" : s; }
    static String value(Map<String,String> m, String... keys) { for (String k : keys) if (!clean(m.get(k)).isEmpty()) return m.get(k); return ""; }
    static long number(String s, long fallback) { try { return Long.parseLong(s); } catch (Exception ignored) { return fallback; } }
    private static String[] timestamp(long millis) {
        LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        return new String[]{date.toLocalDate().toString(), date.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))};
    }
}
