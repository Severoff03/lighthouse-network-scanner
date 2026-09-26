package ru.lighthouse.android;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.os.*;
import ru.lighthouse.core.NetworkCheckResult;
import java.util.*;

/** Continuous BLE advertisements plus repeated Classic discovery; no packet interception. */
@SuppressLint("MissingPermission")
final class BluetoothSurvey {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, NetworkCheckResult> seen = new LinkedHashMap<>();
    private final Map<Integer, BluetoothProfile> profiles = new HashMap<>();
    private final Set<String> acl = new HashSet<>();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private volatile boolean running;
    private boolean registered, discoveryOwned;
    private volatile String state = "Сканирование не запущено";
    BluetoothSurvey(Context context) { this.context = context; }
    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) { synchronized (profiles) { if (running) profiles.put(profile, proxy); else if (adapter != null) adapter.closeProfileProxy(profile,proxy); } }
        public void onServiceDisconnected(int profile) { synchronized (profiles) { profiles.remove(profile); } }
    };
    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("protocol", "BLE"); fields.put("primaryPhy", String.valueOf(result.getPrimaryPhy()));
            fields.put("secondaryPhy", String.valueOf(result.getSecondaryPhy()));
            fields.put("connectable", String.valueOf(result.isConnectable()));
            if (result.getScanRecord() != null) {
                fields.put("serviceUuids", String.valueOf(result.getScanRecord().getServiceUuids()));
                fields.put("advertisedName", String.valueOf(result.getScanRecord().getDeviceName()));
            }
            remember(result.getDevice(), result.getRssi(), result.getTimestampNanos() / 1_000_000, fields);
        }
        @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult r : results) onScanResult(0, r); }
        @Override public void onScanFailed(int code) { state = "BLE: ошибка Android " + code; }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) return;
            try {
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) { synchronized (acl) { acl.add(device.getAddress()); } }
                else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) { synchronized (acl) { acl.remove(device.getAddress()); } }
                else if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    Map<String, String> fields = new LinkedHashMap<>(); fields.put("protocol", "Classic discovery");
                    remember(device, intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE), SystemClock.elapsedRealtime(), fields);
                }
            } catch (SecurityException ignored) { state = "Нет разрешения Bluetooth"; }
        }
    };
    private final Runnable discovery = new Runnable() {
        public void run() {
            if (!running) return;
            try {
                if (adapter != null && adapter.isEnabled()) {
                    if (scanner == null) {
                        scanner = adapter.getBluetoothLeScanner();
                        if (scanner != null) scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
                    }
                    if (!adapter.isDiscovering()) discoveryOwned = adapter.startDiscovery();
                } else { scanner = null; state = "Bluetooth выключен"; }
            } catch (RuntimeException error) { state = "Bluetooth: " + error.getClass().getSimpleName(); }
            main.postDelayed(this, 30_000);
        }
    };
    void start() {
        if (running) return;
        running = true;
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) { state = "Модуль Bluetooth недоступен"; return; }
            IntentFilter filter = new IntentFilter(); filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED); filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            // Bluetooth system broadcasts may originate from a privileged non-system UID.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
            registered = true;
            adapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP);
            adapter.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET);
            state = "BLE + Classic • BW, частота пакета и шифрование Android не раскрывает";
            main.post(discovery);
        } catch (RuntimeException error) { state = "Bluetooth: " + error.getClass().getSimpleName(); }
    }
    private void remember(BluetoothDevice device, int rssi, long observed, Map<String, String> fields) {
        if (!running) return;
        try {
            String address = device.getAddress(), name = device.getName();
            fields.put("address", address); fields.put("name", name == null ? "Без имени" : name);
            if (rssi >= -127 && rssi <= 20) fields.put("rssiDbm", String.valueOf(rssi));
            fields.put("observedElapsedMs", String.valueOf(observed));
            fields.put("bonded", String.valueOf(device.getBondState() == BluetoothDevice.BOND_BONDED));
            fields.put("deviceType", String.valueOf(device.getType()));
            synchronized (seen) { seen.put(address, new NetworkCheckResult("live_bluetooth_" + address, fields.get("name"), "Bluetooth", NetworkCheckResult.Status.OK, "", fields)); }
        } catch (SecurityException ignored) { state = "Нет разрешения Bluetooth"; }
    }
    List<NetworkCheckResult> snapshot() {
        Set<String> connected = new HashSet<>(); synchronized (acl) { connected.addAll(acl); }
        try {
            synchronized (profiles) { for (BluetoothProfile proxy : profiles.values()) for (BluetoothDevice d : proxy.getConnectedDevices()) connected.add(d.getAddress()); }
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            if (manager != null) for (BluetoothDevice d : manager.getConnectedDevices(BluetoothProfile.GATT)) connected.add(d.getAddress());
        } catch (RuntimeException ignored) { /* Unknown is never inferred from bonding. */ }
        List<NetworkCheckResult> result = new ArrayList<>();
        PowerManager power = context.getSystemService(PowerManager.class);
        String reportedState = state + (power != null && !power.isInteractive()
            ? " • экран выключен: Android приостанавливает общий BLE-поиск; свежие BLE-данные не гарантируются" : "");
        result.add(new NetworkCheckResult("live_bluetooth_state", "Bluetooth", "Bluetooth", NetworkCheckResult.Status.WARNING, reportedState, Collections.emptyMap()));
        synchronized (seen) {
            seen.entrySet().removeIf(e -> SystemClock.elapsedRealtime() - Long.parseLong(e.getValue().metrics.get("observedElapsedMs")) > 120_000);
            for (NetworkCheckResult entry : seen.values()) {
                Map<String, String> fields = new LinkedHashMap<>(entry.metrics);
                fields.put("connected", String.valueOf(connected.contains(fields.get("address"))));
                fields.put("ageMs", String.valueOf(SystemClock.elapsedRealtime() - Long.parseLong(fields.get("observedElapsedMs"))));
                result.add(new NetworkCheckResult(entry.id, entry.name, entry.category, entry.status, entry.summary, fields));
            }
        }
        // Connected devices need not advertise; keep identity without fabricating RSSI.
        for (String address : connected) if (result.stream().noneMatch(r -> address.equals(r.metrics.get("address")))) {
            Map<String,String> fields = new LinkedHashMap<>(); fields.put("address", address); fields.put("connected", "true");
            result.add(new NetworkCheckResult("live_bluetooth_connected_" + address, address, "Bluetooth", NetworkCheckResult.Status.OK, "Подключено • RSSI не предоставлен", fields));
        }
        return result;
    }
    void stop() {
        running = false; main.removeCallbacks(discovery);
        try { if (scanner != null) scanner.stopScan(callback); } catch (RuntimeException ignored) { }
        try { if (discoveryOwned && adapter != null) adapter.cancelDiscovery(); } catch (RuntimeException ignored) { }
        if (registered) { context.unregisterReceiver(receiver); registered = false; }
        synchronized (profiles) { for (Map.Entry<Integer,BluetoothProfile> p : profiles.entrySet()) adapter.closeProfileProxy(p.getKey(), p.getValue()); profiles.clear(); }
        scanner = null; synchronized (seen) { seen.clear(); } synchronized (acl) { acl.clear(); }
    }
}
