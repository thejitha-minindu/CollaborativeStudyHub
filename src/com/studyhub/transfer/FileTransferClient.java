package com.studyhub.transfer;

import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.common.ProtocolConstants;
import com.studyhub.security.CryptoIntegrityService;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * Performs end-to-end SHA-256 cryptographic hashing and FileChannel buffer streaming.
 */
public class FileTransferClient {

    public static class TransferResult {
        public final boolean success;
        public final boolean tampered;
        public final String expectedSha;
        public final String actualSha;
        public final String message;
        public final File savedFile;

        public TransferResult(boolean success, boolean tampered, String expectedSha, String actualSha, String message, File savedFile) {
            this.success = success;
            this.tampered = tampered;
            this.expectedSha = expectedSha != null ? expectedSha : "";
            this.actualSha = actualSha != null ? actualSha : "";
            this.message = message != null ? message : "";
            this.savedFile = savedFile;
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
        this.host = host != null ? host : NetworkConfig.DEFAULT_HOST;
        this.port = port > 0 ? port : NetworkConfig.FILE_TRANSFER_PORT;
        this.sessionToken = sessionToken != null ? sessionToken : "";
        this.telemetrySink = telemetrySink != null ? telemetrySink : e -> {};
    }

    /**
     * Uploads a file to the server using TCP Socket + NIO FileChannel.
     */
    public TransferResult uploadFile(String room, File file, boolean simulateTamper) {
        if (file == null || !file.exists() || !file.isFile()) {
            return TransferResult.fail("File does not exist: " + file);
        }

        long fileSize = file.length();
        String localSha256;
        try {
            localSha256 = CryptoIntegrityService.computeSha256(Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            return TransferResult.fail("Failed to compute local SHA-256: " + e.getMessage());
        }

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            // Header line
            String header = String.format("%s|%s|%s|%s|%d|%s|%b",
                ProtocolConstants.CMD_UPLOAD,
                sessionToken,
                room,
                file.getName(),
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

            // Stream file bytes using FileChannel and ByteBuffer
            try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.allocate(NetworkConfig.FILE_CHUNK_SIZE);
                while (fileChannel.read(buffer) > 0) {
                    buffer.flip();
                    byte[] chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    out.write(chunk);
                    buffer.clear();
                }
                out.flush();
            }

            // Read server verification response
            String response = reader.readLine();
            if (response == null) {
                return TransferResult.fail("Server closed connection during upload");
            }

            String[] parts = response.split(ProtocolConstants.DELIMITER_REGEX);
            String status = parts[0];

            if (ProtocolConstants.RESP_UPLOAD_OK.equalsIgnoreCase(status)) {
                String serverSha = parts.length > 1 ? parts[1] : "";
                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.TCP_FILECHANNEL,
                    PacketLogEvent.Direction.RX,
                    "UPLOAD_OK Received",
                    "Server verified SHA-256 integrity match!",
                    "Server SHA: " + serverSha
                ));
                return new TransferResult(true, false, localSha256, serverSha, "Upload successful and verified", file);

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

    /**
     * Downloads a file from the server using TCP Socket + NIO FileChannel.
     */
    public TransferResult downloadFile(String room, String filename, File targetDir) {
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            // Request header
            writer.println(ProtocolConstants.CMD_DOWNLOAD + ProtocolConstants.DELIMITER +
                sessionToken + ProtocolConstants.DELIMITER + room + ProtocolConstants.DELIMITER + filename);

            String headerLine = reader.readLine();
            if (headerLine == null) {
                return TransferResult.fail("Empty response from file server");
            }

            String[] parts = headerLine.split(ProtocolConstants.DELIMITER_REGEX);
            if (!ProtocolConstants.RESP_DOWNLOAD_OK.equalsIgnoreCase(parts[0]) || parts.length < 3) {
                return TransferResult.fail("Download rejected: " + headerLine);
            }

            long fileSize = Long.parseLong(parts[1]);
            String expectedSha256 = parts[2].trim().toLowerCase();

            File tempFile = File.createTempFile("studyhub_dl_", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(NetworkConfig.FILE_CHUNK_SIZE);

            long totalBytesRead = 0;
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
                    fileChannel.write(buffer);

                    totalBytesRead += read;
                }
            }

            String computedSha256 = CryptoIntegrityService.bytesToHex(digest.digest()).toLowerCase();

            if (!expectedSha256.equalsIgnoreCase(computedSha256)) {
                tempFile.delete();
                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.TLS_SECURITY,
                    PacketLogEvent.Direction.INTERNAL,
                    "Download Tamper Detected",
                    "Expected: " + expectedSha256 + " | Computed: " + computedSha256
                ));
                return new TransferResult(false, true, expectedSha256, computedSha256, "Tamper detected during download", null);
            }

            File destFile = new File(targetDir, filename);
            Files.move(tempFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

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
        }
    }
}
