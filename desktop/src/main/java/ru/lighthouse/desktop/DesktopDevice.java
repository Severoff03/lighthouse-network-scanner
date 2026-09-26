package ru.lighthouse.desktop;

import ru.lighthouse.core.DeviceInfo;
import ru.lighthouse.core.NetworkCheckResult;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

final class DesktopDevice {
    static DeviceInfo collect() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) name = "PC";
        String localIp = "unavailable", mac = "unavailable", network = "unavailable";
        boolean vpn = false;
        java.util.List<NetworkCheckResult> observations = new java.util.ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
                fields.put("interface", ni.getName()); fields.put("addresses", Collections.list(ni.getInetAddresses()).toString());
                fields.put("mtu", String.valueOf(ni.getMTU())); fields.put("virtual", String.valueOf(ni.isVirtual()));
                observations.add(new NetworkCheckResult("pc_" + ni.getName(), ni.getDisplayName(), "Интерфейсы", NetworkCheckResult.Status.OK,
                    "Активный интерфейс; MTU " + ni.getMTU() + ".", fields));
                String low = (ni.getName() + " " + ni.getDisplayName()).toLowerCase(Locale.ROOT);
                if (low.matches(".*(tun|tap|vpn|wireguard|wg|tailscale|zerotier|zapret|goodbyedpi).*")) vpn = true;
                for (java.net.InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if ("unavailable".equals(localIp) && a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        localIp = a.getHostAddress();
                        network = ni.getDisplayName();
                        byte[] hardware = ni.getHardwareAddress();
                        if (hardware != null) mac = formatMac(hardware);
                        break;
                    }
                }
            }
        } catch (Exception ignored) { }
        String circumvention = vpn ? "Обнаружен виртуальный сетевой интерфейс" : "Не обнаружено (эвристика)";
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            observations.add(command("pc_wifi", "Wi-Fi Windows", "netsh", "wlan", "show", "interfaces"));
            observations.add(command("pc_ap", "Точки Wi-Fi Windows", "netsh", "wlan", "show", "networks", "mode=bssid"));
            observations.add(command("pc_routes", "Таблица маршрутов Windows", "route", "print"));
            observations.add(command("pc_ipconfig", "Адреса, шлюзы, DHCP и DNS Windows", "ipconfig", "/all"));
            observations.add(command("pc_proxy", "Системный WinHTTP-прокси", "netsh", "winhttp", "show", "proxy"));
            observations.addAll(DesktopNetworkActivity.capture("start"));
        }
        return new DeviceInfo(name, System.getProperty("os.name") + " " + System.getProperty("os.version"),
            localIp, mac, network, vpn, circumvention, "unknown", "unknown", observations);
    }

    private static NetworkCheckResult command(String id, String name, String... command) {
        Process process = null; java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            final java.io.InputStream input = process.getInputStream();
            Thread reader = new Thread(() -> {
                try { byte[] buffer = new byte[2048]; int n; while ((n = input.read(buffer)) != -1) {
                    synchronized (bytes) { if (bytes.size() < 32768) bytes.write(buffer, 0, Math.min(n, 32768 - bytes.size())); }
                } } catch (java.io.IOException ignored) { }
            }, "lighthouse-platform-output"); reader.setDaemon(true); reader.start();
            if (!process.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Command timeout");
            reader.join(500);
            synchronized (bytes) { fields.put("output", bytes.toString(java.nio.charset.Charset.forName("IBM866"))); }
            fields.put("exitCode", String.valueOf(process.exitValue()));
            return new NetworkCheckResult(id, name, "Сеть Windows", process.exitValue() == 0 ? NetworkCheckResult.Status.OK : NetworkCheckResult.Status.WARNING,
                "Системные сведения сохранены в подробностях лога. Для Wi-Fi Windows может требовать разрешение геолокации.", fields);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException();
        } catch (Exception error) {
            fields.put("unavailableReason", error.toString());
            return new NetworkCheckResult(id, name, "Сеть Windows", NetworkCheckResult.Status.WARNING, "Системные данные недоступны.", fields);
        } finally { if (process != null) process.destroyForcibly(); }
    }

    private static String formatMac(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte value : bytes) {
            if (b.length() > 0) b.append(':');
            b.append(String.format("%02X", value));
        }
        return b.toString();
    }
}
