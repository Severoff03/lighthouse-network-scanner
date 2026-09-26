package ru.lighthouse.android;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.os.Process;

import ru.lighthouse.core.NetworkCheckResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads Android's aggregate counters. It cannot inspect payloads or remote destinations. */
final class AndroidAppTraffic {
    private static final long ATTENTION_BYTES = 10L * 1024 * 1024;
    private static final List<String> SENSITIVE = Arrays.asList(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE);

    private static final class Traffic {
        long backgroundTx, backgroundRx, foregroundTx, foregroundRx, unknownTx, unknownRx;
        long sent() { return backgroundTx + foregroundTx + unknownTx; }
    }

    private AndroidAppTraffic() { }

    static boolean allowed(Context context) {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    static List<NetworkCheckResult> collect(Context context) {
        List<NetworkCheckResult> result = new ArrayList<>();
        if (!allowed(context)) {
            result.add(check("app_traffic_permission", "Сетевая активность приложений", NetworkCheckResult.Status.WARNING,
                "Доступ к статистике не выдан. Android не позволяет Lighthouse видеть процессы других приложений без отдельного системного разрешения.",
                map("permission", "usage access not granted", "limitation", "No process list or remote destinations available")));
            return result;
        }
        Map<Integer, Traffic> byUid = new LinkedHashMap<>();
        long end = System.currentTimeMillis(), start = end - 24L * 60 * 60 * 1000;
        try {
            NetworkStatsManager manager = (NetworkStatsManager) context.getSystemService(Context.NETWORK_STATS_SERVICE);
            read(manager, ConnectivityManager.TYPE_WIFI, start, end, byUid);
            read(manager, ConnectivityManager.TYPE_MOBILE, start, end, byUid);
        } catch (Exception error) {
            result.add(check("app_traffic_error", "Сетевая активность приложений", NetworkCheckResult.Status.WARNING,
                "Android не предоставил статистику приложений.", map("error", error.getClass().getSimpleName())));
            return result;
        }
        byUid.remove(Process.myUid());
        List<Map.Entry<Integer, Traffic>> rows = new ArrayList<>(byUid.entrySet());
        rows.removeIf(row -> row.getValue().sent() == 0);
        rows.sort(Comparator.comparingLong((Map.Entry<Integer, Traffic> row) -> row.getValue().backgroundTx).reversed());
        result.add(check("app_traffic_summary", "Передача данных приложениями за 24 часа", NetworkCheckResult.Status.OK,
            "Найдено приложений или системных UID с исходящим трафиком: " + rows.size()
                + ". Фоновый трафик нормален для синхронизации и не доказывает наличие шпионского ПО.",
            map("senders", String.valueOf(rows.size()), "periodHours", "24", "destinationsVisible", "false")));
        for (Map.Entry<Integer, Traffic> row : rows) {
            int uid = row.getKey(); Traffic traffic = row.getValue();
            String packages = packages(context, uid);
            List<String> sensitive = sensitivePermissions(context, uid);
            boolean system = systemUid(context, uid);
            boolean attention = !system && traffic.backgroundTx >= ATTENTION_BYTES && sensitive.size() >= 3;
            result.add(check("app_traffic_" + uid, packages, attention ? NetworkCheckResult.Status.WARNING : NetworkCheckResult.Status.OK,
                "В фоне отправлено " + size(traffic.backgroundTx) + ", получено " + size(traffic.backgroundRx)
                    + ". " + (attention ? "Большой объём и чувствительные разрешения требуют ручной проверки." : "Сам факт трафика не означает слежку."),
                map("uid", String.valueOf(uid), "packages", packages,
                    "backgroundSentBytes", String.valueOf(traffic.backgroundTx), "backgroundReceivedBytes", String.valueOf(traffic.backgroundRx),
                    "foregroundSentBytes", String.valueOf(traffic.foregroundTx), "foregroundReceivedBytes", String.valueOf(traffic.foregroundRx),
                    "trafficWithoutStateBytes", String.valueOf(traffic.unknownTx + traffic.unknownRx),
                    "requestedSensitivePermissions", sensitive.isEmpty() ? "none" : String.join(", ", sensitive),
                    "systemUid", String.valueOf(system), "installers", installers(context, uid),
                    "remoteDestinations", "Android API does not expose them",
                    "limitation", "UID accounting is not a live process monitor")));
        }
        return result;
    }

    private static void read(NetworkStatsManager manager, int type, long start, long end, Map<Integer, Traffic> values) throws Exception {
        if (manager == null) return;
        try (NetworkStats stats = manager.querySummary(type, null, start, end)) {
            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            while (stats.hasNextBucket() && stats.getNextBucket(bucket)) {
                if (bucket.getUid() < 0) continue;
                Traffic traffic = values.computeIfAbsent(bucket.getUid(), ignored -> new Traffic());
                if (bucket.getState() == NetworkStats.Bucket.STATE_FOREGROUND) {
                    traffic.foregroundTx += bucket.getTxBytes(); traffic.foregroundRx += bucket.getRxBytes();
                } else if (bucket.getState() == NetworkStats.Bucket.STATE_DEFAULT) {
                    traffic.backgroundTx += bucket.getTxBytes(); traffic.backgroundRx += bucket.getRxBytes();
                } else {
                    traffic.unknownTx += bucket.getTxBytes(); traffic.unknownRx += bucket.getRxBytes();
                }
            }
        }
    }

    private static String packages(Context context, int uid) {
        PackageManager manager = context.getPackageManager();
        String[] names = manager.getPackagesForUid(uid);
        if (names == null || names.length == 0) return "Системный UID " + uid;
        StringBuilder value = new StringBuilder();
        for (String name : names) {
            if (value.length() > 0) value.append(", ");
            try { ApplicationInfo info = manager.getApplicationInfo(name, 0); value.append(manager.getApplicationLabel(info)).append(" (").append(name).append(')'); }
            catch (Exception ignored) { value.append(name); }
        }
        return value.toString();
    }

    private static List<String> sensitivePermissions(Context context, int uid) {
        List<String> result = new ArrayList<>();
        String[] names = context.getPackageManager().getPackagesForUid(uid);
        if (names == null) return result;
        for (String name : names) try {
            PackageInfo info = context.getPackageManager().getPackageInfo(name, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) continue;
            for (String permission : info.requestedPermissions) if (SENSITIVE.contains(permission)) {
                String shortName = permission.substring(permission.lastIndexOf('.') + 1);
                if (!result.contains(shortName)) result.add(shortName);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static boolean systemUid(Context context, int uid) {
        String[] names = context.getPackageManager().getPackagesForUid(uid);
        if (names == null || names.length == 0) return uid < 10000;
        for (String name : names) try {
            if ((context.getPackageManager().getApplicationInfo(name, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0) return false;
        } catch (Exception ignored) { return false; }
        return true;
    }

    @SuppressWarnings("deprecation")
    private static String installers(Context context, int uid) {
        String[] names = context.getPackageManager().getPackagesForUid(uid);
        if (names == null || names.length == 0) return "system/unknown";
        List<String> result = new ArrayList<>();
        for (String name : names) try {
            String installer = context.getPackageManager().getInstallerPackageName(name);
            if (installer != null && !result.contains(installer)) result.add(installer);
        } catch (Exception ignored) { }
        return result.isEmpty() ? "unknown or sideloaded" : String.join(", ", result);
    }

    private static String size(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f ГБ", bytes / (1024d * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format(java.util.Locale.ROOT, "%.1f МБ", bytes / (1024d * 1024));
        if (bytes >= 1024) return String.format(java.util.Locale.ROOT, "%.1f КБ", bytes / 1024d);
        return bytes + " Б";
    }

    private static NetworkCheckResult check(String id, String name, NetworkCheckResult.Status status, String summary, Map<String, String> metrics) {
        return new NetworkCheckResult(id, name, "Безопасность устройства", status, summary, metrics);
    }

    private static Map<String, String> map(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }
}
