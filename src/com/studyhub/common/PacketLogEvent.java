package com.studyhub.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Telemetry event model captured across all networking modules and visualized
 * in the real-time Network Inspector Panel.
 */
public class PacketLogEvent {

    public enum Module {
        NIO_SELECTOR,
        TCP_FILECHANNEL,
        TLS_SECURITY,
        UDP_BEACON,
        CONCURRENT_HTTP
    }

    public enum Direction {
        TX,
        RX,
        INTERNAL
    }

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String timestamp;
    private final Module module;
    private final Direction direction;
    private final String summary;
    private final String details;
    private final String bufferHexState;

    public PacketLogEvent(Module module, Direction direction, String summary, String details) {
        this(module, direction, summary, details, "");
    }

    public PacketLogEvent(Module module, Direction direction, String summary, String details, String bufferHexState) {
        this.timestamp = LocalTime.now().format(TIME_FORMATTER);
        this.module = module;
        this.direction = direction;
        this.summary = summary;
        this.details = details;
        this.bufferHexState = bufferHexState != null ? bufferHexState : "";
    }

    public String getTimestamp() {
        return timestamp;
    }

    public Module getModule() {
        return module;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetails() {
        return details;
    }

    public String getBufferHexState() {
        return bufferHexState;
    }

    @Override
    public String toString() {
        return String.format("[%s] [%s] [%s] %s - %s %s",
            timestamp, module, direction, summary, details,
            bufferHexState.isEmpty() ? "" : "(Hex: " + bufferHexState + ")");
    }
}
