package com.studyhub.test;

import com.studyhub.ui.*;
import com.studyhub.ui.theme.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Java SE-only Swing rendering and interaction checks; run with a graphical desktop. */
public final class UiSmokeTest {
    private static MainHubFrame workspace;
    private static NetworkInspectorPanel inspector;
    private static final Path OUTPUT = Path.of("build", "ui-review");
    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); ThemeColors.installDefaults();
                    NetworkInspectorPanel panel = new NetworkInspectorPanel();
                    LoginDiscoveryDialog welcome = new LoginDiscoveryDialog(null, panel);
                    welcome.setModal(false); welcome.setVisible(true);
                    require(((JTextField)field(welcome, "username")).getText().isEmpty(), "Sign-in does not advertise or prefill evaluation accounts");
                    require(((JTabbedPane)field(welcome, "workspaceModes")).getSelectedIndex() == 0, "Join workspace is the default");
                    snapshot(welcome, "01-welcome");
                    ((AbstractButton)field(welcome, "create")).doClick();
                    snapshot(welcome, "02-create-account");
                    ((JTabbedPane)field(welcome, "workspaceModes")).setSelectedIndex(1);
                    snapshot(welcome, "03-host-workspace");
                    ((JTextField)field(welcome, "workspaceName")).setText("CS Networking Study Group");
                    require("CS Networking Study Group".equals(call(welcome, "validatedWorkspaceName")), "Workspace names accept ordinary words");
                    ((JTabbedPane)field(welcome, "workspaceModes")).setSelectedIndex(0);
                    AbstractButton manual = button(welcome, "Connect with IP Address"); manual.doClick();
                    ((JTextField)field(welcome, "address")).setText("192.168.1.42");
                    require("192.168.1.42".equals(call(welcome, "joinAddress")), "Manual LAN address passes validation");
                    welcome.setSize(940, 650); welcome.validate();
                    ((JComponent)field(welcome, "primary")).scrollRectToVisible(new Rectangle(0, 0, 100, 36));
                    snapshot(welcome, "07-compact-onboarding");
                    JComponent submit = (JComponent)field(welcome, "primary");
                    require(!submit.getVisibleRect().isEmpty(), "Submit action remains reachable on a short laptop screen");
                    welcome.dispose();
                    LoginDiscoveryDialog.SessionData session = new LoginDiscoveryDialog.SessionData();
                    session.host = "192.0.2.1"; session.nioPort = 9000; session.pulsePort = 9877;
                    session.tlsPort = 9443; session.filePort = 9001; session.username = "alex"; session.displayName = "Alex Morgan";
                    session.role = "STUDENT"; session.workspaceName = "CS Networking"; session.hosting = true;
                    session.sessionToken = "ui-rendering"; session.certificateDetails = new com.studyhub.security.TlsAuthClient.CertificateDetails();
                    inspector = new NetworkInspectorPanel(); workspace = new MainHubFrame(session, inspector); workspace.setVisible(true);
                    require(!inspector.isVisible(), "Diagnostics are hidden by default");
                    require(button(workspace, "Settings") != null && button(workspace, "Invite to workspace") != null, "Settings and invite actions available");
                    require(button(workspace, "Inspector") == null, "No diagnostics button in main chrome");
                    snapshot(workspace, "04-workspace");
                    Object action = workspace.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                        .get(KeyStroke.getKeyStroke(KeyEvent.VK_I, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK));
                    require(action != null, "Ctrl+Shift+I is registered");
                    workspace.getRootPane().getActionMap().get(action).actionPerformed(new ActionEvent(workspace, 0, "diagnostics"));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            Thread.sleep(500);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    require(inspector.isVisible(), "Shortcut opens diagnostics");
                    JTabbedPane tabs = find(inspector, JTabbedPane.class); require(tabs != null && tabs.getTabCount() == 5, "All five networking tabs preserved");
                    tabs.setSelectedIndex(2);
                    AbstractButton tamper = button(inspector, "Simulate MITM File Tampering"); require(tamper != null, "Tamper simulation lives in TLS & Crypto");
                    tamper.doClick(); require((Boolean)field(workspace, "simulateTamperEnabled"), "Evaluation toggle reaches file upload state");
                    snapshot(workspace, "05-developer-diagnostics");
                    workspace.getRootPane().getActionMap().get("diagnostics").actionPerformed(new ActionEvent(workspace, 0, "diagnostics"));
                    require(!(Boolean)field(workspace, "simulateTamperEnabled"), "Closing diagnostics disables tamper simulation");
                    Timer close = new Timer(250, e -> {
                        for (Window window : Window.getWindows()) if (window instanceof JDialog dialog && dialog.isVisible()) {
                            try { snapshot(dialog, "06-settings"); } catch (Exception ex) { throw new RuntimeException(ex); }
                            dialog.dispose();
                        }
                    }); close.setRepeats(false); close.start();
                    button(workspace, "Settings").doClick();
                    call(workspace, "shutdownNetworking"); workspace.dispose();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            System.out.println("Swing smoke checks passed. Rendered previews: " + OUTPUT.toAbsolutePath());
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    private static void snapshot(Window window, String name) throws Exception {
        window.validate();
        BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); window.paintAll(g); g.dispose(); ImageIO.write(image, "png", OUTPUT.resolve(name + ".png").toFile());
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); System.out.println("  ✓ " + message); }
    private static Object field(Object obj, String name) throws Exception { Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
    private static Object call(Object obj, String name) throws Exception { Method m = obj.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(obj); }
    private static AbstractButton button(Container parent, String text) {
        for (Component c : parent.getComponents()) {
            if (c instanceof AbstractButton b && text.equals(b.getText())) return b;
            if (c instanceof Container child) { AbstractButton b = button(child, text); if (b != null) return b; }
        } return null;
    }
    private static <T> T find(Container parent, Class<T> type) {
        for (Component c : parent.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container child) { T result = find(child, type); if (result != null) return result; }
        } return null;
    }
}
