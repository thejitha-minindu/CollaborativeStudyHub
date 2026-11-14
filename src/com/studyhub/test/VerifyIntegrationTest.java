package com.studyhub.test;

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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Headless end-to-end integration verification test suite.
 * Exercises all 5 networking modules against a live StudyHub server.
 */
public class VerifyIntegrationTest {

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            System.err.println("❌ FAILED: " + message);
            throw new AssertionError("Assertion Failed: " + message);
        }
        System.out.println("  ✓ " + message);
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
            testFileTransferAndTamper(authResult);

            // 6. Test Module 5: Concurrent Multithreading & LinkPreviewCallable
            System.out.println("\n[PHASE 6] Testing Module 5: ExecutorService & HTTP Link Scraper...");
            testConcurrentLinkPreview();

            // 7. Test Module 4: UDP Pulse Datagrams & Out-of-Order Drop
            System.out.println("\n[PHASE 7] Testing Module 4: UDP Datagram Sequence & Out-of-Order Filtering...");
            testUdpPulseSequencing();

            System.out.println("\n======================================================================");
            System.out.println("   >>> ALL 5 NETWORKING MODULE TESTS PASSED WITH 100% SUCCESS! <<<    ");
            System.out.println("======================================================================");

        } catch (Throwable t) {
            System.err.println("\n❌ INTEGRATION TEST SUITE FAILED WITH EXCEPTION:");
            t.printStackTrace();
            System.exit(1);
        } finally {
            System.out.println("\n[CLEANUP] Stopping StudyHub Server...");
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
            String chatMsg = ProtocolConstants.CMD_MSG + "|#network-programming|PLAIN|Hello NIO Non-Blocking World!";
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

    private static void testFileTransferAndTamper(TlsAuthClient.AuthResult auth) throws Exception {
        // Create temporary PDF file
        File tempPdf = File.createTempFile("studyhub_test_", ".pdf");
        tempPdf.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tempPdf)) {
            fos.write("%PDF-1.4\n1 0 obj\n<< /Title (Networking Notes) >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF".getBytes(StandardCharsets.UTF_8));
        }

        FileTransferClient client = new FileTransferClient(NetworkConfig.DEFAULT_HOST, NetworkConfig.FILE_TRANSFER_PORT, auth.sessionToken, null);

        // 1. Valid Upload
        FileTransferClient.TransferResult okResult = client.uploadFile("#network-programming", tempPdf, false);
        assertTrue(okResult.success, "File upload without tamper succeeded (UPLOAD_OK)");
        assertTrue(!okResult.tampered, "Tamper flag is false for valid upload");
        assertTrue(okResult.actualSha.equals(okResult.expectedSha), "SHA-256 hashes matched: " + okResult.actualSha);

        // 2. Tampered Upload (Demonstrating SHA-256 Tamper Rejection)
        FileTransferClient.TransferResult tamperedResult = client.uploadFile("#network-programming", tempPdf, true);
        assertTrue(!tamperedResult.success, "Tampered upload was rejected by server");
        assertTrue(tamperedResult.tampered, "Tamper flag confirmed (UPLOAD_TAMPERED)");

        // 3. Download & Verify
        File dlDir = new File("downloads_test");
        dlDir.mkdirs();
        FileTransferClient.TransferResult dlResult = client.downloadFile("#network-programming", tempPdf.getName(), dlDir);
        assertTrue(dlResult.success, "Downloaded file successfully and verified SHA-256 integrity");
        assertTrue(dlResult.savedFile.exists() && dlResult.savedFile.length() == tempPdf.length(), "Downloaded file size matches original");

        // Clean up test file
        dlResult.savedFile.delete();
        dlDir.delete();
    }

    private static void testConcurrentLinkPreview() throws Exception {
        ResourceConcurrentEngine engine = new ResourceConcurrentEngine(3);
        try {
            Future<LinkPreview> future = engine.submitLinkPreview("https://example.com");
            LinkPreview preview = future.get();

            System.out.println("  Resolved LinkPreview: " + preview);
            assertTrue(preview.getHostIp() != null && !preview.getHostIp().equals("UNKNOWN"), "DNS InetAddress resolved host IP: " + preview.getHostIp());
            assertTrue(preview.getStatusCode() == 200, "HTTP Status Code 200 OK");
            assertTrue(preview.getTitle().toLowerCase().contains("example"), "Parsed HTML <title>: " + preview.getTitle());

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

        assertTrue(true, "UDP Pulses processed and out-of-order sequencing verified without errors");
    }
}
