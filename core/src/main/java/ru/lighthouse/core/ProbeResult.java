package ru.lighthouse.core;

public final class ProbeResult {
    public enum Status { AVAILABLE, DEGRADED, UNAVAILABLE }

    public final ServiceTarget target;
    public final Status status;
    public final long dnsMs;
    public final long pingMs;
    public final long tcpMs;
    public final long httpsMs;
    public final int httpCode;
    public final String resolvedIp;
    public final String error;
    public String probeDetail = "";

    public ProbeResult withDetail(String detail) { this.probeDetail = detail; return this; }
    public final long measuredAtMillis;
    public final java.util.List<ProbeResult> samples;

    public ProbeResult(ServiceTarget target, Status status, long dnsMs, long pingMs, long tcpMs, long httpsMs,
                       int httpCode, String resolvedIp, String error) {
        this(target, status, dnsMs, pingMs, tcpMs, httpsMs, httpCode, resolvedIp, error,
            System.currentTimeMillis(), java.util.Collections.emptyList());
    }

    private ProbeResult(ServiceTarget target, Status status, long dnsMs, long pingMs, long tcpMs, long httpsMs,
                       int httpCode, String resolvedIp, String error, long measuredAtMillis,
                       java.util.List<ProbeResult> samples) {
        this.target = target;
        this.status = status;
        this.dnsMs = dnsMs;
        this.pingMs = pingMs;
        this.tcpMs = tcpMs;
        this.httpsMs = httpsMs;
        this.httpCode = httpCode;
        this.resolvedIp = resolvedIp;
        this.error = error;
        this.measuredAtMillis = measuredAtMillis;
        this.samples = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(samples));
    }

    public int attempts() { return samples.isEmpty() ? 1 : samples.size(); }

    public static ProbeResult combine(ProbeResult previous, ProbeResult next) {
        if (previous == null) return next;
        java.util.List<ProbeResult> samples = new java.util.ArrayList<>();
        if (previous.samples.isEmpty()) samples.add(previous); else samples.addAll(previous.samples);
        samples.add(next);
        boolean allUp = true, allDown = true;
        for (ProbeResult sample : samples) {
            allUp &= sample.status == Status.AVAILABLE;
            allDown &= sample.status == Status.UNAVAILABLE;
        }
        return new ProbeResult(next.target, allUp ? Status.AVAILABLE : allDown ? Status.UNAVAILABLE : Status.DEGRADED,
            next.dnsMs, next.pingMs, next.tcpMs, next.httpsMs, next.httpCode, next.resolvedIp, next.error,
            next.measuredAtMillis, samples).withDetail(next.probeDetail);
    }
}
