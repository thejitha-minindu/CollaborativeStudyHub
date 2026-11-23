package com.studyhub.common;

import java.net.*;
import java.util.*;

/** Active physical IPv4 interfaces, refreshed so Wi-Fi changes do not require a restart. */
public final class LanAddresses {
    private LanAddresses() {}
    public record Adapter(Inet4Address address, InetAddress broadcast, String name) {}

    public static List<Adapter> active() {
        List<Adapter> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return List.of();
            for (NetworkInterface nic : Collections.list(interfaces)) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                for (InterfaceAddress entry : nic.getInterfaceAddresses()) {
                    if (entry.getAddress() instanceof Inet4Address ip && !ip.isLoopbackAddress()
                            && !ip.isLinkLocalAddress()) {
                        result.add(new Adapter(ip, entry.getBroadcast(), nic.getDisplayName()));
                    }
                }
            }
        } catch (SocketException ignored) { /* Manual connection remains available. */ }
        result.sort(Comparator.comparingInt(LanAddresses::rank).thenComparing(a -> a.address().getHostAddress()));
        return List.copyOf(result);
    }

    private static int rank(Adapter a) {
        String name = a.name().toLowerCase(Locale.ROOT);
        boolean wifi = name.contains("wi-fi") || name.contains("wifi") || name.contains("wireless") || name.contains("wlan");
        return (a.address().isSiteLocalAddress() ? 0 : 4) + (wifi ? 0 : 1);
    }

    public static String preferredAddress() {
        List<Adapter> interfaces = active();
        return interfaces.isEmpty() ? NetworkConfig.LOCALHOST_IP : interfaces.get(0).address().getHostAddress();
    }
}
