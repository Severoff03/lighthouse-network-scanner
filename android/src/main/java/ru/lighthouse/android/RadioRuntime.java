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
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners = new HashSet<>();
    private ScheduledExecutorService worker;
    private volatile List<NetworkCheckResult> latest = Collections.emptyList();
    private volatile long lastSurveyElapsedMs;
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
    synchronized void addListener(Runnable listener) { listeners.add(listener); }
    synchronized void removeListener(Runnable listener) { listeners.remove(listener); }
    private void notifyListeners() {
        main.post(() -> {
            Runnable[] callbacks;
            synchronized (RadioRuntime.this) { callbacks = listeners.toArray(new Runnable[0]); }
            for (Runnable callback : callbacks) callback.run();
        });
    }
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
                boolean changed = false;
                synchronized (this) { if (token == generation) { latest = values; lastSurveyElapsedMs=SystemClock.elapsedRealtime(); error = ""; changed = true; } }
                if (changed) notifyListeners();
            } catch (CancellationException ignored) { }
            catch (RuntimeException failure) { error = failure.getClass().getSimpleName(); notifyListeners(); }
        }, 0, 15, TimeUnit.SECONDS);
    }
    private void stop() { generation++; if (worker != null) worker.shutdownNow(); worker = null; bluetooth.stop(); latest = Collections.emptyList(); lastSurveyElapsedMs = 0; }
    synchronized List<NetworkCheckResult> snapshot() {
        List<NetworkCheckResult> result = new ArrayList<>(latest);
        if (foreground || (monitoring && bt)) result.addAll(bluetooth.snapshot());
        return result;
    }
    synchronized List<NetworkCheckResult> bluetoothSnapshot() { return bluetooth.snapshot(); }
    long lastSurveyElapsedMs() { return lastSurveyElapsedMs; }
    synchronized void refreshPermissions() { restart(); }
}
