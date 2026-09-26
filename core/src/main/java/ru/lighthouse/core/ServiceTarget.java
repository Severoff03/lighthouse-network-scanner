package ru.lighthouse.core;

public final class ServiceTarget {
    public enum ProbeKind { HTTPS, DNS, TCP }

    public final String id;
    public final String name;
    public final String category;
    public final String host;
    public final String path;
    public final String region;
    public final ProbeKind probeKind;
    public final int port;

    public ServiceTarget(String id, String name, String category, String host, String path, String region) {
        this(id, name, category, host, path, region, ProbeKind.HTTPS, 443);
    }

    public ServiceTarget(String id, String name, String category, String host, String path, String region,
                         ProbeKind probeKind, int port) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.host = host;
        this.path = path;
        this.region = region;
        this.probeKind = probeKind;
        this.port = port;
    }
}
