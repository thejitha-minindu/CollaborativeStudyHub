package com.studyhub.transfer;

import com.studyhub.common.DataPersistenceManager;
import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.common.ProtocolConstants;
import com.studyhub.concurrent.SafeRoomRegistry;
import com.studyhub.nio.NioChatServer;
import com.studyhub.security.CryptoIntegrityService;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * [MEMBER 2: TCP Sockets & NIO FileChannel]
 * Reliable TCP file transfer server utilizing NIO FileChannel for disk I/O
 * and SHA-256 MessageDigest verification for cryptographic integrity validation.
 *
 * <p>Every verified upload is recorded in {@code data/vault_index.db} (room, uploader,
 * original name, physical stored name, byte size, SHA-256 and timestamp) so the Room Study
 * Vault is fully populated immediately after a server restart.</p>
 */
public class FileTransferServer {

    private final int port;
    private final SafeRoomRegistry registry;
    private final NioChatServer chatServer;
    private final Consumer<PacketLogEvent> telemetrySink;

    private ServerSocket serverSocket;
    private ExecutorService transferThreadPool;
    private volatile boolean running = false;

    public FileTransferServer(SafeRoomRegistry registry, NioChatServer chatServer, Consumer<PacketLogEvent> telemetrySink) {
        this(NetworkConfig.FILE_TRANSFER_PORT, registry, chatServer, telemetrySink);
    }

    public FileTransferServer(int port, SafeRoomRegistry registry, NioChatServer chatServer, Consumer<PacketLogEvent> telemetrySink) {
        this.port = port;
        this.registry = registry;
        this.chatServer = chatServer;
        this.telemetrySink = telemetrySink != null ? telemetrySink : e -> {};
    }

    public void start() throws Exception {
        File storageDir = new File(NetworkConfig.SHARED_STORAGE_DIR);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;

        transferThreadPool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "TCP-FileTransfer-Worker");
            t.setDaemon(true);
            return t;
        });

        Thread acceptor = new Thread(this::listenLoop, "TCP-FileTransfer-Acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        telemetrySink.accept(new PacketLogEvent(
            PacketLogEvent.Module.TCP_FILECHANNEL,
            PacketLogEvent.Direction.INTERNAL,
            "File Transfer Server Started",
            "Bound ServerSocket to TCP Port " + port + " | Vault index: " + NetworkConfig.DATA_DIR + "/vault_index.db"
        ));
    }

    private void listenLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
                transferThreadPool.submit(() -> handleTransferSession(socket));
            } catch (Exception e) {
                if (running) {
                    telemetrySink.accept(new PacketLogEvent(
                        PacketLogEvent.Module.TCP_FILECHANNEL,
                        PacketLogEvent.Direction.INTERNAL,
                        "Accept Exception",
                        e.getMessage()
                    ));
                }
            }
        }
    }

    /**
     * Reads one CRLF/LF terminated header line directly from the socket stream.
     * A {@code BufferedReader} is deliberately avoided here: it would read ahead into the
     * binary payload that follows the header and the upload would silently stall.
     */
    static String readHeaderLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            if (c != '\r') {
                if (buffer.size() >= 8192) throw new IOException("Transfer header is too long");
                buffer.write(c);
            }
        }
        return buffer.size() == 0 ? null : new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private void handleTransferSession(Socket socket) {
        try (socket) {
            socket.setSoTimeout(NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);

            String headerLine = readHeaderLine(in);
            if (headerLine == null) return;

            String[] parts = headerLine.split(ProtocolConstants.DELIMITER_REGEX, -1);
            String action = parts[0];

            if (ProtocolConstants.CMD_UPLOAD.equalsIgnoreCase(action)) {
                handleUpload(parts, in, writer);
            } else if (ProtocolConstants.CMD_DOWNLOAD.equalsIgnoreCase(action)) {
                handleDownload(parts, out, writer);
            } else {
                writer.println(ProtocolConstants.RESP_UPLOAD_FAIL + ProtocolConstants.DELIMITER + "Unknown action: " + action);
            }

        } catch (Exception e) {
            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TCP_FILECHANNEL,
                PacketLogEvent.Direction.INTERNAL,
                "Transfer Session Error",
                e.getMessage()
            ));
        }
    }

    private void handleUpload(String[] parts, InputStream in, PrintWriter writer) throws Exception {
        // Format: UPLOAD|<sessionToken>|<room>|<filename>|<fileSize>|<expectedSha256>|<simulateTamper>
        if (parts.length < 7) {
            writer.println(ProtocolConstants.RESP_UPLOAD_FAIL + ProtocolConstants.DELIMITER + "Invalid upload header");
            return;
        }

        String sessionToken = parts[1];
        String room = parts[2];
        String originalFilename = FileNameUtils.sanitizeName(parts[3]);
        long fileSize = Long.parseLong(parts[4]);
        String expectedSha256 = parts[5].trim().toLowerCase();
        boolean simulateTamper = Boolean.parseBoolean(parts[6]);
        if (fileSize < 0 || !expectedSha256.matches("[a-fA-F0-9]{64}")) {
            writer.println(ProtocolConstants.RESP_UPLOAD_FAIL + "|Invalid file size or digest"); return;
        }

        SafeRoomRegistry.UserSession session = registry.getSessionByToken(sessionToken);
        String uploader = (session != null) ? session.getUsername() : "Anonymous";

        // Open temporary FileChannel
        File tempFile = File.createTempFile("studyhub_upload_", ".tmp");
        tempFile.deleteOnExit();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(NetworkConfig.FILE_CHUNK_SIZE);

        long totalBytesRead = 0;
        int chunkCount = 0;

        try (java.nio.channels.FileChannel fileChannel = java.nio.channels.FileChannel.open(tempFile.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] byteBuf = new byte[NetworkConfig.FILE_CHUNK_SIZE];

            while (totalBytesRead < fileSize) {
                int toRead = (int) Math.min(byteBuf.length, fileSize - totalBytesRead);
                int read = in.read(byteBuf, 0, toRead);
                if (read == -1) {
                    break;
                }

                // If tamper simulation requested, flip bits in memory on the first chunk!
                if (simulateTamper && chunkCount == 0 && read > 0) {
                    byteBuf[0] ^= 0xFF; // Invert first byte
                }

                digest.update(byteBuf, 0, read);

                // Transfer from heap to FileChannel via ByteBuffer
                buffer.clear();
                buffer.put(byteBuf, 0, read);
                buffer.flip();
                while (buffer.hasRemaining()) fileChannel.write(buffer);

                totalBytesRead += read;
                chunkCount++;
            }
        }

        String actualSha256 = CryptoIntegrityService.bytesToHex(digest.digest()).toLowerCase();

        telemetrySink.accept(new PacketLogEvent(
            PacketLogEvent.Module.TCP_FILECHANNEL,
            PacketLogEvent.Direction.RX,
            "FileChannel Upload Completed",
            "File: " + originalFilename + " (" + totalBytesRead + " bytes, " + chunkCount + " chunks) | SimTamper=" + simulateTamper,
            "Expected: " + expectedSha256 + " | Actual: " + actualSha256
        ));

        // Integrity verification step
        if (totalBytesRead != fileSize || !expectedSha256.equalsIgnoreCase(actualSha256)) {
            tempFile.delete();
            writer.println(ProtocolConstants.RESP_UPLOAD_TAMPERED + ProtocolConstants.DELIMITER + expectedSha256 + ProtocolConstants.DELIMITER + actualSha256);

            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TLS_SECURITY,
                PacketLogEvent.Direction.INTERNAL,
                "TAMPER DETECTED",
                "Integrity violation detected! Expected: " + expectedSha256 + " but computed: " + actualSha256 + " -> Corrupt file rejected!"
            ));
            return;
        }

        // Integrity Verified: Save to target destination (never clobber an existing name)
        File roomDir = new File(NetworkConfig.SHARED_STORAGE_DIR, sanitizeRoomDir(room));
        if (!roomDir.exists()) {
            roomDir.mkdirs();
        }
        File destFile = FileNameUtils.uniqueFile(roomDir, originalFilename);
        Files.move(tempFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        String fileId = UUID.randomUUID().toString();
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        SharedFileRecord record = new SharedFileRecord(fileId, room, originalFilename, destFile.getName(),
            fileSize, actualSha256, uploader, timestamp);
        registry.addFileRecord(record);

        writer.println(ProtocolConstants.RESP_UPLOAD_OK + ProtocolConstants.DELIMITER + actualSha256
            + ProtocolConstants.DELIMITER + DataPersistenceManager.encodeField(destFile.getName())
            + ProtocolConstants.DELIMITER + DataPersistenceManager.encodeField(timestamp));

        // Notify all clients in the room via NIO Chat Server (and journal the transcript)
        if (chatServer != null) {
            chatServer.broadcastFileAdded(room, originalFilename, fileSize, uploader, actualSha256);
        }

        telemetrySink.accept(new PacketLogEvent(
            PacketLogEvent.Module.TCP_FILECHANNEL,
            PacketLogEvent.Direction.INTERNAL,
            "Vault Index Updated",
            "Recorded " + destFile.getName() + " (" + originalFilename + ") for " + room + " in data/vault_index.db"
        ));
    }

    private void handleDownload(String[] parts, OutputStream out, PrintWriter writer) throws Exception {
        // Format: DOWNLOAD|<sessionToken>|<room>|<filename>
        if (parts.length < 4) {
            writer.println(ProtocolConstants.RESP_DOWNLOAD_FAIL + ProtocolConstants.DELIMITER + "Invalid download header");
            return;
        }

        String room = parts[2];
        String requestedName = FileNameUtils.sanitizeName(parts[3]);

        File roomDir = new File(NetworkConfig.SHARED_STORAGE_DIR, sanitizeRoomDir(room));

        // Resolve the physical file through the persisted vault index first so duplicated
        // display names ("notes (1).pdf") map back to the exact stored artefact.
        String physicalName = requestedName;
        SharedFileRecord known = registry.findFile(room, requestedName);
        if (known != null) {
            physicalName = known.getStoredFilename();
        }

        File targetFile = new File(roomDir, physicalName);
        if (!targetFile.exists() || !targetFile.isFile()) {
            targetFile = new File(roomDir, requestedName);
        }

        if (!targetFile.exists() || !targetFile.isFile()) {
            writer.println(ProtocolConstants.RESP_DOWNLOAD_FAIL + ProtocolConstants.DELIMITER + "File not found: " + requestedName);
            return;
        }

        long fileSize = targetFile.length();
        String sha256;
        try (InputStream source = Files.newInputStream(targetFile.toPath())) { sha256 = CryptoIntegrityService.computeSha256(source); }

        // Send confirmation header
        writer.println(ProtocolConstants.RESP_DOWNLOAD_OK + ProtocolConstants.DELIMITER + fileSize + ProtocolConstants.DELIMITER + sha256);
        writer.flush();

        // Stream file using FileChannel & ByteBuffer
        try (java.nio.channels.FileChannel fileChannel = java.nio.channels.FileChannel.open(targetFile.toPath(), StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(NetworkConfig.FILE_CHUNK_SIZE);
            long totalRead = 0;

            while (fileChannel.read(buffer) > 0) {
                buffer.flip();
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                out.write(chunk);
                totalRead += chunk.length;
                buffer.clear();
            }
            out.flush();

            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TCP_FILECHANNEL,
                PacketLogEvent.Direction.TX,
                "FileChannel Download Completed",
                "Streamed " + totalRead + " bytes for " + physicalName + " (" + room + ")",
                "SHA-256: " + sha256
            ));
        }
    }

    private static String sanitizeRoomDir(String room) {
        String cleaned = room == null ? "general" : room.replace("#", "");
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._\\-]", "_");
        return cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..") ? "general" : cleaned;
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
        if (transferThreadPool != null) {
            transferThreadPool.shutdownNow();
        }
    }
}
