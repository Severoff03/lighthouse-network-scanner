package ru.lighthouse.android;

import android.content.Context;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import ru.lighthouse.core.NetworkCheckResult;

/** One survey shared by the visible application and explicit foreground monitoring. */
final class RadioRuntime {
    private static RadioRuntime instance;
    static synchronized RadioRuntime get(Context c) { if (instance == null) instance = new RadioRuntime(c.getApplicationContext()); return instance; }
    private final Context context;
    private final BluetoothSurvey bluetooth;
    private ScheduledExecutorService worker;
    private volatile List<NetworkCheckResult> latest = Collections.emptyList();
    private int generation;
    private boolean foreground, monitoring;
    private boolean wifi = true, bt = true, cell = true;
    volatile String error = "";
    private RadioRuntime(Context c) { context = c; bluetooth = new BluetoothSurvey(c); }
    synchronized void foreground(boolean value) { if (foreground == value) return; foreground = value; restart(); }
    synchronized void monitoring(boolean value, boolean w, boolean b, boolean c) {
        monitoring = value; wifi = w; bt = b; cell = c; restart();
    }
    private void restart() { stop(); reconcile(); }
    private synchronized void reconcile() {
        if (!foreground && !monitoring) { stop(); return; }
        if (worker != null) return;
        boolean w = foreground || wifi, b = foreground || bt, c = foreground || cell;
        int token = ++generation;
        if (b) bluetooth.start();
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleAtFixedRate(() -> {
            try {
                List<NetworkCheckResult> values = new RadioDiagnostics(context, "live", true).collectSelected(w, c, monitoring);
                synchronized (this) { if (token == generation) { latest = values; error = ""; } }
            } catch (CancellationException ignored) { }
            catch (RuntimeException failure) { error = failure.getClass().getSimpleName(); }
        }, 0, 30, TimeUnit.SECONDS);
    }
    private void stop() { generation++; if (worker != null) worker.shutdownNow(); worker = null; bluetooth.stop(); latest = Collections.emptyList(); }
    synchronized List<NetworkCheckResult> snapshot() {
        List<NetworkCheckResult> result = new ArrayList<>(latest);
        if (foreground || (monitoring && bt)) result.addAll(bluetooth.snapshot());
        return result;
    }
    synchronized List<NetworkCheckResult> bluetoothSnapshot() { return bluetooth.snapshot(); }
    synchronized void refreshPermissions() { restart(); }
}
