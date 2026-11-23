package com.studyhub.test;

import com.studyhub.server.StudyHubServerMain;
import com.studyhub.ui.*;
import com.studyhub.udp.LanDiscoveryListener;
import com.studyhub.security.TlsAuthClient;
import java.net.*;
import javax.swing.SwingUtilities;

/** Conflicted embedded startup must roll back and permit a clean retry on the original ports. */
public final class ServerLifecycleTest {
    public static void main(String[] args) {
        int exit = 0;
        System.setProperty("studyhub.knownHosts", "build/lifecycle-known-hosts.properties");
        try {
            StudyHubServerMain candidate = new StudyHubServerMain();
            boolean rejected = false;
            try (ServerSocket blocker = new ServerSocket(9001)) {
                try { candidate.start(); }
                catch (BindException expected) { rejected = true; }
            } finally { candidate.stop(); }
            if (!rejected) throw new AssertionError("Conflicting startup must fail");
            try (ServerSocket tls = new ServerSocket(9443); ServerSocket nio = new ServerSocket(9000)) {
                System.out.println("  ✓ Partial host startup releases TLS and NIO ports");
            }
            NetworkInspectorPanel[] holder = new NetworkInspectorPanel[1];
            SwingUtilities.invokeAndWait(() -> holder[0] = new NetworkInspectorPanel());
            LanDiscoveryListener discovery = new LanDiscoveryListener(null, null); discovery.start();
            try {
                String address = StudyHubClientMain.hostWorkspace("CS Networking Study Group", holder[0]);
                String reused = StudyHubClientMain.hostWorkspace("Second click", holder[0]);
                if (!address.equals(reused) || !"CS Networking Study Group".equals(StudyHubClientMain.hostedWorkspaceName()))
                    throw new AssertionError("Embedded startup must be idempotent");
                TlsAuthClient.AuthResult signedIn = new TlsAuthClient(address, 9443, null).authenticate("student1", "pass123");
                if (!signedIn.success) throw new AssertionError(signedIn.errorMessage);
                long deadline = System.currentTimeMillis() + 6000;
                while (discovery.getDiscoveredHubs().stream().noneMatch(h -> h.hubName.equals("CS Networking Study Group"))
                        && System.currentTimeMillis() < deadline) Thread.sleep(25);
                if (discovery.getDiscoveredHubs().stream().noneMatch(h -> h.hubName.equals("CS Networking Study Group")))
                    throw new AssertionError("Embedded host must advertise the chosen workspace name");
                System.out.println("  ✓ One-app hosting retries, signs in, advertises the workspace, and reuses all services on a second click");
            } finally { discovery.stop(); }
            System.out.println("Host lifecycle checks passed.");
        } catch (Throwable e) { e.printStackTrace(); exit = 1; }
        System.exit(exit); // Exercises the real host shutdown hook, including persistence flush.
    }
}
