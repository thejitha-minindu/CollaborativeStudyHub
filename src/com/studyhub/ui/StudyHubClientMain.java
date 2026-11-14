package com.studyhub.ui;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Client desktop application entry point.
 * Launches the UDP discovery / TLS login dialog, followed by the main collaborative workspace.
 */
public class StudyHubClientMain {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Configure clean desktop look-and-feel
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            NetworkInspectorPanel inspectorPanel = new NetworkInspectorPanel();
            LoginDiscoveryDialog loginDialog = new LoginDiscoveryDialog(null, inspectorPanel);
            loginDialog.setVisible(true);

            LoginDiscoveryDialog.SessionData session = loginDialog.getSessionData();
            if (session != null) {
                MainHubFrame mainFrame = new MainHubFrame(session, inspectorPanel);
                mainFrame.setVisible(true);
            } else {
                System.exit(0);
            }
        });
    }
}
