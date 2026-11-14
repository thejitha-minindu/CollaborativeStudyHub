package com.studyhub.concurrent;

import com.studyhub.common.NetworkConfig;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [MEMBER 5: Multithreading & URL/HTTP Networking]
 * Asynchronous worker resolving URL syntax, DNS InetAddress, HTTP connection,
 * and parsing HTML titles for live chat link preview cards.
 */
public class LinkPreviewCallable implements Callable<LinkPreview> {

    private static final Pattern TITLE_PATTERN = Pattern.compile("<title\\b[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private final String rawUrl;

    public LinkPreviewCallable(String rawUrl) {
        this.rawUrl = rawUrl != null ? rawUrl.trim() : "";
    }

    @Override
    public LinkPreview call() {
        long startTime = System.currentTimeMillis();
        String hostIp = "UNKNOWN";

        // Step 1: URI syntax validation
        URI uri;
        try {
            uri = new URI(rawUrl);
            if (uri.getHost() == null) {
                return LinkPreview.fallback(rawUrl, hostIp, "Malformed URI / Missing Host");
            }
        } catch (URISyntaxException e) {
            return LinkPreview.fallback(rawUrl, hostIp, "URI Syntax Error: " + e.getMessage());
        }

        // Step 2: URL conversion
        URL url;
        try {
            url = uri.toURL();
        } catch (MalformedURLException e) {
            return LinkPreview.fallback(rawUrl, hostIp, "Malformed URL: " + e.getMessage());
        }

        // Step 3: DNS resolution via InetAddress
        try {
            InetAddress address = InetAddress.getByName(url.getHost());
            hostIp = address.getHostAddress();
        } catch (UnknownHostException e) {
            return LinkPreview.fallback(rawUrl, hostIp, "DNS Unknown Host");
        } catch (Exception e) {
            return LinkPreview.fallback(rawUrl, hostIp, "DNS Lookup Failed: " + e.getMessage());
        }

        // Step 4: HTTP connection
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(NetworkConfig.HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(NetworkConfig.HTTP_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "StudyHub-LinkPreview/1.0 (Java JDK)");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");

            int statusCode = connection.getResponseCode();
            long latencyMs = System.currentTimeMillis() - startTime;

            String title = extractTitle(connection);
            if (title == null || title.isEmpty()) {
                title = uri.getHost() + " (HTTP " + statusCode + ")";
            }

            return new LinkPreview(rawUrl, hostIp, statusCode, title, latencyMs, statusCode >= 200 && statusCode < 400);

        } catch (java.net.SocketTimeoutException e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            return new LinkPreview(rawUrl, hostIp, 0, "OFFLINE / TIMEOUT (" + latencyMs + "ms)", latencyMs, false);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            return new LinkPreview(rawUrl, hostIp, 0, "OFFLINE (" + e.getClass().getSimpleName() + ")", latencyMs, false);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String extractTitle(HttpURLConnection connection) {
        try {
            InputStream stream = connection.getInputStream();
            if (stream == null) return null;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                int linesRead = 0;
                while ((line = reader.readLine()) != null && linesRead < 50) {
                    sb.append(line).append(" ");
                    linesRead++;
                    if (line.toLowerCase().contains("</title>")) {
                        break;
                    }
                }
                Matcher matcher = TITLE_PATTERN.matcher(sb.toString());
                if (matcher.find()) {
                    return matcher.group(1).trim().replaceAll("\\s+", " ");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
