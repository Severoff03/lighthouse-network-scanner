package ru.lighthouse.android;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** App-private error report; never uploaded automatically. */
public final class CrashDiagnostics extends Application {
    private static CrashDiagnostics application;
    private static volatile String phase = "Запуск приложения";
    private static final String FILE = "last-error.txt";

    @Override public void onCreate() {
        super.onCreate();
        application = this;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try { record("Необработанная ошибка / " + thread.getName(), error); }
            finally {
                // Keep Android's normal crash handling; never swallow fatal VM errors.
                if (previous != null) previous.uncaughtException(thread, error);
                else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(1); }
            }
        });
    }

    static void phase(String value) { phase = value; }

    static synchronized void record(String operation, Throwable error) {
        Log.e("Lighthouse", operation + " / " + phase, error);
        if (application == null) return;
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                application.openFileOutput(FILE, MODE_PRIVATE), StandardCharsets.UTF_8))) {
            String version = application.getPackageManager().getPackageInfo(application.getPackageName(), 0).versionName;
            writer.println("Lighthouse " + version + " | Mothman");
            writer.println("Time: " + new java.util.Date());
            writer.println("Android: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
            writer.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
            writer.println("Phase: " + phase);
            writer.println("Operation: " + operation);
            error.printStackTrace(writer);
        } catch (Exception ignored) { /* Logging must never replace the original failure. */ }
    }

    static byte[] read() throws java.io.IOException {
        File file = new File(application.getFilesDir(), FILE);
        if (!file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while (out.size() < 65536 && (count = in.read(buffer, 0, Math.min(buffer.length, 65536 - out.size()))) > 0)
                out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
}
