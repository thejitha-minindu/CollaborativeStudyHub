package com.studyhub.security;

import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.common.ProtocolConstants;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * [MEMBER 3: Secure Network Programming]
 * TLS client connecting via SSLSocket on Port 9443.
 * Authenticates user credentials, parses server X.509 certificate metadata,
 * and extracts negotiated cipher parameters for network inspection.
 */
public class TlsAuthClient {

    public static class CertificateDetails {
        public String subjectDN = "None";
        public String issuerDN = "None";
        public String validTo = "None";
        public String cipherSuite = "None";
        public String protocol = "None";
        public String serialNumber = "None";
        public String sha256Fingerprint = "None";
    }

    public static class AuthResult {
        public final boolean success;
        public final String sessionToken;
        public final String role;
        public final String aesKeyBase64;
        public final String displayName;
        public final String errorMessage;
        public final CertificateDetails certificateDetails;

        public AuthResult(boolean success, String sessionToken, String role, String aesKeyBase64, String displayName,
                          String errorMessage, CertificateDetails certificateDetails) {
            this.success = success;
            this.sessionToken = sessionToken != null ? sessionToken : "";
            this.role = role != null ? role : "";
            this.aesKeyBase64 = aesKeyBase64 != null ? aesKeyBase64 : "";
            this.displayName = displayName != null && !displayName.isEmpty() ? displayName : "";
            this.errorMessage = errorMessage != null ? errorMessage : "";
            this.certificateDetails = certificateDetails != null ? certificateDetails : new CertificateDetails();
        }

        public static AuthResult fail(String error, CertificateDetails certs) {
            return new AuthResult(false, null, null, null, null, error, certs);
        }
    }

    /** Outcome of a self-service {@code REGISTER|...} request over the same TLS channel. */
    public static class RegisterResult {
        public final boolean success;
        public final String username;
        public final String displayName;
        public final String role;
        public final int totalAccounts;
        public final String errorMessage;
        public final CertificateDetails certificateDetails;

        public RegisterResult(boolean success, String username, String displayName, String role, int totalAccounts,
                              String errorMessage, CertificateDetails certificateDetails) {
            this.success = success;
            this.username = username != null ? username : "";
            this.displayName = displayName != null ? displayName : "";
            this.role = role != null ? role : "";
            this.totalAccounts = totalAccounts;
            this.errorMessage = errorMessage != null ? errorMessage : "";
            this.certificateDetails = certificateDetails != null ? certificateDetails : new CertificateDetails();
        }

        public static RegisterResult fail(String error, CertificateDetails certs) {
            return new RegisterResult(false, null, null, null, 0, error, certs);
        }
    }

    private final String host;
    private final int port;
    private final Consumer<PacketLogEvent> telemetrySink;

    public TlsAuthClient(String host, int port, Consumer<PacketLogEvent> telemetrySink) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Select a workspace address first");
        this.host = host.trim();
        this.port = port > 0 ? port : NetworkConfig.TLS_AUTH_PORT;
        this.telemetrySink = telemetrySink != null ? telemetrySink : e -> {};
    }

    public AuthResult authenticate(String username, String plainPassword) {
        return authenticateWithDigest(username, CryptoIntegrityService.computeSha256(plainPassword));
    }

    /**
     * Authenticates using an already computed SHA-256 digest.
     * Used by the auto-reconnect path so the client never has to keep a plaintext password.
     */
    public AuthResult authenticateHashed(String username, String passwordSha256) {
        return authenticateWithDigest(username, passwordSha256);
    }

    /** Sends {@code AUTH|<username>|<sha256(password)>} over the negotiated TLS session. */
    private AuthResult authenticateWithDigest(String username, String passwordDigest) {
        CertificateDetails certDetails = new CertificateDetails();
        try {
            SSLContext sslContext = KeystoreBootstrap.createClientSslContext(host + ":" + port);
            SSLSocketFactory ssf = sslContext.getSocketFactory();

            try (SSLSocket socket = (SSLSocket) ssf.createSocket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
                socket.setEnabledProtocols(new String[]{NetworkConfig.TLS_PROTOCOL});
                socket.setSoTimeout(NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
                socket.startHandshake();

                SSLSession session = socket.getSession();
                certDetails.protocol = session.getProtocol();
                certDetails.cipherSuite = session.getCipherSuite();

                Certificate[] certs = session.getPeerCertificates();
                if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
                    X509Certificate x509 = (X509Certificate) certs[0];
                    certDetails.subjectDN = x509.getSubjectX500Principal().getName();
                    certDetails.issuerDN = x509.getIssuerX500Principal().getName();
                    certDetails.validTo = x509.getNotAfter().toString();
                    certDetails.serialNumber = x509.getSerialNumber().toString(16);
                    certDetails.sha256Fingerprint = CryptoIntegrityService.computeSha256(x509.getEncoded());
                }

                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.TLS_SECURITY,
                    PacketLogEvent.Direction.TX,
                    "TLS Handshake Negotiated",
                    "Connected to " + host + ":" + port + " | " + certDetails.protocol + " | " + certDetails.cipherSuite
                ));

                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                String request = ProtocolConstants.CMD_AUTH + ProtocolConstants.DELIMITER + username + ProtocolConstants.DELIMITER + passwordDigest;
                writer.println(request);

                String response = reader.readLine();
                if (response == null) {
                    return AuthResult.fail("Empty response from TLS Auth Server", certDetails);
                }

                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.TLS_SECURITY,
                    PacketLogEvent.Direction.RX,
                    "Auth Response Received",
                    "Server replied: " + response.split("\\|", 2)[0]
                ));

                String[] parts = response.split(ProtocolConstants.DELIMITER_REGEX, -1);
                if (ProtocolConstants.RESP_AUTH_OK.equals(parts[0]) && parts.length >= 4) {
                    String displayName = parts.length >= 5 ? DataPersistenceManagerHolder.decode(parts[4]) : username;
                    return new AuthResult(true, parts[1], parts[2], parts[3], displayName, null, certDetails);
                } else if (ProtocolConstants.RESP_AUTH_FAIL.equals(parts[0])) {
                    String reason = parts.length > 1 ? parts[1] : "Authentication failed";
                    return AuthResult.fail(reason, certDetails);
                } else {
                    return AuthResult.fail("Malformed server auth reply: " + response, certDetails);
                }
            }

        } catch (Exception e) {
            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TLS_SECURITY,
                PacketLogEvent.Direction.INTERNAL,
                "TLS Client Exception",
                e.getMessage()
            ));
            return AuthResult.fail(e.getMessage(), certDetails);
        }
    }

    /**
     * Self-service student registration over the same TLSv1.3 channel:
     * {@code REGISTER|<username>|<sha256(password)>|<displayName>}.
     * The new account is written to {@code data/users.db} by the auth server.
     */
    public RegisterResult register(String username, String plainPassword, String displayName) {
        return registerWithDigest(username, CryptoIntegrityService.computeSha256(plainPassword), displayName);
    }

    /** Sends {@code REGISTER|<username>|<sha256(password)>|<displayName>} over TLS. */
    private RegisterResult registerWithDigest(String username, String passwordDigest, String displayName) {
        CertificateDetails certDetails = new CertificateDetails();
        try {
            SSLContext sslContext = KeystoreBootstrap.createClientSslContext(host + ":" + port);
            SSLSocketFactory ssf = sslContext.getSocketFactory();

            try (SSLSocket socket = (SSLSocket) ssf.createSocket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
                socket.setEnabledProtocols(new String[]{NetworkConfig.TLS_PROTOCOL});
                socket.setSoTimeout(NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
                socket.startHandshake();

                SSLSession session = socket.getSession();
                certDetails.protocol = session.getProtocol();
                certDetails.cipherSuite = session.getCipherSuite();

                Certificate[] certs = session.getPeerCertificates();
                if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
                    X509Certificate x509 = (X509Certificate) certs[0];
                    certDetails.subjectDN = x509.getSubjectX500Principal().getName();
                    certDetails.issuerDN = x509.getIssuerX500Principal().getName();
                    certDetails.validTo = x509.getNotAfter().toString();
                    certDetails.serialNumber = x509.getSerialNumber().toString(16);
                    certDetails.sha256Fingerprint = CryptoIntegrityService.computeSha256(x509.getEncoded());
                }

                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.TLS_SECURITY,
                    PacketLogEvent.Direction.TX,
                    "TLS Handshake Negotiated (REGISTER)",
                    "Connected to " + host + ":" + port + " | " + certDetails.protocol + " | " + certDetails.cipherSuite
                ));

                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                String request = ProtocolConstants.CMD_REGISTER + ProtocolConstants.DELIMITER
                    + username + ProtocolConstants.DELIMITER
                    + passwordDigest + ProtocolConstants.DELIMITER
                    + com.studyhub.common.DataPersistenceManager.encodeField(displayName == null ? username : displayName);
                writer.println(request);

                String response = reader.readLine();
                if (response == null) {
                    return RegisterResult.fail("Empty response from TLS Auth Server", certDetails);
                }

                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.TLS_SECURITY,
                    PacketLogEvent.Direction.RX,
                    "Register Response Received",
                    "Server replied: " + response.split("\\|", 2)[0]
                ));

                String[] parts = response.split(ProtocolConstants.DELIMITER_REGEX, -1);
                if (ProtocolConstants.RESP_REG_OK.equals(parts[0]) && parts.length >= 4) {
                    int total = 0;
                    if (parts.length >= 5) {
                        try {
                            total = Integer.parseInt(parts[4].trim());
                        } catch (NumberFormatException ignored) {
                            total = 0;
                        }
                    }
                    return new RegisterResult(true, parts[1],
                        com.studyhub.common.DataPersistenceManager.decodeField(parts[2]),
                        parts[3], total, null, certDetails);
                } else if (ProtocolConstants.RESP_REG_FAIL.equals(parts[0])) {
                    return RegisterResult.fail(parts.length > 1 ? parts[1] : "Registration rejected", certDetails);
                } else {
                    return RegisterResult.fail("Malformed server register reply: " + response, certDetails);
                }
            }

        } catch (Exception e) {
            telemetrySink.accept(new PacketLogEvent(
                PacketLogEvent.Module.TLS_SECURITY,
                PacketLogEvent.Direction.INTERNAL,
                "TLS Client Exception",
                e.getMessage()
            ));
            return RegisterResult.fail(e.getMessage(), certDetails);
        }
    }

    /** Tiny indirection so the security package does not need a static import of common helpers. */
    private static final class DataPersistenceManagerHolder {
        static String decode(String value) {
            return com.studyhub.common.DataPersistenceManager.decodeField(value);
        }
    }
}
