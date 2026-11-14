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
        public final String errorMessage;
        public final CertificateDetails certificateDetails;

        public AuthResult(boolean success, String sessionToken, String role, String aesKeyBase64,
                          String errorMessage, CertificateDetails certificateDetails) {
            this.success = success;
            this.sessionToken = sessionToken != null ? sessionToken : "";
            this.role = role != null ? role : "";
            this.aesKeyBase64 = aesKeyBase64 != null ? aesKeyBase64 : "";
            this.errorMessage = errorMessage != null ? errorMessage : "";
            this.certificateDetails = certificateDetails != null ? certificateDetails : new CertificateDetails();
        }

        public static AuthResult fail(String error, CertificateDetails certs) {
            return new AuthResult(false, null, null, null, error, certs);
        }
    }

    private final String host;
    private final int port;
    private final Consumer<PacketLogEvent> telemetrySink;

    public TlsAuthClient(String host, int port, Consumer<PacketLogEvent> telemetrySink) {
        this.host = host != null ? host : NetworkConfig.DEFAULT_HOST;
        this.port = port > 0 ? port : NetworkConfig.TLS_AUTH_PORT;
        this.telemetrySink = telemetrySink != null ? telemetrySink : e -> {};
    }

    public AuthResult authenticate(String username, String plainPassword) {
        CertificateDetails certDetails = new CertificateDetails();
        try {
            SSLContext sslContext = KeystoreBootstrap.createClientSslContext();
            SSLSocketFactory ssf = sslContext.getSocketFactory();

            try (SSLSocket socket = (SSLSocket) ssf.createSocket(host, port)) {
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

                String passwordHash = CryptoIntegrityService.computeSha256(plainPassword);
                String request = ProtocolConstants.CMD_AUTH + ProtocolConstants.DELIMITER + username + ProtocolConstants.DELIMITER + passwordHash;
                writer.println(request);

                String response = reader.readLine();
                if (response == null) {
                    return AuthResult.fail("Empty response from TLS Auth Server", certDetails);
                }

                telemetrySink.accept(new PacketLogEvent(
                    PacketLogEvent.Module.TLS_SECURITY,
                    PacketLogEvent.Direction.RX,
                    "Auth Response Received",
                    response
                ));

                String[] parts = response.split(ProtocolConstants.DELIMITER_REGEX);
                if (ProtocolConstants.RESP_AUTH_OK.equals(parts[0]) && parts.length >= 4) {
                    return new AuthResult(true, parts[1], parts[2], parts[3], null, certDetails);
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
}
