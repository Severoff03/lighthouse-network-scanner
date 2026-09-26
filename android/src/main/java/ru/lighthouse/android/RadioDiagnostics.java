package ru.lighthouse.android;

import android.annotation.SuppressLint;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.CancellationSignal;
import android.telephony.*;
import android.telephony.gsm.GsmCellLocation;
import android.telephony.cdma.CdmaCellLocation;
import ru.lighthouse.core.NetworkCheckResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** OS-visible radio and location diagnostics. Never reads IMEI, IMSI or the phone number. */
final class RadioDiagnostics {
    private final Context context;
    private final String prefix;
    private final List<NetworkCheckResult> results = new ArrayList<>();
    private final boolean refresh;
    private final ProgressListener progress;
    private boolean cellReferenceAvailable;

    RadioDiagnostics(Context context, String prefix, boolean refresh) {
        this(context, prefix, refresh, null);
    }

    RadioDiagnostics(Context context, String prefix, boolean refresh, ProgressListener progress) {
        this.context = context.getApplicationContext(); this.prefix = prefix; this.refresh = refresh; this.progress = progress;
    }

    List<NetworkCheckResult> collect() {
        Map<String, String> access = new LinkedHashMap<>();
        access.put("fineLocationPermission", String.valueOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)));
        access.put("phoneStatePermission", String.valueOf(granted(Manifest.permission.READ_PHONE_STATE)));
        access.put("bluetoothScanPermission", String.valueOf(Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_SCAN)));
        access.put("bluetoothConnectPermission", String.valueOf(Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)));
        access.put("locationServicesEnabled", String.valueOf(locationEnabled()));
        access.put("snapshot", prefix); access.put("capturedAt", Instant.now().toString());
        access.put("privacy", "Cell ID, BSSID and GPS/network coordinates can disclose location. IMEI/IMSI/phone number are not requested.");
        add("permissions", "Доступ к радиоданным", "Разрешения", NetworkCheckResult.Status.OK,
            "Доступные поля зависят от разрешений, геолокации, модема и версии Android.", access);
        phase(1, "Сети Android"); guarded("networks", "Сети Android", "Подключения", this::networks);
        phase(2, "Wi-Fi"); guarded("wifi", "Wi-Fi", "Wi-Fi", this::wifi);
        phase(3, "Bluetooth"); guarded("bluetooth", "Bluetooth", "Bluetooth", this::bluetooth);
        phase(4, "Сотовая сеть и Cell ID"); guarded("radio", "Сотовая сеть", "Сотовая связь", this::radio);
        phase(5, "GPS"); guarded("location", "GPS и положение", "Геопозиция", this::location);
        return results;
    }

    List<NetworkCheckResult> collectSelected(boolean wifi, boolean cell, boolean position) {
        if (wifi) guarded("wifi", "Wi-Fi", "Wi-Fi", this::wifi);
        if (cell) guarded("radio", "Cell", "Сотовая связь", this::radio);
        if (position) guarded("location", "Положение", "Геопозиция", this::location);
        return results;
    }

    private void phase(int number, String label) { if (progress != null) progress.update(number, 5, label); }

    interface ProgressListener { void update(int completed, int total, String phase); }

    @SuppressLint("MissingPermission") // Explicit runtime check below; calls are also isolated by guarded().
    private void location() throws Exception {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            unavailable("location", "GPS и положение", "Геопозиция", "Нужно разрешение точного местоположения."); return;
        }
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) throw new UnsupportedOperationException("LocationManager недоступен");
        boolean gpsEnabled = safeProvider(manager, LocationManager.GPS_PROVIDER);
        boolean networkEnabled = safeProvider(manager, LocationManager.NETWORK_PROVIDER);
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("gpsProviderEnabled", String.valueOf(gpsEnabled));
        summary.put("networkProviderEnabled", String.valueOf(networkEnabled));
        summary.put("networkProviderMeaning", "Approximate position may be derived by Android from cell towers and Wi-Fi");
        summary.put("cellReferenceAvailable", String.valueOf(cellReferenceAvailable));
        add("location_summary", "Службы геопозиции", "Геопозиция",
            gpsEnabled || networkEnabled ? NetworkCheckResult.Status.OK : NetworkCheckResult.Status.WARNING,
            gpsEnabled ? "GPS включён; проверяем доступность координат."
                : networkEnabled ? "GPS выключен, доступно приблизительное положение по сети." : "GPS и сетевое определение положения выключены.", summary);

        Location network = networkEnabled ? obtainLocation(manager, LocationManager.NETWORK_PROVIDER) : null;
        addLocation("network_location", "Положение по сети", network, "android_network_provider", "Cell / Wi-Fi по данным Android");
        boolean networkFresh = network != null && !isMock(network)
            && SystemClock.elapsedRealtime() >= network.getElapsedRealtimeNanos()/1_000_000
            && SystemClock.elapsedRealtime() - network.getElapsedRealtimeNanos()/1_000_000 <= 120_000;
        if (!networkFresh) {
            Location gps = gpsEnabled ? obtainLocation(manager, LocationManager.GPS_PROVIDER) : null;
            addLocation("gps_fix", "GPS", gps, "android_gps_provider", "Спутниковое положение");
        }
    }

    @SuppressLint("MissingPermission")
    private Location obtainLocation(LocationManager manager, String provider) throws InterruptedException {
        if (refresh && Build.VERSION.SDK_INT >= 30) {
            CountDownLatch ready = new CountDownLatch(1); AtomicReference<Location> value = new AtomicReference<>();
            CancellationSignal cancellation = new CancellationSignal();
            try {
                manager.getCurrentLocation(provider, cancellation, Runnable::run, result -> { value.set(result); ready.countDown(); });
                ready.await(8, TimeUnit.SECONDS);
                if (value.get() != null) return value.get();
            } finally { cancellation.cancel(); }
        }
        if (refresh && Build.VERSION.SDK_INT < 30) {
            CountDownLatch ready = new CountDownLatch(1); AtomicReference<Location> value = new AtomicReference<>();
            android.location.LocationListener listener = new android.location.LocationListener() {
                public void onLocationChanged(Location result) { value.set(result); ready.countDown(); }
                public void onStatusChanged(String p, int status, android.os.Bundle extras) { }
                public void onProviderEnabled(String p) { }
                public void onProviderDisabled(String p) { ready.countDown(); }
            };
            try { manager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper()); ready.await(8, TimeUnit.SECONDS); }
            finally { manager.removeUpdates(listener); }
            if (value.get() != null) return value.get();
        }
        return manager.getLastKnownLocation(provider);
    }

    private void addLocation(String id, String name, Location location, String source, String meaning) {
        if (location == null) { unavailable(id, name, "Геопозиция", "Координаты не получены; это не доказывает неисправность GPS или модема."); return; }
        Map<String, String> fields = new LinkedHashMap<>(); fields.put("provider", location.getProvider()); fields.put("source", source);
        fields.put("latitude", String.valueOf(location.getLatitude())); fields.put("longitude", String.valueOf(location.getLongitude()));
        fields.put("observedElapsedMs", String.valueOf(location.getElapsedRealtimeNanos() / 1_000_000));
        if (location.hasAccuracy()) fields.put("accuracyMeters", String.valueOf(location.getAccuracy())); fields.put("fixAgeMs", String.valueOf(Math.max(0, SystemClock.elapsedRealtime() - location.getElapsedRealtimeNanos() / 1_000_000)));
        boolean mock = isMock(location); fields.put("mockLocation", String.valueOf(mock));
        if (location.hasAltitude()) fields.put("altitudeMeters", String.valueOf(location.getAltitude()));
        if (location.hasSpeed()) fields.put("speedMetersPerSecond", String.valueOf(location.getSpeed()));
        fields.put("privacy", "Precise coordinates are stored only in the local diagnostic log until the user exports it");
        add(id, name, "Геопозиция", mock ? NetworkCheckResult.Status.WARNING : NetworkCheckResult.Status.OK,
            meaning + (location.hasAccuracy() ? "; точность около " + Math.round(location.getAccuracy()) + " м." : "; точность не предоставлена")
                + (mock ? " Android пометил координаты как смоделированные." : ""), fields);
    }

    private static boolean isMock(Location location) {
        try { return Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider(); }
        catch (Exception ignored) { return false; }
    }

    private static Map<String, String> map(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return values;
    }

    private static boolean safeProvider(LocationManager manager, String provider) {
        try { return manager.isProviderEnabled(provider); } catch (Exception ignored) { return false; }
    }

    private void networks() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) throw new IllegalStateException("ConnectivityManager недоступен");
        Network active = manager.getActiveNetwork(); Network[] networks = manager.getAllNetworks();
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("visibleNetworks", String.valueOf(networks.length)); summary.put("activeNetwork", String.valueOf(active));
        summary.put("backgroundRestriction", String.valueOf(manager.getRestrictBackgroundStatus()));
        add("networks_summary", "Подключения Android", "Подключения", active == null ? NetworkCheckResult.Status.WARNING : NetworkCheckResult.Status.OK,
            active == null ? "Активная сеть отсутствует. Радиосигнал сам по себе не даёт доступ в интернет."
                : "Видимых Android сетей: " + networks.length + ". Активная сеть: " + active + ".", summary);
        for (Network network : networks) {
            guard(); NetworkCapabilities caps = manager.getNetworkCapabilities(network); LinkProperties links = manager.getLinkProperties(network);
            if (caps == null) continue;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("active", String.valueOf(network.equals(active))); fields.put("networkHandle", String.valueOf(network.getNetworkHandle()));
            fields.put("transports", transports(caps));
            fields.put("internetCapability", String.valueOf(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)));
            fields.put("validated", String.valueOf(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)));
            fields.put("captivePortal", String.valueOf(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)));
            fields.put("metered", String.valueOf(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)));
            fields.put("estimatedDownKbps", String.valueOf(caps.getLinkDownstreamBandwidthKbps()));
            fields.put("estimatedUpKbps", String.valueOf(caps.getLinkUpstreamBandwidthKbps()));
            fields.put("trafficPolicy", "Активные запросы идут только по системному маршруту; VPN не обходится принудительно.");
            if (links != null) {
                fields.put("interface", String.valueOf(links.getInterfaceName())); fields.put("addresses", links.getLinkAddresses().toString());
                fields.put("routes", links.getRoutes().toString()); fields.put("dns", links.getDnsServers().toString());
                fields.put("domains", String.valueOf(links.getDomains())); fields.put("proxy", String.valueOf(links.getHttpProxy()));
                if (Build.VERSION.SDK_INT >= 29) fields.put("mtu", String.valueOf(links.getMtu()));
                if (Build.VERSION.SDK_INT >= 28) {
                    fields.put("privateDnsActive", String.valueOf(links.isPrivateDnsActive()));
                    fields.put("privateDnsServer", String.valueOf(links.getPrivateDnsServerName()));
                }
            }
            add("network_" + network, transports(caps), "Подключения", NetworkCheckResult.Status.OK,
                (network.equals(active) ? "Системный маршрут. " : "Не системный маршрут. ")
                    + (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "Android подтвердил интернет." : "Интернет системой не подтверждён."), fields);
        }
    }

    private void wifi() throws Exception {
        WifiManager manager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (manager == null || !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI))
            throw new UnsupportedOperationException("Wi-Fi модуль не обнаружен");
        WifiInfo info = manager.getConnectionInfo(); Map<String, String> fields = new LinkedHashMap<>();
        fields.put("enabled", String.valueOf(manager.isWifiEnabled())); fields.put("state", String.valueOf(manager.getWifiState()));
        boolean connected = info != null && info.getSupplicantState() == android.net.wifi.SupplicantState.COMPLETED;
        if (info != null && connected) {
            fields.put("supplicantState", info.getSupplicantState().name()); fields.put("ssid", readableSsid(info.getSSID()));
            fields.put("bssid", readableMac(info.getBSSID())); fields.put("rssiDbm", info.getRssi() == -127 ? "unavailable" : String.valueOf(info.getRssi()));
            numeric(fields, "frequencyMHz", info.getFrequency()); numeric(fields, "linkSpeedMbps", info.getLinkSpeed());
            fields.put("linkSpeedMeaning", "PHY-скорость соединения, не измерение скорости интернета");
            if (Build.VERSION.SDK_INT >= 29) { numeric(fields, "txLinkMbps", info.getTxLinkSpeedMbps()); numeric(fields, "rxLinkMbps", info.getRxLinkSpeedMbps()); }
            if (Build.VERSION.SDK_INT >= 30) numeric(fields, "wifiStandardCode", info.getWifiStandard());
            if (Build.VERSION.SDK_INT >= 31) numeric(fields, "securityTypeCode", info.getCurrentSecurityType());
        }
        add("wifi_summary", "Текущее Wi-Fi соединение", "Wi-Fi", connected ? NetworkCheckResult.Status.OK : NetworkCheckResult.Status.WARNING,
            connected ? "Wi-Fi подключён: " + readableSsid(info.getSSID()) + ", сигнал " + info.getRssi() + " dBm. Интернет проверяется отдельно."
                : manager.isWifiEnabled() ? "Wi-Fi включён, подключение к точке не подтверждено." : "Wi-Fi выключен; приложение не включает его самостоятельно.", fields);
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || !locationEnabled()) {
            unavailable("wifi_nearby", "Окружающие точки Wi-Fi", "Wi-Fi", "Нужны точное местоположение и включённая геолокация. Нельзя считать отсутствие данных отсутствием точек."); return;
        }
        String source = "cached";
        if (refresh && manager.isWifiEnabled()) {
            CountDownLatch ready = new CountDownLatch(1); AtomicBoolean updated = new AtomicBoolean();
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    updated.set(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)); ready.countDown();
                }
            };
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            try { source = manager.startScan() ? (ready.await(8, TimeUnit.SECONDS) && updated.get() ? "updated" : "cached_after_timeout") : "cached_scan_throttled"; }
            finally { context.unregisterReceiver(receiver); }
        }
        List<ScanResult> nearby = new ArrayList<>(manager.getScanResults());
        nearby.sort((a, b) -> Integer.compare(b.level, a.level));
        Map<String, String> scan = new LinkedHashMap<>(); scan.put("source", source); scan.put("visible", String.valueOf(nearby.size()));
        scan.put("saved", String.valueOf(nearby.size()));
        add("wifi_scan", "Обзор Wi-Fi эфира", "Wi-Fi", NetworkCheckResult.Status.OK,
            "Виден список из " + nearby.size() + " точек. Данные могут быть кэшированными; подключение к чужим сетям не выполняется.", scan);
        for (int i = 0; i < nearby.size(); i++) {
            ScanResult ap = nearby.get(i); Map<String, String> item = new LinkedHashMap<>();
            item.put("ssid", readableSsid(ap.SSID)); item.put("bssid", readableMac(ap.BSSID)); item.put("security", ap.capabilities);
            numeric(item, "rssiDbm", ap.level); numeric(item, "frequencyMHz", ap.frequency); numeric(item, "channelWidthCode", ap.channelWidth);
            numeric(item, "centerFreq0MHz", ap.centerFreq0); numeric(item, "centerFreq1MHz", ap.centerFreq1);
            item.put("ageMs", String.valueOf(Math.max(0, SystemClock.elapsedRealtime() - ap.timestamp / 1000)));
            item.put("source", source);
            item.put("connected", String.valueOf(connected && ap.BSSID != null && ap.BSSID.equalsIgnoreCase(info.getBSSID())));
            item.put("observedElapsedMs", String.valueOf(ap.timestamp / 1000));
            if (Build.VERSION.SDK_INT >= 30) item.put("protocol", wifiStandard(ap.getWifiStandard()));
            item.put("bandwidthMHz", switch (ap.channelWidth) { case 0 -> "20"; case 1 -> "40"; case 2 -> "80"; case 3 -> "160"; case 4 -> "80+80"; case 5 -> "320"; default -> "unavailable"; });
            add("wifi_ap_" + i, readableSsid(ap.SSID), "Точки Wi-Fi", NetworkCheckResult.Status.OK,
                ap.level + " dBm / " + ap.frequency + " MHz / " + readableMac(ap.BSSID), item);
        }
    }

    private void bluetooth() {
        results.addAll(RadioRuntime.get(context).bluetoothSnapshot());
    }

    @SuppressLint("MissingPermission")
    private void radio() throws Exception {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            throw new UnsupportedOperationException("Телефония не поддерживается устройством");
        TelephonyManager base = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (base == null) throw new UnsupportedOperationException("TelephonyManager недоступен");
        Map<String, String> general = new LinkedHashMap<>();
        general.put("airplaneMode", String.valueOf(android.provider.Settings.Global.getInt(
            context.getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1));
        numeric(general, "simStateCode", base.getSimState()); general.put("operator", base.getNetworkOperatorName());
        general.put("operatorMccMnc", base.getNetworkOperator()); general.put("roaming", String.valueOf(base.isNetworkRoaming()));
        List<TelephonyManager> managers = new ArrayList<>(); List<String> labels = new ArrayList<>();
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            SubscriptionManager subscriptions = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> active = subscriptions == null ? null : subscriptions.getActiveSubscriptionInfoList();
            if (active != null) for (SubscriptionInfo sim : active) {
                managers.add(base.createForSubscriptionId(sim.getSubscriptionId())); labels.add("SIM " + (sim.getSimSlotIndex() + 1));
            }
        }
        if (managers.isEmpty()) { managers.add(base); labels.add("Основной модем"); }
        int registered = 0, visible = 0;
        for (int s = 0; s < Math.min(4, managers.size()); s++) {
            guard(); TelephonyManager manager = managers.get(s); String label = labels.get(s);
            Map<String, String> sim = new LinkedHashMap<>();
            sim.put("operator", manager.getNetworkOperatorName()); sim.put("mccMnc", manager.getNetworkOperator());
            numeric(sim, "simStateCode", manager.getSimState());
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                numeric(sim, "dataNetworkTypeCode", manager.getDataNetworkType()); numeric(sim, "dataStateCode", manager.getDataState());
                sim.put("mobileDataEnabled", String.valueOf(manager.isDataEnabled()));
                ServiceState service = manager.getServiceState();
                if (service != null) { numeric(sim, "serviceStateCode", service.getState()); sim.put("roaming", String.valueOf(service.getRoaming())); }
            } else sim.put("restricted", "Для состояния SIM/передачи данных не разрешён доступ к телефону");
            add("sim_" + s, label, "Сотовая связь", NetworkCheckResult.Status.OK, "Оператор: " + manager.getNetworkOperatorName() + ".", sim);
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || !locationEnabled()) continue;
            List<CellInfo> cells; String source = "cached";
            if (refresh && Build.VERSION.SDK_INT >= 29) {
                CountDownLatch ready = new CountDownLatch(1); AtomicReference<List<CellInfo>> updated = new AtomicReference<>();
                manager.requestCellInfoUpdate(Runnable::run, new TelephonyManager.CellInfoCallback() {
                    @Override public void onCellInfo(List<CellInfo> data) { updated.set(data); ready.countDown(); }
                    @Override public void onError(int code, Throwable detail) { ready.countDown(); }
                });
                ready.await(5, TimeUnit.SECONDS); cells = updated.get();
                if (cells != null) source = "update_callback"; else { cells = manager.getAllCellInfo(); source = "cached_update_unavailable"; }
            } else cells = manager.getAllCellInfo();
            if (cells == null) continue;
            for (int i = 0; i < cells.size(); i++) {
                CellInfo cell = cells.get(i); visible++; if (cell.isRegistered()) registered++;
                Map<String, String> values = cellFields(cell); values.put("sim", label); values.put("source", source);
                values.put("registered", String.valueOf(cell.isRegistered()));
                values.put("name", values.get("technology") + " / " + values.getOrDefault("mcc", "?") + "-" + values.getOrDefault("mnc", "?") + " / " + values.getOrDefault("cellId", "?"));
                values.put("observedElapsedMs", String.valueOf(cell.getTimeStamp() / 1_000_000));
                values.put("simPresent", String.valueOf(manager.getSimState() == TelephonyManager.SIM_STATE_READY));
                values.put("ageMs", String.valueOf(Math.max(0, SystemClock.elapsedRealtime() - cell.getTimeStamp() / 1_000_000)));
                addCellLookupHints(values);
                add("cell_" + s + "_" + i, label + " / " + values.get("technology"), "Соты и Cell ID", NetworkCheckResult.Status.OK,
                    (cell.isRegistered() ? "Обслуживающая/зарегистрированная сота" : "Наблюдаемая соседняя сота")
                        + "; Cell ID: " + values.getOrDefault("cellId", "недоступен") + "; " + values.getOrDefault("dbm", "?") + " dBm.", values);
            }
        }
        cellReferenceAvailable = registered > 0;
        cellReferenceAvailable = registered > 0;
        general.put("visibleCells", String.valueOf(visible)); general.put("registeredCells", String.valueOf(registered));
        general.put("identityState", visible == 0 ? "not_returned_by_os_or_modem" : "cell_identity_returned");
        general.put("meaning", "Регистрация/видимость соты не доказывает доступ к интернету. Lighthouse не обращается к внешней базе вышек: карта может показать «вышка не найдена», если её база не знает эту пару MCC/MNC/LAC/CID.");
        add("radio_summary", "Радиосвязь и доступность сот", "Сотовая связь", visible == 0 ? NetworkCheckResult.Status.WARNING : NetworkCheckResult.Status.OK,
            visible == 0 ? "Android/модем не вернул Cell ID. Это не означает отсутствие вышки: проверьте разрешения, геолокацию и регистрацию SIM; внешняя карта может не содержать эту вышку."
                : "Модем показывает " + visible + " сот, зарегистрированных: " + registered + ". Интернет проверяется отдельно.", general);
    }

    private Map<String, String> cellFields(CellInfo cell) {
        Map<String, String> fields = new LinkedHashMap<>(); CellSignalStrength signal = null; CellIdentity identity = null;
        if (cell instanceof CellInfoLte) {
            CellInfoLte lte = (CellInfoLte) cell; CellIdentityLte id = lte.getCellIdentity(); CellSignalStrengthLte strength = lte.getCellSignalStrength(); signal = strength;
            if (Build.VERSION.SDK_INT >= 28) identity = id;
            fields.put("technology", "LTE"); numeric(fields, "cellId", id.getCi()); numeric(fields, "tac", id.getTac()); numeric(fields, "pci", id.getPci());
            numeric(fields, "earfcn", id.getEarfcn()); fields.put("mcc", Build.VERSION.SDK_INT >= 28 ? id.getMccString() : String.valueOf(id.getMcc())); fields.put("mnc", Build.VERSION.SDK_INT >= 28 ? id.getMncString() : String.valueOf(id.getMnc()));
            numeric(fields, "rsrpDbm", strength.getRsrp()); numeric(fields, "rsrqDb", strength.getRsrq()); numeric(fields, "rssnrTenthsDb", strength.getRssnr()); numeric(fields, "timingAdvance", strength.getTimingAdvance());
            if (Build.VERSION.SDK_INT >= 28) numeric(fields, "bandwidthKHz", id.getBandwidth());
            if (Build.VERSION.SDK_INT >= 30) fields.put("bands", Arrays.toString(id.getBands()));
        } else if (Build.VERSION.SDK_INT >= 29 && cell instanceof CellInfoNr) {
            CellInfoNr nr = (CellInfoNr) cell; CellIdentityNr id = (CellIdentityNr) nr.getCellIdentity(); CellSignalStrengthNr strength = (CellSignalStrengthNr) nr.getCellSignalStrength(); signal = strength; identity = id;
            fields.put("technology", "5G NR"); numeric(fields, "cellId", id.getNci()); numeric(fields, "tac", id.getTac()); numeric(fields, "pci", id.getPci()); numeric(fields, "nrarfcn", id.getNrarfcn());
            fields.put("mcc", String.valueOf(id.getMccString())); fields.put("mnc", String.valueOf(id.getMncString()));
            numeric(fields, "ssRsrpDbm", strength.getSsRsrp()); numeric(fields, "ssRsrqDb", strength.getSsRsrq()); numeric(fields, "ssSinrDb", strength.getSsSinr());
            if (Build.VERSION.SDK_INT >= 30) fields.put("bands", Arrays.toString(id.getBands()));
            numeric(fields, "csiRsrpDbm", strength.getCsiRsrp()); numeric(fields, "csiRsrqDb", strength.getCsiRsrq()); numeric(fields, "csiSinrDb", strength.getCsiSinr());
        } else if (cell instanceof CellInfoGsm) {
            CellInfoGsm gsm = (CellInfoGsm) cell; CellIdentityGsm id = gsm.getCellIdentity(); signal = gsm.getCellSignalStrength();
            if (Build.VERSION.SDK_INT >= 28) identity = id;
            fields.put("technology", "GSM"); numeric(fields, "cellId", id.getCid()); numeric(fields, "lac", id.getLac()); numeric(fields, "arfcn", id.getArfcn()); numeric(fields, "bsic", id.getBsic()); fields.put("mcc", Build.VERSION.SDK_INT >= 28 ? id.getMccString() : String.valueOf(id.getMcc())); fields.put("mnc", Build.VERSION.SDK_INT >= 28 ? id.getMncString() : String.valueOf(id.getMnc()));
        } else if (cell instanceof CellInfoWcdma) {
            CellInfoWcdma w = (CellInfoWcdma) cell; CellIdentityWcdma id = w.getCellIdentity(); signal = w.getCellSignalStrength();
            if (Build.VERSION.SDK_INT >= 28) identity = id;
            fields.put("technology", "WCDMA"); numeric(fields, "cellId", id.getCid()); numeric(fields, "lac", id.getLac()); numeric(fields, "psc", id.getPsc()); numeric(fields, "uarfcn", id.getUarfcn()); fields.put("mcc", Build.VERSION.SDK_INT >= 28 ? id.getMccString() : String.valueOf(id.getMcc())); fields.put("mnc", Build.VERSION.SDK_INT >= 28 ? id.getMncString() : String.valueOf(id.getMnc()));
        } else if (cell instanceof CellInfoCdma) {
            CellInfoCdma c = (CellInfoCdma) cell; CellIdentityCdma id = c.getCellIdentity(); signal = c.getCellSignalStrength();
            if (Build.VERSION.SDK_INT >= 28) identity = id;
            fields.put("technology", "CDMA"); numeric(fields, "cellId", id.getBasestationId()); numeric(fields, "networkId", id.getNetworkId()); numeric(fields, "systemId", id.getSystemId());
        } else if (Build.VERSION.SDK_INT >= 29 && cell instanceof CellInfoTdscdma) {
            CellInfoTdscdma c = (CellInfoTdscdma) cell; CellIdentityTdscdma id = c.getCellIdentity(); signal = c.getCellSignalStrength(); identity = id;
            fields.put("technology", "TD-SCDMA"); numeric(fields, "cellId", id.getCid()); numeric(fields, "lac", id.getLac()); numeric(fields, "cpid", id.getCpid());
        } else fields.put("technology", cell.getClass().getSimpleName());
        if (Build.VERSION.SDK_INT >= 28 && identity != null) {
            CharSequence operator = identity.getOperatorAlphaLong();
            if (operator == null || operator.toString().isBlank()) operator = identity.getOperatorAlphaShort();
            if (operator != null && !operator.toString().isBlank()) fields.put("operator", operator.toString());
        }
        if (signal != null) { numeric(fields, "dbm", signal.getDbm()); numeric(fields, "asu", signal.getAsuLevel()); numeric(fields, "level0to4", signal.getLevel()); }
        double frequency = Double.NaN;
        try {
            if (fields.containsKey("earfcn")) frequency = ru.lighthouse.core.RadioFrequencies.lteDownlinkMHz(Long.parseLong(fields.get("earfcn")));
            if (fields.containsKey("nrarfcn")) frequency = ru.lighthouse.core.RadioFrequencies.nrReferenceMHz(Long.parseLong(fields.get("nrarfcn")));
        } catch (NumberFormatException ignored) { }
        if (Double.isFinite(frequency)) {
            fields.put("frequencyMHz", String.format(Locale.ROOT,"%.3f",frequency));
            fields.put("frequencySource", "3GPP channel conversion; NR reference frequency, LTE downlink");
        }
        return fields;
    }

    private static void addCellLookupHints(Map<String, String> fields) {
        String mcc = fields.get("mcc"), mnc = fields.get("mnc"), id = fields.get("cellId");
        String area = fields.containsKey("tac") ? fields.get("tac") : fields.get("lac");
        if (validIdentity(mcc) && validIdentity(mnc) && validIdentity(area) && validIdentity(id)) {
            fields.put("cellLookupKey", mcc + "-" + mnc + "-" + area + "-" + id);
            fields.put("cellLookupHint", "Для CellMapper/OpenCellID нужны MCC/MNC + TAC(LAC) + CID; публичные базы могут не содержать новую или закрытую вышку.");
        } else fields.put("cellLookupHint", "Недостаточно полей для поиска: нужны MCC, MNC, TAC/LAC и Cell ID.");
    }
    private static boolean validIdentity(String value) {
        try { return value != null && Long.parseLong(value) >= 0; } catch (NumberFormatException error) { return false; }
    }

    private static String wifiStandard(int code) {
        return switch (code) { case 1 -> "802.11 legacy"; case 4 -> "802.11n"; case 5 -> "802.11ac";
            case 6 -> "802.11ax"; case 7 -> "802.11ad"; case 8 -> "802.11be"; default -> "unavailable"; };
    }

    static String transports(NetworkCapabilities caps) {
        List<String> types = new ArrayList<>();
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) types.add("Wi-Fi");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) types.add("Сотовая сеть");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) types.add("VPN");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) types.add("Ethernet");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) types.add("Bluetooth-модем");
        return types.isEmpty() ? "Другой транспорт" : String.join(" + ", types);
    }
    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private boolean locationEnabled() {
        try {
            LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            return manager != null && (Build.VERSION.SDK_INT >= 28 ? manager.isLocationEnabled()
                : manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        } catch (Exception ignored) { return false; }
    }
    private void guarded(String id, String name, String category, Checked action) {
        guard(); try { action.run(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        catch (CancellationException cancelled) { throw cancelled; }
        catch (Exception | LinkageError error) { unavailable(id, name, category, error.getClass().getSimpleName() + ": " + error.getMessage()); }
    }
    private void unavailable(String id, String name, String category, String reason) {
        Map<String, String> metrics = new LinkedHashMap<>(); metrics.put("unavailableReason", reason);
        add(id, name, category, NetworkCheckResult.Status.WARNING, reason, metrics);
    }
    private void add(String id, String name, String category, NetworkCheckResult.Status status, String summary, Map<String, String> metrics) {
        metrics.put("capturedAt", Instant.now().toString()); metrics.put("snapshot", prefix);
        results.add(new NetworkCheckResult(prefix + "_" + id, name, category, status, summary, metrics));
    }
    private static void numeric(Map<String, String> values, String key, long value) {
        values.put(key, value == Integer.MAX_VALUE || value == Long.MAX_VALUE || value < 0 && !key.toLowerCase(Locale.ROOT).contains("db") ? "unavailable" : String.valueOf(value));
    }
    private static String readableSsid(String value) { return value == null || value.equals(WifiManager.UNKNOWN_SSID) || value.isEmpty() ? "SSID скрыт/недоступен" : value; }
    private static String readableMac(String value) { return value == null || value.equals("02:00:00:00:00:00") ? "BSSID скрыт/недоступен" : value; }
    private static void guard() { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); }
    private interface Checked { void run() throws Exception; }
}
