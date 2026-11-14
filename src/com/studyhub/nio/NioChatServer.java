package com.studyhub.nio;

import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.common.ProtocolConstants;
import com.studyhub.concurrent.ResourceConcurrentEngine;
import com.studyhub.concurrent.SafeRoomRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [MEMBER 1: Java NIO Non-Blocking Multiplexing]
 * Non-blocking multiplexed chat and event coordination server.
 * Operates a single-threaded Selector event loop on Port 9000 for OP_ACCEPT, OP_READ, and OP_WRITE.
 */
public class NioChatServer {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w\\-\\.\\~:/?#\\[\\]@!$&'()*+,;=%]+", Pattern.CASE_INSENSITIVE);

    private final int port;
    private final SafeRoomRegistry registry;
    private final ResourceConcurrentEngine concurrentEngine;
    private final Consumer<PacketLogEvent> telemetrySink;
    private final MessageFramer framer = new MessageFramer();

    private final Map<String, ClientChannelContext> contextsByUsername = new ConcurrentHashMap<>();
    private final Set<ClientChannelContext> allContexts = ConcurrentHashMap.newKeySet();

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private Thread eventLoopThread;
    private volatile boolean running = false;

    public NioChatServer(SafeRoomRegistry registry, ResourceConcurrentEngine concurrentEngine, Consumer<PacketLogEvent> telemetrySink) {
        this(NetworkConfig.NIO_CHAT_PORT, registry, concurrentEngine, telemetrySink);
    }

    public NioChatServer(int port, SafeRoomRegistry registry, ResourceConcurrentEngine concurrentEngine, Consumer<PacketLogEvent> telemetrySink) {
        this.port = port;
        this.registry = registry;
        this.concurrentEngine = concurrentEngine;
        this.telemetrySink = telemetrySink != null ? telemetrySink : e -> {};
    }

    public MessageFramer getFramer() {
        return framer;
    }

    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;
        eventLoopThread = new Thread(this::runEventLoop, "NIO-Selector-EventLoop");
        eventLoopThread.start();

        telemetrySink.accept(new PacketLogEvent(
            PacketLogEvent.Module.NIO_SELECTOR,
            PacketLogEvent.Direction.INTERNAL,
            "NIO Selector Started",
            "Single-threaded event loop bound to Port " + port + " (OP_ACCEPT registered)"
        ));
    }

    private void runEventLoop() {
        while (running) {
            try {
                int readyChannels = selector.select(500);
                if (!running) break;
                if (readyChannels == 0) continue;

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    telemetrySink.accept(new PacketLogEvent(
                        PacketLogEvent.Module.NIO_SELECTOR,
                        PacketLogEvent.Direction.INTERNAL,
                        "Selector Loop Warning",
                        e.getMessage()
                    ));
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) {
        try {
            ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
            SocketChannel clientChannel = ssc.accept();
            if (clientChannel != null) {
                clientChannel.configureBlocking(false);
                ClientChannelContext ctx = new ClientChannelContext(clientChannel);
                clientChannel.register(selector, SelectionKey.OP_READ, ctx);
                allContexts.add(ctx);

                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.NIO_SELECTOR,
                    PacketLogEvent.Direction.RX,
                    "OP_ACCEPT Handled",
                    "Accepted client: " + clientChannel.getRemoteAddress(),
                    framer.getBufferInspectionSummary()
                ));
            }
        } catch (IOException e) {
            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.NIO_SELECTOR,
                PacketLogEvent.Direction.INTERNAL,
                "Accept Error",
                e.getMessage()
            ));
        }
    }

    private void handleRead(SelectionKey key) {
        ClientChannelContext ctx = (ClientChannelContext) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer readBuffer = ctx.getReadBuffer();

        try {
            int bytesRead = channel.read(readBuffer);
            if (bytesRead == -1) {
                disconnectClient(key, ctx);
                return;
            }

            if (bytesRead > 0) {
                List<String> frames = framer.extractFrames(readBuffer);
                for (String frame : frames) {
                    processInboundFrame(ctx, frame);
                }

                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.NIO_SELECTOR,
                    PacketLogEvent.Direction.RX,
                    "OP_READ Handled",
                    "Read " + bytesRead + " bytes from " + (ctx.getUsername() != null ? ctx.getUsername() : channel.getRemoteAddress()),
                    framer.getBufferInspectionSummary()
                ));
            }
        } catch (IOException e) {
            disconnectClient(key, ctx);
        }
    }

    private void handleWrite(SelectionKey key) {
        ClientChannelContext ctx = (ClientChannelContext) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();

        try {
            ByteBuffer buffer;
            while ((buffer = ctx.getOutboundQueue().peek()) != null) {
                channel.write(buffer);
                if (buffer.hasRemaining()) {
                    // Channel socket send buffer is full; keep OP_WRITE set and return to selector
                    return;
                }
                ctx.getOutboundQueue().poll();
            }

            // All queued buffers sent; clear OP_WRITE interest to avoid busy-spin
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);

        } catch (IOException e) {
            disconnectClient(key, ctx);
        }
    }

    private void processInboundFrame(ClientChannelContext ctx, String frame) {
        String[] parts = frame.split(ProtocolConstants.DELIMITER_REGEX);
        if (parts.length == 0) return;

        String cmd = parts[0];

        switch (cmd) {
            case ProtocolConstants.CMD_HELLO:
                if (parts.length >= 3) {
                    String token = parts[1].trim();
                    String username = parts[2].trim();
                    SafeRoomRegistry.UserSession session = registry.getSessionByToken(token);

                    if (session != null && session.getUsername().equalsIgnoreCase(username)) {
                        ctx.setUsername(session.getUsername());
                        ctx.setSessionToken(token);
                        ctx.setCurrentRoom(session.getCurrentRoom());
                        ctx.setAuthenticated(true);

                        contextsByUsername.put(session.getUsername().toLowerCase(), ctx);

                        sendDirectMessage(ctx, ProtocolConstants.EVT_SYSTEM + ProtocolConstants.DELIMITER +
                            "Welcome to StudyHub, " + session.getUsername() + "! Role: " + session.getRole());

                        broadcastUserList(session.getCurrentRoom());
                    } else {
                        sendDirectMessage(ctx, ProtocolConstants.EVT_SYSTEM + ProtocolConstants.DELIMITER + "Auth failed or token expired.");
                    }
                }
                break;

            case ProtocolConstants.CMD_JOIN:
                if (parts.length >= 2 && ctx.isAuthenticated()) {
                    String oldRoom = ctx.getCurrentRoom();
                    String newRoom = parts[1].trim();
                    ctx.setCurrentRoom(newRoom);
                    registry.switchRoom(ctx.getUsername(), newRoom);

                    broadcastUserList(oldRoom);
                    broadcastUserList(newRoom);

                    sendDirectMessage(ctx, ProtocolConstants.EVT_SYSTEM + ProtocolConstants.DELIMITER + "Joined room " + newRoom);
                }
                break;

            case ProtocolConstants.CMD_MSG:
                if (parts.length >= 4 && ctx.isAuthenticated()) {
                    String room = parts[1].trim();
                    String encFlag = parts[2].trim();
                    String content = parts[3];
                    String time = LocalTime.now().format(TIME_FMT);

                    String broadcastPayload = ProtocolConstants.EVT_CHAT + ProtocolConstants.DELIMITER +
                        room + ProtocolConstants.DELIMITER +
                        ctx.getUsername() + ProtocolConstants.DELIMITER +
                        encFlag + ProtocolConstants.DELIMITER +
                        content + ProtocolConstants.DELIMITER +
                        time;

                    broadcastToRoom(room, broadcastPayload);

                    // If PLAIN text and contains URL, asynchronously trigger LinkPreviewCallable
                    if (ProtocolConstants.ENC_PLAIN.equalsIgnoreCase(encFlag)) {
                        Matcher matcher = URL_PATTERN.matcher(content);
                        if (matcher.find()) {
                            String url = matcher.group();
                            concurrentEngine.execute(() -> {
                                try {
                                    com.studyhub.concurrent.LinkPreview preview = concurrentEngine.submitLinkPreview(url).get();
                                    String linkPayload = ProtocolConstants.EVT_LINK_CARD + ProtocolConstants.DELIMITER +
                                        room + ProtocolConstants.DELIMITER +
                                        preview.getRawUrl() + ProtocolConstants.DELIMITER +
                                        preview.getHostIp() + ProtocolConstants.DELIMITER +
                                        preview.getStatusCode() + ProtocolConstants.DELIMITER +
                                        preview.getTitle();
                                    broadcastToRoom(room, linkPayload);
                                } catch (Exception ignored) {}
                            });
                        }
                    }
                }
                break;

            case ProtocolConstants.CMD_DM:
                if (parts.length >= 4 && ctx.isAuthenticated()) {
                    String targetUser = parts[1].trim();
                    String encFlag = parts[2].trim();
                    String content = parts[3];
                    String time = LocalTime.now().format(TIME_FMT);

                    ClientChannelContext targetCtx = contextsByUsername.get(targetUser.toLowerCase());
                    if (targetCtx != null) {
                        String dmPayload = ProtocolConstants.EVT_CHAT + ProtocolConstants.DELIMITER +
                            "DM:" + ctx.getUsername() + ProtocolConstants.DELIMITER +
                            ctx.getUsername() + ProtocolConstants.DELIMITER +
                            encFlag + ProtocolConstants.DELIMITER +
                            content + ProtocolConstants.DELIMITER +
                            time;
                        sendDirectMessage(targetCtx, dmPayload);
                        sendDirectMessage(ctx, dmPayload);
                    } else {
                        sendDirectMessage(ctx, ProtocolConstants.EVT_SYSTEM + ProtocolConstants.DELIMITER + "User " + targetUser + " is offline.");
                    }
                }
                break;

            default:
                break;
        }
    }

    public void broadcastToRoom(String room, String payload) {
        ByteBuffer buffer = framer.frameMessage(payload);
        for (ClientChannelContext ctx : allContexts) {
            if (ctx.isAuthenticated() && room.equalsIgnoreCase(ctx.getCurrentRoom())) {
                enqueueBuffer(ctx, buffer.duplicate());
            }
        }
    }

    public void broadcastUserList(String room) {
        List<String> members = registry.getRoomMembers(room);
        StringBuilder sb = new StringBuilder();
        sb.append(ProtocolConstants.EVT_USER_LIST).append(ProtocolConstants.DELIMITER).append(room);
        for (String m : members) {
            SafeRoomRegistry.UserSession s = registry.getSessionByUsername(m);
            String status = (s != null) ? s.getPresenceStatus() : ProtocolConstants.STATUS_ONLINE;
            sb.append(ProtocolConstants.DELIMITER).append(m).append(":").append(status);
        }
        broadcastToRoom(room, sb.toString());
    }

    public void broadcastPresenceUpdate(String room, String username, String status) {
        String payload = ProtocolConstants.EVT_PRESENCE_UPDATE + ProtocolConstants.DELIMITER +
            room + ProtocolConstants.DELIMITER +
            username + ProtocolConstants.DELIMITER +
            status;
        broadcastToRoom(room, payload);
    }

    public void broadcastFileAdded(String room, String filename, long size, String uploader, String sha256) {
        String payload = ProtocolConstants.EVT_FILE_ADDED + ProtocolConstants.DELIMITER +
            room + ProtocolConstants.DELIMITER +
            filename + ProtocolConstants.DELIMITER +
            size + ProtocolConstants.DELIMITER +
            uploader + ProtocolConstants.DELIMITER +
            sha256;
        broadcastToRoom(room, payload);
    }

    public void sendDirectMessage(ClientChannelContext ctx, String payload) {
        ByteBuffer buffer = framer.frameMessage(payload);
        enqueueBuffer(ctx, buffer);
    }

    private void enqueueBuffer(ClientChannelContext ctx, ByteBuffer buffer) {
        ctx.queueMessage(buffer);
        SocketChannel ch = ctx.getChannel();
        if (ch != null && ch.isOpen() && selector != null && selector.isOpen()) {
            SelectionKey key = ch.keyFor(selector);
            if (key != null && key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                selector.wakeup();
            }
        }
    }

    private void disconnectClient(SelectionKey key, ClientChannelContext ctx) {
        try {
            if (key != null) key.cancel();
            if (ctx.getChannel() != null) ctx.getChannel().close();
        } catch (Exception ignored) {}

        allContexts.remove(ctx);
        if (ctx.getUsername() != null) {
            contextsByUsername.remove(ctx.getUsername().toLowerCase());
            String room = ctx.getCurrentRoom();
            registry.removeUserFromAllRooms(ctx.getUsername());
            if (room != null) {
                broadcastUserList(room);
            }
        }

        telemetrySink.accept(new PacketLogEvent(
            PacketLogEvent.Module.NIO_SELECTOR,
            PacketLogEvent.Direction.INTERNAL,
            "Client Disconnected",
            "Closed channel for " + (ctx.getUsername() != null ? ctx.getUsername() : "anonymous")
        ));
    }

    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
        for (ClientChannelContext ctx : allContexts) {
            try {
                if (ctx.getChannel() != null) ctx.getChannel().close();
            } catch (Exception ignored) {}
        }
        allContexts.clear();
        contextsByUsername.clear();

        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
            if (selector != null && selector.isOpen()) {
                selector.close();
            }
        } catch (Exception ignored) {}
    }
}
