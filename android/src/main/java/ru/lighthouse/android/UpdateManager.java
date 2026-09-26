package ru.lighthouse.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** GitHub release updater with optional local Mothman fallback. Network work stays off the UI thread. */
final class UpdateManager {
    static final long AUTOMATIC_CHECK_INTERVAL_MS = ru.lighthouse.core.MothmanUpdates.INTERVAL_MS;
    private static final long MAX_APK_BYTES = 200L * 1024L * 1024L;
    private static final String PENDING_VERSION = "pendingUpdateVersion";
    private static final String PENDING_CODE = "pendingUpdateCode";
    private static final String PENDING_APK = "pendingUpdateApk";
    private static final String PENDING_URL = "pendingUpdateUrl";
    private static final String PENDING_SHA = "pendingUpdateSha";
    private static final String PENDING_SIZE = "pendingUpdateSize";
    private static final String PENDING_NOTES = "pendingUpdateNotes";
    private static final String PENDING_RELEASE_URL = "pendingUpdateReleaseUrl";

    interface Callback {
        default void onChecking() { }
        default void onNoUpdate() { }
        default void onUpdateAvailable(UpdateInfo update) { }
        default void onProgress(int percent) { }
        default void onDownloaded(File apk) { }
        default void onError(String message) { }
    }

    static final class UpdateInfo {
        final String version;
        final long versionCode;
        final String apkName;
        final String assetUrl;
        final String sha256;
        final long sizeBytes;
        final String notes;
        final String releaseUrl;

        UpdateInfo(String version, long versionCode, String apkName, String assetUrl,
                   String sha256, long sizeBytes, String notes, String releaseUrl) {
            this.version = version;
            this.versionCode = versionCode;
            this.apkName = apkName;
            this.assetUrl = assetUrl;
            this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
            this.sizeBytes = sizeBytes;
            this.notes = notes == null ? "" : notes.trim();
            this.releaseUrl = releaseUrl == null ? "" : releaseUrl;
        }

        String displayVersion() { return version.isEmpty() ? "новая версия" : "v" + version; }
    }

    private final Context app;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final String owner = BuildConfig.UPDATE_REPOSITORY_OWNER.trim();
    private final String repository = BuildConfig.UPDATE_REPOSITORY_NAME.trim();

    private final MothmanAndroid mothman;
    UpdateManager(Context context) {
        app = context.getApplicationContext(); mothman = new MothmanAndroid(app);
        try {
            String current=app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName;
            android.content.SharedPreferences state=app.getSharedPreferences("mothman-update",0);
            if(!current.equals(state.getString("lastInstalledVersion",""))) {
                mothman.log("installed version: "+current);state.edit().putString("lastInstalledVersion",current).apply();
            }
        }catch(Exception ignored){}
    }

    boolean isConfigured() { return true; }

    private boolean githubConfigured() {
        return !owner.isEmpty() && !repository.isEmpty()
            && owner.matches("[A-Za-z0-9_.-]+") && repository.matches("[A-Za-z0-9_.-]+");
    }

    void check(Callback callback) {
        if (!isConfigured()) {
            post(() -> callback.onError("Параметры приватного репозитория не заданы в этой сборке."));
            return;
        }
        if (!busy.compareAndSet(false, true)) return;
        post(callback::onChecking);
        executor.submit(() -> {
            try {
                UpdateInfo update;
                try { update = fetchLatest(); }
                catch (Exception githubError) {
                    mothman.log("GitHub check: " + githubError.getMessage());
                    try {
                        ru.lighthouse.core.MothmanUpdates.Offer offer=mothman.check();
                        update=isNewer(offer.version,0) ? new UpdateInfo(offer.version,0,"lighthouse-update.apk",
                            offer.base+"update/android-package.apk",offer.sha256,offer.size,offer.notes,offer.base) : null;
                    } catch (java.io.IOException localError) { throw githubError; }
                }
                final UpdateInfo found=update;
                if (update == null) post(callback::onNoUpdate);
                else post(() -> callback.onUpdateAvailable(found));
            } catch (Exception error) {
                post(() -> callback.onError(userMessage(error)));
            } finally {
                busy.set(false);
            }
        });
    }

    void download(UpdateInfo update, Callback callback) {
        if (!isConfigured() || update == null || update.assetUrl.isEmpty()) {
            post(() -> callback.onError("Обновление недоступно."));
            return;
        }
        if (!busy.compareAndSet(false, true)) return;
        executor.submit(() -> {
            File part = new File(app.getCacheDir(), "lighthouse-update.apk.part");
            File target = new File(app.getCacheDir(), "lighthouse-update.apk");
            try {
                if (part.exists() && !part.delete()) throw new IOException("Не удалось подготовить загрузку");
                if (update.assetUrl.startsWith("http://")) {
                    if(update.sizeBytes<=0 || update.sizeBytes>MAX_APK_BYTES)throw new IOException("Неверный размер APK");
                    mothman.download(update,part);
                } else downloadFile(update, part, callback);
                verifyChecksum(update, part);
                validateApk(update, part);
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                mothman.log("verified APK " + update.version + "; awaiting system installer");
                post(() -> callback.onDownloaded(target));
            } catch (Exception error) {
                if (part.exists()) part.delete();
                mothman.log("download rejected: " + userMessage(error));
                post(() -> callback.onError(userMessage(error)));
            } finally {
                busy.set(false);
            }
        });
    }

    void shutdown() { executor.shutdownNow(); main.removeCallbacksAndMessages(null); }

    static void savePending(Context context, UpdateInfo update) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString(PENDING_VERSION, update.version).putLong(PENDING_CODE, update.versionCode)
            .putString(PENDING_APK, update.apkName).putString(PENDING_URL, update.assetUrl)
            .putString(PENDING_SHA, update.sha256).putLong(PENDING_SIZE, update.sizeBytes)
            .putString(PENDING_NOTES, update.notes).putString(PENDING_RELEASE_URL, update.releaseUrl).apply();
    }

    static void clearPending(Context context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .remove(PENDING_VERSION).remove(PENDING_CODE).remove(PENDING_APK).remove(PENDING_URL)
            .remove(PENDING_SHA).remove(PENDING_SIZE).remove(PENDING_NOTES).remove(PENDING_RELEASE_URL).apply();
    }

    static UpdateInfo loadPending(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String version = preferences.getString(PENDING_VERSION, "");
        String url = preferences.getString(PENDING_URL, "");
        if (version.isEmpty() || url.isEmpty()) return null;
        UpdateInfo update = new UpdateInfo(version, preferences.getLong(PENDING_CODE, 0),
            preferences.getString(PENDING_APK, "update.apk"), url,
            preferences.getString(PENDING_SHA, ""), preferences.getLong(PENDING_SIZE, 0),
            preferences.getString(PENDING_NOTES, ""), preferences.getString(PENDING_RELEASE_URL, ""));
        if (url.startsWith("https://github.com/")
            && !update.apkName.equals("Lighthouse-Android-v" + version + ".apk")) {
            clearPending(context);
            return null;
        }
        try {
            PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long current = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
            if ((update.versionCode > 0 && update.versionCode <= current)
                || (update.versionCode == 0 && compareVersions(update.version, installed.versionName) <= 0)) {
                clearPending(context);
                return null;
            }
        } catch (Exception ignored) { }
        return update;
    }

    private UpdateInfo fetchLatest() throws Exception {
        if (!githubConfigured()) throw new IOException("GitHub repository is not configured");
        JSONObject release = new JSONObject(requestText(apiBase() + "/releases/latest", "application/vnd.github+json"));
        JSONArray assetsJson = release.optJSONArray("assets");
        JSONObject apkAsset = null;
        if (assetsJson != null) {
            for (int i = 0; i < assetsJson.length(); i++) {
                JSONObject item = assetsJson.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "");
                if (name.matches("Lighthouse-Android-v[0-9]+\\.[0-9]+\\.[0-9]+\\.apk")) apkAsset = item;
            }
        }
        if (apkAsset == null) return null;
        String version = release.optString("tag_name", "");
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("Некорректная версия GitHub Release");
        String apkName = apkAsset.optString("name", "");
        if (!apkName.equals("Lighthouse-Android-v" + version + ".apk"))
            throw new IOException("APK не соответствует версии релиза");
        String url = apkAsset.optString("browser_download_url", "");
        String digest = apkAsset.optString("digest", "");
        if (!digest.startsWith("sha256:") || !digest.substring(7).matches("[0-9a-fA-F]{64}"))
            throw new IOException("GitHub Release не содержит SHA-256 APK");
        long size = apkAsset.optLong("size", 0);
        if (!url.startsWith("https://github.com/") || size <= 0 || size > MAX_APK_BYTES)
            throw new IOException("Недопустимый APK GitHub Release");
        String notes = release.optString("body", "");
        return isNewer(version, 0)
            ? new UpdateInfo(version, 0, apkName, url, digest.substring(7), size,
                notes, release.optString("html_url", ""))
            : null;
    }

    private boolean isNewer(String remoteVersion, long remoteCode) throws Exception {
        PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        long currentCode = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        if (remoteCode > 0) return remoteCode > currentCode;
        return compareVersions(remoteVersion, installed.versionName) > 0;
    }

    private static int compareVersions(String left, String right) throws IOException {
        return ru.lighthouse.core.MothmanUpdates.compareVersions(left,right);
    }

    private void downloadFile(UpdateInfo update, File destination, Callback callback) throws Exception {
        if (update.sizeBytes > MAX_APK_BYTES) throw new IOException("APK обновления слишком большой");
        HttpURLConnection connection = openFollowingRedirects(update.assetUrl, "application/octet-stream");
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new IOException("Сервер обновлений вернул ошибку");
            long total = update.sizeBytes > 0 ? update.sizeBytes : connection.getContentLengthLong();
            long copied = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    copied += count;
                    if (copied > MAX_APK_BYTES) throw new IOException("APK обновления слишком большой");
                    if (total > 0) {
                        int percent = (int) Math.min(100, copied * 100L / total);
                        post(() -> callback.onProgress(percent));
                    }
                }
            }
            if (copied == 0 || (update.sizeBytes > 0 && copied != update.sizeBytes))
                throw new IOException("Загрузка APK завершилась неполностью");
        } finally {
            connection.disconnect();
        }
    }

    private void verifyChecksum(UpdateInfo update, File file) throws Exception {
        if (!update.sha256.matches("[0-9a-f]{64}")) throw new IOException("В манифесте нет SHA-256");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        String actual = hex(digest.digest());
        if (!actual.equalsIgnoreCase(update.sha256)) throw new IOException("Контрольная сумма APK не совпала");
    }

    private void validateApk(UpdateInfo update, File file) throws Exception {
        PackageManager packages = app.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = packages.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (archive == null || !app.getPackageName().equals(archive.packageName))
            throw new IOException("Файл не является APK Lighthouse");
        long archiveCode = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
        if (update.versionCode > 0 && archiveCode != update.versionCode)
            throw new IOException("Версия APK не соответствует манифесту релиза");
        PackageInfo installed = packages.getPackageInfo(app.getPackageName(), flags);
        long installedCode=Build.VERSION.SDK_INT>=28 ? installed.getLongVersionCode() : installed.versionCode;
        if(archiveCode<=installedCode || compareVersions(archive.versionName,update.version)!=0)
            throw new IOException("APK не новее установленного или версия не совпала с манифестом");
        Signature[] expected = signatures(installed);
        Signature[] actual = signatures(archive);
        if (expected.length == 0 || actual.length == 0 || expected.length != actual.length)
            throw new IOException("Подпись APK не совпадает с установленным приложением");
        for (Signature signature : expected) {
            boolean found = false;
            for (Signature candidate : actual) if (signature.equals(candidate)) { found = true; break; }
            if (!found) throw new IOException("Подпись APK не совпадает с установленным приложением");
        }
    }

    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null)
            return info.signingInfo.hasMultipleSigners()
                ? info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory();
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private HttpURLConnection open(String endpoint, String accept) throws IOException {
        URL url = new URL(endpoint);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Небезопасный адрес сервера обновлений");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "Lighthouse-Android-Updater/1");
        return connection;
    }

    private HttpURLConnection openFollowingRedirects(String endpoint, String accept) throws IOException {
        String current = endpoint;
        for (int attempt = 0; attempt < 6; attempt++) {
            HttpURLConnection connection = open(current, accept);
            int response = connection.getResponseCode();
            if (response < 300 || response >= 400) return connection;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.isEmpty()) throw new IOException("Сервер обновлений вернул неверное перенаправление");
            current = new URL(new URL(current), location).toString();
        }
        throw new IOException("Слишком много перенаправлений сервера обновлений");
    }

    private String requestText(String endpoint, String accept) throws IOException {
        HttpURLConnection connection = openFollowingRedirects(endpoint, accept);
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new IOException("Сервер обновлений вернул ошибку");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024) throw new IOException("Слишком большой манифест обновления");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }

    private String apiBase() { return "https://api.github.com/repos/" + owner + "/" + repository; }

    private void post(Runnable action) { main.post(() -> { try { action.run(); } catch (Exception ignored) { } }); }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item));
        return value.toString();
    }

    private static String userMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "Не удалось проверить обновления";
        if (message.contains("UnknownHostException") || message.contains("timeout")) return "Сервер обновлений недоступен";
        return message;
    }
}
