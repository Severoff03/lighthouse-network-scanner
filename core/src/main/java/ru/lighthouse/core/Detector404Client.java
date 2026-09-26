package ru.lighthouse.core;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional read-only integration with the authenticated Detector404 alerts API. */
public final class Detector404Client {
    private static final String ENDPOINT = "https://detector404.ru/api/v1/alerts/filtered";
    private static final Pattern SUCCESS = Pattern.compile("\\\"success\\\"\\s*:\\s*true");
    private static final Pattern TYPE = Pattern.compile("\\\"type\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SERVICE = Pattern.compile("\\\"service\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private Detector404Client() { }

    public static NetworkCheckResult fetch(String token, int timeoutMs) {
        if (token == null || token.isBlank()) return check(NetworkCheckResult.Status.OK,
            "API Detector404 не настроен. Добавьте личный токен в настройках; без него внешние данные не влияют на аналитику.",
            map("configured", "false", "source", ENDPOINT));
        String clean = token.trim();
        if (clean.length() > 4096 || clean.indexOf('\r') >= 0 || clean.indexOf('\n') >= 0)
            return check(NetworkCheckResult.Status.WARNING, "Токен Detector404 имеет недопустимый формат.", map("configured", "true"));
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(ENDPOINT).openConnection();
            connection.setConnectTimeout(timeoutMs); connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(false); connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + clean);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Lighthouse network-diagnostic");
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = read(stream, 131072);
            if (code != 200) return check(NetworkCheckResult.Status.WARNING,
                code == 401 || code == 403 ? "Detector404 отклонил токен или доступ к API." : "Detector404 API ответил кодом HTTP " + code + ".",
                map("configured", "true", "httpCode", String.valueOf(code), "source", ENDPOINT));
            return parse(body);
        } catch (Exception error) {
            return check(NetworkCheckResult.Status.WARNING, "Данные Detector404 не получены: " + error.getClass().getSimpleName() + ".",
                map("configured", "true", "error", error.getClass().getSimpleName(), "source", ENDPOINT));
        } finally { if (connection != null) connection.disconnect(); }
    }

    static NetworkCheckResult parse(String body) {
        if (body == null || !SUCCESS.matcher(body).find())
            return check(NetworkCheckResult.Status.WARNING, "Detector404 вернул ответ без подтверждения успеха.", map("source", ENDPOINT));
        Map<String, Integer> types = new LinkedHashMap<>(); Matcher type = TYPE.matcher(body); int alerts = 0;
        while (type.find()) { alerts++; types.merge(unescape(type.group(1)), 1, Integer::sum); }
        Set<String> services = new LinkedHashSet<>(); Matcher service = SERVICE.matcher(body);
        while (service.find() && services.size() < 40) services.add(unescape(service.group(1)));
        Map<String, String> metrics = map("configured", "true", "source", ENDPOINT, "activeAlerts", String.valueOf(alerts));
        metrics.put("alertTypes", types.isEmpty() ? "none" : types.toString());
        metrics.put("services", services.isEmpty() ? "none" : String.join(", ", services));
        metrics.put("interpretation", "Third-party aggregate monitoring; never proof of a local block by itself");
        return check(alerts > 0 ? NetworkCheckResult.Status.WARNING : NetworkCheckResult.Status.OK,
            alerts == 0 ? "Detector404 не сообщил активных событий по настроенным порогам."
                : "Detector404 сообщает активных событий: " + alerts + ". Сопоставьте их с локальными измерениями.", metrics);
    }

    private static String read(InputStream input, int limit) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) >= 0 && output.size() < limit)
                output.write(buffer, 0, Math.min(count, limit - output.size()));
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static String unescape(String value) { return value.replace("\\\"", "\"").replace("\\\\", "\\"); }
    private static NetworkCheckResult check(NetworkCheckResult.Status status, String summary, Map<String, String> metrics) {
        return new NetworkCheckResult("detector404_api", "Detector404 API", "Внешний мониторинг", status, summary, metrics);
    }
    private static Map<String, String> map(String... pairs) { Map<String, String> value = new LinkedHashMap<>(); for (int i = 0; i + 1 < pairs.length; i += 2) value.put(pairs[i], pairs[i + 1]); return value; }
}
