package ru.lighthouse.core;

public final class DeviceInfo {
    public final String deviceName;
    public final String os;
    public final String localIp;
    public final String macAddress;
    public final String networkName;
    public final boolean vpnDetected;
    public final String circumvention;
    public final String networkValidation;
    public final String metered;
    public final java.util.List<NetworkCheckResult> observations;

    public DeviceInfo(String deviceName, String os, String localIp, String macAddress,
                      String networkName, boolean vpnDetected, String circumvention) {
        this(deviceName, os, localIp, macAddress, networkName, vpnDetected, circumvention,
            "unknown", "unknown");
    }

    public DeviceInfo(String deviceName, String os, String localIp, String macAddress,
                      String networkName, boolean vpnDetected, String circumvention,
                      String networkValidation, String metered) {
        this(deviceName, os, localIp, macAddress, networkName, vpnDetected, circumvention,
            networkValidation, metered, java.util.Collections.emptyList());
    }

    public DeviceInfo(String deviceName, String os, String localIp, String macAddress,
                      String networkName, boolean vpnDetected, String circumvention,
                      String networkValidation, String metered, java.util.List<NetworkCheckResult> observations) {
        this.deviceName = deviceName;
        this.os = os;
        this.localIp = localIp;
        this.macAddress = macAddress;
        this.networkName = networkName;
        this.vpnDetected = vpnDetected;
        this.circumvention = circumvention;
        this.networkValidation = networkValidation;
        this.metered = metered;
        this.observations = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(observations));
    }
}
