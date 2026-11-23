package com.studyhub.transfer;

import com.studyhub.common.DataPersistenceManager;
import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.common.ProtocolConstants;
import com.studyhub.security.CryptoIntegrityService;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.function.Consumer;

/**
 * [MEMBER 2: TCP Sockets & NIO FileChannel]
 * TCP File Transfer client for uploading and downloading study resources.
 * Performs end-to-end SHA-256 cryptographic hashing and FileChannel buffer streaming,
 * with live byte-progress reporting and duplicate-safe local downloads.
 */
public class FileTransferClient {

    /** Live transfer progress sink (bytes transferred / total bytes). */
    public interface ProgressListener {
        void onProgress(long transferred, long totalBytes);
    }

    public static class TransferResult {
        public final boolean success;
        public final boolean tampered;
        public final String expectedSha;
        public final String actualSha;
        public final String message;
        public final File savedFile;
        public final String storedFilename;
        public final String timestamp;

        public TransferResult(boolean success, boolean tampered, String expectedSha, String actualSha,
                              String message, File savedFile) {
            this(success, tampered, expectedSha, actualSha, message, savedFile, "", "");
        }

        public TransferResult(boolean success, boolean tampered, String expectedSha, String actualSha,
                              String message, File savedFile, String storedFilename, String timestamp) {
            this.success = success;
            this.tampered = tampered;
            this.expectedSha = expectedSha != null ? expectedSha : "";
            this.actualSha = actualSha != null ? actualSha : "";
            this.message = message != null ? message : "";
            this.savedFile = savedFile;
            this.storedFilename = storedFilename != null ? storedFilename : "";
            this.timestamp = timestamp != null ? timestamp : "";
        }

        public static TransferResult fail(String msg) {
            return new TransferResult(false, false, "", "", msg, null);
        }
    }

    private final String host;
    private final int port;
    private final String sessionToken;
    private final Consumer<PacketLogEvent> telemetrySink;

    public FileTransferClient(String host, int port, String sessionToken, Consumer<PacketLogEvent> telemetrySink) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Select a workspace address first");
        this.host = host.trim();
        this.port = port > 0 ? port : NetworkConfig.FILE_TRANSFER_PORT;
        this.sessionToken = sessionToken != null ? sessionToken : "";
        this.telemetrySink = telemetrySink != null ? telemetrySink : e -> {};
    }

    /** Directory that keeps this user's downloads tidy: {@code downloads/<username>/}. */
    public static File getUserDownloadDir(String username) {
        String safeUser = (username == null || username.trim().isEmpty()) ? "guest" : username.trim();
        safeUser = safeUser.replaceAll("[^A-Za-z0-9._\\-]", "_");
        File dir = new File(NetworkConfig.DOWNLOADS_DIR, safeUser);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    // ==================================================================
    // UPLOAD
    // ==================================================================

    public TransferResult uploadFile(String room, File file, boolean simulateTamper) {
        return uploadFile(room, file, simulateTamper, null);
    }

    /**
     * Uploads a file to the server using TCP Socket + NIO FileChannel.
     *
     * @param listener optional sink invoked with live byte progress (may be {@code null})
     */
    public TransferResult uploadFile(String room, File file, boolean simulateTamper, ProgressListener listener) {
        if (file == null || !file.exists() || !file.isFile()) {
            return TransferResult.fail("File does not exist: " + file);
        }

        long fileSize = file.length();
        String localSha256;
        try (InputStream source = Files.newInputStream(file.toPath())) {
            localSha256 = CryptoIntegrityService.computeSha256(source);
        } catch (Exception e) {
            return TransferResult.fail("Failed to compute local SHA-256: " + e.getMessage());
        }

        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);

            // Header line
            String header = String.format("%s|%s|%s|%s|%d|%s|%b",
                ProtocolConstants.CMD_UPLOAD,
                sessionToken,
                room,
                FileNameUtils.sanitizeName(file.getName()),
                fileSize,
                localSha256,
                simulateTamper
            );
            writer.println(header);

            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TCP_FILECHANNEL,
                PacketLogEvent.Direction.TX,
                "FileChannel Upload Started",
                "File: " + file.getName() + " (" + fileSize + " bytes) -> " + host + ":" + port,
                "SHA-256: " + localSha256
            ));

            long sent = 0;
            if (listener != null) {
                listener.onProgress(0L, fileSize);
            }

            // Stream file bytes using FileChannel and ByteBuffer
            try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.allocate(NetworkConfig.FILE_CHUNK_SIZE);
                while (fileChannel.read(buffer) > 0) {
                    buffer.flip();
                    byte[] chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    out.write(chunk);
                    buffer.clear();

                    sent += chunk.length;
                    if (listener != null) {
                        listener.onProgress(sent, fileSize);
                    }
                }
                out.flush();
            }

            if (listener != null) {
                listener.onProgress(fileSize, fileSize);
            }

            // Read server verification response
            String response = FileTransferServer.readHeaderLine(in);
            if (response == null) {
                return TransferResult.fail("Server closed connection during upload");
            }

            String[] parts = response.split(ProtocolConstants.DELIMITER_REGEX, -1);
            String status = parts[0];

            if (ProtocolConstants.RESP_UPLOAD_OK.equalsIgnoreCase(status)) {
                String serverSha = parts.length > 1 ? parts[1] : "";
                String storedName = parts.length > 2 ? DataPersistenceManager.decodeField(parts[2].trim()) : file.getName();
                String timestamp = parts.length > 3 ? DataPersistenceManager.decodeField(parts[3].trim()) : "";
                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.TCP_FILECHANNEL,
                    PacketLogEvent.Direction.RX,
                    "UPLOAD_OK Received",
                    "Server verified SHA-256 integrity match!",
                    "Server SHA: " + serverSha + " | Stored as: " + storedName
                ));
                return new TransferResult(true, false, localSha256, serverSha,
                    "Upload successful and verified", file, storedName, timestamp);

            } else if (ProtocolConstants.RESP_UPLOAD_TAMPERED.equalsIgnoreCase(status)) {
                String expSha = parts.length > 1 ? parts[1] : localSha256;
                String actSha = parts.length > 2 ? parts[2] : "";
                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.TLS_SECURITY,
                    PacketLogEvent.Direction.RX,
                    "UPLOAD_TAMPERED Detected",
                    "Server detected SHA-256 mismatch and discarded file!",
                    "Expected: " + expSha + " | Got: " + actSha
                ));
                return new TransferResult(false, true, expSha, actSha, "Server rejected upload: SHA-256 Tamper detected!", null);

            } else {
                return TransferResult.fail("Upload error: " + response);
            }

        } catch (Exception e) {
            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TCP_FILECHANNEL,
                PacketLogEvent.Direction.INTERNAL,
                "Upload Error",
                e.getMessage()
            ));
            return TransferResult.fail(e.getMessage());
        }
    }

    // ==================================================================
    // DOWNLOAD
    // ==================================================================

    /** Downloads into {@code targetDir}, auto-renaming duplicates ({@code notes (1).pdf}). */
    public TransferResult downloadFile(String room, String filename, File targetDir) {
        return downloadInto(room, filename, targetDir, null, null);
    }

    public TransferResult downloadFile(String room, String filename, File targetDir, ProgressListener listener) {
        return downloadInto(room, filename, targetDir, null, listener);
    }

    /** Downloads to one exact destination file (overwriting it). */
    public TransferResult downloadToFile(String room, String filename, File destination, ProgressListener listener) {
        return downloadInto(room, filename, destination.getParentFile(), destination, listener);
    }

    private TransferResult downloadInto(String room, String filename, File targetDir, File forcedDestination, ProgressListener listener) {
        File dir = targetDir != null ? targetDir : new File(NetworkConfig.DOWNLOADS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File destFile = forcedDestination != null
            ? forcedDestination
            : FileNameUtils.uniqueFile(dir, filename);

        java.nio.file.Path pendingDownload = null;
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);

            // Request header
            writer.println(ProtocolConstants.CMD_DOWNLOAD + ProtocolConstants.DELIMITER +
                sessionToken + ProtocolConstants.DELIMITER + room + ProtocolConstants.DELIMITER
                + FileNameUtils.sanitizeName(filename));

            String headerLine = FileTransferServer.readHeaderLine(in);
            if (headerLine == null) {
                return TransferResult.fail("Empty response from file server");
            }

            String[] parts = headerLine.split(ProtocolConstants.DELIMITER_REGEX, -1);
            if (!ProtocolConstants.RESP_DOWNLOAD_OK.equalsIgnoreCase(parts[0]) || parts.length < 3) {
                return TransferResult.fail("Download rejected: " + headerLine);
            }

            long fileSize = Long.parseLong(parts[1]);
            String expectedSha256 = parts[2].trim().toLowerCase();

            if (fileSize < 0) throw new java.io.IOException("Invalid file length");
            File tempFile = File.createTempFile("studyhub_dl_", ".tmp", dir);
            pendingDownload = tempFile.toPath();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(NetworkConfig.FILE_CHUNK_SIZE);

            long totalBytesRead = 0;
            if (listener != null) {
                listener.onProgress(0L, fileSize);
            }

            try (FileChannel fileChannel = FileChannel.open(tempFile.toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

                byte[] chunk = new byte[NetworkConfig.FILE_CHUNK_SIZE];
                while (totalBytesRead < fileSize) {
                    int toRead = (int) Math.min(chunk.length, fileSize - totalBytesRead);
                    int read = in.read(chunk, 0, toRead);
                    if (read == -1) break;

                    digest.update(chunk, 0, read);

                    buffer.clear();
                    buffer.put(chunk, 0, read);
                    buffer.flip();
                    while (buffer.hasRemaining()) fileChannel.write(buffer);

                    totalBytesRead += read;
                    if (listener != null) {
                        listener.onProgress(totalBytesRead, fileSize);
                    }
                }
            }

            String computedSha256 = CryptoIntegrityService.bytesToHex(digest.digest()).toLowerCase();

            if (totalBytesRead != fileSize || !expectedSha256.equalsIgnoreCase(computedSha256)) {
                tempFile.delete();
                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.TLS_SECURITY,
                    PacketLogEvent.Direction.INTERNAL,
                    "Download Tamper Detected",
                    "Expected: " + expectedSha256 + " | Computed: " + computedSha256
                ));
                return new TransferResult(false, true, expectedSha256, computedSha256, "Tamper detected during download", null);
            }

            Files.move(tempFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            pendingDownload = null;

            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TCP_FILECHANNEL,
                PacketLogEvent.Direction.RX,
                "DOWNLOAD_OK Verified",
                "Saved to " + destFile.getAbsolutePath() + " (" + totalBytesRead + " bytes)",
                "SHA-256: " + computedSha256
            ));

            return new TransferResult(true, false, expectedSha256, computedSha256, "Download verified and saved", destFile);

        } catch (Exception e) {
            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TCP_FILECHANNEL,
                PacketLogEvent.Direction.INTERNAL,
                "Download Error",
                e.getMessage()
            ));
            return TransferResult.fail(e.getMessage());
        } finally {
            if (pendingDownload != null) try { Files.deleteIfExists(pendingDownload); } catch (java.io.IOException ignored) {}
        }
    }
}
