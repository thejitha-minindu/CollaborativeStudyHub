package com.studyhub.ui;

import com.studyhub.common.NetworkConfig;
import com.studyhub.security.CryptoIntegrityService;
import com.studyhub.security.TlsAuthClient;
import com.studyhub.udp.LanDiscoveryListener;
import com.studyhub.ui.theme.ModernScrollBarUI;
import com.studyhub.ui.theme.PillButton;
import com.studyhub.ui.theme.RoundedPanel;
import com.studyhub.ui.theme.ThemeColors;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
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
import javax.swing.SwingUtilities;

/**
 * Modern Discord-styled onboarding and LAN auto-discovery portal.
 * Features live UDP server cards, one-click demo account switcher pills,
 * a self-service student registration form, and secure TLSv1.3 mutual cipher negotiation.
 */
public class LoginDiscoveryDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.]{3,24}$");
    private static final String CARD_LOGIN = "LOGIN";
    private static final String CARD_REGISTER = "REGISTER";

    public static class SessionData {
        public String host;
        public int tlsPort;
        public int nioPort;
        public int filePort;
        public int pulsePort;
        public String username;
        public String displayName;
        public String sessionToken;
        public String role;
        public String aesKeyBase64;
        /** SHA-256 of the password, kept in memory so the NIO channel can auto-reconnect. */
        public String passwordSha256;
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

    // Mode switching (Sign In <-> Create Student Account)
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardHost = new JPanel(cardLayout);
    private String activeCard = CARD_LOGIN;
    private PillButton signInModeBtn;
    private PillButton registerModeBtn;
    private PillButton primaryActionBtn;

    // Collapsible manual connection panel
    private JPanel manualSection;
    private boolean manualExpanded = false;
    private JTextField hostField;
    private JTextField tlsPortField;
    private JTextField userField;
    private JPasswordField passField;

    // Registration form
    private JTextField regUserField;
    private JTextField regNameField;
    private JPasswordField regPassField;
    private JPasswordField regConfirmField;

    private JLabel statusLabel;
    private PillButton[] accountPills;

    public LoginDiscoveryDialog(JFrame parent, NetworkInspectorPanel inspectorPanel) {
        super(parent, "Join Collaborative Study Hub", true);
        this.inspectorPanel = inspectorPanel;

        buildModernUI();
        startUdpDiscovery();
    }

    private void buildModernUI() {
        setSize(860, 700);
        setMinimumSize(new Dimension(760, 620));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        getContentPane().setBackground(ThemeColors.BG_RAIL);

        // Centered Card Container
        JPanel centerWrapper = new JPanel(new GridBagLayout());
        centerWrapper.setOpaque(false);

        RoundedPanel mainCard = new RoundedPanel(18, ThemeColors.BG_PANEL, ThemeColors.BORDER_SUBTLE, 1);
        mainCard.setPreferredSize(new Dimension(720, 600));
        mainCard.setLayout(new BorderLayout(0, 14));
        mainCard.setBorder(BorderFactory.createEmptyBorder(22, 26, 18, 26));

        // 1. Header (App Icon + Title + Subtitle)
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setOpaque(false);

        JLabel logoLabel = new JLabel("🎓 COLLABORATIVE STUDY HUB");
        logoLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        logoLabel.setForeground(ThemeColors.TEXT_PRIMARY);
        logoLabel.setAlignmentX(0.0f);

        JLabel subLabel = new JLabel("Zero-Config LAN Discovery • TLSv1.3 Security • Persistent Study Rooms");
        subLabel.setFont(ThemeColors.FONT_MUTED);
        subLabel.setForeground(ThemeColors.TEXT_MUTED);
        subLabel.setAlignmentX(0.0f);

        headerPanel.add(logoLabel);
        headerPanel.add(Box.createVerticalStrut(4));
        headerPanel.add(subLabel);

        // 2. Middle Content
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

        serversScrollPane.setPreferredSize(new Dimension(660, 105));
        serversScrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 105));
        serversScrollPane.setBackground(ThemeColors.BG_CARD);
        serversScrollPane.getViewport().setBackground(ThemeColors.BG_CARD);
        serversScrollPane.setBorder(BorderFactory.createLineBorder(ThemeColors.BORDER_SUBTLE, 1));
        serversScrollPane.getVerticalScrollBar().setUI(new ModernScrollBarUI(6));

        renderEmptyHubsPlaceholder();

        // Section B: Mode toggle (Sign In / Create Student Account)
        JPanel modeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modeBar.setOpaque(false);
        modeBar.setAlignmentX(0.0f);
        modeBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        signInModeBtn = new PillButton("Sign In", ThemeColors.ACCENT_PRIMARY, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY, 14);
        signInModeBtn.setFont(ThemeColors.FONT_BOLD);
        signInModeBtn.addActionListener(e -> switchMode(CARD_LOGIN));

        registerModeBtn = new PillButton("Create Student Account", ThemeColors.BG_INPUT, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_MUTED, 14);
        registerModeBtn.setFont(ThemeColors.FONT_BOLD);
        registerModeBtn.addActionListener(e -> switchMode(CARD_REGISTER));

        modeBar.add(signInModeBtn);
        modeBar.add(registerModeBtn);

        // Section C: Card host (login form / registration form)
        cardHost.setOpaque(false);
        cardHost.setAlignmentX(0.0f);
        cardHost.setMaximumSize(new Dimension(Integer.MAX_VALUE, 320));
        cardHost.add(createLoginCard(), CARD_LOGIN);
        cardHost.add(createRegisterCard(), CARD_REGISTER);

        contentPanel.add(serversHeader);
        contentPanel.add(Box.createVerticalStrut(6));
        contentPanel.add(serversScrollPane);
        contentPanel.add(Box.createVerticalStrut(14));
        contentPanel.add(modeBar);
        contentPanel.add(Box.createVerticalStrut(6));
        contentPanel.add(cardHost);

        // 3. Footer Bar (Status + Primary Action Button)
        JPanel footerBar = new JPanel(new BorderLayout(12, 0));
        footerBar.setOpaque(false);

        statusLabel = new JLabel("Ready. Select a server and profile to begin.");
        statusLabel.setFont(ThemeColors.FONT_MUTED);
        statusLabel.setForeground(ThemeColors.TEXT_MUTED);

        primaryActionBtn = new PillButton("Enter Study Hub", ThemeColors.ACCENT_PRIMARY, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY, 20);
        primaryActionBtn.setPreferredSize(new Dimension(200, 40));
        primaryActionBtn.addActionListener(e -> {
            if (CARD_REGISTER.equals(activeCard)) {
                performRegister();
            } else {
                performLogin();
            }
        });

        footerBar.add(statusLabel, BorderLayout.CENTER);
        footerBar.add(primaryActionBtn, BorderLayout.EAST);

        mainCard.add(headerPanel, BorderLayout.NORTH);
        mainCard.add(contentPanel, BorderLayout.CENTER);
        mainCard.add(footerBar, BorderLayout.SOUTH);

        centerWrapper.add(mainCard);
        add(centerWrapper, BorderLayout.CENTER);
    }

    // =========================================================================
    // CARDS
    // =========================================================================

    private JPanel createLoginCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(false);
        card.setAlignmentX(0.0f);

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
            cardHost.revalidate();
            cardHost.repaint();
        });

        toggleManualBar.add(toggleManualBtn);

        card.add(accTitle);
        card.add(Box.createVerticalStrut(8));
        card.add(accountPillsBar);
        card.add(Box.createVerticalStrut(10));
        card.add(toggleManualBar);
        card.add(manualSection);

        return card;
    }

    private JPanel createRegisterCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(false);
        card.setAlignmentX(0.0f);

        JLabel title = new JLabel("SELF-SERVICE STUDENT REGISTRATION (Port 9443 TLS)");
        title.setFont(ThemeColors.FONT_BOLD);
        title.setForeground(ThemeColors.TEXT_MUTED);
        title.setAlignmentX(0.0f);

        regUserField = new JTextField(14);
        styleTextField(regUserField);
        regNameField = new JTextField(14);
        styleTextField(regNameField);
        regPassField = new JPasswordField(14);
        styleTextField(regPassField);
        regConfirmField = new JPasswordField(14);
        styleTextField(regConfirmField);

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        row1.setOpaque(false);
        row1.setAlignmentX(0.0f);
        row1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        row1.add(createInputLabel("Username:"));
        row1.add(regUserField);
        row1.add(createInputLabel("Display Name:"));
        row1.add(regNameField);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        row2.setOpaque(false);
        row2.setAlignmentX(0.0f);
        row2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        row2.add(createInputLabel("Password:"));
        row2.add(regPassField);
        row2.add(createInputLabel("Confirm:"));
        row2.add(regConfirmField);

        JLabel hint = new JLabel("<html><body style='width: 560px; color:#949BA4;'>"
            + "New accounts are validated by the TLS auth service and written to <b>data/users.db</b> "
            + "with a per-account salt, so your username survives a server restart. "
            + "Usernames are 3-24 characters (letters, digits, _ and .) and passwords need 6+ characters."
            + "</body></html>");
        hint.setFont(ThemeColors.FONT_MUTED);
        hint.setForeground(ThemeColors.TEXT_MUTED);
        hint.setAlignmentX(0.0f);

        card.add(title);
        card.add(Box.createVerticalStrut(10));
        card.add(row1);
        card.add(Box.createVerticalStrut(8));
        card.add(row2);
        card.add(Box.createVerticalStrut(8));
        card.add(hint);

        return card;
    }

    private void switchMode(String card) {
        activeCard = card;
        cardLayout.show(cardHost, card);

        boolean login = CARD_LOGIN.equals(card);
        signInModeBtn.setColors(login ? ThemeColors.ACCENT_PRIMARY : ThemeColors.BG_INPUT,
            ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY);
        registerModeBtn.setColors(login ? ThemeColors.BG_INPUT : ThemeColors.ACCENT_PRIMARY,
            ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY);
        primaryActionBtn.setText(login ? "Enter Study Hub" : "Create Account & Enter");
        statusLabel.setText(login
            ? "Ready. Select a server and profile to begin."
            : "Create a brand new student account - it is stored on the server immediately.");
        statusLabel.setForeground(ThemeColors.TEXT_MUTED);
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

        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                selectedHost = hub.hostAddress;
                selectedTlsPort = hub.tlsPort;
                if (hostField != null) hostField.setText(selectedHost);
                if (tlsPortField != null) tlsPortField.setText(String.valueOf(selectedTlsPort));
                updateHubsView(cachedHubs);
            }
        });

        return card;
    }

    // =========================================================================
    // ACTIONS
    // =========================================================================

    private void resolveManualOverrides() {
        if (manualExpanded && hostField != null) {
            selectedHost = hostField.getText().trim();
            try {
                selectedTlsPort = Integer.parseInt(tlsPortField.getText().trim());
            } catch (Exception ignored) {}
        }
    }

    private void performLogin() {
        resolveManualOverrides();
        if (manualExpanded && userField != null) {
            currentUsername = userField.getText().trim();
            currentPassword = new String(passField.getPassword());
        }

        if (currentUsername.isEmpty()) {
            statusLabel.setText("Enter a username before connecting.");
            statusLabel.setForeground(ThemeColors.STATUS_DANGER);
            return;
        }

        statusLabel.setText("Negotiating TLSv1.3 Handshake with " + selectedHost + ":" + selectedTlsPort + "...");
        statusLabel.setForeground(ThemeColors.STATUS_TYPING);
        primaryActionBtn.setEnabled(false);

        new Thread(() -> {
            TlsAuthClient authClient = new TlsAuthClient(selectedHost, selectedTlsPort,
                inspectorPanel != null ? inspectorPanel::logEvent : null);
            TlsAuthClient.AuthResult result = authClient.authenticate(currentUsername, currentPassword);

            SwingUtilities.invokeLater(() -> {
                primaryActionBtn.setEnabled(true);
                if (result.success) {
                    openSession(authClient, result, currentUsername,
                        CryptoIntegrityService.computeSha256(currentPassword));
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

    private void performRegister() {
        resolveManualOverrides();

        final String username = regUserField.getText().trim();
        final String displayName = regNameField.getText().trim().isEmpty() ? username : regNameField.getText().trim();
        final String password = new String(regPassField.getPassword());
        final String confirm = new String(regConfirmField.getPassword());

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            statusLabel.setText("Username must be 3-24 characters (letters, digits, _ and . only).");
            statusLabel.setForeground(ThemeColors.STATUS_DANGER);
            return;
        }
        if (password.length() < 6) {
            statusLabel.setText("Password must be at least 6 characters long.");
            statusLabel.setForeground(ThemeColors.STATUS_DANGER);
            return;
        }
        if (!password.equals(confirm)) {
            statusLabel.setText("Passwords do not match.");
            statusLabel.setForeground(ThemeColors.STATUS_DANGER);
            return;
        }

        statusLabel.setText("Registering '" + username + "' over TLSv1.3 (" + selectedHost + ":" + selectedTlsPort + ")...");
        statusLabel.setForeground(ThemeColors.STATUS_TYPING);
        primaryActionBtn.setEnabled(false);

        new Thread(() -> {
            TlsAuthClient authClient = new TlsAuthClient(selectedHost, selectedTlsPort,
                inspectorPanel != null ? inspectorPanel::logEvent : null);

            TlsAuthClient.RegisterResult registration = authClient.register(username, password, displayName);
            if (!registration.success) {
                SwingUtilities.invokeLater(() -> {
                    primaryActionBtn.setEnabled(true);
                    statusLabel.setText("Registration failed: " + registration.errorMessage);
                    statusLabel.setForeground(ThemeColors.STATUS_DANGER);
                    JOptionPane.showMessageDialog(LoginDiscoveryDialog.this,
                        "Registration rejected by the Study Hub server:\n" + registration.errorMessage,
                        "Registration Failed", JOptionPane.WARNING_MESSAGE);
                });
                return;
            }

            // Seamless: log straight in with the freshly created account.
            TlsAuthClient.AuthResult result = authClient.authenticate(username, password);
            SwingUtilities.invokeLater(() -> {
                primaryActionBtn.setEnabled(true);
                if (result.success) {
                    if (inspectorPanel != null) {
                        inspectorPanel.logEvent(new com.studyhub.common.PacketLogEvent(
                            com.studyhub.common.PacketLogEvent.Module.TLS_SECURITY,
                            com.studyhub.common.PacketLogEvent.Direction.RX,
                            "Student Account Created",
                            "'" + username + "' (" + displayName + ") is now persisted on the server vault"));
                    }
                    openSession(authClient, result, username, CryptoIntegrityService.computeSha256(password));
                } else {
                    statusLabel.setText("Account created, but login failed: " + result.errorMessage);
                    statusLabel.setForeground(ThemeColors.STATUS_DANGER);
                }
            });
        }, "TLS-Register-Thread").start();
    }

    private void openSession(TlsAuthClient authClient, TlsAuthClient.AuthResult result, String username, String passwordSha256) {
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
        resultSession.username = username;
        resultSession.displayName = result.displayName.isEmpty() ? username : result.displayName;
        resultSession.sessionToken = result.sessionToken;
        resultSession.role = result.role;
        resultSession.aesKeyBase64 = result.aesKeyBase64;
        resultSession.passwordSha256 = passwordSha256;
        resultSession.certificateDetails = result.certificateDetails;

        if (discoveryListener != null) {
            discoveryListener.stop();
        }

        dispose();
    }

    public SessionData getSessionData() {
        return resultSession;
    }
}
