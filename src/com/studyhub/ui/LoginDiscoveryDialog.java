package com.studyhub.ui;

import com.studyhub.common.*;
import com.studyhub.security.*;
import com.studyhub.udp.LanDiscoveryListener;
import com.studyhub.ui.theme.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;

/** One application for joining a nearby workspace or hosting one on this computer. */
public class LoginDiscoveryDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    public static class SessionData {
        public String host, username, displayName, sessionToken, role, aesKeyBase64, passwordSha256;
        public String workspaceName = "StudyHub";
        public boolean hosting;
        public int tlsPort, nioPort, filePort, pulsePort;
        public TlsAuthClient.CertificateDetails certificateDetails;
    }

    private final NetworkInspectorPanel inspectorPanel;
    private final LanDiscoveryListener discovery;
    private SessionData resultSession;
    private String selectedHost = "";
    private String selectedName = "StudyHub";
    private boolean registering, busy, closed, manual;
    private List<LanDiscoveryListener.DiscoveredHub> hubs = List.of();
    private final JPanel serverList = column();
    private final JTabbedPane workspaceModes = new JTabbedPane();
    private final JTextField address = field();
    private final JTextField workspaceName = field();
    private final JTextField username = field();
    private final JTextField displayName = field();
    private final JPasswordField password = new JPasswordField();
    private final JPasswordField confirm = new JPasswordField();
    private final JPanel nameRow = row("Display name", displayName);
    private final JPanel confirmRow = row("Confirm password", confirm);
    private final JLabel status = new JLabel("Choose a workspace to continue.");
    private final JLabel hostAddress = new JLabel("Your Wi-Fi address will appear here.");
    private final PillButton primary = new PillButton("Sign In");
    private final PillButton signIn = secondary("Sign In");
    private final PillButton create = secondary("Create Account");
    private final Radar radar = new Radar();
    private final Timer animation = new Timer(65, e -> radar.advance());

    public LoginDiscoveryDialog(JFrame parent, NetworkInspectorPanel inspector) {
        super(parent, "Welcome to StudyHub", true);
        inspectorPanel = inspector;
        buildUI();
        discovery = new LanDiscoveryListener(inspector::logEvent,
            found -> SwingUtilities.invokeLater(() -> { if (!closed) updateHubs(found); }));
        discovery.start();
        animation.start();
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { if (!busy) dispose(); }
        });
    }

    private void buildUI() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1040, usable.width - 24), Math.min(810, usable.height - 24));
        setMinimumSize(new Dimension(Math.min(900, usable.width - 24), Math.min(600, usable.height - 24)));
        setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(ThemeColors.BG_CHAT);
        JPanel brand = column();
        brand.setBackground(ThemeColors.BG_RAIL);
        brand.setBorder(BorderFactory.createEmptyBorder(40, 30, 32, 30));
        brand.setPreferredSize(new Dimension(300, 0));
        JLabel mark = new JLabel(new VectorIcon(VectorIcon.Kind.BRAND, 52, ThemeColors.ACCENT_PRIMARY));
        mark.setAlignmentX(0);
        brand.add(mark); brand.add(Box.createVerticalStrut(24));
        brand.add(label("StudyHub", ThemeColors.FONT_TITLE.deriveFont(30f)));
        brand.add(Box.createVerticalStrut(12));
        brand.add(label("<html>Local Workspace<br>for Study Groups</html>", ThemeColors.FONT_HEADER.deriveFont(18f)));
        brand.add(Box.createVerticalStrut(26));
        brand.add(label("<html>Make room for your next idea.<br><br>Conversations, shared resources,<br>and your group. Together on Wi-Fi.</html>", ThemeColors.FONT_BODY));
        brand.add(Box.createVerticalGlue());
        brand.add(label("Your group. Your local workspace.", ThemeColors.FONT_MUTED));
        root.add(brand, BorderLayout.WEST);

        JPanel content = new ScrollableColumn();
        content.setBackground(ThemeColors.BG_CHAT);
        content.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
        content.add(label("Welcome to your workspace", ThemeColors.FONT_TITLE.deriveFont(23f)));
        content.add(Box.createVerticalStrut(6));
        content.add(label("Join your group nearby, or start a space of your own.", ThemeColors.FONT_BODY));
        content.add(Box.createVerticalStrut(16));

        JPanel join = column();
        join.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel scan = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); scan.setOpaque(false);
        scan.setAlignmentX(0); scan.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        scan.add(radar); scan.add(label("Looking for workspaces on your Wi-Fi", ThemeColors.FONT_MUTED));
        join.add(scan); join.add(Box.createVerticalStrut(8));
        serverList.setOpaque(false);
        JScrollPane list = new JScrollPane(serverList);
        list.setBorder(null); list.getViewport().setBackground(ThemeColors.BG_PANEL);
        list.getVerticalScrollBar().setUI(new ModernScrollBarUI(5));
        list.setPreferredSize(new Dimension(500, 96));
        join.add(list);
        PillButton manualButton = secondary("Connect with IP Address");
        manualButton.setAlignmentX(0);
        JPanel manualRow = row("Host Wi-Fi IP", address); manualRow.setVisible(false);
        address.setToolTipText("For example: 192.168.1.42");
        manualButton.addActionListener(e -> {
            manual = !manual; manualRow.setVisible(manual);
            if (manual) { address.setText(selectedHost); address.requestFocusInWindow(); }
            join.revalidate();
        });
        join.add(Box.createVerticalStrut(8)); join.add(manualButton); join.add(manualRow);
        workspaceModes.addTab("Join a Workspace", join);

        JPanel host = column(); host.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        workspaceName.setText(StudyHubClientMain.hostedWorkspaceName() == null ? "My Study Group" : StudyHubClientMain.hostedWorkspaceName());
        host.add(row("Workspace name", workspaceName));
        host.add(label("Invite friends on the same Wi-Fi. Keep this app open while hosting.", ThemeColors.FONT_MUTED));
        host.add(Box.createVerticalStrut(12));
        JPanel hostActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); hostActions.setOpaque(false);
        PillButton start = secondary("Start Workspace");
        start.setIcon(new VectorIcon(VectorIcon.Kind.WIFI, 16, ThemeColors.TEXT_PRIMARY));
        start.addActionListener(e -> startHosting());
        PillButton copy = secondary("Copy Address");
        copy.addActionListener(e -> copyHostAddress());
        hostActions.setAlignmentX(0); hostActions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        hostActions.add(start); hostActions.add(copy); host.add(hostActions);
        host.add(Box.createVerticalStrut(12)); hostAddress.setFont(ThemeColors.FONT_BODY); hostAddress.setAlignmentX(0); hostAddress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28)); host.add(hostAddress);
        workspaceModes.addTab("Host a Workspace", host);
        workspaceModes.setUI(new WorkspaceTabsUI());
        workspaceModes.setFont(ThemeColors.FONT_HEADER);
        workspaceModes.setForeground(ThemeColors.TEXT_PRIMARY); workspaceModes.setBackground(ThemeColors.BG_PANEL);
        workspaceModes.setAlignmentX(0);
        workspaceModes.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));
        workspaceModes.setPreferredSize(new Dimension(570, 260));
        workspaceModes.addChangeListener(e -> updatePrimary());
        content.add(workspaceModes); content.add(Box.createVerticalStrut(16));

        JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); modes.setOpaque(false); modes.setAlignmentX(0);
        modes.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        signIn.addActionListener(e -> mode(false)); create.addActionListener(e -> mode(true));
        modes.add(signIn); modes.add(create); content.add(modes); content.add(Box.createVerticalStrut(8));
        content.add(nameRow); content.add(row("Username", username));
        style(password); style(confirm);
        content.add(row("Password", password)); content.add(confirmRow);
        JCheckBox show = new JCheckBox("Show password"); show.setOpaque(false); show.setForeground(ThemeColors.TEXT_MUTED);
        char echo = password.getEchoChar();
        show.addActionListener(e -> { password.setEchoChar(show.isSelected() ? (char) 0 : echo); confirm.setEchoChar(show.isSelected() ? (char) 0 : echo); });
        content.add(show); content.add(Box.createVerticalStrut(10));
        primary.setAlignmentX(0); primary.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        primary.addActionListener(e -> submit()); content.add(primary);
        content.add(Box.createVerticalStrut(10)); status.setAlignmentX(0); status.setFont(ThemeColors.FONT_MUTED);
        status.setForeground(ThemeColors.TEXT_MUTED); status.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54)); content.add(status); content.add(Box.createVerticalGlue());
        JScrollPane contentScroll = new JScrollPane(content, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        contentScroll.setBorder(null); contentScroll.getViewport().setBackground(ThemeColors.BG_CHAT);
        contentScroll.getVerticalScrollBar().setUI(new ModernScrollBarUI(5));
        contentScroll.getVerticalScrollBar().setUnitIncrement(20);
        root.add(contentScroll, BorderLayout.CENTER); setContentPane(root);
        getRootPane().setDefaultButton(primary);
        mode(false); updateHubs(List.of());
        SwingUtilities.invokeLater(username::requestFocusInWindow);
    }

    private void mode(boolean register) {
        registering = register; nameRow.setVisible(register); confirmRow.setVisible(register);
        signIn.setColors(register ? ThemeColors.BG_INPUT : ThemeColors.ACCENT_PRIMARY, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY);
        create.setColors(register ? ThemeColors.ACCENT_PRIMARY : ThemeColors.BG_INPUT, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY);
        updatePrimary(); revalidate();
    }
    private void updatePrimary() {
        primary.setText((workspaceModes.getSelectedIndex() == 1 ? "Host & " : "") + (registering ? "Create Account" : "Sign In"));
    }

    private void updateHubs(List<LanDiscoveryListener.DiscoveredHub> found) {
        hubs = List.copyOf(found);
        if (busy) return;
        serverList.removeAll();
        if (hubs.isEmpty()) {
            serverList.add(label("No workspaces yet. Start one, or connect with an IP address.", ThemeColors.FONT_MUTED));
        } else {
            for (LanDiscoveryListener.DiscoveredHub hub : hubs) {
                boolean selected = hub.hostAddress.equals(selectedHost) && hub.hubName.equals(selectedName);
                PillButton card = new PillButton(hub.hubName + "   ·   " + hub.hostAddress + "   ·   " + hub.activeUsers + " online",
                    selected ? ThemeColors.BG_HOVER : ThemeColors.BG_CARD, ThemeColors.BG_HOVER, ThemeColors.TEXT_PRIMARY, 10);
                card.setIcon(new ActiveDot());
                card.setToolTipText(hub.hubName + " at " + hub.hostAddress);
                card.setBorderColor(selected ? ThemeColors.BORDER_ACTIVE : ThemeColors.BORDER_SUBTLE);
                card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
                card.addActionListener(e -> { selectedHost = hub.hostAddress; selectedName = hub.hubName; manual = false;
                    address.setText(selectedHost); updateHubs(hubs); status("Ready to join " + hub.hubName, false); });
                serverList.add(card); serverList.add(Box.createVerticalStrut(6));
            }
        }
        serverList.revalidate(); serverList.repaint();
    }

    private String validatedWorkspaceName() {
        String value = workspaceName.getText().trim();
        if (value.isEmpty() || value.length() > 48 || value.matches("(?s).*[|\\r\\n].*"))
            throw new IllegalArgumentException("Enter a workspace name with 1–48 characters and no | symbol.");
        return value;
    }
    private String joinAddress() {
        String value = manual ? address.getText().trim() : selectedHost;
        if (!value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))
            throw new IllegalArgumentException("Select a workspace or enter its Wi-Fi IPv4 address.");
        for (String part : value.split("\\.")) if (Integer.parseInt(part) > 255)
            throw new IllegalArgumentException("Enter a valid IPv4 address, such as 192.168.1.42.");
        if (value.equals("0.0.0.0") || Integer.parseInt(value.split("\\.")[0]) >= 224)
            throw new IllegalArgumentException("Enter the host laptop's IPv4 address.");
        return value;
    }
    private void startHosting() {
        if (busy) return;
        final String name;
        try { name = validatedWorkspaceName(); } catch (IllegalArgumentException e) { status(e.getMessage(), true); return; }
        setBusy(true); status("Starting your workspace…", false);
        new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception { return StudyHubClientMain.hostWorkspace(name, inspectorPanel); }
            protected void done() {
                setBusy(false);
                try { String ip = get(); hosted(ip); status("Workspace is ready. Sign in or create your account below.", false); }
                catch (Exception e) { status(hostError(e), true); }
            }
        }.execute();
    }
    private void hosted(String ip) {
        hostAddress.setText("Wi-Fi address: " + ip); selectedHost = ip;
        selectedName = StudyHubClientMain.hostedWorkspaceName(); workspaceName.setText(selectedName); workspaceName.setEditable(false);
    }
    private void copyHostAddress() {
        if (StudyHubClientMain.hostedWorkspaceName() == null) { status("Start the workspace to get a shareable address.", true); return; }
        String ip = LanAddresses.preferredAddress();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(ip), null);
        hostAddress.setText("Wi-Fi address: " + ip + "  ·  Copied");
    }

    private void submit() {
        if (busy) return;
        final boolean host = workspaceModes.getSelectedIndex() == 1;
        final boolean register = registering;
        final boolean manualAddress = manual;
        final String target, name, user = username.getText().trim(), display = displayName.getText().trim();
        final String pass = new String(password.getPassword());
        try {
            target = host ? "" : joinAddress(); name = host ? validatedWorkspaceName() : selectedName;
            if (!user.matches("[A-Za-z0-9_.]{3,24}")) throw new IllegalArgumentException("Username: 3–24 letters, numbers, _ or .");
            if (pass.isEmpty()) throw new IllegalArgumentException("Enter your password.");
            if (register && (display.isEmpty() || display.length() > 48)) throw new IllegalArgumentException("Display name: 1–48 characters.");
            if (register && pass.length() < 6) throw new IllegalArgumentException("Choose a password with at least 6 characters.");
            if (register && !pass.equals(new String(confirm.getPassword()))) throw new IllegalArgumentException("Your passwords do not match.");
        } catch (IllegalArgumentException e) { status(e.getMessage(), true); return; }
        setBusy(true); status(host ? "Starting your workspace…" : "Connecting securely…", false);
        new SwingWorker<SessionData, Void>() {
            protected SessionData doInBackground() throws Exception {
                String ip = host ? StudyHubClientMain.hostWorkspace(name, inspectorPanel) : target;
                TlsAuthClient auth = new TlsAuthClient(ip, NetworkConfig.TLS_AUTH_PORT, inspectorPanel::logEvent);
                if (register) {
                    TlsAuthClient.RegisterResult created = auth.register(user, pass, display);
                    if (!created.success) throw new IllegalStateException(created.errorMessage);
                }
                TlsAuthClient.AuthResult result = auth.authenticate(user, pass);
                if (!result.success) throw new IllegalStateException(result.errorMessage);
                SessionData data = new SessionData();
                data.host = ip; data.workspaceName = host ? StudyHubClientMain.hostedWorkspaceName() : (manualAddress ? "StudyHub · " + ip : name);
                data.hosting = StudyHubClientMain.hostedWorkspaceName() != null && ip.equals(LanAddresses.preferredAddress());
                data.tlsPort = NetworkConfig.TLS_AUTH_PORT; data.nioPort = NetworkConfig.NIO_CHAT_PORT;
                data.filePort = NetworkConfig.FILE_TRANSFER_PORT; data.pulsePort = NetworkConfig.UDP_PULSE_PORT;
                data.username = user; data.displayName = result.displayName; data.sessionToken = result.sessionToken;
                data.role = result.role; data.aesKeyBase64 = result.aesKeyBase64;
                data.passwordSha256 = CryptoIntegrityService.computeSha256(pass); data.certificateDetails = result.certificateDetails;
                return data;
            }
            protected void done() {
                setBusy(false);
                if (host && StudyHubClientMain.hostedWorkspaceName() != null) hosted(LanAddresses.preferredAddress());
                try {
                    resultSession = get();
                    TlsAuthClient.CertificateDetails c = resultSession.certificateDetails;
                    inspectorPanel.updateTlsDetails(c.cipherSuite, c.protocol, c.subjectDN, c.sha256Fingerprint);
                    password.setText(""); confirm.setText(""); dispose();
                } catch (Exception e) { status(hostError(e), true); }
            }
        }.execute();
    }
    private static String hostError(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage() == null ? "Unable to connect" : cause.getMessage();
        if (cause instanceof java.net.BindException) message = "A workspace is already using these ports. Join it or close the other host.";
        return message + (message.contains("timed out") || message.contains("refused") ? ". Check the host address and Private-network firewall rules." : "");
    }
    private void setBusy(boolean value) { busy = value; enableChildren(getContentPane(), !value); if (!value) updateHubs(hubs); }
    private static void enableChildren(Container parent, boolean value) {
        for (Component child : parent.getComponents()) { child.setEnabled(value); if (child instanceof Container c) enableChildren(c, value); }
    }
    private void status(String message, boolean error) {
        status.setText("<html>" + escape(message) + "</html>");
        status.setForeground(error ? ThemeColors.STATUS_DANGER : ThemeColors.TEXT_MUTED);
    }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static JPanel column() { JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.setAlignmentX(0); return p; }
    private static JLabel label(String text, Font font) { JLabel l = new JLabel(text); l.setFont(font); l.setForeground(ThemeColors.TEXT_PRIMARY); l.setAlignmentX(0); l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height + 2)); return l; }
    private static JTextField field() { JTextField f = new JTextField(); style(f); return f; }
    private static void style(JTextField f) {
        f.setFont(ThemeColors.FONT_BODY); f.setBackground(ThemeColors.BG_INPUT); f.setForeground(ThemeColors.TEXT_PRIMARY); f.setCaretColor(ThemeColors.TEXT_PRIMARY);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ThemeColors.BORDER_SUBTLE), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }
    private static JPanel row(String title, JTextField f) {
        JPanel p = new JPanel(new BorderLayout(10, 0)); p.setOpaque(false); p.setAlignmentX(0);
        JLabel l = label(title, ThemeColors.FONT_BODY); l.setLabelFor(f); l.setPreferredSize(new Dimension(130, 32));
        p.add(l, BorderLayout.WEST); p.add(f, BorderLayout.CENTER); p.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46)); return p;
    }
    private static PillButton secondary(String text) { return new PillButton(text, ThemeColors.BG_INPUT, ThemeColors.BG_HOVER, ThemeColors.TEXT_PRIMARY, 10); }
    public SessionData getSessionData() { return resultSession; }
    @Override public void dispose() { closed = true; animation.stop(); if (discovery != null) discovery.stop(); super.dispose(); }

    /** Short laptop screens can scroll the full form without hiding the submit action. */
    private static class ScrollableColumn extends JPanel implements Scrollable {
        ScrollableColumn() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 20; }
        public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return Math.max(20, r.height - 40); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return getParent() != null && getPreferredSize().height < getParent().getHeight(); }
    }

    private static class ActiveDot implements Icon {
        public int getIconWidth() { return 10; } public int getIconHeight() { return 10; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D p = (Graphics2D) g.create(); p.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            p.setColor(ThemeColors.STATUS_ONLINE); p.fillOval(x + 1, y + 1, 8, 8); p.dispose();
        }
    }
    private static class Radar extends JComponent {
        private double angle;
        Radar() { setPreferredSize(new Dimension(22, 22)); }
        void advance() { angle += .09; repaint(); }
        protected void paintComponent(Graphics g) {
            Graphics2D p = (Graphics2D) g.create(); p.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            p.setColor(ThemeColors.BORDER_SUBTLE); p.drawOval(2, 2, 17, 17); p.drawOval(6, 6, 9, 9);
            p.setColor(ThemeColors.ACCENT_PRIMARY); p.drawLine(11, 11, 11 + (int)(8 * Math.cos(angle)), 11 + (int)(8 * Math.sin(angle))); p.dispose();
        }
    }
}
