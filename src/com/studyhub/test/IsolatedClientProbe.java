package com.studyhub.test;

import com.studyhub.security.*;
import java.nio.file.*;

/** Spawned in a completely empty client directory by VerifyIntegrationTest. */
public final class IsolatedClientProbe {
    public static void main(String[] args) throws Exception {
        if (Files.exists(Path.of("certs"))) throw new AssertionError("Probe must start without certs");
        System.setProperty("studyhub.knownHosts", Path.of("client-known-hosts.properties").toAbsolutePath().toString());
        TlsAuthClient.AuthResult result = new TlsAuthClient(args[0], 9443, null).authenticate("student2", "pass123");
        if (!result.success || !"TLSv1.3".equals(result.certificateDetails.protocol)) throw new AssertionError(result.errorMessage);
        if (!Files.exists(KeystoreBootstrap.knownHostsPath()) || Files.exists(Path.of("certs"))) throw new AssertionError("Invalid client certificate bootstrap");
        System.out.println("TLSv1.3 verified; fingerprint " + result.certificateDetails.sha256Fingerprint);
    }
}
