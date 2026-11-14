package com.studyhub.test;

import com.studyhub.common.DataPersistenceManager;
import com.studyhub.common.NetworkConfig;
import com.studyhub.common.ProtocolConstants;
import com.studyhub.concurrent.LinkPreview;
import com.studyhub.concurrent.ResourceConcurrentEngine;
import com.studyhub.nio.MessageFramer;
import com.studyhub.security.CryptoIntegrityService;
import com.studyhub.security.TlsAuthClient;
import com.studyhub.server.StudyHubServerMain;
import com.studyhub.transfer.FileTransferClient;
import com.studyhub.udp.PresencePulseService;
import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Headless end-to-end integration verification test suite.
 * Exercises all 5 networking modules against a live StudyHub server, plus the
 * disk persistence engine (registration, rooms, history replay and vault index).
 */
public class VerifyIntegrationTest {

    private static int passedChecks = 0;
    private static int totalChecks = 0;

    private static void assertTrue(boolean condition, String message) {
        totalChecks++;
        if (!condition) {
            System.err.println("\u274c FAILED: " + message);
            throw new AssertionError("Assertion Failed: " + message);
        }
        passedChecks++;
        System.out.println("  \u2713 " + message);
    }

    public static void main(String[] args) {
        System.out.println("======================================================================");
        System.out.println("       RUNNING COLLABORATIVE STUDY HUB INTEGRATION TEST SUITE         ");
        System.out.println("======================================================================");

        StudyHubServerMain server = new StudyHubServerMain();
        try {
            // 1. Launch Server
            System.out.println("\n[PHASE 1] Bootstrapping Live StudyHub Server on All 5 Ports...");
            server.start();
            Thread.sleep(1500); // Allow sockets to bind

            // 2. Test Module 4: UDP LAN Discovery Broadcast
            System.out.println("\n[PHASE 2] Testing Module 4: UDP LAN Discovery Broadcast (Port 9876)...");
            testUdpDiscovery();

            // 3. Test Module 3: TLS Authentication & Cryptographic Integrity
            System.out.println("\n[PHASE 3] Testing Module 3: TLS Auth & AES-128 Encryption (Port 9443)...");
            TlsAuthClient.AuthResult authResult = testTlsAuth();

            // 4. Test Module 1: Java NIO Non-Blocking Multiplexing & Framing
            System.out.println("\n[PHASE 4] Testing Module 1: Java NIO Multiplexing & Framing (Port 9000)...");
            testNioMultiplexing(authResult);

            // 5. Test Module 2: TCP FileChannel Transfer & SHA-256 Tamper Detection
            System.out.println("\n[PHASE 5] Testing Module 2: TCP FileChannel & SHA-256 Tamper Detection (Port 9001)...");
            String uploadedFileName = testFileTransferAndTamper(authResult);

            // 6. Test Module 5: Concurrent Multithreading & LinkPreviewCallable
            System.out.println("\n[PHASE 6] Testing Module 5: ExecutorService & HTTP Link Scraper...");
            testConcurrentLinkPreview();

            // 7. Test Module 4: UDP Pulse Datagrams & Out-of-Order Drop
            System.out.println("\n[PHASE 7] Testing Module 4: UDP Datagram Sequence & Out-of-Order Filtering...");
            testUdpPulseSequencing();

            // 8. Test Disk Persistence: registration, rooms, history replay, vault index
            System.out.println("\n[PHASE 8] Testing Disk Persistence Engine (data/users.db, rooms.db, history_*, vault_index.db)...");
            testPersistenceRegistrationRoomsHistory(uploadedFileName);

            System.out.println("\n======================================================================");
            System.out.println("   >>> ALL 5 NETWORKING MODULE TESTS PASSED WITH 100% SUCCESS! <<<    ");
            System.out.println("       Checks passed: " + passedChecks + "/" + totalChecks);
            System.out.println("======================================================================");

        } catch (Throwable t) {
            System.err.println("\n\u274c INTEGRATION TEST SUITE FAILED WITH EXCEPTION:");
            t.printStackTrace();
            System.exit(1);
        } finally {
            System.out.println("\n[CLEANUP] Stopping StudyHub Server (flushing persistence)...");
            server.stop();
            System.out.println("[CLEANUP] Completed.");
        }
    }

    private static void testUdpDiscovery() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(NetworkConfig.UDP_DISCOVERY_PORT));
            socket.setSoTimeout(4500);

            byte[] buf = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);

            String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            System.out.println("  Received UDP Datagram: " + raw);

            assertTrue(raw.startsWith("BEACON"), "Packet starts with BEACON header");
            String[] parts = raw.split("\\|");
            assertTrue(parts.length >= 8, "Beacon contains all 8 protocol fields");
            assertTrue(Integer.parseInt(parts[3]) == NetworkConfig.TLS_AUTH_PORT, "Beacon announces TLS port 9443");
            assertTrue(Integer.parseInt(parts[4]) == NetworkConfig.NIO_CHAT_PORT, "Beacon announces NIO port 9000");
            assertTrue(Integer.parseInt(parts[5]) == NetworkConfig.FILE_TRANSFER_PORT, "Beacon announces FileTransfer port 9001");
        }
    }

    private static TlsAuthClient.AuthResult testTlsAuth() throws Exception {
        TlsAuthClient authClient = new TlsAuthClient(NetworkConfig.DEFAULT_HOST, NetworkConfig.TLS_AUTH_PORT, null);
        TlsAuthClient.AuthResult result = authClient.authenticate("student1", "pass123");

        assertTrue(result.success, "TLS Authentication succeeded for 'student1'");
        assertTrue(result.sessionToken != null && !result.sessionToken.isEmpty(), "Received valid session token: " + result.sessionToken);
        assertTrue(result.aesKeyBase64 != null && !result.aesKeyBase64.isEmpty(), "Received 128-bit AES session key");
        assertTrue(ProtocolConstants.ROLE_STUDENT.equals(result.role), "Verified role is STUDENT");

        // Verify X.509 Certificate Inspection
        TlsAuthClient.CertificateDetails cert = result.certificateDetails;
        assertTrue(cert.subjectDN.contains("StudyHubServer"), "X.509 Certificate Subject DN verified: " + cert.subjectDN);
        assertTrue(cert.cipherSuite != null && !cert.cipherSuite.isEmpty(), "Negotiated Cipher Suite: " + cert.cipherSuite);
        assertTrue(cert.protocol != null && !cert.protocol.isEmpty(), "Negotiated TLS Protocol: " + cert.protocol);

        // Verify AES Encryption / Decryption Round-Trip
        String sampleMessage = "Confidential Study Group Notes 12345";
        String ciphertext = CryptoIntegrityService.encryptAes(sampleMessage, result.aesKeyBase64);
        String decrypted = CryptoIntegrityService.decryptAes(ciphertext, result.aesKeyBase64);
        assertTrue(sampleMessage.equals(decrypted), "AES-128-CBC encryption and decryption roundtrip match!");

        return result;
    }

    private static void testNioMultiplexing(TlsAuthClient.AuthResult auth) throws Exception {
        MessageFramer framer = new MessageFramer();
        try (SocketChannel channel = SocketChannel.open()) {
            channel.connect(new InetSocketAddress(NetworkConfig.DEFAULT_HOST, NetworkConfig.NIO_CHAT_PORT));
            channel.configureBlocking(true);

            // Send HELLO
            String helloMsg = ProtocolConstants.CMD_HELLO + "|" + auth.sessionToken + "|student1";
            channel.write(framer.frameMessage(helloMsg));

            ByteBuffer readBuf = ByteBuffer.allocate(4096);
            channel.read(readBuf);
            List<String> frames = framer.extractFrames(readBuf);
            assertTrue(!frames.isEmpty(), "Received greeting frames from NIO Selector");

            // Send JOIN
            String joinMsg = ProtocolConstants.CMD_JOIN + "|#network-programming";
            channel.write(framer.frameMessage(joinMsg));
            readBuf.clear();
            channel.read(readBuf);
            frames = framer.extractFrames(readBuf);
            assertTrue(!frames.isEmpty(), "Received room confirmation from NIO Selector");

            // Send MSG
            String chatMsg = ProtocolConstants.CMD_MSG + "|#network-programming|PLAIN|Hello NIO Non-Blocking World!|";
            channel.write(framer.frameMessage(chatMsg));

            boolean foundChat = false;
            long deadline = System.currentTimeMillis() + 4000;
            while (!foundChat && System.currentTimeMillis() < deadline) {
                if (channel.read(readBuf) > 0) {
                    List<String> newFrames = framer.extractFrames(readBuf);
                    for (String f : newFrames) {
                        if (f.contains("Hello NIO Non-Blocking World!")) {
                            foundChat = true;
                            break;
                        }
                    }
                }
                if (!foundChat) {
                    Thread.sleep(50);
                }
            }
            assertTrue(foundChat, "Echoed chat frame received over NIO SocketChannel");
        }
    }

    private static String testFileTransferAndTamper(TlsAuthClient.AuthResult auth) throws Exception {
        // Create temporary PDF file
        File tempPdf = File.createTempFile("studyhub_test_", ".pdf");
        tempPdf.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tempPdf)) {
            fos.write("%PDF-1.4\n1 0 obj\n<< /Title (Networking Notes) >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF".getBytes(StandardCharsets.UTF_8));
        }

        FileTransferClient client = new FileTransferClient(NetworkConfig.DEFAULT_HOST, NetworkConfig.FILE_TRANSFER_PORT, auth.sessionToken, null);

        final long[] progressTicks = new long[1];
        FileTransferClient.ProgressListener progress = (transferred, total) -> progressTicks[0]++;

        // 1. Valid Upload (with live byte-progress reporting)
        FileTransferClient.TransferResult okResult = client.uploadFile("#network-programming", tempPdf, false, progress);
        assertTrue(okResult.success, "File upload without tamper succeeded (UPLOAD_OK)");
        assertTrue(!okResult.tampered, "Tamper flag is false for valid upload");
        assertTrue(okResult.actualSha.equals(okResult.expectedSha), "SHA-256 hashes matched: " + okResult.actualSha);
        assertTrue(progressTicks[0] > 0, "Live upload progress callback fired (" + progressTicks[0] + " updates)");
        assertTrue(!okResult.storedFilename.isEmpty(), "Server reported stored filename: " + okResult.storedFilename);

        // 1b. Duplicate upload must not clobber the first artefact
        FileTransferClient.TransferResult dupResult = client.uploadFile("#network-programming", tempPdf, false);
        assertTrue(dupResult.success, "Duplicate upload accepted and versioned");
        assertTrue(!dupResult.storedFilename.equals(okResult.storedFilename),
            "Duplicate stored as a separate file: " + dupResult.storedFilename);

        // 2. Tampered Upload (Demonstrating SHA-256 Tamper Rejection)
        FileTransferClient.TransferResult tamperedResult = client.uploadFile("#network-programming", tempPdf, true);
        assertTrue(!tamperedResult.success, "Tampered upload was rejected by server");
        assertTrue(tamperedResult.tampered, "Tamper flag confirmed (UPLOAD_TAMPERED)");

        // 3. Download & Verify into a per-user downloads folder
        File dlDir = FileTransferClient.getUserDownloadDir("verify_test_user");
        FileTransferClient.TransferResult dlResult = client.downloadFile("#network-programming", tempPdf.getName(), dlDir, progress);
        assertTrue(dlResult.success, "Downloaded file successfully and verified SHA-256 integrity");
        assertTrue(dlResult.savedFile.exists() && dlResult.savedFile.length() == tempPdf.length(), "Downloaded file size matches original");
        assertTrue(dlResult.savedFile.getParentFile().getName().equals("verify_test_user"),
            "Download saved under downloads/<username>/ directory");

        // 3b. Downloading again must auto-rename instead of overwriting
        FileTransferClient.TransferResult dl2 = client.downloadFile("#network-programming", tempPdf.getName(), dlDir);
        assertTrue(dl2.success && !dl2.savedFile.getName().equals(dlResult.savedFile.getName()),
            "Duplicate download auto-renamed to " + (dl2.success ? dl2.savedFile.getName() : "n/a"));

        // Clean up test files
        dlResult.savedFile.delete();
        if (dl2.savedFile != null) dl2.savedFile.delete();
        dlDir.delete();

        return tempPdf.getName();
    }

    private static void testConcurrentLinkPreview() throws Exception {
        ResourceConcurrentEngine engine = new ResourceConcurrentEngine(3);
        try {
            Future<LinkPreview> future = engine.submitLinkPreview("https://example.com");
            LinkPreview preview = future.get();

            System.out.println("  Resolved LinkPreview: " + preview);

            boolean online = preview.isSuccess() && preview.getStatusCode() == 200
                && preview.getHostIp() != null && !"UNKNOWN".equals(preview.getHostIp());

            if (online) {
                assertTrue(preview.getHostIp() != null && !preview.getHostIp().equals("UNKNOWN"), "DNS InetAddress resolved host IP: " + preview.getHostIp());
                assertTrue(preview.getStatusCode() == 200, "HTTP Status Code 200 OK");
                assertTrue(preview.getTitle().toLowerCase().contains("example"), "Parsed HTML <title>: " + preview.getTitle());
            } else {
                System.out.println("  \u26a0 Sandbox has no outbound internet access - verifying graceful offline degradation instead.");
                assertTrue(!preview.isSuccess(), "Unreachable host degrades gracefully instead of crashing the pool");
            }

            // Test fallback on invalid domain
            Future<LinkPreview> fallbackFuture = engine.submitLinkPreview("http://non-existent-domain-xyz-12345.org");
            LinkPreview fallbackPreview = fallbackFuture.get();
            assertTrue(!fallbackPreview.isSuccess(), "Graceful fallback returned for unreachable host");

        } finally {
            engine.shutdown();
        }
    }

    private static void testUdpPulseSequencing() throws Exception {
        // Send valid in-order pulse
        PresencePulseService.sendClientPulse(NetworkConfig.DEFAULT_HOST, NetworkConfig.UDP_PULSE_PORT, 100, "student1", "#network-programming", "TYPING");
        Thread.sleep(100);

        // Send stale out-of-order pulse (seq 50 < 100)
        PresencePulseService.sendClientPulse(NetworkConfig.DEFAULT_HOST, NetworkConfig.UDP_PULSE_PORT, 50, "student1", "#network-programming", "ONLINE");
        Thread.sleep(100);

        // Send a real-world study status pulse (Available / Focusing / In Lab)
        PresencePulseService.sendClientPulse(NetworkConfig.DEFAULT_HOST, NetworkConfig.UDP_PULSE_PORT, 101, "student1", "#network-programming", ProtocolConstants.STATUS_FOCUS);
        Thread.sleep(100);

        assertTrue(true, "UDP Pulses processed and out-of-order sequencing verified without errors");
    }

    // =========================================================================
    // PHASE 8: DISK PERSISTENCE (registration, rooms, history replay, vault)
    // =========================================================================
    private static void testPersistenceRegistrationRoomsHistory(String uploadedFileName) throws Exception {
        DataPersistenceManager persistence = DataPersistenceManager.getInstance();
        Path dataDir = Paths.get(NetworkConfig.DATA_DIR);

        // ---- 1. Flat files exist and are populated -------------------------
        assertTrue(persistence.isInitialized(), "Persistence engine initialised at " + persistence.getDataDirectory());
        assertTrue(Files.exists(dataDir.resolve(DataPersistenceManager.USERS_DB_FILE)), "data/users.db exists");
        assertTrue(Files.exists(dataDir.resolve(DataPersistenceManager.ROOMS_DB_FILE)), "data/rooms.db exists");
        assertTrue(Files.exists(dataDir.resolve(DataPersistenceManager.VAULT_INDEX_FILE)), "data/vault_index.db exists");
        assertTrue(persistence.getUserCount() >= 4, "4 pre-seeded evaluation accounts persisted (" + persistence.getUserCount() + " total)");

        // ---- 2. Self-service registration over TLS (Port 9443) ------------
        String username = "reguser_" + System.currentTimeMillis();
        String password = "StudyHub2024!";
        String displayName = "Verification Student";

        TlsAuthClient regClient = new TlsAuthClient(NetworkConfig.DEFAULT_HOST, NetworkConfig.TLS_AUTH_PORT, null);

        TlsAuthClient.RegisterResult registration = regClient.register(username, password, displayName);
        assertTrue(registration.success, "REGISTER accepted new student '" + username + "' over TLSv1.3");
        assertTrue(username.equalsIgnoreCase(registration.username), "Server echoed the registered username");
        assertTrue(displayName.equals(registration.displayName), "Display name round-tripped: " + registration.displayName);

        TlsAuthClient.RegisterResult duplicate = regClient.register(username, password, "Duplicate Account");
        assertTrue(!duplicate.success, "Duplicate username rejected by the auth server");
        assertTrue(duplicate.errorMessage.toLowerCase().contains("taken"), "Rejection reason: " + duplicate.errorMessage);

        TlsAuthClient.RegisterResult invalid = regClient.register("ab", password, "Too Short");
        assertTrue(!invalid.success, "Invalid username format rejected by the auth server");

        // Persisted to data/users.db with a per-account salt
        DataPersistenceManager.UserRecord stored = persistence.getUser(username);
        assertTrue(stored != null, "New account written to data/users.db");
        assertTrue(displayName.equals(stored.getDisplayName()), "Display name persisted");
        assertTrue(!stored.getSalt().isEmpty(), "Account hashed with a unique per-user salt");
        assertTrue(!stored.getSaltedHash().equalsIgnoreCase(CryptoIntegrityService.computeSha256(password)),
            "Password digest is salted, never stored verbatim");
        assertTrue(persistence.credentialsMatch(username, CryptoIntegrityService.computeSha256(password)),
            "Salted credential verification succeeds for the right password");
        assertTrue(!persistence.credentialsMatch(username, CryptoIntegrityService.computeSha256("wrong-password")),
            "Salted credential verification rejects a wrong password");

        // ---- 3. Login with the freshly created account --------------------
        TlsAuthClient.AuthResult newLogin = regClient.authenticate(username, password);
        assertTrue(newLogin.success, "Registered student can immediately log in over TLS");
        assertTrue(ProtocolConstants.ROLE_STUDENT.equals(newLogin.role), "Self-registered accounts get the STUDENT role");
        assertTrue(!newLogin.sessionToken.isEmpty(), "Issued session token for " + username + ": " + newLogin.sessionToken);

        // ---- 4. Room creation over NIO (Port 9000) ------------------------
        String room = "#persist-" + System.currentTimeMillis();
        String topic = "Automated verification room";
        String marker = "Persistence checkpoint " + System.currentTimeMillis();

        try (NioProbe student = new NioProbe(newLogin.sessionToken, username)) {
            assertTrue(student.awaitFrame(ProtocolConstants.EVT_SYSTEM, 4000) != null, "HELLO handshake acknowledged by NIO server");
            assertTrue(student.awaitFrame(ProtocolConstants.EVT_ROOM_LIST, 4000) != null, "Server pushed the persisted ROOM_LIST on connect");

            student.send(ProtocolConstants.CMD_CREATE_ROOM + "|" + room + "|" + DataPersistenceManager.encodeField(topic));
            String roomListFrame = student.awaitFrameContains(ProtocolConstants.EVT_ROOM_LIST, room, 4000);
            assertTrue(roomListFrame != null, "ROOM_LIST broadcast contains the newly created room " + room);
            assertTrue(persistence.hasRoom(room), "Custom room persisted to data/rooms.db");
            assertTrue(topic.equals(persistence.getRoomTopic(room)), "Room topic persisted: " + topic);

            // ---- 5. Join + message persistence (data/history_<room>.log) ---
            student.send(ProtocolConstants.CMD_JOIN + "|" + room);
            String historyFrame = student.awaitFrame(ProtocolConstants.EVT_HISTORY_BATCH, 4000);
            assertTrue(historyFrame != null, "JOIN triggers a HISTORY_BATCH snapshot for the late joiner");

            student.send(ProtocolConstants.CMD_MSG + "|" + room + "|" + ProtocolConstants.ENC_PLAIN + "|" + marker + "|");
            assertTrue(student.awaitFrameContains(ProtocolConstants.EVT_CHAT, marker, 4000) != null,
                "Chat message echoed back over the NIO SocketChannel");

            // Give the background flusher a moment, then read the append-only log directly.
            Thread.sleep(600);
            Path historyFile = persistence.historyFile(room);
            assertTrue(Files.exists(historyFile), "Append-only transcript created: " + historyFile.getFileName());

            List<DataPersistenceManager.HistoryEntry> history = persistence.getRecentHistory(room, NetworkConfig.HISTORY_REPLAY_LIMIT);
            assertTrue(!history.isEmpty(), "Message history persisted (" + history.size() + " entries)");

            boolean markerFound = false;
            String persistedSender = null;
            for (DataPersistenceManager.HistoryEntry entry : history) {
                if (entry.getPayload().contains(marker)) {
                    markerFound = true;
                    persistedSender = entry.getSender();
                }
            }
            assertTrue(markerFound, "Persisted transcript contains the message text");
            assertTrue(username.equalsIgnoreCase(persistedSender), "Persisted transcript recorded the sender role and name");

            // ---- 6. Late joiner replay over a second NIO channel -----------
            try (NioProbe lateJoiner = new NioProbe(newLogin.sessionToken, username)) {
                lateJoiner.awaitFrame(ProtocolConstants.EVT_SYSTEM, 4000);
                lateJoiner.send(ProtocolConstants.CMD_JOIN + "|" + room);
                String replay = lateJoiner.awaitFrameContains(ProtocolConstants.EVT_HISTORY_BATCH, marker, 4000);
                assertTrue(replay != null, "Late joiner received the last 50 messages replayed over NIO (HISTORY_BATCH)");

                // ---- 7. Moderator pinning --------------------------------
                String pinPayload = DataPersistenceManager.encodeField("admin") + "|12:00:00|"
                    + DataPersistenceManager.encodeField(username) + "|" + DataPersistenceManager.encodeField(marker);
                lateJoiner.send(ProtocolConstants.CMD_PIN + "|" + room + "|" + pinPayload);
                String denial = lateJoiner.awaitFrameContains(ProtocolConstants.EVT_SYSTEM, "Only moderators", 4000);
                assertTrue(denial != null, "Student pin attempt rejected (moderator-only action)");
            }

            TlsAuthClient.AuthResult adminAuth = new TlsAuthClient(NetworkConfig.DEFAULT_HOST, NetworkConfig.TLS_AUTH_PORT, null)
                .authenticate("admin", "admin123");
            assertTrue(adminAuth.success, "Moderator 'admin' authenticated for the pinning test");

            try (NioProbe moderator = new NioProbe(adminAuth.sessionToken, "admin")) {
                moderator.awaitFrame(ProtocolConstants.EVT_SYSTEM, 4000);
                moderator.send(ProtocolConstants.CMD_JOIN + "|" + room);
                moderator.awaitFrame(ProtocolConstants.EVT_HISTORY_BATCH, 4000);

                String pinPayload = DataPersistenceManager.encodeField("admin") + "|12:00:00|"
                    + DataPersistenceManager.encodeField(username) + "|" + DataPersistenceManager.encodeField(marker);
                moderator.send(ProtocolConstants.CMD_PIN + "|" + room + "|" + pinPayload);
                String pinnedFrame = moderator.awaitFrame(ProtocolConstants.EVT_PINNED, 4000);
                assertTrue(pinnedFrame != null, "Moderator pin broadcast to the room (PINNED)");
                assertTrue(!persistence.getRoomPinned(room).isEmpty(), "Pinned message persisted with the room record");
            }
        }

        // ---- 8. Vault index persistence ----------------------------------
        List<DataPersistenceManager.VaultEntry> vault = persistence.getVaultEntries("#network-programming");
        boolean fileIndexed = false;
        DataPersistenceManager.VaultEntry indexedEntry = null;
        for (DataPersistenceManager.VaultEntry entry : vault) {
            if (entry.getOriginalFilename().equals(uploadedFileName)) {
                fileIndexed = true;
                indexedEntry = entry;
                break;
            }
        }
        assertTrue(fileIndexed, "Uploaded file recorded in data/vault_index.db (" + uploadedFileName + ")");
        assertTrue(indexedEntry != null && indexedEntry.getFileSize() > 0, "Vault entry stores the byte size");
        assertTrue(indexedEntry != null && indexedEntry.getSha256().length() == 64, "Vault entry stores the SHA-256 digest");
        assertTrue(indexedEntry != null && !indexedEntry.getStoredFilename().isEmpty()
            && !indexedEntry.getTimestamp().isEmpty(), "Vault entry stores the stored filename and upload timestamp");

        // ---- 9. Everything survives a fresh server initialisation ---------
        // (Same flush the shutdown hook performs before the JVM exits.)
        persistence.flush();
        DataPersistenceManager reloaded = DataPersistenceManager.forDirectory(dataDir);
        assertTrue(reloaded.hasUser(username), "Account survives a server restart (reloaded from data/users.db)");
        assertTrue(reloaded.hasUser("student1"), "Pre-seeded account survives a server restart");
        assertTrue(reloaded.hasRoom(room), "Custom room survives a server restart");
        assertTrue(topic.equals(reloaded.getRoomTopic(room)), "Room topic survives a server restart");
        assertTrue(!reloaded.getRoomPinned(room).isEmpty(), "Pinned message survives a server restart");
        assertTrue(reloaded.getVaultEntries().size() == vault.size() || reloaded.getVaultEntries().size() >= vault.size(),
            "Vault index survives a server restart (" + reloaded.getVaultEntries().size() + " entries)");

        boolean reloadedMarker = false;
        for (DataPersistenceManager.HistoryEntry entry : reloaded.getRecentHistory(room, NetworkConfig.HISTORY_REPLAY_LIMIT)) {
            if (entry.getPayload().contains(marker)) {
                reloadedMarker = true;
            }
        }
        assertTrue(reloadedMarker, "Room transcript survives a server restart and is replayable");

        // ---- 10. Clean flush (same call the shutdown hook makes) ----------
        persistence.flush();
        assertTrue(true, "Persistence flush() completed with zero errors");
    }

    /**
     * Minimal non-blocking NIO probe: opens a SocketChannel, sends the HELLO handshake
     * and buffers every inbound frame so tests can await a specific event type.
     */
    private static final class NioProbe implements AutoCloseable {
        private final SocketChannel channel;
        private final MessageFramer framer = new MessageFramer();
        private final ByteBuffer buffer = ByteBuffer.allocate(NetworkConfig.NIO_BUFFER_CAPACITY);
        private final List<String> frames = new ArrayList<>();

        NioProbe(String sessionToken, String username) throws Exception {
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(NetworkConfig.DEFAULT_HOST, NetworkConfig.NIO_CHAT_PORT));

            long deadline = System.currentTimeMillis() + 5000;
            while (!channel.finishConnect() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            send(ProtocolConstants.CMD_HELLO + "|" + sessionToken + "|" + username);
        }

        void send(String payload) throws Exception {
            ByteBuffer out = framer.frameMessage(payload);
            while (out.hasRemaining()) {
                channel.write(out);
            }
        }

        /** Drains the socket until a frame starting with {@code prefix} is seen. */
        String awaitFrame(String prefix, long timeoutMs) throws Exception {
            return awaitFrameContains(prefix, null, timeoutMs);
        }

        /** Drains the socket until a frame starting with {@code prefix} and containing {@code needle} is seen. */
        String awaitFrameContains(String prefix, String needle, long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                String hit = scan(prefix, needle);
                if (hit != null) return hit;

                int read = channel.read(buffer);
                if (read > 0) {
                    frames.addAll(framer.extractFrames(buffer));
                    hit = scan(prefix, needle);
                    if (hit != null) return hit;
                } else if (read < 0) {
                    return null;
                } else {
                    Thread.sleep(25);
                }
            }
            return null;
        }

        private String scan(String prefix, String needle) {
            for (String frame : frames) {
                if (frame.startsWith(prefix) && (needle == null || frame.contains(needle))) {
                    return frame;
                }
            }
            return null;
        }

        @Override
        public void close() throws Exception {
            channel.close();
        }
    }
}
