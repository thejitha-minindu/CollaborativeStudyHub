package com.studyhub.udp;

import com.studyhub.common.NetworkConfig;
import com.studyhub.common.LanAddresses;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.common.ProtocolConstants;
import com.studyhub.concurrent.SafeRoomRegistry;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * [MEMBER 4: UDP Connectionless Networking]
 * Periodic UDP broadcast beacon announcing active Study Hub server presence on Port 9876.
 * Emits beacons to 255.255.255.255 and 127.0.0.1 with monotonic sequence numbers.
 */
public class LanDiscoveryBroadcaster {

    private final String hubName;
    private final SafeRoomRegistry registry;
    private final Consumer<PacketLogEvent> telemetrySink;
    private final AtomicLong sequenceNumber = new AtomicLong(1);

    private DatagramSocket socket;
    private Thread broadcastThread;
    private volatile boolean running = false;

    public LanDiscoveryBroadcaster(String hubName, SafeRoomRegistry registry, Consumer<PacketLogEvent> telemetrySink) {
        this.hubName = hubName != null ? hubName : "StudyHub-Main";
        this.registry = registry;
        this.telemetrySink = telemetrySink != null ? telemetrySink : e -> {};
    }

    public void start() throws Exception {
        socket = new DatagramSocket();
        socket.setBroadcast(true);
        running = true;

        broadcastThread = new Thread(this::broadcastLoop, "UDP-Beacon-Broadcaster");
        broadcastThread.setDaemon(true);
        broadcastThread.start();

        telemetrySink.accept(new PacketLogEvent(
            PacketLogEvent.Module.UDP_BEACON,
            PacketLogEvent.Direction.INTERNAL,
            "UDP Beacon Broadcaster Started",
            "Targeting Port " + NetworkConfig.UDP_DISCOVERY_PORT + " every " + NetworkConfig.UDP_BEACON_INTERVAL_MS + "ms"
        ));
    }

    private void broadcastLoop() {
        while (running && !socket.isClosed()) {
            try {
                long seq = sequenceNumber.getAndIncrement();
                int activeUsers = (registry != null) ? registry.getActiveUserCount() : 0;

                // Extend the original eight fields with the advertised IPv4 (old clients remain compatible).
                String preferred = LanAddresses.preferredAddress();
                for (LanAddresses.Adapter adapter : LanAddresses.active()) {
                    if (adapter.broadcast() == null) continue;
                    // Bind the sender to this adapter so multi-homed hosts announce a reachable source.
                    try (DatagramSocket directed = new DatagramSocket(new java.net.InetSocketAddress(adapter.address(), 0))) {
                        directed.setBroadcast(true);
                        sendBeacon(directed, adapter.broadcast(), seq, activeUsers, adapter.address().getHostAddress());
                    } catch (Exception ignored) { /* Continue with the remaining adapters. */ }
                }
                String payload = beacon(seq, activeUsers, preferred);
                for (String destination : new String[]{NetworkConfig.BROADCAST_IP, NetworkConfig.LOCALHOST_IP}) {
                    try {
                        sendBeacon(socket, InetAddress.getByName(destination), seq, activeUsers, preferred);
                    } catch (Exception ignored) { /* A blocked broadcast must not prevent local discovery. */ }
                }

                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.UDP_BEACON,
                    PacketLogEvent.Direction.TX,
                    "UDP Discovery Beacon #" + seq,
                    payload
                ));

                Thread.sleep(NetworkConfig.UDP_BEACON_INTERVAL_MS);

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (running) {
                    telemetrySink.accept(new PacketLogEvent(
                        PacketLogEvent.Module.UDP_BEACON,
                        PacketLogEvent.Direction.INTERNAL,
                        "Broadcast Warning",
                        e.getMessage()
                    ));
                }
            }
        }
    }

    private String beacon(long seq, int users, String ip) {
        return String.format("%s|%d|%s|%d|%d|%d|%d|%d|%s", ProtocolConstants.UDP_BEACON_HEADER,
            seq, hubName.replace('|', ' ').replace('\n', ' ').replace('\r', ' '), NetworkConfig.TLS_AUTH_PORT,
            NetworkConfig.NIO_CHAT_PORT, NetworkConfig.FILE_TRANSFER_PORT, NetworkConfig.UDP_PULSE_PORT, users, ip);
    }

    private void sendBeacon(DatagramSocket sender, InetAddress destination, long seq, int users, String ip) throws Exception {
        byte[] data = beacon(seq, users, ip).getBytes(StandardCharsets.UTF_8);
        sender.send(new DatagramPacket(data, data.length, destination, NetworkConfig.UDP_DISCOVERY_PORT));
    }

    public void stop() {
        running = false;
        if (broadcastThread != null) {
            broadcastThread.interrupt();
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
