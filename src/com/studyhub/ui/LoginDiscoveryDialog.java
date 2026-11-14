package com.studyhub.ui;

import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.security.TlsAuthClient;
import com.studyhub.udp.LanDiscoveryListener;
import com.studyhub.ui.theme.AvatarComponent;
import com.studyhub.ui.theme.ModernScrollBarUI;
import com.studyhub.ui.theme.PillButton;
import com.studyhub.ui.theme.RoundedPanel;
import com.studyhub.ui.theme.ThemeColors;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * Modern Discord-styled onboarding and LAN auto-discovery portal.
 * Features live UDP server cards, one-click demo account switcher pills,
 * and secure TLSv1.3 mutual cipher negotiation.
 */
public class LoginDiscoveryDialog extends JDialog {

    public static class SessionData {
        public String host;
        public int tlsPort;
        public int nioPort;
        public int filePort;
        public int pulsePort;
        public String username;
        public String sessionToken;
        public String role;
        public String aesKeyBase64;
        public TlsAuthClient.CertificateDetails certificateDetails;
    }

    private final NetworkInspectorPanel inspectorPanel;
    private LanDiscoveryListener discoveryListener;
    private SessionData resultSession = null;

    // Discovered servers container
    private final JPanel serversListPanel = new JPanel();
    private final JScrollPane serversScrollPane = new JScrollPane(serversListPanel);
    private final List<LanDiscoveryListener.DiscoveredHub> cachedHubs = new ArrayList<>();

    // Connection & Credential state
    private String selectedHost = NetworkConfig.DEFAULT_HOST;
    private int selectedTlsPort = NetworkConfig.TLS_AUTH_PORT;
    private String currentUsername = "student1";
    private String currentPassword = "pass123";

    // Collapsible manual connection panel
    private JPanel manualSection;
    private boolean manualExpanded = false;
    private JTextField hostField;
    private JTextField tlsPortField;
    private JTextField userField;
    private JPasswordField passField;

    private JLabel statusLabel;
    private PillButton connectButton;
    private PillButton[] accountPills;

    public LoginDiscoveryDialog(JFrame parent, NetworkInspectorPanel inspectorPanel) {
        super(parent, "Join Collaborative Study Hub", true);
        this.inspectorPanel = inspectorPanel;

        buildModernUI();
        startUdpDiscovery();
    }

    private void buildModernUI() {
        setSize(820, 640);
        setMinimumSize(new Dimension(740, 580));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        getContentPane().setBackground(ThemeColors.BG_RAIL);

        // Centered Card Container
        JPanel centerWrapper = new JPanel(new GridBagLayout());
        centerWrapper.setOpaque(false);

        RoundedPanel mainCard = new RoundedPanel(18, ThemeColors.BG_PANEL, ThemeColors.BORDER_SUBTLE, 1);
        mainCard.setPreferredSize(new Dimension(700, 540));
        mainCard.setLayout(new BorderLayout(0, 16));
        mainCard.setBorder(BorderFactory.createEmptyBorder(24, 28, 20, 28));

        // 1. Header (App Icon + Title + Subtitle)
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setOpaque(false);

        JLabel logoLabel = new JLabel("🎓 COLLABORATIVE STUDY HUB");
        logoLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        logoLabel.setForeground(ThemeColors.TEXT_PRIMARY);
        logoLabel.setAlignmentX(0.0f);

        JLabel subLabel = new JLabel("Zero-Config LAN Discovery & TLSv1.3 Secure Academic Workspace");
        subLabel.setFont(ThemeColors.FONT_MUTED);
        subLabel.setForeground(ThemeColors.TEXT_MUTED);
        subLabel.setAlignmentX(0.0f);

        headerPanel.add(logoLabel);
        headerPanel.add(Box.createVerticalStrut(4));
        headerPanel.add(subLabel);

        // 2. Middle Content (Servers List + Account Switcher)
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setOpaque(false);

        // Section A: LAN Servers header
        JPanel serversHeader = new JPanel(new BorderLayout());
        serversHeader.setOpaque(false);
        serversHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JLabel serversTitle = new JLabel("DISCOVERED LAN HUBS (UDP Port 9876)");
        serversTitle.setFont(ThemeColors.FONT_BOLD);
        serversTitle.setForeground(ThemeColors.ACCENT_PRIMARY);

        JLabel pulseIndicator = new JLabel("● LIVE SEARCHING");
        pulseIndicator.setFont(ThemeColors.FONT_MUTED);
        pulseIndicator.setForeground(ThemeColors.STATUS_ONLINE);

        serversHeader.add(serversTitle, BorderLayout.WEST);
        serversHeader.add(pulseIndicator, BorderLayout.EAST);

        // Servers Scroll List
        serversListPanel.setLayout(new BoxLayout(serversListPanel, BoxLayout.Y_AXIS));
        serversListPanel.setBackground(ThemeColors.BG_CARD);
        serversListPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        serversScrollPane.setPreferredSize(new Dimension(640, 115));
        serversScrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 115));
        serversScrollPane.setBackground(ThemeColors.BG_CARD);
        serversScrollPane.getViewport().setBackground(ThemeColors.BG_CARD);
        serversScrollPane.setBorder(BorderFactory.createLineBorder(ThemeColors.BORDER_SUBTLE, 1));
        serversScrollPane.getVerticalScrollBar().setUI(new ModernScrollBarUI(6));

        renderEmptyHubsPlaceholder();

        // Section B: Quick Account Switcher
        JLabel accTitle = new JLabel("SELECT EVALUATION PROFILE (2-MINUTE DEMO)");
        accTitle.setFont(ThemeColors.FONT_BOLD);
        accTitle.setForeground(ThemeColors.TEXT_MUTED);
        accTitle.setAlignmentX(0.0f);

        JPanel accountPillsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        accountPillsBar.setOpaque(false);
        accountPillsBar.setAlignmentX(0.0f);
        accountPillsBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        accountPills = new PillButton[4];
        accountPills[0] = createAccountPill("Student 1 (student1)", "student1", "pass123", true);
        accountPills[1] = createAccountPill("Student 2 (student2)", "student2", "pass123", false);
        accountPills[2] = createAccountPill("Student 3 (student3)", "student3", "pass123", false);
        accountPills[3] = createAccountPill("Moderator (admin)", "admin", "admin123", false);

        for (PillButton p : accountPills) {
            accountPillsBar.add(p);
        }

        // Section C: Collapsible Manual Connection Section
        JPanel toggleManualBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        toggleManualBar.setOpaque(false);
        toggleManualBar.setAlignmentX(0.0f);

        JButton toggleManualBtn = new JButton("▶ Manual Connection Settings (Advanced)");
        toggleManualBtn.setFont(ThemeColors.FONT_MUTED);
        toggleManualBtn.setForeground(ThemeColors.TEXT_MUTED);
        toggleManualBtn.setContentAreaFilled(false);
        toggleManualBtn.setBorderPainted(false);
        toggleManualBtn.setFocusPainted(false);
        toggleManualBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        manualSection = createManualSettingsPanel();
        manualSection.setVisible(false);

        toggleManualBtn.addActionListener(e -> {
            manualExpanded = !manualExpanded;
            toggleManualBtn.setText((manualExpanded ? "▼ " : "▶ ") + "Manual Connection Settings (Advanced)");
            manualSection.setVisible(manualExpanded);
            mainCard.revalidate();
            mainCard.repaint();
        });

        toggleManualBar.add(toggleManualBtn);

        contentPanel.add(serversHeader);
        contentPanel.add(Box.createVerticalStrut(6));
        contentPanel.add(serversScrollPane);
        contentPanel.add(Box.createVerticalStrut(14));
        contentPanel.add(accTitle);
        contentPanel.add(Box.createVerticalStrut(8));
        contentPanel.add(accountPillsBar);
        contentPanel.add(Box.createVerticalStrut(10));
        contentPanel.add(toggleManualBar);
        contentPanel.add(manualSection);

        // 3. Footer Bar (Status + Connect Button)
        JPanel footerBar = new JPanel(new BorderLayout(12, 0));
        footerBar.setOpaque(false);

        statusLabel = new JLabel("Ready. Select a server and profile to begin.");
        statusLabel.setFont(ThemeColors.FONT_MUTED);
        statusLabel.setForeground(ThemeColors.TEXT_MUTED);

        connectButton = new PillButton("Enter Study Hub", ThemeColors.ACCENT_PRIMARY, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY, 20);
        connectButton.setPreferredSize(new Dimension(170, 40));
        connectButton.addActionListener(e -> performLogin());

        footerBar.add(statusLabel, BorderLayout.CENTER);
        footerBar.add(connectButton, BorderLayout.EAST);

        mainCard.add(headerPanel, BorderLayout.NORTH);
        mainCard.add(contentPanel, BorderLayout.CENTER);
        mainCard.add(footerBar, BorderLayout.SOUTH);

        centerWrapper.add(mainCard);
        add(centerWrapper, BorderLayout.CENTER);
    }

    private PillButton createAccountPill(String label, String user, String pass, boolean initialSelected) {
        PillButton pill = new PillButton(label,
            initialSelected ? ThemeColors.ACCENT_PRIMARY : ThemeColors.BG_INPUT,
            ThemeColors.ACCENT_HOVER,
            ThemeColors.TEXT_PRIMARY, 16);

        pill.addActionListener(e -> {
            currentUsername = user;
            currentPassword = pass;
            if (userField != null) userField.setText(user);
            if (passField != null) passField.setText(pass);

            // Highlight selected pill
            for (PillButton p : accountPills) {
                p.setColors(ThemeColors.BG_INPUT, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_MUTED);
            }
            pill.setColors(ThemeColors.ACCENT_PRIMARY, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY);
        });

        return pill;
    }

    private JPanel createManualSettingsPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        panel.setOpaque(false);
        panel.setAlignmentX(0.0f);

        hostField = new JTextField(selectedHost, 11);
        styleTextField(hostField);

        tlsPortField = new JTextField(String.valueOf(selectedTlsPort), 5);
        styleTextField(tlsPortField);

        userField = new JTextField(currentUsername, 8);
        styleTextField(userField);

        passField = new JPasswordField(currentPassword, 8);
        styleTextField(passField);

        panel.add(createInputLabel("Host:"));
        panel.add(hostField);
        panel.add(createInputLabel("TLS Port:"));
        panel.add(tlsPortField);
        panel.add(createInputLabel("User:"));
        panel.add(userField);
        panel.add(createInputLabel("Pass:"));
        panel.add(passField);

        return panel;
    }

    private void styleTextField(JTextField f) {
        f.setBackground(ThemeColors.BG_INPUT);
        f.setForeground(ThemeColors.TEXT_PRIMARY);
        f.setCaretColor(ThemeColors.TEXT_PRIMARY);
        f.setFont(ThemeColors.FONT_MONO);
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ThemeColors.BORDER_SUBTLE, 1),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)
        ));
    }

    private JLabel createInputLabel(String txt) {
        JLabel l = new JLabel(txt);
        l.setFont(ThemeColors.FONT_MUTED);
        l.setForeground(ThemeColors.TEXT_MUTED);
        return l;
    }

    private void renderEmptyHubsPlaceholder() {
        serversListPanel.removeAll();
        JPanel emptyCard = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 12));
        emptyCard.setOpaque(false);

        JLabel fallbackHost = new JLabel("Connecting to Localhost (" + selectedHost + ":" + selectedTlsPort + ") or waiting for UDP broadcast...");
        fallbackHost.setFont(ThemeColors.FONT_MUTED);
        fallbackHost.setForeground(ThemeColors.TEXT_MUTED);

        emptyCard.add(fallbackHost);
        serversListPanel.add(emptyCard);
        serversListPanel.revalidate();
        serversListPanel.repaint();
    }

    private void startUdpDiscovery() {
        discoveryListener = new LanDiscoveryListener(
            inspectorPanel != null ? inspectorPanel::logEvent : null,
            hubs -> SwingUtilities.invokeLater(() -> updateHubsView(hubs))
        );
        discoveryListener.start();
    }

    private void updateHubsView(List<LanDiscoveryListener.DiscoveredHub> hubs) {
        cachedHubs.clear();
        cachedHubs.addAll(hubs);
        serversListPanel.removeAll();

        if (hubs.isEmpty()) {
            renderEmptyHubsPlaceholder();
            return;
        }

        for (LanDiscoveryListener.DiscoveredHub hub : hubs) {
            JPanel card = createHubCard(hub);
            serversListPanel.add(card);
            serversListPanel.add(Box.createVerticalStrut(4));
        }

        serversListPanel.revalidate();
        serversListPanel.repaint();
    }

    private JPanel createHubCard(LanDiscoveryListener.DiscoveredHub hub) {
        boolean isSelected = hub.hostAddress.equals(selectedHost) && hub.tlsPort == selectedTlsPort;

        RoundedPanel card = new RoundedPanel(10,
            isSelected ? ThemeColors.BG_HOVER : ThemeColors.BG_CARD,
            isSelected ? ThemeColors.BORDER_ACTIVE : ThemeColors.BORDER_SUBTLE, 1);
        card.setLayout(new BorderLayout(12, 0));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        card.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Left info
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        left.setOpaque(false);

        JLabel dot = new JLabel("●");
        dot.setForeground(ThemeColors.STATUS_ONLINE);
        dot.setFont(new Font("Segoe UI", Font.BOLD, 14));

        JLabel nameLbl = new JLabel(hub.hubName);
        nameLbl.setFont(ThemeColors.FONT_HEADER);
        nameLbl.setForeground(ThemeColors.TEXT_PRIMARY);

        JLabel hostLbl = new JLabel(hub.hostAddress + ":" + hub.tlsPort);
        hostLbl.setFont(ThemeColors.FONT_MONO);
        hostLbl.setForeground(ThemeColors.TEXT_MUTED);

        left.add(dot);
        left.add(nameLbl);
        left.add(hostLbl);

        // Right metrics
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        right.setOpaque(false);

        JLabel usersBadge = new JLabel(" " + hub.activeUsers + " Active Users ");
        usersBadge.setFont(ThemeColors.FONT_BOLD);
        usersBadge.setForeground(ThemeColors.TEXT_PRIMARY);
        usersBadge.setOpaque(true);
        usersBadge.setBackground(ThemeColors.BG_INPUT);
        usersBadge.setBorder(BorderFactory.createLineBorder(ThemeColors.BORDER_SUBTLE, 1));

        right.add(usersBadge);

        card.add(left, BorderLayout.WEST);
        card.add(right, BorderLayout.EAST);

        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                selectedHost = hub.hostAddress;
                selectedTlsPort = hub.tlsPort;
                if (hostField != null) hostField.setText(selectedHost);
                if (tlsPortField != null) tlsPortField.setText(String.valueOf(selectedTlsPort));
                updateHubsView(cachedHubs);
            }
        });

        return card;
    }

    private void performLogin() {
        if (manualExpanded) {
            selectedHost = hostField.getText().trim();
            try {
                selectedTlsPort = Integer.parseInt(tlsPortField.getText().trim());
            } catch (Exception ignored) {}
            currentUsername = userField.getText().trim();
            currentPassword = new String(passField.getPassword());
        }

        statusLabel.setText("Negotiating TLSv1.3 Handshake with " + selectedHost + ":" + selectedTlsPort + "...");
        statusLabel.setForeground(ThemeColors.STATUS_TYPING);
        connectButton.setEnabled(false);

        new Thread(() -> {
            TlsAuthClient authClient = new TlsAuthClient(selectedHost, selectedTlsPort,
                inspectorPanel != null ? inspectorPanel::logEvent : null);
            TlsAuthClient.AuthResult result = authClient.authenticate(currentUsername, currentPassword);

            SwingUtilities.invokeLater(() -> {
                connectButton.setEnabled(true);
                if (result.success) {
                    if (inspectorPanel != null) {
                        inspectorPanel.updateTlsDetails(
                            result.certificateDetails.cipherSuite,
                            result.certificateDetails.protocol,
                            result.certificateDetails.subjectDN,
                            result.certificateDetails.sha256Fingerprint
                        );
                    }

                    resultSession = new SessionData();
                    resultSession.host = selectedHost;
                    resultSession.tlsPort = selectedTlsPort;
                    resultSession.nioPort = NetworkConfig.NIO_CHAT_PORT;
                    resultSession.filePort = NetworkConfig.FILE_TRANSFER_PORT;
                    resultSession.pulsePort = NetworkConfig.UDP_PULSE_PORT;
                    resultSession.username = currentUsername;
                    resultSession.sessionToken = result.sessionToken;
                    resultSession.role = result.role;
                    resultSession.aesKeyBase64 = result.aesKeyBase64;
                    resultSession.certificateDetails = result.certificateDetails;

                    if (discoveryListener != null) {
                        discoveryListener.stop();
                    }

                    dispose();
                } else {
                    statusLabel.setText("Authentication failed: " + result.errorMessage);
                    statusLabel.setForeground(ThemeColors.STATUS_DANGER);
                    JOptionPane.showMessageDialog(LoginDiscoveryDialog.this,
                        "TLS Authentication Failed:\n" + result.errorMessage,
                        "Security Exception", JOptionPane.ERROR_MESSAGE);
                }
            });
        }, "TLS-Auth-Thread").start();
    }

    public SessionData getSessionData() {
        return resultSession;
    }
}
