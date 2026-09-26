package ru.lighthouse.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import rikka.shizuku.Shizuku;

/** Optional ADB-shell sweep. Every mode change is checked and the user's mode is restored. */
final class CellModeSweep {
    private static final int PERMISSION_REQUEST = 6731;
    private static final int[] DWELL_SECONDS = {35, 45, 35, 35};
    private final Context context;
    private final Consumer<String> status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Binder owner = new Binder();
    private final Shizuku.UserServiceArgs args;
    private volatile IBinder binder;
    private volatile boolean active;
    private boolean bound, closed, completed;
    private volatile boolean opening, running;
    private volatile int generation;
    private volatile ScheduledFuture<?> nextStage;
    private final Shizuku.OnBinderReceivedListener received = () -> main.post(this::startIfReady);
    private final Shizuku.OnBinderDeadListener died = () -> main.post(() -> {
        binder = null;
        if (active) update("Shizuku stopped. Check the mobile network mode in Settings.","Shizuku остановлен. Проверьте режим сети в настройках телефона.");
        active = false;
        completed = true;
        bound = false;
        cancelStage();
    });
    private final Shizuku.OnRequestPermissionResultListener permission = (request, result) -> {
        if (request != PERMISSION_REQUEST) return;
        main.post(() -> {
            if (result == PackageManager.PERMISSION_GRANTED) startIfReady();
            else update("Shizuku permission denied; passive Cell scan continues.","Доступ Shizuku отклонён; пассивное сканирование Cell продолжается.");
        });
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            if (active) open();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            binder = null;
            if (active) update("Telephony service disconnected; check the mobile network mode in Settings.","Служба телефонии отключилась; проверьте режим сети в настройках телефона.");
        }
    };
    CellModeSweep(Context context, Consumer<String> status) {
        this.context = context;
        this.status = status;
        args = new Shizuku.UserServiceArgs(new ComponentName(context, CellModeShellService.class))
            .daemon(true).tag("lighthouse-cell-mode").version(BuildConfig.VERSION_CODE);
        Shizuku.addBinderReceivedListener(received);
        Shizuku.addBinderDeadListener(died);
        Shizuku.addRequestPermissionResultListener(permission);
    }

    void start() {
        if (closed || active || completed) return;
        active = true;
        update("Connecting to Shizuku…","Подключение к Shizuku…");
        startIfReady();
    }

    void prepareEntry() { if (!active && !closed) completed = false; }

    private void startIfReady() {
        if (!active || closed) return;
        try {
            if (!Shizuku.pingBinder()) {
                update("Start Shizuku via wireless debugging for automatic GSM/3G/LTE/5G survey. Passive Cell scan continues.","Запустите Shizuku через беспроводную отладку для обхода GSM/3G/LTE/5G. Пассивное сканирование Cell продолжается.");
                return;
            }
            if (Shizuku.getUid() != 2000) { update("Shizuku must run with ADB shell access.","Shizuku должен работать с правами ADB shell."); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                update("Grant Lighthouse access in Shizuku to survey network modes.","Разрешите Lighthouse доступ в Shizuku для обхода режимов сети.");
                if (!Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(PERMISSION_REQUEST);
                return;
            }
            if (!bound) { bound = true; Shizuku.bindUserService(args, connection); }
            else if (binder != null) open();
        } catch (RuntimeException failure) {
            if (binder == null) bound = false;
            update("Shizuku unavailable: " + failure.getClass().getSimpleName(),"Shizuku недоступен: " + failure.getClass().getSimpleName());
        }
    }

    private void open() {
        if (opening || running || !active) return;
        opening = true;
        int token = ++generation;
        worker.execute(() -> {
            if (!active || token != generation) { opening = false; return; }
            try {
                int slot = dataSimSlot();
                rpc(CellModeShellService.OPEN, slot);
                opening = false;
                if (active && token == generation) { running = true; apply(0, token); }
                else rpc(CellModeShellService.RESTORE, 0);
            } catch (Exception failure) { opening = false; fail(failure); }
        });
    }

    private int dataSimSlot() throws IOException {
        SubscriptionManager manager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (manager == null || context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            throw new IOException("Phone permission or active data SIM is unavailable");
        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        SubscriptionInfo info = manager.getActiveSubscriptionInfo(subId);
        if (info == null || info.getSimSlotIndex() < 0) throw new IOException("Active data SIM is unavailable");
        return info.getSimSlotIndex();
    }

    private void apply(int mode, int token) {
        worker.execute(() -> {
            if (!active || token != generation) return;
            try {
                String label = rpc(CellModeShellService.APPLY, mode);
                update("Surveying " + label + " · only cells reported by Android are shown","Обход "+label+" · показаны только соты, полученные от Android");
                nextStage = worker.schedule(() -> {
                    if (!active || token != generation) return;
                    if (mode + 1 < DWELL_SECONDS.length) apply(mode + 1, token);
                    else finish();
                }, DWELL_SECONDS[mode], TimeUnit.SECONDS);
            } catch (Exception failure) { fail(failure); }
        });
    }

    private void finish() {
        active = false;
        completed = true;
        generation++;
        running = false;
        cancelStage();
        worker.execute(() -> {
            try { rpc(CellModeShellService.RESTORE, 0); update("Survey complete · original network mode restored","Обход завершён · исходный режим сети восстановлен"); }
            catch (Exception failure) { restoreError(failure); }
        });
    }

    void stop() {
        if (!active && !opening && !running) return;
        active = false;
        completed = true;
        generation++;
        opening = false;
        running = false;
        cancelStage();
        worker.execute(() -> {
            try { if (binder != null) rpc(CellModeShellService.RESTORE, 0); }
            catch (Exception failure) { restoreError(failure); }
        });
    }

    void close() {
        if (closed) return;
        closed = true; stop();
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(died);
        Shizuku.removeRequestPermissionResultListener(permission);
        worker.execute(() -> {
            if (bound && Shizuku.pingBinder()) {
                try { Shizuku.unbindUserService(args, connection, false); } catch (RuntimeException ignored) { }
            }
        });
        worker.shutdown();
    }

    private void fail(Exception failure) {
        active = false; generation++;
        completed = true;
        opening = false;
        running = false;
        cancelStage();
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        update("Automatic survey stopped: " + message,"Автоматический обход остановлен: " + message);
        worker.execute(() -> {
            try { if (binder != null) rpc(CellModeShellService.RESTORE, 0); }
            catch (Exception restoreFailure) { restoreError(restoreFailure); }
        });
    }

    private String rpc(int code, int value) throws IOException, RemoteException {
        IBinder service = binder;
        if (service == null) throw new IOException("Shizuku service is disconnected");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            if (code == CellModeShellService.OPEN) { data.writeInt(value); data.writeStrongBinder(owner); }
            else if (code == CellModeShellService.APPLY) data.writeInt(value);
            if (!service.transact(code, data, reply, 0)) throw new IOException("Telephony service did not handle the request");
            reply.readException();
            if (reply.readInt() == 0) throw new IOException(reply.readString());
            return reply.readString();
        } finally { data.recycle(); reply.recycle(); }
    }

    private void restoreError(Exception failure) { update("Could not restore network mode: " + failure.getMessage(),"Не удалось восстановить режим сети: " + failure.getMessage()); }
    private void update(String english,String russian) { main.post(() -> { if (!closed) status.accept(UiLanguage.isRussian()?russian:english); }); }

    private void cancelStage() {
        ScheduledFuture<?> stage = nextStage;
        nextStage = null;
        if (stage != null) stage.cancel(false);
    }
}
