package ru.lighthouse.core;

import java.util.ArrayList;
import java.util.List;

/** Public, untrusted community entries bundled for diagnostics and manual copying. */
public final class DefaultConnections {
    private DefaultConnections() { }

    public static List<NamedConfiguration> telegramProxies() {
        return new ArrayList<>(List.of(
            new NamedConfiguration("Публичный MTProto · 1", "https://t.me/proxy?server=megaconnect.click&port=443&secret=ee283d3bf19b80aa8a9ca06a2244c01c8a617669746f2e7275"),
            new NamedConfiguration("Публичный MTProto · 2", "https://t.me/proxy?server=45.61.180.171&port=443&secret=dd9d50345eb12d715708862cae82def8e0"),
            new NamedConfiguration("Публичный MTProto · 3", "https://t.me/proxy?server=api.server2-5mk.info&port=443&secret=ee1603010200010001fc030386e24c3add6d656469612e737465616d706f77657265642e636f6d"),
            new NamedConfiguration("Публичный MTProto · 4", "https://t.me/proxy?server=www.telbet.app&port=443&secret=ee3073e1ec5c594e267e7636f8f5dc73f12d6d656469612e737465616d706f77657265642e636f6d"),
            new NamedConfiguration("Публичный MTProto · 5", "https://t.me/proxy?server=65.109.83.141&port=443&secret=eeddffffffc5a1168b2ff3eba31cbfffff7765622e62616c652e6169"),
            new NamedConfiguration("Публичный MTProto · 6", "https://t.me/proxy?server=ir.speed.finecooking.info&port=443&secret=ddf0eeb0bd9adc4fd4a93994ee3b2a216b")
        ));
    }

    public static List<NamedConfiguration> vpnSubscriptions() {
        return new ArrayList<>(List.of(
            new NamedConfiguration("Публичная VPN-подписка · проверенные", "https://raw.githubusercontent.com/aviamastersgh/vpn-free-russia/main/verified_configs.txt"),
            new NamedConfiguration("Публичная VPN-подписка · белые списки", "https://raw.githubusercontent.com/aviamastersgh/vpn-free-russia/main/ru_configs.txt"),
            new NamedConfiguration("Публичная VPN-подписка · MeraVPN", "https://github.com/JustIwakura/MeraServers/raw/refs/heads/main/all.txt"),
            new NamedConfiguration("Публичный OpenVPN-каталог · VPN Gate", "https://www.vpngate.net/api/iphone/")
        ));
    }
}
