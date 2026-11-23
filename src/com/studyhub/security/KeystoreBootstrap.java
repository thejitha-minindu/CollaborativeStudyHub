package com.studyhub.security;

import com.studyhub.common.NetworkConfig;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * [MEMBER 3: Secure Network Programming]
 * Automated PKCS12 Keystore bootstrap service.
 * Ensures certificates exist without requiring manual developer setup.
 */
public class KeystoreBootstrap {

    /**
     * Ensures certs/studyhub_keystore.p12 exists, generating it via keytool if absent.
     */
    public static synchronized void ensureKeystoreExists() throws Exception {
        File keystoreFile = new File(NetworkConfig.KEYSTORE_PATH);
        if (keystoreFile.exists() && keystoreFile.length() > 0) {
            return;
        }

        File parent = keystoreFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        String javaHome = System.getProperty("java.home");
        String keytoolPath = "keytool";
        File javaBinKeytool = new File(javaHome, "bin/keytool.exe");
        if (javaBinKeytool.exists()) {
            keytoolPath = javaBinKeytool.getAbsolutePath();
        } else {
            File unixKeytool = new File(javaHome, "bin/keytool");
            if (unixKeytool.exists()) {
                keytoolPath = unixKeytool.getAbsolutePath();
            }
        }

        ProcessBuilder pb = new ProcessBuilder(
            keytoolPath,
            "-genkeypair",
            "-alias", NetworkConfig.KEY_ALIAS,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-storetype", NetworkConfig.KEYSTORE_TYPE,
            "-keystore", NetworkConfig.KEYSTORE_PATH,
            "-validity", "365",
            "-storepass", NetworkConfig.KEYSTORE_PASSWORD,
            "-keypass", NetworkConfig.KEYSTORE_PASSWORD,
            "-dname", "CN=StudyHubServer, O=StudyHub"
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            InputStream err = process.getInputStream();
            String errorMsg = new String(err.readAllBytes());
            throw new IllegalStateException("Failed to bootstrap keystore via keytool: " + errorMsg);
        }
    }

    /**
     * Loads the server SSLContext configured with the studyhub keystore.
     */
    public static SSLContext createServerSslContext() throws Exception {
        ensureKeystoreExists();

        KeyStore keyStore = KeyStore.getInstance(NetworkConfig.KEYSTORE_TYPE);
        try (FileInputStream fis = new FileInputStream(NetworkConfig.KEYSTORE_PATH)) {
            keyStore.load(fis, NetworkConfig.KEYSTORE_PASSWORD.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, NetworkConfig.KEYSTORE_PASSWORD.toCharArray());

        SSLContext sslContext = SSLContext.getInstance(NetworkConfig.TLS_PROTOCOL);
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    /** No server keystore or shared truststore is loaded on the client. */
    public static SSLContext createClientSslContext() throws Exception {
        return createClientSslContext("default");
    }

    /** TOFU: inspect identity and validity, then persist the certificate pin per host + port. */
    public static SSLContext createClientSslContext(String endpoint) throws Exception {
        TrustManager[] trust = { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                throw new java.security.cert.CertificateException("Client certificates are not supported");
            }
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("Missing server certificate");
                X509Certificate leaf = chain[0];
                leaf.checkValidity();
                try {
                    boolean identity = new javax.naming.ldap.LdapName(leaf.getSubjectX500Principal().getName())
                        .getRdns().stream().anyMatch(r -> "CN".equalsIgnoreCase(r.getType()) && "StudyHubServer".equals(r.getValue()));
                    if (!identity) throw new java.security.cert.CertificateException("Expected StudyHubServer certificate identity");
                    if (chain.length == 1) leaf.verify(leaf.getPublicKey());
                    else {
                        for (int i = 0; i < chain.length - 1; i++) {
                            chain[i + 1].checkValidity();
                            chain[i].verify(chain[i + 1].getPublicKey());
                        }
                    }
                    rememberCertificate(endpoint, CryptoIntegrityService.computeSha256(leaf.getEncoded()));
                } catch (java.security.cert.CertificateException e) { throw e; }
                catch (Exception e) { throw new java.security.cert.CertificateException("Unable to verify workspace certificate: " + e.getMessage(), e); }
            }
        }};
        SSLContext context = SSLContext.getInstance(NetworkConfig.TLS_PROTOCOL);
        context.init(null, trust, new java.security.SecureRandom());
        return context;
    }

    /** Stable workspace encryption key, delivered only after TLS authentication. */
    public static synchronized String workspaceAesKey() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of("certs", "workspace.key");
        if (java.nio.file.Files.exists(path)) {
            String key = java.nio.file.Files.readString(path).trim();
            if (java.util.Base64.getDecoder().decode(key).length != 16) throw new java.io.IOException("Invalid workspace key");
            return key;
        }
        java.nio.file.Files.createDirectories(path.getParent());
        String key = CryptoIntegrityService.generateAesKeyBase64();
        java.nio.file.Files.writeString(path, key, java.nio.file.StandardOpenOption.CREATE_NEW);
        return key;
    }

    public static java.nio.file.Path knownHostsPath() {
        return java.nio.file.Path.of(System.getProperty("studyhub.knownHosts",
            java.nio.file.Path.of(System.getProperty("user.home"), ".studyhub", "known_hosts.properties").toString()));
    }

    private static synchronized void rememberCertificate(String endpoint, String fingerprint) throws Exception {
        java.nio.file.Path path = knownHostsPath().toAbsolutePath();
        java.nio.file.Files.createDirectories(path.getParent());
        // Coordinate separate client processes as well as threads. Never overwrite another client's pins.
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                path.resolveSibling(path.getFileName() + ".lock"), java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = channel.lock()) {
            java.util.Properties pins = new java.util.Properties();
            if (java.nio.file.Files.exists(path)) {
                try (InputStream in = java.nio.file.Files.newInputStream(path)) { pins.load(in); }
            }
            String previous = pins.getProperty(endpoint);
            if (previous != null && !previous.equals(fingerprint)) {
                throw new java.security.cert.CertificateException("Workspace certificate changed for " + endpoint
                    + ". Compare its fingerprint with the host before removing its saved pin in " + path);
            }
            if (previous == null) {
                pins.setProperty(endpoint, fingerprint);
                java.nio.file.Path temp = java.nio.file.Files.createTempFile(path.getParent(), "known-hosts-", ".tmp");
                try {
                    try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(temp)) {
                        pins.store(out, "StudyHub TOFU certificate fingerprints (SHA-256)");
                    }
                    try { java.nio.file.Files.move(temp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                    catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        java.nio.file.Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { java.nio.file.Files.deleteIfExists(temp); }
            }
        }
    }
}
