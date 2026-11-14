package com.studyhub.concurrent;

/**
 * [MEMBER 5: Multithreading & URL/HTTP Networking]
 * Data model representing the result of an asynchronous HTTP link preview lookup.
 */
public class LinkPreview {

    private final String rawUrl;
    private final String hostIp;
    private final int statusCode;
    private final String title;
    private final long latencyMs;
    private final boolean success;

    public LinkPreview(String rawUrl, String hostIp, int statusCode, String title, long latencyMs, boolean success) {
        this.rawUrl = rawUrl;
        this.hostIp = hostIp != null ? hostIp : "UNKNOWN";
        this.statusCode = statusCode;
        this.title = title != null && !title.isEmpty() ? title : "No Title Available";
        this.latencyMs = latencyMs;
        this.success = success;
    }

    public static LinkPreview fallback(String rawUrl, String hostIp, String errorReason) {
        return new LinkPreview(rawUrl, hostIp, 0, "[Preview Unavailable: " + errorReason + "]", 0, false);
    }

    public String getRawUrl() {
        return rawUrl;
    }

    public String getHostIp() {
        return hostIp;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getTitle() {
        return title;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public boolean isSuccess() {
        return success;
    }

    @Override
    public String toString() {
        return String.format("LinkPreview[URL=%s, IP=%s, Status=%d, Latency=%dms, Title='%s']",
            rawUrl, hostIp, statusCode, latencyMs, title);
    }
}
