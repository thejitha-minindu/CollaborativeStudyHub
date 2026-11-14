package com.studyhub.udp;

import com.studyhub.common.NetworkConfig;
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

                // Wire format: BEACON|<seqNum>|<hubName>|<tlsPort>|<nioPort>|<filePort>|<pulsePort>|<activeUsers>
                String payload = String.format("%s|%d|%s|%d|%d|%d|%d|%d",
                    ProtocolConstants.UDP_BEACON_HEADER,
                    seq,
                    hubName,
                    NetworkConfig.TLS_AUTH_PORT,
                    NetworkConfig.NIO_CHAT_PORT,
                    NetworkConfig.FILE_TRANSFER_PORT,
                    NetworkConfig.UDP_PULSE_PORT,
                    activeUsers
                );

                byte[] data = payload.getBytes(StandardCharsets.UTF_8);

                // 1. Send to 255.255.255.255 LAN Broadcast
                try {
                    InetAddress broadcastAddr = InetAddress.getByName(NetworkConfig.BROADCAST_IP);
                    DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddr, NetworkConfig.UDP_DISCOVERY_PORT);
                    socket.send(packet);
                } catch (Exception ignored) {}

                // 2. Send to 127.0.0.1 Localhost (ensures guaranteed single-machine lab evaluation)
                try {
                    InetAddress localAddr = InetAddress.getByName(NetworkConfig.LOCALHOST_IP);
                    DatagramPacket localPacket = new DatagramPacket(data, data.length, localAddr, NetworkConfig.UDP_DISCOVERY_PORT);
                    socket.send(localPacket);
                } catch (Exception ignored) {}

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
