package com.studyhub.security;

import com.studyhub.common.DataPersistenceManager;
import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.common.ProtocolConstants;
import com.studyhub.concurrent.SafeRoomRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

/**
 * [MEMBER 3: Secure Network Programming]
 * TLS-secured authentication service operating over SSLServerSocket on Port 9443.
 * Enforces mutual cipher negotiation, validates credentials, distributes 128-bit AES session keys,
 * and now also supports self-service student registration ({@code REGISTER|...}) which is
 * written straight through to {@code data/users.db}.
 */
public class TlsAuthServer {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.]{3,24}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");

    private final int port;
    private final SafeRoomRegistry registry;
    private final Consumer<PacketLogEvent> telemetrySink;
    private volatile boolean running = false;
    private SSLServerSocket serverSocket;
    private ExecutorService clientWorkerPool;

    public TlsAuthServer(SafeRoomRegistry registry, Consumer<PacketLogEvent> telemetrySink) {
        this(NetworkConfig.TLS_AUTH_PORT, registry, telemetrySink);
    }

    public TlsAuthServer(int port, SafeRoomRegistry registry, Consumer<PacketLogEvent> telemetrySink) {
        this.port = port;
        this.registry = registry;
        this.telemetrySink = telemetrySink != null ? telemetrySink : e -> {};
        initPreSeededAccounts();
    }

    /**
     * Ensures the 4 pre-seeded evaluation accounts exist. Accounts are only created when
     * missing, so passwords that students have already changed on disk are never overwritten.
     */
    private void initPreSeededAccounts() {
        seedAccount("student1", "pass123", ProtocolConstants.ROLE_STUDENT, "Student One");
        seedAccount("student2", "pass123", ProtocolConstants.ROLE_STUDENT, "Student Two");
        seedAccount("student3", "pass123", ProtocolConstants.ROLE_STUDENT, "Student Three");
        seedAccount("admin", "admin123", ProtocolConstants.ROLE_MODERATOR, "Lab Moderator");
    }

    private void seedAccount(String username, String password, String role, String displayName) {
        if (!registry.hasAccount(username)) {
            registry.registerAccount(username, CryptoIntegrityService.computeSha256(password), role, displayName);
        }
    }

    public void start() throws Exception {
        SSLContext sslContext = KeystoreBootstrap.createServerSslContext();
        SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
        serverSocket = (SSLServerSocket) ssf.createServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;

        clientWorkerPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "TLS-AuthWorker");
            t.setDaemon(true);
            return t;
        });

        Thread listenerThread = new Thread(this::listenLoop, "TLS-Auth-Acceptor");
        listenerThread.setDaemon(true);
        listenerThread.start();

        telemetrySink.accept(new PacketLogEvent(
            PacketLogEvent.Module.TLS_SECURITY,
            PacketLogEvent.Direction.INTERNAL,
            "TLS Auth Server Started",
            "Bound to SSLServerSocket port " + port + " (" + NetworkConfig.TLS_PROTOCOL + ") | AUTH + REGISTER enabled"
        ));
    }

    private void listenLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                clientWorkerPool.submit(() -> handleClientAuth(socket));
            } catch (Exception e) {
                if (running) {
                    telemetrySink.accept(new PacketLogEvent(
                        PacketLogEvent.Module.TLS_SECURITY,
                        PacketLogEvent.Direction.INTERNAL,
                        "SSL Accept Warning",
                        e.getMessage()
                    ));
                }
            }
        }
    }

    private void handleClientAuth(SSLSocket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            socket.startHandshake();
            String cipherSuite = socket.getSession().getCipherSuite();
            String protocol = socket.getSession().getProtocol();

            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TLS_SECURITY,
                PacketLogEvent.Direction.RX,
                "TLS Handshake Completed",
                "Client: " + socket.getRemoteSocketAddress() + " | " + protocol + " | Cipher: " + cipherSuite
            ));

            String line = reader.readLine();
            if (line == null) return;

            String[] parts = line.split(ProtocolConstants.DELIMITER_REGEX, -1);
            if (parts.length >= 3 && ProtocolConstants.CMD_REGISTER.equals(parts[0])) {
                handleRegister(parts, writer);
            } else if (parts.length >= 3 && ProtocolConstants.CMD_AUTH.equals(parts[0])) {
                handleAuth(parts, writer);
            } else {
                writer.println(ProtocolConstants.RESP_AUTH_FAIL + ProtocolConstants.DELIMITER + "Bad protocol handshake");
            }

        } catch (Exception e) {
            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TLS_SECURITY,
                PacketLogEvent.Direction.INTERNAL,
                "TLS Client Auth Error",
                e.getMessage()
            ));
        }
    }

    // ------------------------------------------------------------------
    // AUTH|<username>|<sha256Password>
    // ------------------------------------------------------------------
    private void handleAuth(String[] parts, PrintWriter writer) {
        String username = parts[1].trim();
        String sha256Password = parts[2].trim();

        boolean valid = registry.validateCredentials(username, sha256Password);
        if (!valid && !registry.hasAccount(username)) {
            // Demo convenience: unknown classroom accounts are provisioned on first login
            // and immediately persisted to data/users.db for subsequent restarts.
            registry.registerAccount(username, sha256Password, ProtocolConstants.ROLE_STUDENT, username);
            valid = true;
        }

        if (valid) {
            String role = registry.getUserRole(username);
            String sessionKeyBase64 = CryptoIntegrityService.generateAesKeyBase64();
            SafeRoomRegistry.UserSession session = registry.createSession(username, sessionKeyBase64);

            String response = ProtocolConstants.RESP_AUTH_OK + ProtocolConstants.DELIMITER +
                session.getSessionToken() + ProtocolConstants.DELIMITER +
                role + ProtocolConstants.DELIMITER +
                sessionKeyBase64 + ProtocolConstants.DELIMITER +
                registry.getDisplayName(username);

            writer.println(response);

            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TLS_SECURITY,
                PacketLogEvent.Direction.TX,
                "AUTH_OK Granted",
                "User: " + username + " (" + role + ") | Token: " + session.getSessionToken()
            ));
        } else {
            writer.println(ProtocolConstants.RESP_AUTH_FAIL + ProtocolConstants.DELIMITER + "Invalid credentials");
            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TLS_SECURITY,
                PacketLogEvent.Direction.TX,
                "AUTH_FAIL",
                "User: " + username + " rejected"
            ));
        }
    }

    // ------------------------------------------------------------------
    // REGISTER|<username>|<sha256Password>|<displayName>
    // ------------------------------------------------------------------
    private void handleRegister(String[] parts, PrintWriter writer) {
        String username = parts[1].trim();
        String sha256Password = parts[2].trim();
        String displayName = parts.length >= 4 ? DataPersistenceManager.decodeField(parts[3].trim()) : username;
        if (displayName.isEmpty()) {
            displayName = username;
        }

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            writer.println(ProtocolConstants.RESP_REG_FAIL + ProtocolConstants.DELIMITER
                + "Username must be 3-24 characters (letters, digits, _ and . only)");
            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TLS_SECURITY,
                PacketLogEvent.Direction.TX,
                "REG_FAIL",
                "Invalid username format: " + username
            ));
            return;
        }

        if (!SHA256_PATTERN.matcher(sha256Password).matches()) {
            writer.println(ProtocolConstants.RESP_REG_FAIL + ProtocolConstants.DELIMITER + "Malformed password digest");
            return;
        }

        if (registry.hasAccount(username)) {
            writer.println(ProtocolConstants.RESP_REG_FAIL + ProtocolConstants.DELIMITER
                + "Username '" + username + "' is already taken");
            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TLS_SECURITY,
                PacketLogEvent.Direction.TX,
                "REG_FAIL",
                "Duplicate registration attempt for " + username
            ));
            return;
        }

        boolean created = registry.registerAccount(username, sha256Password, ProtocolConstants.ROLE_STUDENT, displayName);
        if (!created) {
            writer.println(ProtocolConstants.RESP_REG_FAIL + ProtocolConstants.DELIMITER
                + "Username '" + username + "' is already taken");
            return;
        }

        String role = registry.getUserRole(username);
        String resolvedName = registry.getDisplayName(username);
        writer.println(ProtocolConstants.RESP_REG_OK + ProtocolConstants.DELIMITER
            + username + ProtocolConstants.DELIMITER
            + DataPersistenceManager.encodeField(resolvedName) + ProtocolConstants.DELIMITER
            + role + ProtocolConstants.DELIMITER
            + registry.getRegisteredUserCount());

        telemetrySink.accept(new PacketLogEvent(
            PacketLogEvent.Module.TLS_SECURITY,
            PacketLogEvent.Direction.TX,
            "REG_OK Persisted",
            "New student account '" + username + "' (" + resolvedName + ") written to " + NetworkConfig.DATA_DIR + "/users.db"
        ));
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
        if (clientWorkerPool != null) {
            clientWorkerPool.shutdownNow();
        }
    }
}
