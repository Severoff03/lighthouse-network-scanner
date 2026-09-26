package ru.lighthouse.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NetworkCheckResult {
    public enum Status { OK, WARNING, FAILED }

    public final String id;
    public final String name;
    public final String category;
    public final Status status;
    public final String summary;
    public final Map<String, String> metrics;

    public NetworkCheckResult(String id, String name, String category, Status status,
                              String summary, Map<String, String> metrics) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.status = status;
        this.summary = summary;
        this.metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }
}
