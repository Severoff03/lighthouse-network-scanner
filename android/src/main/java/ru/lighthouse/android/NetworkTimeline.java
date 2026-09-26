package ru.lighthouse.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import ru.lighthouse.core.NetworkCheckResult;
import java.util.*;

final class NetworkTimeline implements AutoCloseable {
    private final ConnectivityManager manager;
    private final List<NetworkCheckResult> events = new ArrayList<>();
    private boolean registered;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { record("available", network, "Появилась системная сеть"); }
        @Override public void onLost(Network network) { record("lost", network, "Системная сеть потеряна"); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            record("capabilities", network, RadioDiagnostics.transports(caps) + "; validated=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        }
    };
    NetworkTimeline(Context context) {
        manager = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        try { if (manager != null) { manager.registerDefaultNetworkCallback(callback); registered = true; } }
        catch (Exception error) { record("unavailable", null, error.getClass().getSimpleName()); }
    }
    private synchronized void record(String event, Network network, String summary) {
        if (events.size() >= 128) return;
        Map<String, String> metrics = new LinkedHashMap<>(); metrics.put("event", event); metrics.put("network", String.valueOf(network));
        metrics.put("capturedAt", java.time.Instant.now().toString());
        events.add(new NetworkCheckResult("event_" + events.size(), "Изменение сети", "События во время скана", NetworkCheckResult.Status.OK, summary, metrics));
    }
    synchronized List<NetworkCheckResult> snapshot() { return new ArrayList<>(events); }
    @Override public void close() {
        if (!registered) return;
        try { manager.unregisterNetworkCallback(callback); }
        catch (RuntimeException ignored) { /* Teardown must not discard a completed report. */ }
        finally { registered = false; }
    }
}
