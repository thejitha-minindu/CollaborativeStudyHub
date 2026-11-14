package com.studyhub.udp;

import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.common.ProtocolConstants;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * [MEMBER 4: UDP Connectionless Networking]
 * UDP listener capturing LAN discovery beacons on Port 9876.
 * Implements non-blocking polling using SocketTimeoutException handling.
 */
public class LanDiscoveryListener {

    public static class DiscoveredHub {
        public final String hubName;
        public final String hostAddress;
        public final int tlsPort;
        public final int nioPort;
        public final int filePort;
        public final int pulsePort;
        public final int activeUsers;
        public final long lastSeenTimestamp;

        public DiscoveredHub(String hubName, String hostAddress, int tlsPort, int nioPort, int filePort, int pulsePort, int activeUsers) {
            this.hubName = hubName;
            this.hostAddress = hostAddress;
            this.tlsPort = tlsPort;
            this.nioPort = nioPort;
            this.filePort = filePort;
            this.pulsePort = pulsePort;
            this.activeUsers = activeUsers;
            this.lastSeenTimestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("%s (%s) [TLS:%d, Users:%d]", hubName, hostAddress, tlsPort, activeUsers);
        }
    }

    private final Consumer<PacketLogEvent> telemetrySink;
    private final Consumer<List<DiscoveredHub>> discoveryCallback;
    private final Map<String, DiscoveredHub> hubsByKey = new ConcurrentHashMap<>();

    private DatagramSocket socket;
    private Thread listenerThread;
    private volatile boolean running = false;

    public LanDiscoveryListener(Consumer<PacketLogEvent> telemetrySink, Consumer<List<DiscoveredHub>> discoveryCallback) {
        this.telemetrySink = telemetrySink != null ? telemetrySink : e -> {};
        this.discoveryCallback = discoveryCallback != null ? discoveryCallback : h -> {};
    }

    public void start() {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(NetworkConfig.UDP_DISCOVERY_PORT));
            socket.setSoTimeout(NetworkConfig.UDP_DISCOVERY_TIMEOUT_MS);
            running = true;

            listenerThread = new Thread(this::listenLoop, "UDP-Discovery-Listener");
            listenerThread.setDaemon(true);
            listenerThread.start();

            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.UDP_BEACON,
                PacketLogEvent.Direction.INTERNAL,
                "Discovery Listener Active",
                "Listening on UDP Port " + NetworkConfig.UDP_DISCOVERY_PORT + " (Timeout: " + NetworkConfig.UDP_DISCOVERY_TIMEOUT_MS + "ms)"
            ));

        } catch (Exception e) {
            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.UDP_BEACON,
                PacketLogEvent.Direction.INTERNAL,
                "Discovery Bind Note",
                "Port 9876 in use or restricted: " + e.getMessage() + ". Manual IP input available."
            ));
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running && socket != null && !socket.isClosed()) {
            try {
                socket.receive(packet);
                String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                String senderIp = packet.getAddress().getHostAddress();

                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.UDP_BEACON,
                    PacketLogEvent.Direction.RX,
                    "UDP Beacon Received",
                    "From: " + senderIp + ":" + packet.getPort() + " | Payload: " + raw
                ));

                parseBeacon(raw, senderIp);

            } catch (SocketTimeoutException e) {
                // Graceful timeout: clean stale hubs and log inspection heartbeat
                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.UDP_BEACON,
                    PacketLogEvent.Direction.INTERNAL,
                    "SocketTimeoutException Handled",
                    "No datagram received within 4000ms timeout window - continuing listen loop cleanly."
                ));
                purgeStaleHubs();
            } catch (Exception e) {
                if (running) {
                    telemetrySink.accept(new PacketLogEvent(
                        PacketLogEvent.Module.UDP_BEACON,
                        PacketLogEvent.Direction.INTERNAL,
                        "Discovery Listener Exception",
                        e.getMessage()
                    ));
                }
            }
        }
    }

    private void parseBeacon(String raw, String senderIp) {
        String[] parts = raw.split(ProtocolConstants.DELIMITER_REGEX);
        // Format: BEACON|<seqNum>|<hubName>|<tlsPort>|<nioPort>|<filePort>|<pulsePort>|<activeUsers>
        if (parts.length >= 8 && ProtocolConstants.UDP_BEACON_HEADER.equals(parts[0])) {
            try {
                String hubName = parts[2];
                int tlsPort = Integer.parseInt(parts[3]);
                int nioPort = Integer.parseInt(parts[4]);
                int filePort = Integer.parseInt(parts[5]);
                int pulsePort = Integer.parseInt(parts[6]);
                int activeUsers = Integer.parseInt(parts[7]);

                String key = senderIp + ":" + tlsPort;
                DiscoveredHub hub = new DiscoveredHub(hubName, senderIp, tlsPort, nioPort, filePort, pulsePort, activeUsers);
                hubsByKey.put(key, hub);

                discoveryCallback.accept(new ArrayList<>(hubsByKey.values()));

            } catch (NumberFormatException ignored) {}
        }
    }

    private void purgeStaleHubs() {
        long now = System.currentTimeMillis();
        boolean removed = hubsByKey.entrySet().removeIf(entry -> now - entry.getValue().lastSeenTimestamp > 12000);
        if (removed) {
            discoveryCallback.accept(new ArrayList<>(hubsByKey.values()));
        }
    }

    public List<DiscoveredHub> getDiscoveredHubs() {
        return new ArrayList<>(hubsByKey.values());
    }

    public void stop() {
        running = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
