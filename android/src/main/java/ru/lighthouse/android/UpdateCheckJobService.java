package ru.lighthouse.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Build;

/** Periodic network-aware update check; installation still requires the user. */
public final class UpdateCheckJobService extends JobService {
    static final int JOB_ID = 2417;
    private UpdateManager manager;
    private boolean finished;

    @Override public boolean onStartJob(JobParameters params) {
        manager = new UpdateManager(this);
        if (!manager.isConfigured()) { manager.shutdown(); return false; }
        getSharedPreferences("settings", MODE_PRIVATE).edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
        manager.check(new UpdateManager.Callback() {
            @Override public void onUpdateAvailable(UpdateManager.UpdateInfo update) {
                UpdateManager.savePending(UpdateCheckJobService.this, update);
                showNotification(update);
                finish(params, false);
            }

            @Override public void onNoUpdate() {
                UpdateManager.clearPending(UpdateCheckJobService.this);
                finish(params, false);
            }

            @Override public void onError(String message) { finish(params, true); }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (manager != null) manager.shutdown();
        return true;
    }

    private void finish(JobParameters params, boolean retry) {
        if (finished) return;
        finished = true;
        if (manager != null) manager.shutdown();
        jobFinished(params, retry);
    }

    private void showNotification(UpdateManager.UpdateInfo update) {
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notifications == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("updates", "Обновления Lighthouse", NotificationManager.IMPORTANCE_DEFAULT);
            notifications.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 2417, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, "updates") : new Notification.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Доступно обновление Lighthouse")
            .setContentText(update.displayVersion() + ". Откройте приложение для установки.")
            .setContentIntent(pending).setAutoCancel(true);
        try { notifications.notify(2417, builder.build()); } catch (SecurityException ignored) { }
    }
}
