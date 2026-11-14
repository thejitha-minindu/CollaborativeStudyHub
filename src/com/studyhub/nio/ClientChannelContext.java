package com.studyhub.nio;

import com.studyhub.common.NetworkConfig;
import com.studyhub.common.ProtocolConstants;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * [MEMBER 1: Java NIO Non-Blocking Multiplexing]
 * State context associated with each non-blocking SocketChannel registered with the Selector.
 * Holds per-client read accumulation buffers and thread-safe outbound write queues.
 */
public class ClientChannelContext {

    private final SocketChannel channel;
    private final ByteBuffer readBuffer;
    private final Queue<ByteBuffer> outboundQueue;

    private volatile String username;
    private volatile String sessionToken;
    private volatile String currentRoom;
    private volatile boolean authenticated = false;

    public ClientChannelContext(SocketChannel channel) {
        this.channel = channel;
        this.readBuffer = ByteBuffer.allocate(NetworkConfig.NIO_BUFFER_CAPACITY);
        this.outboundQueue = new ConcurrentLinkedQueue<>();
        this.currentRoom = ProtocolConstants.DEFAULT_ROOMS[0];
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }

    public Queue<ByteBuffer> getOutboundQueue() {
        return outboundQueue;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public String getCurrentRoom() {
        return currentRoom;
    }

    public void setCurrentRoom(String currentRoom) {
        this.currentRoom = currentRoom;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public void queueMessage(ByteBuffer buffer) {
        outboundQueue.offer(buffer);
    }
}
