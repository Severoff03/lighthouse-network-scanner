package ru.lighthouse.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads public GitHub Releases; never stores a credential in the application. */
final class GitHubDesktopUpdates {
    static final String REPOSITORY = "Severoff03/lighthouse-network-scanner";
    private static final long MAX_ZIP = 1_500_000_000L;
    record Offer(String version, String url, String sha256, long size, String notes) { }

    static Offer latest() throws Exception {
        String json = read("https://api.github.com/repos/" + REPOSITORY + "/releases/latest", 2_000_000);
        String tag = string(json, "tag_name");
        if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("Invalid GitHub release version");
        String version = tag.substring(1);
        String wanted = "Lighthouse-Windows-" + tag + ".zip";
        int assets = json.indexOf("\"assets\"");
        if (assets < 0) throw new IOException("GitHub release has no assets");
        int array = json.indexOf('[', assets);
        if (array < 0) throw new IOException("GitHub release assets are invalid");
        int cursor = array + 1;
        while (cursor < json.length()) {
            while (cursor < json.length() && (Character.isWhitespace(json.charAt(cursor)) || json.charAt(cursor) == ',')) cursor++;
            if (cursor >= json.length() || json.charAt(cursor) == ']') break;
            if (json.charAt(cursor) != '{') throw new IOException("Invalid GitHub release asset");
            int end = objectEnd(json, cursor);
            String asset = json.substring(cursor, end);
            if (wanted.equals(string(asset, "name"))) {
                String url = string(asset, "browser_download_url");
                String digest = string(asset, "digest");
                long size = number(asset, "size");
                if (!url.startsWith("https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/")
                    || !digest.matches("sha256:[0-9a-fA-F]{64}") || size <= 0 || size > MAX_ZIP)
                    throw new IOException("GitHub release asset cannot be verified");
                return new Offer(version, url, digest.substring(7).toLowerCase(Locale.ROOT), size, string(json, "body"));
            }
            cursor = end;
        }
        throw new IOException("Windows ZIP is absent from the latest GitHub release");
    }

    static void download(Offer offer, Path destination) throws Exception {
        HttpURLConnection connection = open(offer.url());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (InputStream input = connection.getInputStream(); var output = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    copied += count;
                    if (copied > MAX_ZIP) throw new IOException("Windows update is too large");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                }
            }
            if (copied != offer.size() || !HexFormat.of().formatHex(digest.digest()).equals(offer.sha256()))
                throw new IOException("Windows update size or SHA-256 does not match GitHub");
        } finally { connection.disconnect(); }
    }

    private static String read(String url, int limit) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream()) {
            byte[] data = input.readNBytes(limit + 1);
            if (data.length > limit) throw new IOException("GitHub release metadata is too large");
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
    }

    private static HttpURLConnection open(String address) throws IOException {
        URL url = new URL(address);
        for (int hops = 0; hops < 6; hops++) {
            if (!"https".equals(url.getProtocol())) throw new IOException("Insecure GitHub redirect");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000); connection.setReadTimeout(120_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Lighthouse-Desktop-Updater/1");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) return connection;
            if (code < 300 || code >= 400) { connection.disconnect(); throw new IOException("GitHub returned HTTP " + code); }
            String location = connection.getHeaderField("Location"); connection.disconnect();
            if (location == null || location.isBlank()) throw new IOException("Invalid GitHub redirect");
            url = new URL(url, location);
        }
        throw new IOException("Too many GitHub redirects");
    }

    private static int objectEnd(String json, int start) throws IOException {
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; }
            else if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i + 1;
        }
        throw new IOException("Invalid GitHub asset JSON");
    }

    private static String string(String json, String key) throws IOException {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").matcher(json);
        if (!matcher.find()) throw new IOException("Missing GitHub field: " + key);
        String raw = matcher.group(1); StringBuilder value = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && ++i < raw.length()) {
                char escaped = raw.charAt(i);
                value.append(escaped == 'n' ? '\n' : escaped == 'r' ? '\r' : escaped == 't' ? '\t' : escaped);
            } else value.append(c);
        }
        return value.toString();
    }

    private static long number(String json, String key) throws IOException {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(\\d+)").matcher(json);
        if (!matcher.find()) throw new IOException("Missing GitHub field: " + key);
        return Long.parseLong(matcher.group(1));
    }
}
