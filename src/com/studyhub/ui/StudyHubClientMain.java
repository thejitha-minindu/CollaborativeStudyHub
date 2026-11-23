package com.studyhub.ui;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Client desktop application entry point.
 * Launches the UDP discovery / TLS login dialog, followed by the main collaborative workspace.
 * When a user chooses "Switch User / Log Out" the workspace disposes itself and this launcher
 * brings the login portal back up, so several group members can share one machine.
 */
public class StudyHubClientMain {
    private static com.studyhub.server.StudyHubServerMain embeddedServer;
    private static String hostedName;

    /** Called only from the onboarding worker, never from the Swing event thread. */
    public static synchronized String hostWorkspace(String name, NetworkInspectorPanel inspector) throws Exception {
        if (embeddedServer == null) {
            com.studyhub.server.StudyHubServerMain candidate = new com.studyhub.server.StudyHubServerMain(name, inspector::logEvent);
            candidate.start(); // Transactional startup cleans up every bound service on failure.
            embeddedServer = candidate;
            hostedName = name;
        }
        return com.studyhub.common.LanAddresses.preferredAddress();
    }

    public static synchronized String hostedWorkspaceName() { return hostedName; }


    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Configure clean desktop look-and-feel
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            com.studyhub.ui.theme.ThemeColors.installDefaults();
            launchSession(new NetworkInspectorPanel());
        });
    }

    /**
     * Shows the login / registration portal and, on success, opens the main hub.
     * Returns immediately after the main frame is shown.
     */
    public static void launchSession(NetworkInspectorPanel inspectorPanel) {
        NetworkInspectorPanel inspector = inspectorPanel != null ? inspectorPanel : new NetworkInspectorPanel();

        LoginDiscoveryDialog loginDialog = new LoginDiscoveryDialog(null, inspector);
        loginDialog.setVisible(true);

        LoginDiscoveryDialog.SessionData session = loginDialog.getSessionData();
        if (session != null) {
            MainHubFrame mainFrame = new MainHubFrame(session, inspector);
            mainFrame.setVisible(true);
        } else {
            System.exit(0);
        }
    }
}
