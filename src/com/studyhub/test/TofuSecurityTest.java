package com.studyhub.test;

import com.studyhub.security.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Negative handshakes prove TOFU does not degrade into trust-all TLS. */
public final class TofuSecurityTest {
    public static void run() throws Exception {
        Path dir = Files.createTempDirectory(Path.of("build"), "certificate-negative-");
        verifyRejected(dir, "wrong-name", "CN=OtherServer", false);
        verifyRejected(dir, "expired", "CN=StudyHubServer", true);
        System.out.println("  ✓ Wrong certificate identity and expired certificate rejected");
    }
    private static void verifyRejected(Path dir, String name, String dn, boolean expired) throws Exception {
        Path keystore = dir.resolve(name + ".p12");
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "test", "-keyalg", "RSA",
            "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "test-pass", "-keypass", "test-pass", "-dname", dn,
            "-validity", expired ? "1" : "365", "-noprompt"));
        if (expired) { args.add("-startdate"); args.add("-3d"); }
        Process keytool = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(dir.resolve(name + ".log").toFile()).start();
        if (!keytool.waitFor(30, TimeUnit.SECONDS) || keytool.exitValue() != 0) throw new AssertionError("Test certificate generation failed");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) { ks.load(in, "test-pass".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(ks, "test-pass".toCharArray());
        SSLContext context = SSLContext.getInstance("TLSv1.3"); context.init(km.getKeyManagers(), null, null);
        try (SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0)) {
            server.setSoTimeout(10000);
            Thread accept = new Thread(() -> {
                try (SSLSocket peer = (SSLSocket) server.accept()) { peer.setSoTimeout(10000); peer.startHandshake(); }
                catch (IOException expected) { /* Client rejects the certificate. */ }
            });
            accept.setDaemon(true); accept.start();
            TlsAuthClient.AuthResult result = new TlsAuthClient("127.0.0.1", server.getLocalPort(), null).authenticate("student1", "pass123");
            if (result.success || (!result.errorMessage.contains(expired ? "NotAfter" : "identity") && !result.errorMessage.contains("validity")))
                throw new AssertionError("Expected " + name + " certificate rejection: " + result.errorMessage);
            accept.join(11000);
        }
    }
}
