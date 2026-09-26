package ru.lighthouse.core;

import java.net.URI;

/** A session-only, user-named connection profile. */
public final class NamedConfiguration {
    public final String name;
    public final String value;

    public NamedConfiguration(String name, String value) {
        this.name = name == null ? "" : name.trim();
        this.value = value == null ? "" : value.trim();
    }

    /** UI-safe preview: never exposes credentials, query parameters, paths or proxy secrets. */
    public String safePreview() {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null) return "Настройка сохранена";
            if (host == null || host.isBlank()) return scheme + "://…";
            return scheme + "://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (Exception ignored) { return "Настройка сохранена"; }
    }
}
