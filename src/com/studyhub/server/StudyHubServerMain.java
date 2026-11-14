package com.studyhub.server;

import com.studyhub.common.DataPersistenceManager;
import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.concurrent.ResourceConcurrentEngine;
import com.studyhub.concurrent.SafeRoomRegistry;
import com.studyhub.nio.NioChatServer;
import com.studyhub.security.KeystoreBootstrap;
import com.studyhub.security.TlsAuthServer;
import com.studyhub.transfer.FileTransferServer;
import com.studyhub.udp.LanDiscoveryBroadcaster;
import com.studyhub.udp.PresencePulseService;

/**
 * Unified server bootstrapper for the Collaborative Study Hub platform.
 * Launches all 5 networking modules and displays live console telemetry.
 */
public class StudyHubServerMain {

    private final SafeRoomRegistry registry;
    private final ResourceConcurrentEngine concurrentEngine;
    private final TlsAuthServer tlsAuthServer;
    private final NioChatServer nioChatServer;
    private final FileTransferServer fileTransferServer;
    private final LanDiscoveryBroadcaster discoveryBroadcaster;
    private final PresencePulseService presencePulseService;
    private volatile boolean shutdownHookRegistered = false;

    public StudyHubServerMain() {
        this.registry = new SafeRoomRegistry();
        this.concurrentEngine = new ResourceConcurrentEngine(5);

        this.tlsAuthServer = new TlsAuthServer(registry, this::logTelemetry);
        this.nioChatServer = new NioChatServer(registry, concurrentEngine, this::logTelemetry);
        this.fileTransferServer = new FileTransferServer(registry, nioChatServer, this::logTelemetry);
        this.discoveryBroadcaster = new LanDiscoveryBroadcaster("StudyHub-MainHub", registry, this::logTelemetry);
        this.presencePulseService = new PresencePulseService(registry, nioChatServer, this::logTelemetry);
    }

    private void logTelemetry(PacketLogEvent event) {
        System.out.println(event.toString());
    }

    public void start() throws Exception {
        printBanner();

        // 0. Restore durable state from disk (users, rooms + topics + pins, vault index, histories)
        DataPersistenceManager persistence = DataPersistenceManager.getInstance();
        System.out.println("[BOOTSTRAP] Disk persistence online at " + persistence.getDataDirectory().toAbsolutePath().normalize());
        System.out.println("[BOOTSTRAP] Restored " + persistence.getUserCount() + " accounts, "
            + persistence.getRooms().size() + " rooms, " + persistence.getVaultEntries().size() + " vaulted files.");

        // 1. Ensure Keystore exists (Auto-generate via keytool if absent)
        System.out.println("[BOOTSTRAP] Checking TLS Keystore at " + NetworkConfig.KEYSTORE_PATH + "...");
        KeystoreBootstrap.ensureKeystoreExists();
        System.out.println("[BOOTSTRAP] Keystore verified.");

        // 2. Start TLS Auth Server (Port 9443)
        tlsAuthServer.start();
        System.out.println("[SERVICE 1/5] TLS Auth Server active on Port " + NetworkConfig.TLS_AUTH_PORT);

        // 3. Start NIO Chat Multiplexer (Port 9000)
        nioChatServer.start();
        System.out.println("[SERVICE 2/5] NIO Non-Blocking Chat Server active on Port " + NetworkConfig.NIO_CHAT_PORT);

        // 4. Start TCP FileChannel Transfer (Port 9001)
        fileTransferServer.start();
        System.out.println("[SERVICE 3/5] TCP FileTransfer Server active on Port " + NetworkConfig.FILE_TRANSFER_PORT);

        // 5. Start UDP Presence Pulse Receiver (Port 9877)
        presencePulseService.start();
        System.out.println("[SERVICE 4/5] UDP Presence Pulse Service active on Port " + NetworkConfig.UDP_PULSE_PORT);

        // 6. Start UDP LAN Discovery Broadcaster (Port 9876)
        discoveryBroadcaster.start();
        System.out.println("[SERVICE 5/5] UDP Discovery Broadcaster active on Port " + NetworkConfig.UDP_DISCOVERY_PORT);

        System.out.println("\n>>> All 5 Collaborative Study Hub networking services are LIVE. <<<\n");

        if (!shutdownHookRegistered) {
            shutdownHookRegistered = true;
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "StudyHub-ShutdownHook"));
        }
    }

    public void stop() {
        System.out.println("\n[SHUTDOWN] Terminating StudyHub networking services...");
        discoveryBroadcaster.stop();
        presencePulseService.stop();
        fileTransferServer.stop();
        nioChatServer.stop();
        tlsAuthServer.stop();
        concurrentEngine.shutdown();

        // Final clean flush of every durable slice of state (users, rooms, histories, vault index)
        try {
            nioChatServer.flushPersistence();
        } catch (Exception ignored) {}
        try {
            DataPersistenceManager.getInstance().flush();
            System.out.println("[SHUTDOWN] Persistent state flushed to " + NetworkConfig.DATA_DIR + "/");
        } catch (Exception e) {
            System.err.println("[SHUTDOWN] Persistence flush warning: " + e.getMessage());
        }

        System.out.println("[SHUTDOWN] All services stopped cleanly.");
    }

    private void printBanner() {
        System.out.println("======================================================================");
        System.out.println("     COLLABORATIVE STUDY HUB & FILE SHARING PLATFORM (SERVER)        ");
        System.out.println("======================================================================");
        System.out.println(" * Module 1 (NIO Multiplexing):   Port " + NetworkConfig.NIO_CHAT_PORT + " (TCP Non-blocking)");
        System.out.println(" * Module 2 (FileChannel + SHA):  Port " + NetworkConfig.FILE_TRANSFER_PORT + " (TCP Stream)");
        System.out.println(" * Module 3 (TLS & Keystore):     Port " + NetworkConfig.TLS_AUTH_PORT + " (SSL/TLS v1.3)");
        System.out.println(" * Module 4 (UDP LAN Beacons):    Port " + NetworkConfig.UDP_DISCOVERY_PORT + " & " + NetworkConfig.UDP_PULSE_PORT + " (Datagram)");
        System.out.println(" * Module 5 (Thread Pool & HTTP): 5 Worker Threads active");
        System.out.println(" * Disk Persistence Engine:       " + NetworkConfig.DATA_DIR
            + "/ (users.db, rooms.db, vault_index.db, history_<room>.log)");
        System.out.println("======================================================================\n");
    }

    public static void main(String[] args) {
        try {
            StudyHubServerMain server = new StudyHubServerMain();
            server.start();

            // Keep main thread alive
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("[FATAL] Failed to launch StudyHub Server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
