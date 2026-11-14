package com.studyhub.nio;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [MEMBER 1: Java NIO Non-Blocking Multiplexing]
 * Length-prefixed framing engine for TCP streams.
 * Handles exact buffer state transitions: put(), flip(), compact(), and clear().
 */
public class MessageFramer {

    // Metrics exposed to the live Network Inspector
    private final AtomicLong totalFramesRead = new AtomicLong(0);
    private final AtomicLong totalFramesWritten = new AtomicLong(0);
    private final AtomicLong compactInvocations = new AtomicLong(0);
    private volatile int lastPosition = 0;
    private volatile int lastLimit = 0;
    private volatile int lastCapacity = 0;

    /**
     * Encodes a string payload into a length-prefixed ByteBuffer.
     * Wire format: [4-byte big-endian int length][UTF-8 payload bytes]
     * Invokes flip() so the buffer is ready for SocketChannel.write().
     */
    public ByteBuffer frameMessage(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + bytes.length);
        buffer.putInt(bytes.length);
        buffer.put(bytes);

        // Transition from WRITING into buffer to READING from buffer for socket write
        buffer.flip();

        totalFramesWritten.incrementAndGet();
        lastPosition = buffer.position();
        lastLimit = buffer.limit();
        lastCapacity = buffer.capacity();

        return buffer;
    }

    /**
     * Extracts complete frames from an inbound accumulator buffer.
     * Expects readBuffer in write-mode (as populated by SocketChannel.read()).
     * Transitions readBuffer via flip(), loops while remaining >= 4, extracts frames,
     * and finishes by invoking compact() so unread fragments remain at index 0.
     */
    public List<String> extractFrames(ByteBuffer readBuffer) {
        List<String> frames = new ArrayList<>();

        // Flip buffer from write-mode (after channel.read) to read-mode
        readBuffer.flip();

        while (readBuffer.remaining() >= 4) {
            readBuffer.mark();
            int frameLen = readBuffer.getInt();

            if (frameLen < 0 || frameLen > 10 * 1024 * 1024) {
                // Safeguard against stream corruption or protocol desync
                readBuffer.clear();
                break;
            }

            if (readBuffer.remaining() >= frameLen) {
                byte[] frameBytes = new byte[frameLen];
                readBuffer.get(frameBytes);
                frames.add(new String(frameBytes, StandardCharsets.UTF_8));
                totalFramesRead.incrementAndGet();
            } else {
                // Incomplete frame; rewind to before the 4-byte length prefix
                readBuffer.reset();
                break;
            }
        }

        // Compact buffer: moves any unread bytes to index 0 and sets position to remaining()
        readBuffer.compact();
        compactInvocations.incrementAndGet();

        lastPosition = readBuffer.position();
        lastLimit = readBuffer.limit();
        lastCapacity = readBuffer.capacity();

        return frames;
    }

    public long getTotalFramesRead() {
        return totalFramesRead.get();
    }

    public long getTotalFramesWritten() {
        return totalFramesWritten.get();
    }

    public long getCompactInvocations() {
        return compactInvocations.get();
    }

    public int getLastPosition() {
        return lastPosition;
    }

    public int getLastLimit() {
        return lastLimit;
    }

    public int getLastCapacity() {
        return lastCapacity;
    }

    public String getBufferInspectionSummary() {
        return String.format("Pos=%d, Limit=%d, Cap=%d | ReadFrames=%d, WrittenFrames=%d, Compacts=%d",
            lastPosition, lastLimit, lastCapacity, totalFramesRead.get(), totalFramesWritten.get(), compactInvocations.get());
    }
}
