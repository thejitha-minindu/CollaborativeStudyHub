package com.studyhub.udp;

import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.common.ProtocolConstants;
import com.studyhub.concurrent.SafeRoomRegistry;
import com.studyhub.nio.NioChatServer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * [MEMBER 4: UDP Connectionless Networking]
 * Connectionless UDP presence and typing heartbeat listener on Port 9877.
 * Implements monotonic datagram sequence tracking to detect and drop out-of-order or stale packets.
 */
public class PresencePulseService {

    private final int port;
    private final SafeRoomRegistry registry;
    private final NioChatServer chatServer;
    private final Consumer<PacketLogEvent> telemetrySink;

    private final Map<String, Long> lastSequenceByUser = new ConcurrentHashMap<>();
    private DatagramSocket serverSocket;
    private Thread listenThread;
    private volatile boolean running = false;

    public PresencePulseService(SafeRoomRegistry registry, NioChatServer chatServer, Consumer<PacketLogEvent> telemetrySink) {
        this(NetworkConfig.UDP_PULSE_PORT, registry, chatServer, telemetrySink);
    }

    public PresencePulseService(int port, SafeRoomRegistry registry, NioChatServer chatServer, Consumer<PacketLogEvent> telemetrySink) {
        this.port = port;
        this.registry = registry;
        this.chatServer = chatServer;
        this.telemetrySink = telemetrySink != null ? telemetrySink : e -> {};
    }

    public void start() throws Exception {
        serverSocket = new DatagramSocket(null);
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;

        listenThread = new Thread(this::listenLoop, "UDP-Pulse-Listener");
        listenThread.setDaemon(true);
        listenThread.start();

        telemetrySink.accept(new PacketLogEvent(
            PacketLogEvent.Module.UDP_BEACON,
            PacketLogEvent.Direction.INTERNAL,
            "Presence Pulse Receiver Active",
            "Bound to UDP Port " + port
        ));
    }

    private void listenLoop() {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                packet.setLength(buffer.length);
                serverSocket.receive(packet);
                String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                handleIncomingPulse(raw);
            } catch (Exception e) {
                if (running) {
                    telemetrySink.accept(new PacketLogEvent(
                        PacketLogEvent.Module.UDP_BEACON,
                        PacketLogEvent.Direction.INTERNAL,
                        "Pulse Receive Error",
                        e.getMessage()
                    ));
                }
            }
        }
    }

    public void handleIncomingPulse(String raw) {
        String[] parts = raw.split(ProtocolConstants.DELIMITER_REGEX);
        // Format: PULSE|<seqNum>|<username>|<room>|<ONLINE|TYPING|IDLE>
        if (parts.length >= 5 && ProtocolConstants.UDP_PULSE_HEADER.equalsIgnoreCase(parts[0])) {
            try {
                long seqNum = Long.parseLong(parts[1]);
                String username = parts[2].trim();
                String room = parts[3].trim();
                String status = parts[4].trim();

                SafeRoomRegistry.UserSession session = registry.getSessionByUsername(username);
                if (session == null || !session.getCurrentRoom().equalsIgnoreCase(room)) return;
                String sequenceKey = session.getSessionToken();
                lastSequenceByUser.keySet().removeIf(token -> registry.getSessionByToken(token) == null);
                Long lastSeq = lastSequenceByUser.get(sequenceKey);
                if (lastSeq != null && seqNum <= lastSeq) {
                    // Out-of-Order or duplicate packet detected!
                    telemetrySink.accept(new PacketLogEvent(
                        PacketLogEvent.Module.UDP_BEACON,
                        PacketLogEvent.Direction.RX,
                        "DROPPED STALE UDP PULSE",
                        "User: " + username + " | Incoming Seq: " + seqNum + " <= Last Seq: " + lastSeq + " -> Discarded!"
                    ));
                    return;
                }

                // Monotonic progression: accept and update
                lastSequenceByUser.put(sequenceKey, seqNum);

                if (session != null) {
                    session.setPresenceStatus(status);
                    session.updatePulseTimestamp();
                }

                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.UDP_BEACON,
                    PacketLogEvent.Direction.RX,
                    "UDP Pulse Accepted",
                    "User: " + username + " | Room: " + room + " | Status: " + status + " | Seq: " + seqNum
                ));

                if (chatServer != null) {
                    chatServer.broadcastPresenceUpdate(room, username, status);
                }

            } catch (NumberFormatException ignored) {}
        }
    }

    /**
     * Static helper for client nodes to emit a UDP pulse datagram.
     */
    public static void sendClientPulse(String host, int port, long seqNum, String username, String room, String status) {
        try (DatagramSocket socket = new DatagramSocket()) {
            String payload = String.format("%s|%d|%s|%s|%s",
                ProtocolConstants.UDP_PULSE_HEADER,
                seqNum,
                username,
                room,
                status
            );
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            InetAddress addr = InetAddress.getByName(host);
            DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
            socket.send(packet);
        } catch (Exception ignored) {}
    }

    public void stop() {
        running = false;
        if (listenThread != null) {
            listenThread.interrupt();
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }
}
