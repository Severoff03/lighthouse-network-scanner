package ru.lighthouse.core;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public final class ScanReport {
    public enum Level { NO_CONNECTION, ALLOWLIST_SUSPECTED, SEVERE_RESTRICTIONS, DEGRADED, NORMAL, INCOMPLETE }

    public final String scanId;
    public final Instant startedAt;
    public final Instant finishedAt;
    public final DeviceInfo device;
    public final String publicIp;
    public final String networkOrigin;
    public final Level level;
    public final List<ProbeResult> results;
    public final List<NetworkCheckResult> networkChecks;
    public final List<String> newlyUnavailable;
    public final List<String> recovered;
    public final List<String> recommendations;
    public final ScanProfile profile;
    public final int expectedTargets;
    public final boolean complete;
    public final NetworkAssessment assessment;

    public ScanReport(String scanId, Instant startedAt, Instant finishedAt, DeviceInfo device,
                      String publicIp, String networkOrigin, Level level, List<ProbeResult> results,
                      List<NetworkCheckResult> networkChecks,
                      List<String> newlyUnavailable, List<String> recovered, List<String> recommendations) {
        this(scanId, startedAt, finishedAt, device, publicIp, networkOrigin, level, results, networkChecks,
            newlyUnavailable, recovered, recommendations, ScanProfile.QUICK, results.size(), true);
    }

    public ScanReport(String scanId, Instant startedAt, Instant finishedAt, DeviceInfo device,
                      String publicIp, String networkOrigin, Level level, List<ProbeResult> results,
                      List<NetworkCheckResult> networkChecks, List<String> newlyUnavailable, List<String> recovered,
                      List<String> recommendations, ScanProfile profile, int expectedTargets, boolean complete) {
        this(scanId, startedAt, finishedAt, device, publicIp, networkOrigin, level, results, networkChecks,
            newlyUnavailable, recovered, recommendations, profile, expectedTargets, complete, NetworkAssessment.legacy(level));
    }

    public ScanReport(String scanId, Instant startedAt, Instant finishedAt, DeviceInfo device,
                      String publicIp, String networkOrigin, Level level, List<ProbeResult> results,
                      List<NetworkCheckResult> networkChecks, List<String> newlyUnavailable, List<String> recovered,
                      List<String> recommendations, ScanProfile profile, int expectedTargets, boolean complete,
                      NetworkAssessment assessment) {
        this.scanId = scanId;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.device = device;
        this.publicIp = publicIp;
        this.networkOrigin = networkOrigin;
        this.level = level;
        this.results = Collections.unmodifiableList(results);
        this.networkChecks = Collections.unmodifiableList(networkChecks);
        this.newlyUnavailable = Collections.unmodifiableList(newlyUnavailable);
        this.recovered = Collections.unmodifiableList(recovered);
        this.recommendations = Collections.unmodifiableList(recommendations);
        this.profile = profile; this.expectedTargets = expectedTargets; this.complete = complete;
        this.assessment = assessment;
    }
}
