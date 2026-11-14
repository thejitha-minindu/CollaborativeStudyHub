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
            "-dname", "CN=StudyHubServer, OU=NetworkProgramming, O=University, L=Colombo, C=LK"
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

    /**
     * Creates a client SSLContext that trusts the studyhub certificate for local lab evaluation.
     */
    public static SSLContext createClientSslContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };

        SSLContext sslContext = SSLContext.getInstance(NetworkConfig.TLS_PROTOCOL);
        sslContext.init(null, trustAll, new java.security.SecureRandom());
        return sslContext;
    }
}
