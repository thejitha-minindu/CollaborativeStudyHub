package com.studyhub.ui;

import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.common.ProtocolConstants;
import com.studyhub.nio.MessageFramer;
import com.studyhub.security.CryptoIntegrityService;
import com.studyhub.security.TlsAuthClient;
import com.studyhub.transfer.FileTransferClient;
import com.studyhub.transfer.SharedFileRecord;
import com.studyhub.udp.PresencePulseService;
import com.studyhub.ui.theme.AvatarComponent;
import com.studyhub.ui.theme.ModernScrollBarUI;
import com.studyhub.ui.theme.PillButton;
import com.studyhub.ui.theme.RoundedPanel;
import com.studyhub.ui.theme.ThemeColors;
import com.studyhub.ui.theme.ToastNotification;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * [COLLABORATIVE STUDY HUB - REDESIGNED MODERN CLIENT]
 * Discord/Slack-inspired 3-pane collaboration workspace with custom Java2D rendering,
 * rich message cards, active room management, room study vault, and live collapsible telemetry inspector.
 */
public class MainHubFrame extends JFrame {

    private final LoginDiscoveryDialog.SessionData session;
    private final NetworkInspectorPanel inspectorPanel;
    private final MessageFramer framer = new MessageFramer();
    private final AtomicLong pulseSeq = new AtomicLong(1);

    // NIO Client Channel
    private SocketChannel nioChannel;
    private Thread nioReaderThread;
    private volatile boolean connected = false;

    // Room & Navigation State
    private String currentRoom = ProtocolConstants.DEFAULT_ROOMS[0];
    private final List<String> roomList = new ArrayList<>();
    private final JPanel roomsContainer = new JPanel();

    // Member & Vault Models
    private final DefaultListModel<MemberItem> membersModel = new DefaultListModel<>();
    private final JList<MemberItem> membersList = new JList<>(membersModel);

    private final DefaultListModel<SharedFileRecord> vaultModel = new DefaultListModel<>();
    private final JList<SharedFileRecord> vaultList = new JList<>(vaultModel);
    private final List<SharedFileRecord> allRoomFiles = new ArrayList<>();

    // Feed & Composer
    private final JPanel chatFeedPanel = new JPanel();
    private final JScrollPane chatScrollPane = new JScrollPane(chatFeedPanel);
    private final JTextField messageInput = new JTextField();
    private final PillButton sendBtn = new PillButton("Send");
    private final PillButton aesToggleBtn = new PillButton("🔒 AES-128: ON", ThemeColors.STATUS_ONLINE, ThemeColors.STATUS_ONLINE.darker(), ThemeColors.TEXT_PRIMARY, 14);
    private final PillButton tamperToggleBtn = new PillButton("⚠ Tamper Test: OFF", ThemeColors.BG_INPUT, ThemeColors.BG_HOVER, ThemeColors.TEXT_MUTED, 14);
    private final JLabel typingIndicatorLabel = new JLabel(" ");
    private final JLabel currentRoomHeaderLabel = new JLabel("#general");
    private final JLabel currentRoomDescLabel = new JLabel("General study discussion and resource sharing");
    private PillButton newMessagesFloatBtn;

    // Collapsible Inspector Drawer
    private JSplitPane verticalDrawerSplit;
    private boolean inspectorExpanded = false;
    private PillButton inspectorToggleBtn;

    // Encryption & Tamper States
    private boolean aesEncryptionEnabled = true;
    private boolean simulateTamperEnabled = false;
    private Timer idlePulseTimer;
    private Timer typingDebounceTimer;
    private String lastSender = "";

    public static class MemberItem {
        public final String username;
        public final String status;
        public final String role;

        public MemberItem(String username, String status, String role) {
            this.username = username;
            this.status = status != null ? status : ProtocolConstants.STATUS_ONLINE;
            this.role = role != null ? role : ProtocolConstants.ROLE_STUDENT;
        }
    }

    public MainHubFrame(LoginDiscoveryDialog.SessionData session, NetworkInspectorPanel inspectorPanel) {
        super("Collaborative Study Hub — " + session.username + " (" + session.role + ")");
        this.session = session;
        this.inspectorPanel = inspectorPanel;

        for (String r : ProtocolConstants.DEFAULT_ROOMS) {
            roomList.add(r);
        }

        buildDiscordLikeUI();
        connectToNioServer();
        startPresenceHeartbeat();
    }

    private void buildDiscordLikeUI() {
        setSize(1240, 840);
        setMinimumSize(new Dimension(1020, 680));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        getContentPane().setBackground(ThemeColors.BG_CHAT);
        setLayout(new BorderLayout());

        // 3-Pane Workspace
        JPanel leftPane = buildLeftSidebar();
        JPanel centerPane = buildCenterStage();
        JPanel rightPane = buildRightSidebar();

        JSplitPane centerRightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerPane, rightPane);
        centerRightSplit.setDividerLocation(760);
        centerRightSplit.setDividerSize(1);
        centerRightSplit.setBorder(null);

        JSplitPane mainHorizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, centerRightSplit);
        mainHorizontalSplit.setDividerLocation(240);
        mainHorizontalSplit.setDividerSize(1);
        mainHorizontalSplit.setBorder(null);

        // Bottom Collapsible Inspector Drawer Split
        verticalDrawerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainHorizontalSplit, inspectorPanel);
        verticalDrawerSplit.setDividerLocation(840); // Collapsed by default
        verticalDrawerSplit.setDividerSize(1);
        verticalDrawerSplit.setBorder(null);
        inspectorPanel.setVisible(false);

        add(verticalDrawerSplit, BorderLayout.CENTER);
    }

    // =========================================================================
    // 1. LEFT SIDEBAR (Rooms, Security Status, Profile)
    // =========================================================================
    private JPanel buildLeftSidebar() {
        JPanel left = new JPanel(new BorderLayout());
        left.setBackground(ThemeColors.BG_RAIL);
        left.setPreferredSize(new Dimension(240, 0));
        left.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, ThemeColors.BORDER_SUBTLE));

        // Top Hub Header
        JPanel topHubHeader = new JPanel();
        topHubHeader.setLayout(new BoxLayout(topHubHeader, BoxLayout.Y_AXIS));
        topHubHeader.setBackground(ThemeColors.BG_RAIL);
        topHubHeader.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeColors.BORDER_SUBTLE),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)
        ));

        JLabel hubTitle = new JLabel("Study Hub Platform");
        hubTitle.setFont(ThemeColors.FONT_TITLE);
        hubTitle.setForeground(ThemeColors.TEXT_PRIMARY);

        PillButton tlsVerifiedPill = new PillButton("🔒 TLSv1.3 • X.509 Verified", ThemeColors.BG_CARD, ThemeColors.BG_HOVER, ThemeColors.STATUS_ONLINE, 12);
        tlsVerifiedPill.setFont(ThemeColors.FONT_MUTED);
        tlsVerifiedPill.setBorderColor(ThemeColors.STATUS_ONLINE);
        tlsVerifiedPill.addActionListener(e -> showTlsCertDialog());

        topHubHeader.add(hubTitle);
        topHubHeader.add(Box.createVerticalStrut(6));
        topHubHeader.add(tlsVerifiedPill);

        // Rooms Navigation Section
        JPanel roomsSection = new JPanel(new BorderLayout());
        roomsSection.setOpaque(false);
        roomsSection.setBorder(BorderFactory.createEmptyBorder(12, 10, 8, 10));

        JPanel roomsHeaderBar = new JPanel(new BorderLayout());
        roomsHeaderBar.setOpaque(false);
        roomsHeaderBar.setBorder(BorderFactory.createEmptyBorder(0, 6, 8, 6));

        JLabel roomsCategory = new JLabel("STUDY ROOMS");
        roomsCategory.setFont(ThemeColors.FONT_BOLD);
        roomsCategory.setForeground(ThemeColors.TEXT_MUTED);

        JButton addRoomBtn = new JButton("+");
        addRoomBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        addRoomBtn.setForeground(ThemeColors.TEXT_MUTED);
        addRoomBtn.setContentAreaFilled(false);
        addRoomBtn.setBorderPainted(false);
        addRoomBtn.setFocusPainted(false);
        addRoomBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addRoomBtn.addActionListener(e -> promptCreateRoom());

        roomsHeaderBar.add(roomsCategory, BorderLayout.WEST);
        roomsHeaderBar.add(addRoomBtn, BorderLayout.EAST);

        roomsContainer.setLayout(new BoxLayout(roomsContainer, BoxLayout.Y_AXIS));
        roomsContainer.setOpaque(false);
        refreshRoomsList();

        JScrollPane roomsScroll = new JScrollPane(roomsContainer);
        roomsScroll.setOpaque(false);
        roomsScroll.getViewport().setOpaque(false);
        roomsScroll.setBorder(null);
        roomsScroll.getVerticalScrollBar().setUI(new ModernScrollBarUI(4));

        roomsSection.add(roomsHeaderBar, BorderLayout.NORTH);
        roomsSection.add(roomsScroll, BorderLayout.CENTER);

        // Bottom User Profile Bar
        JPanel profileBar = buildUserProfileBar();

        left.add(topHubHeader, BorderLayout.NORTH);
        left.add(roomsSection, BorderLayout.CENTER);
        left.add(profileBar, BorderLayout.SOUTH);

        return left;
    }

    private JPanel buildUserProfileBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(ThemeColors.BG_CARD);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeColors.BORDER_SUBTLE),
            BorderFactory.createEmptyBorder(10, 14, 10, 14)
        ));

        // User Avatar + Name + Role
        JPanel userPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        userPanel.setOpaque(false);

        AvatarComponent avatar = new AvatarComponent(session.username, 36, ThemeColors.STATUS_ONLINE, true);

        JPanel namePanel = new JPanel();
        namePanel.setLayout(new BoxLayout(namePanel, BoxLayout.Y_AXIS));
        namePanel.setOpaque(false);

        JLabel usernameLabel = new JLabel(session.username);
        usernameLabel.setFont(ThemeColors.FONT_HEADER);
        usernameLabel.setForeground(ThemeColors.TEXT_PRIMARY);

        JLabel roleBadge = new JLabel(session.role.toUpperCase());
        roleBadge.setFont(new Font("Segoe UI", Font.BOLD, 9));
        roleBadge.setForeground(ProtocolConstants.ROLE_MODERATOR.equalsIgnoreCase(session.role) ? ThemeColors.BADGE_MOD : ThemeColors.BADGE_STUDENT);

        namePanel.add(usernameLabel);
        namePanel.add(roleBadge);

        userPanel.add(avatar);
        userPanel.add(namePanel);

        // Inspector Drawer Toggle Button
        inspectorToggleBtn = new PillButton("⚡ Inspector", ThemeColors.BG_INPUT, ThemeColors.BG_HOVER, ThemeColors.TEXT_MUTED, 12);
        inspectorToggleBtn.setFont(ThemeColors.FONT_MUTED);
        inspectorToggleBtn.addActionListener(e -> toggleInspectorDrawer());

        bar.add(userPanel, BorderLayout.WEST);
        bar.add(inspectorToggleBtn, BorderLayout.EAST);

        return bar;
    }

    private void refreshRoomsList() {
        roomsContainer.removeAll();
        for (String room : roomList) {
            boolean isActive = room.equalsIgnoreCase(currentRoom);

            RoundedPanel roomCard = new RoundedPanel(8, isActive ? ThemeColors.BG_HOVER : ThemeColors.BG_RAIL);
            roomCard.setLayout(new BorderLayout(8, 0));
            roomCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            roomCard.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
            roomCard.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel hash = new JLabel("#");
            hash.setFont(new Font("Segoe UI", Font.BOLD, 14));
            hash.setForeground(isActive ? ThemeColors.ACCENT_PRIMARY : ThemeColors.TEXT_MUTED);

            JLabel name = new JLabel(room.replace("#", ""));
            name.setFont(isActive ? ThemeColors.FONT_BOLD : ThemeColors.FONT_BODY);
            name.setForeground(isActive ? ThemeColors.TEXT_PRIMARY : ThemeColors.TEXT_MUTED);

            roomCard.add(hash, BorderLayout.WEST);
            roomCard.add(name, BorderLayout.CENTER);

            roomCard.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!room.equalsIgnoreCase(currentRoom)) {
                        switchRoom(room);
                    }
                }
            });

            roomsContainer.add(roomCard);
            roomsContainer.add(Box.createVerticalStrut(3));
        }
        roomsContainer.revalidate();
        roomsContainer.repaint();
    }

    private void promptCreateRoom() {
        String newRoom = JOptionPane.showInputDialog(this, "Enter new room name (e.g. #exam-prep):", "Create Study Room", JOptionPane.PLAIN_MESSAGE);
        if (newRoom != null && !newRoom.trim().isEmpty()) {
            String roomFormatted = newRoom.trim().startsWith("#") ? newRoom.trim() : "#" + newRoom.trim();
            if (!roomList.contains(roomFormatted)) {
                roomList.add(roomFormatted);
                refreshRoomsList();
                switchRoom(roomFormatted);
                ToastNotification.show(this, "Room Created", "Joined " + roomFormatted, ToastNotification.Type.SUCCESS);
            }
        }
    }

    private void toggleInspectorDrawer() {
        inspectorExpanded = !inspectorExpanded;
        inspectorPanel.setVisible(inspectorExpanded);
        if (inspectorExpanded) {
            verticalDrawerSplit.setDividerLocation(getHeight() - 250);
            inspectorToggleBtn.setColors(ThemeColors.ACCENT_PRIMARY, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY);
        } else {
            verticalDrawerSplit.setDividerLocation(getHeight());
            inspectorToggleBtn.setColors(ThemeColors.BG_INPUT, ThemeColors.BG_HOVER, ThemeColors.TEXT_MUTED);
        }
    }

    // =========================================================================
    // 2. CENTER STAGE (Rich Message Stream & Composer)
    // =========================================================================
    private JPanel buildCenterStage() {
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(ThemeColors.BG_CHAT);

        // Top Room Header
        JPanel topHeader = new JPanel(new BorderLayout());
        topHeader.setBackground(ThemeColors.BG_CHAT);
        topHeader.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeColors.BORDER_SUBTLE),
            BorderFactory.createEmptyBorder(10, 20, 10, 20)
        ));

        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        titlePanel.setOpaque(false);

        currentRoomHeaderLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));
        currentRoomHeaderLabel.setForeground(ThemeColors.TEXT_PRIMARY);

        JLabel divider = new JLabel("|");
        divider.setForeground(ThemeColors.TEXT_MUTED);

        currentRoomDescLabel.setFont(ThemeColors.FONT_MUTED);
        currentRoomDescLabel.setForeground(ThemeColors.TEXT_MUTED);

        titlePanel.add(currentRoomHeaderLabel);
        titlePanel.add(divider);
        titlePanel.add(currentRoomDescLabel);

        // Action Pills (Share File + Tamper Toggle)
        JPanel actionPills = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actionPills.setOpaque(false);

        PillButton shareFileTopBtn = new PillButton("📁 Share File", ThemeColors.ACCENT_PRIMARY, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY, 14);
        shareFileTopBtn.addActionListener(e -> chooseAndUploadFile());

        tamperToggleBtn.addActionListener(e -> {
            simulateTamperEnabled = !simulateTamperEnabled;
            if (simulateTamperEnabled) {
                tamperToggleBtn.setColors(ThemeColors.STATUS_DANGER, ThemeColors.STATUS_DANGER.darker(), ThemeColors.TEXT_PRIMARY);
                tamperToggleBtn.setText("⚠ Tamper Test: ON");
                ToastNotification.show(this, "Tamper Simulation Active", "Next file upload will invert byte 0 to test SHA-256 detection!", ToastNotification.Type.WARNING);
            } else {
                tamperToggleBtn.setColors(ThemeColors.BG_INPUT, ThemeColors.BG_HOVER, ThemeColors.TEXT_MUTED);
                tamperToggleBtn.setText("⚠ Tamper Test: OFF");
            }
        });

        actionPills.add(tamperToggleBtn);
        actionPills.add(shareFileTopBtn);

        topHeader.add(titlePanel, BorderLayout.WEST);
        topHeader.add(actionPills, BorderLayout.EAST);

        // Chat Message Feed
        chatFeedPanel.setLayout(new BoxLayout(chatFeedPanel, BoxLayout.Y_AXIS));
        chatFeedPanel.setBackground(ThemeColors.BG_CHAT);
        chatFeedPanel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        chatScrollPane.setBackground(ThemeColors.BG_CHAT);
        chatScrollPane.getViewport().setBackground(ThemeColors.BG_CHAT);
        chatScrollPane.setBorder(null);
        chatScrollPane.getVerticalScrollBar().setUI(new ModernScrollBarUI(6));

        // Smart scroll detection
        chatScrollPane.getVerticalScrollBar().addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                JScrollBar bar = chatScrollPane.getVerticalScrollBar();
                int extent = bar.getModel().getExtent();
                int max = bar.getMaximum();
                int value = bar.getValue();
                boolean atBottom = (value + extent >= max - 50);
                if (newMessagesFloatBtn != null) {
                    newMessagesFloatBtn.setVisible(!atBottom);
                }
            }
        });

        // Bottom Composer Container
        JPanel bottomContainer = buildComposerSection();

        center.add(topHeader, BorderLayout.NORTH);
        center.add(chatScrollPane, BorderLayout.CENTER);
        center.add(bottomContainer, BorderLayout.SOUTH);

        return center;
    }

    private JPanel buildComposerSection() {
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBackground(ThemeColors.BG_CHAT);
        container.setBorder(BorderFactory.createEmptyBorder(4, 16, 14, 16));

        // Typing indicator row
        JPanel typingRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        typingRow.setOpaque(false);
        typingIndicatorLabel.setFont(ThemeColors.FONT_MUTED);
        typingIndicatorLabel.setForeground(ThemeColors.STATUS_TYPING);
        typingRow.add(typingIndicatorLabel);

        // Input pill container
        RoundedPanel inputCard = new RoundedPanel(18, ThemeColors.BG_INPUT, ThemeColors.BORDER_SUBTLE, 1);
        inputCard.setLayout(new BorderLayout(8, 0));
        inputCard.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));

        // + Attach File Button
        JButton attachBtn = new JButton("+");
        attachBtn.setFont(new Font("Segoe UI", Font.BOLD, 18));
        attachBtn.setForeground(ThemeColors.TEXT_MUTED);
        attachBtn.setContentAreaFilled(false);
        attachBtn.setBorderPainted(false);
        attachBtn.setFocusPainted(false);
        attachBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        attachBtn.addActionListener(e -> chooseAndUploadFile());

        // Message text input
        messageInput.setFont(ThemeColors.FONT_BODY);
        messageInput.setBackground(ThemeColors.BG_INPUT);
        messageInput.setForeground(ThemeColors.TEXT_PRIMARY);
        messageInput.setCaretColor(ThemeColors.TEXT_PRIMARY);
        messageInput.setBorder(null);
        messageInput.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                handleComposerTyping();
            }
        });
        messageInput.addActionListener(e -> sendMessage());

        // Right side options (AES Toggle + Send Pill)
        JPanel rightTools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightTools.setOpaque(false);

        aesToggleBtn.addActionListener(e -> {
            aesEncryptionEnabled = !aesEncryptionEnabled;
            if (aesEncryptionEnabled) {
                aesToggleBtn.setColors(ThemeColors.STATUS_ONLINE, ThemeColors.STATUS_ONLINE.darker(), ThemeColors.TEXT_PRIMARY);
                aesToggleBtn.setText("🔒 AES-128: ON");
            } else {
                aesToggleBtn.setColors(ThemeColors.BG_HOVER, ThemeColors.BG_HOVER.darker(), ThemeColors.TEXT_MUTED);
                aesToggleBtn.setText("🔓 Plaintext: ON");
            }
        });

        sendBtn.addActionListener(e -> sendMessage());

        rightTools.add(aesToggleBtn);
        rightTools.add(sendBtn);

        inputCard.add(attachBtn, BorderLayout.WEST);
        inputCard.add(messageInput, BorderLayout.CENTER);
        inputCard.add(rightTools, BorderLayout.EAST);

        container.add(typingRow);
        container.add(Box.createVerticalStrut(4));
        container.add(inputCard);

        return container;
    }

    // =========================================================================
    // 3. RIGHT SIDEBAR (Online Members & Room Study Vault)
    // =========================================================================
    private JPanel buildRightSidebar() {
        JPanel right = new JPanel(new GridLayout(2, 1, 0, 1));
        right.setBackground(ThemeColors.BG_PANEL);
        right.setPreferredSize(new Dimension(240, 0));
        right.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, ThemeColors.BORDER_SUBTLE));

        // 1. Members Panel
        JPanel membersPanel = new JPanel(new BorderLayout());
        membersPanel.setBackground(ThemeColors.BG_PANEL);
        membersPanel.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));

        JLabel membersHeader = new JLabel("MEMBERS IN ROOM");
        membersHeader.setFont(ThemeColors.FONT_BOLD);
        membersHeader.setForeground(ThemeColors.TEXT_MUTED);
        membersHeader.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        membersList.setBackground(ThemeColors.BG_PANEL);
        membersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        membersList.setCellRenderer(new MemberCellRenderer());

        JScrollPane membersScroll = new JScrollPane(membersList);
        membersScroll.setBorder(null);
        membersScroll.getVerticalScrollBar().setUI(new ModernScrollBarUI(4));
        membersScroll.getViewport().setBackground(ThemeColors.BG_PANEL);

        membersPanel.add(membersHeader, BorderLayout.NORTH);
        membersPanel.add(membersScroll, BorderLayout.CENTER);

        // 2. Study Vault Panel
        JPanel vaultPanel = new JPanel(new BorderLayout(0, 6));
        vaultPanel.setBackground(ThemeColors.BG_PANEL);
        vaultPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeColors.BORDER_SUBTLE),
            BorderFactory.createEmptyBorder(10, 14, 10, 14)
        ));

        JLabel vaultHeader = new JLabel("ROOM STUDY VAULT");
        vaultHeader.setFont(ThemeColors.FONT_BOLD);
        vaultHeader.setForeground(ThemeColors.TEXT_MUTED);

        JTextField vaultSearchField = new JTextField("Search files...");
        vaultSearchField.setFont(ThemeColors.FONT_MUTED);
        vaultSearchField.setBackground(ThemeColors.BG_INPUT);
        vaultSearchField.setForeground(ThemeColors.TEXT_MUTED);
        vaultSearchField.setCaretColor(ThemeColors.TEXT_PRIMARY);
        vaultSearchField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ThemeColors.BORDER_SUBTLE, 1),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        ));
        vaultSearchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                filterVaultFiles(vaultSearchField.getText().trim());
            }
        });

        vaultList.setBackground(ThemeColors.BG_PANEL);
        vaultList.setCellRenderer(new VaultCellRenderer());

        JScrollPane vaultScroll = new JScrollPane(vaultList);
        vaultScroll.setBorder(null);
        vaultScroll.getVerticalScrollBar().setUI(new ModernScrollBarUI(4));
        vaultScroll.getViewport().setBackground(ThemeColors.BG_PANEL);

        JPanel vaultTop = new JPanel(new BorderLayout(0, 6));
        vaultTop.setOpaque(false);
        vaultTop.add(vaultHeader, BorderLayout.NORTH);
        vaultTop.add(vaultSearchField, BorderLayout.SOUTH);

        vaultPanel.add(vaultTop, BorderLayout.NORTH);
        vaultPanel.add(vaultScroll, BorderLayout.CENTER);

        right.add(membersPanel);
        right.add(vaultPanel);

        return right;
    }

    // =========================================================================
    // NETWORKING: NIO SocketChannel Multiplexing
    // =========================================================================
    private void connectToNioServer() {
        new Thread(() -> {
            try {
                nioChannel = SocketChannel.open();
                nioChannel.connect(new InetSocketAddress(session.host, session.nioPort));
                connected = true;

                inspectorPanel.logEvent(new PacketLogEvent(
                    PacketLogEvent.Module.NIO_SELECTOR,
                    PacketLogEvent.Direction.TX,
                    "NIO SocketChannel Connected",
                    "Connected to " + session.host + ":" + session.nioPort
                ));

                // Send HELLO handshake
                String hello = ProtocolConstants.CMD_HELLO + ProtocolConstants.DELIMITER +
                    session.sessionToken + ProtocolConstants.DELIMITER + session.username;
                sendNioFrame(hello);

                readNioLoop();

            } catch (Exception e) {
                inspectorPanel.logEvent(new PacketLogEvent(
                    PacketLogEvent.Module.NIO_SELECTOR,
                    PacketLogEvent.Direction.INTERNAL,
                    "NIO Connect Failed",
                    e.getMessage()
                ));
            }
        }, "NIO-Client-Worker").start();
    }

    private void readNioLoop() {
        ByteBuffer readBuf = ByteBuffer.allocate(NetworkConfig.NIO_BUFFER_CAPACITY);
        while (connected && nioChannel.isOpen()) {
            try {
                int read = nioChannel.read(readBuf);
                if (read == -1) break;
                if (read > 0) {
                    List<String> frames = framer.extractFrames(readBuf);
                    for (String frame : frames) {
                        handleInboundNioFrame(frame);
                    }
                }
            } catch (Exception e) {
                break;
            }
        }
        connected = false;
    }

    private void handleInboundNioFrame(String frame) {
        String[] parts = frame.split(ProtocolConstants.DELIMITER_REGEX);
        if (parts.length == 0) return;

        String type = parts[0];
        SwingUtilities.invokeLater(() -> {
            switch (type) {
                case ProtocolConstants.EVT_CHAT:
                    // Format: CHAT|<room>|<sender>|<encFlag>|<content>|<time>
                    if (parts.length >= 6) {
                        String room = parts[1];
                        String sender = parts[2];
                        String encFlag = parts[3];
                        String content = parts[4];
                        String time = parts[5];

                        String displayContent = content;
                        if (ProtocolConstants.ENC_AES.equalsIgnoreCase(encFlag)) {
                            try {
                                displayContent = CryptoIntegrityService.decryptAes(content, session.aesKeyBase64);
                            } catch (Exception e) {
                                displayContent = "[AES Decryption Error: " + e.getMessage() + "]";
                            }
                        }

                        renderChatMessageCard(sender, displayContent, time, ProtocolConstants.ENC_AES.equalsIgnoreCase(encFlag));
                    }
                    break;

                case ProtocolConstants.EVT_USER_LIST:
                    // Format: USER_LIST|<room>|<user:status>|...
                    if (parts.length >= 2) {
                        String room = parts[1];
                        if (room.equalsIgnoreCase(currentRoom)) {
                            membersModel.clear();
                            for (int i = 2; i < parts.length; i++) {
                                String[] us = parts[i].split(":");
                                String u = us[0];
                                String st = (us.length > 1) ? us[1] : ProtocolConstants.STATUS_ONLINE;
                                String role = u.equalsIgnoreCase("admin") ? ProtocolConstants.ROLE_MODERATOR : ProtocolConstants.ROLE_STUDENT;
                                membersModel.addElement(new MemberItem(u, st, role));
                            }
                        }
                    }
                    break;

                case ProtocolConstants.EVT_PRESENCE_UPDATE:
                    // Format: PRESENCE_UPDATE|<room>|<user>|<status>
                    if (parts.length >= 4) {
                        String user = parts[2];
                        String status = parts[3];
                        updateMemberStatus(user, status);
                    }
                    break;

                case ProtocolConstants.EVT_LINK_CARD:
                    // Format: LINK_CARD|<room>|<url>|<ip>|<status>|<title>
                    if (parts.length >= 6) {
                        String url = parts[2];
                        String ip = parts[3];
                        String status = parts[4];
                        String title = parts[5];
                        renderLinkPreviewCard(url, ip, status, title);
                    }
                    break;

                case ProtocolConstants.EVT_FILE_ADDED:
                    // Format: FILE_ADDED|<room>|<filename>|<size>|<uploader>|<sha256>
                    if (parts.length >= 6) {
                        String room = parts[1];
                        String filename = parts[2];
                        long size = Long.parseLong(parts[3]);
                        String uploader = parts[4];
                        String sha256 = parts[5];

                        SharedFileRecord rec = new SharedFileRecord("vault_" + filename, room, filename, size, sha256, uploader);
                        allRoomFiles.add(rec);
                        vaultModel.addElement(rec);

                        renderFileVaultCard(room, filename, size, uploader, sha256);
                        ToastNotification.show(this, "New File Shared", filename + " in " + room, ToastNotification.Type.SUCCESS);
                    }
                    break;

                case ProtocolConstants.EVT_SYSTEM:
                    if (parts.length >= 2) {
                        renderSystemNotice(parts[1]);
                    }
                    break;
            }
        });
    }

    private void sendNioFrame(String payload) {
        if (!connected || nioChannel == null || !nioChannel.isOpen()) return;
        try {
            ByteBuffer buf = framer.frameMessage(payload);
            while (buf.hasRemaining()) {
                nioChannel.write(buf);
            }
            inspectorPanel.logEvent(new PacketLogEvent(
                PacketLogEvent.Module.NIO_SELECTOR,
                PacketLogEvent.Direction.TX,
                "Sent Framed Packet",
                payload,
                framer.getBufferInspectionSummary()
            ));
        } catch (IOException e) {
            inspectorPanel.logEvent(new PacketLogEvent(
                PacketLogEvent.Module.NIO_SELECTOR,
                PacketLogEvent.Direction.INTERNAL,
                "Send Error",
                e.getMessage()
            ));
        }
    }

    private void sendMessage() {
        String text = messageInput.getText().trim();
        if (text.isEmpty()) return;

        boolean encrypt = aesEncryptionEnabled;
        String payloadToSend = text;
        String encFlag = ProtocolConstants.ENC_PLAIN;

        if (encrypt) {
            try {
                payloadToSend = CryptoIntegrityService.encryptAes(text, session.aesKeyBase64);
                encFlag = ProtocolConstants.ENC_AES;
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "AES Encryption failed: " + e.getMessage(), "Crypto Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        String cmd = ProtocolConstants.CMD_MSG + ProtocolConstants.DELIMITER +
            currentRoom + ProtocolConstants.DELIMITER + encFlag + ProtocolConstants.DELIMITER + payloadToSend;

        sendNioFrame(cmd);
        messageInput.setText("");
        emitPresencePulse(ProtocolConstants.STATUS_ONLINE);
    }

    private void switchRoom(String newRoom) {
        currentRoom = newRoom;
        currentRoomHeaderLabel.setText(newRoom);
        currentRoomDescLabel.setText("Study discussions and resources for " + newRoom);
        chatFeedPanel.removeAll();
        lastSender = "";
        vaultModel.clear();
        allRoomFiles.clear();

        refreshRoomsList();

        String cmd = ProtocolConstants.CMD_JOIN + ProtocolConstants.DELIMITER + newRoom;
        sendNioFrame(cmd);
        emitPresencePulse(ProtocolConstants.STATUS_ONLINE);
    }

    // =========================================================================
    // UDP PRESENCE & HEARTBEAT PULSES (Port 9877)
    // =========================================================================
    private void handleComposerTyping() {
        emitPresencePulse(ProtocolConstants.STATUS_TYPING);
        if (typingDebounceTimer != null && typingDebounceTimer.isRunning()) {
            typingDebounceTimer.restart();
        } else {
            typingDebounceTimer = new Timer(2500, e -> emitPresencePulse(ProtocolConstants.STATUS_ONLINE));
            typingDebounceTimer.setRepeats(false);
            typingDebounceTimer.start();
        }
    }

    private void emitPresencePulse(String status) {
        long seq = pulseSeq.getAndIncrement();
        PresencePulseService.sendClientPulse(session.host, session.pulsePort, seq, session.username, currentRoom, status);

        inspectorPanel.logEvent(new PacketLogEvent(
            PacketLogEvent.Module.UDP_BEACON,
            PacketLogEvent.Direction.TX,
            "Sent UDP Pulse #" + seq,
            "User: " + session.username + " | Status: " + status + " | Room: " + currentRoom
        ));
    }

    private void startPresenceHeartbeat() {
        idlePulseTimer = new Timer(NetworkConfig.UDP_PULSE_INTERVAL_MS, e -> {
            emitPresencePulse(ProtocolConstants.STATUS_ONLINE);
        });
        idlePulseTimer.start();
    }

    private void updateMemberStatus(String user, String status) {
        if (ProtocolConstants.STATUS_TYPING.equalsIgnoreCase(status) && !user.equalsIgnoreCase(session.username)) {
            typingIndicatorLabel.setText("✍ " + user + " is typing...");
            Timer resetTyping = new Timer(3000, e -> typingIndicatorLabel.setText(" "));
            resetTyping.setRepeats(false);
            resetTyping.start();
        }

        for (int i = 0; i < membersModel.size(); i++) {
            MemberItem m = membersModel.get(i);
            if (m.username.equalsIgnoreCase(user)) {
                membersModel.set(i, new MemberItem(user, status, m.role));
                return;
            }
        }
        membersModel.addElement(new MemberItem(user, status, ProtocolConstants.ROLE_STUDENT));
    }

    // =========================================================================
    // TCP FILE VAULT & CHANNEL TRANSFERS (Port 9001)
    // =========================================================================
    private void chooseAndUploadFile() {
        JFileChooser chooser = new JFileChooser();
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            boolean tamper = simulateTamperEnabled;

            new Thread(() -> {
                FileTransferClient client = new FileTransferClient(session.host, session.filePort, session.sessionToken, inspectorPanel::logEvent);
                FileTransferClient.TransferResult result = client.uploadFile(currentRoom, selected, tamper);

                SwingUtilities.invokeLater(() -> {
                    if (result.success) {
                        ToastNotification.show(MainHubFrame.this, "File Upload Verified",
                            selected.getName() + " saved to server vault.", ToastNotification.Type.SUCCESS);
                    } else if (result.tampered) {
                        ToastNotification.show(MainHubFrame.this, "TAMPER DETECTED",
                            "Server rejected upload! Computed: " + result.actualSha, ToastNotification.Type.ERROR);
                    } else {
                        ToastNotification.show(MainHubFrame.this, "Upload Failed", result.message, ToastNotification.Type.ERROR);
                    }
                });
            }, "File-Uploader").start();
        }
    }

    private void downloadFile(String room, String filename) {
        File dlDir = new File(NetworkConfig.DOWNLOADS_DIR);
        new Thread(() -> {
            FileTransferClient client = new FileTransferClient(session.host, session.filePort, session.sessionToken, inspectorPanel::logEvent);
            FileTransferClient.TransferResult result = client.downloadFile(room, filename, dlDir);

            SwingUtilities.invokeLater(() -> {
                if (result.success) {
                    ToastNotification.show(MainHubFrame.this, "Download Complete",
                        "Saved to " + result.savedFile.getAbsolutePath(), ToastNotification.Type.SUCCESS);
                } else {
                    ToastNotification.show(MainHubFrame.this, "Download Failed", result.message, ToastNotification.Type.ERROR);
                }
            });
        }, "File-Downloader").start();
    }

    private void filterVaultFiles(String query) {
        vaultModel.clear();
        String q = query.toLowerCase();
        for (SharedFileRecord r : allRoomFiles) {
            if (q.isEmpty() || r.getFilename().toLowerCase().contains(q) || r.getUploader().toLowerCase().contains(q)) {
                vaultModel.addElement(r);
            }
        }
    }

    // =========================================================================
    // RICH MESSAGE CARDS & JAVA2D RENDERING
    // =========================================================================
    private void renderChatMessageCard(String sender, String message, String time, boolean isAes) {
        boolean isConsecutive = sender.equalsIgnoreCase(lastSender);
        lastSender = sender;

        JPanel card = new JPanel(new BorderLayout(12, 4));
        card.setBackground(ThemeColors.BG_CHAT);
        card.setBorder(BorderFactory.createEmptyBorder(isConsecutive ? 2 : 8, 8, 2, 8));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));

        if (!isConsecutive) {
            AvatarComponent avatar = new AvatarComponent(sender, 38, null, false);
            card.add(avatar, BorderLayout.WEST);

            JPanel contentBox = new JPanel();
            contentBox.setLayout(new BoxLayout(contentBox, BoxLayout.Y_AXIS));
            contentBox.setOpaque(false);

            JPanel metaRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            metaRow.setOpaque(false);

            JLabel nameLbl = new JLabel(sender);
            nameLbl.setFont(ThemeColors.FONT_BOLD);
            nameLbl.setForeground(sender.equalsIgnoreCase(session.username) ? ThemeColors.ACCENT_PRIMARY : ThemeColors.TEXT_PRIMARY);

            String role = sender.equalsIgnoreCase("admin") ? "MOD" : "STUDENT";
            JLabel roleTag = new JLabel(" " + role + " ");
            roleTag.setFont(new Font("Segoe UI", Font.BOLD, 9));
            roleTag.setForeground(ThemeColors.TEXT_DARK);
            roleTag.setOpaque(true);
            roleTag.setBackground(role.equals("MOD") ? ThemeColors.BADGE_MOD : ThemeColors.BADGE_STUDENT);

            JLabel timeLbl = new JLabel(time);
            timeLbl.setFont(ThemeColors.FONT_MUTED);
            timeLbl.setForeground(ThemeColors.TEXT_MUTED);

            metaRow.add(nameLbl);
            metaRow.add(roleTag);
            metaRow.add(timeLbl);

            if (isAes) {
                JLabel aesPill = new JLabel(" 🔒 AES-128 ");
                aesPill.setFont(new Font("Segoe UI", Font.BOLD, 9));
                aesPill.setForeground(ThemeColors.STATUS_ONLINE);
                aesPill.setOpaque(true);
                aesPill.setBackground(ThemeColors.BG_INPUT);
                metaRow.add(aesPill);
            }

            JLabel textLbl = new JLabel("<html><body style='width: 580px; color: #f2f3f5; font-family: Segoe UI; font-size: 13px;'>" +
                escapeHtml(message) + "</body></html>");

            contentBox.add(metaRow);
            contentBox.add(Box.createVerticalStrut(3));
            contentBox.add(textLbl);

            card.add(contentBox, BorderLayout.CENTER);
        } else {
            // Indent consecutive messages
            JPanel indentBox = new JPanel(new BorderLayout());
            indentBox.setOpaque(false);
            indentBox.setBorder(BorderFactory.createEmptyBorder(0, 50, 0, 0));

            JLabel textLbl = new JLabel("<html><body style='width: 580px; color: #f2f3f5; font-family: Segoe UI; font-size: 13px;'>" +
                escapeHtml(message) + "</body></html>");
            indentBox.add(textLbl, BorderLayout.CENTER);
            card.add(indentBox, BorderLayout.CENTER);
        }

        chatFeedPanel.add(card);
        chatFeedPanel.revalidate();
        scrollToBottom();
    }

    private void renderLinkPreviewCard(String rawUrl, String hostIp, String status, String title) {
        RoundedPanel card = new RoundedPanel(10, ThemeColors.BG_CARD, ThemeColors.BORDER_SUBTLE, 1);
        card.setLayout(new BorderLayout(10, 4));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(4, 50, 4, 16),
            BorderFactory.createEmptyBorder(10, 14, 10, 14)
        ));

        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        topRow.setOpaque(false);

        JLabel badge = new JLabel(" HTTP PREVIEW ");
        badge.setFont(new Font("Segoe UI", Font.BOLD, 9));
        badge.setForeground(ThemeColors.ACCENT_PRIMARY);
        badge.setOpaque(true);
        badge.setBackground(ThemeColors.BG_INPUT);

        JLabel statusPill = new JLabel(" " + status + " ");
        statusPill.setFont(new Font("Segoe UI", Font.BOLD, 9));
        statusPill.setForeground(ThemeColors.STATUS_ONLINE);
        statusPill.setOpaque(true);
        statusPill.setBackground(ThemeColors.BG_INPUT);

        JLabel ipLbl = new JLabel("DNS: " + hostIp);
        ipLbl.setFont(ThemeColors.FONT_MUTED);
        ipLbl.setForeground(ThemeColors.TEXT_MUTED);

        topRow.add(badge);
        topRow.add(statusPill);
        topRow.add(ipLbl);

        JLabel titleLbl = new JLabel("<html><b style='color: #5865F2; font-size: 13px;'>" + escapeHtml(title) + "</b></html>");
        JLabel urlLbl = new JLabel("<html><u style='color: #949BA4;'>" + escapeHtml(rawUrl) + "</u></html>");
        urlLbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        urlLbl.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new URI(rawUrl));
                } catch (Exception ignored) {}
            }
        });

        card.add(topRow, BorderLayout.NORTH);
        card.add(titleLbl, BorderLayout.CENTER);
        card.add(urlLbl, BorderLayout.SOUTH);

        chatFeedPanel.add(card);
        chatFeedPanel.revalidate();
        scrollToBottom();
    }

    private void renderFileVaultCard(String room, String filename, long size, String uploader, String sha256) {
        RoundedPanel card = new RoundedPanel(10, ThemeColors.BG_CARD, ThemeColors.STATUS_ONLINE, 1);
        card.setLayout(new BorderLayout(12, 0));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 75));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(4, 50, 4, 16),
            BorderFactory.createEmptyBorder(8, 14, 8, 14)
        ));

        JLabel iconLbl = new JLabel("📄");
        iconLbl.setFont(new Font("Segoe UI", Font.PLAIN, 28));

        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setOpaque(false);

        String sizeStr = (size < 1024) ? size + " B" : String.format("%.1f KB", size / 1024.0);
        JLabel nameLbl = new JLabel(filename + " (" + sizeStr + ")");
        nameLbl.setFont(ThemeColors.FONT_HEADER);
        nameLbl.setForeground(ThemeColors.TEXT_PRIMARY);

        JLabel shaLbl = new JLabel("SHA-256: " + sha256 + " • Uploaded by " + uploader);
        shaLbl.setFont(ThemeColors.FONT_MUTED);
        shaLbl.setForeground(ThemeColors.TEXT_MUTED);

        infoPanel.add(nameLbl);
        infoPanel.add(Box.createVerticalStrut(2));
        infoPanel.add(shaLbl);

        PillButton dlBtn = new PillButton("Download", ThemeColors.STATUS_ONLINE, ThemeColors.STATUS_ONLINE.darker(), ThemeColors.TEXT_PRIMARY, 14);
        dlBtn.setPreferredSize(new Dimension(100, 32));
        dlBtn.addActionListener(e -> downloadFile(room, filename));

        card.add(iconLbl, BorderLayout.WEST);
        card.add(infoPanel, BorderLayout.CENTER);
        card.add(dlBtn, BorderLayout.EAST);

        chatFeedPanel.add(card);
        chatFeedPanel.revalidate();
        scrollToBottom();
    }

    private void renderSystemNotice(String text) {
        JPanel notice = new JPanel(new FlowLayout(FlowLayout.CENTER));
        notice.setOpaque(false);
        JLabel l = new JLabel("— " + text + " —");
        l.setFont(ThemeColors.FONT_MUTED);
        l.setForeground(ThemeColors.TEXT_MUTED);
        notice.add(l);
        chatFeedPanel.add(notice);
        chatFeedPanel.revalidate();
        scrollToBottom();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = chatScrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void showTlsCertDialog() {
        TlsAuthClient.CertificateDetails cert = session.certificateDetails;
        if (cert == null) return;

        JDialog dialog = new JDialog(this, "TLSv1.3 Security & X.509 Certificate Details", true);
        dialog.setSize(520, 340);
        dialog.setLocationRelativeTo(this);
        dialog.getContentPane().setBackground(ThemeColors.BG_PANEL);
        dialog.setLayout(new BorderLayout());

        JPanel p = new JPanel(new GridLayout(6, 1, 6, 6));
        p.setBackground(ThemeColors.BG_PANEL);
        p.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        p.add(createCertField("Negotiated Protocol:", cert.protocol));
        p.add(createCertField("Cipher Suite:", cert.cipherSuite));
        p.add(createCertField("Subject DN:", cert.subjectDN));
        p.add(createCertField("Issuer DN:", cert.issuerDN));
        p.add(createCertField("Valid Until:", cert.validTo));
        p.add(createCertField("SHA-256 Fingerprint:", cert.sha256Fingerprint));

        PillButton closeBtn = new PillButton("Close", ThemeColors.ACCENT_PRIMARY, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY, 14);
        closeBtn.addActionListener(e -> dialog.dispose());

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 10));
        btnBar.setOpaque(false);
        btnBar.add(closeBtn);

        dialog.add(p, BorderLayout.CENTER);
        dialog.add(btnBar, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private JPanel createCertField(String label, String val) {
        JPanel f = new JPanel(new BorderLayout(8, 0));
        f.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setFont(ThemeColors.FONT_BOLD);
        l.setForeground(ThemeColors.TEXT_MUTED);

        JLabel v = new JLabel(val);
        v.setFont(ThemeColors.FONT_MONO);
        v.setForeground(ThemeColors.TEXT_PRIMARY);

        f.add(l, BorderLayout.WEST);
        f.add(v, BorderLayout.CENTER);
        return f;
    }

    // =========================================================================
    // CUSTOM RENDERERS FOR SIDEBARS
    // =========================================================================
    private static class MemberCellRenderer extends JPanel implements ListCellRenderer<MemberItem> {
        private final AvatarComponent avatar = new AvatarComponent("?", 28);
        private final JLabel nameLbl = new JLabel();
        private final JLabel roleLbl = new JLabel();

        public MemberCellRenderer() {
            setLayout(new BorderLayout(8, 0));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

            nameLbl.setFont(ThemeColors.FONT_HEADER);
            roleLbl.setFont(new Font("Segoe UI", Font.BOLD, 9));

            JPanel textP = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            textP.setOpaque(false);
            textP.add(nameLbl);
            textP.add(roleLbl);

            add(avatar, BorderLayout.WEST);
            add(textP, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends MemberItem> list, MemberItem item, int index, boolean isSelected, boolean cellHasFocus) {
            setBackground(isSelected ? ThemeColors.BG_HOVER : ThemeColors.BG_PANEL);
            nameLbl.setText(item.username);
            nameLbl.setForeground(ThemeColors.TEXT_PRIMARY);

            Color dotColor = ProtocolConstants.STATUS_TYPING.equalsIgnoreCase(item.status) ? ThemeColors.STATUS_TYPING : ThemeColors.STATUS_ONLINE;
            avatar.setStatusColor(dotColor);

            if (ProtocolConstants.ROLE_MODERATOR.equalsIgnoreCase(item.role)) {
                roleLbl.setText("MOD");
                roleLbl.setForeground(ThemeColors.BADGE_MOD);
            } else {
                roleLbl.setText("");
            }

            return this;
        }
    }

    private class VaultCellRenderer extends JPanel implements ListCellRenderer<SharedFileRecord> {
        private final JLabel nameLbl = new JLabel();
        private final JLabel metaLbl = new JLabel();

        public VaultCellRenderer() {
            setLayout(new BorderLayout(0, 2));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

            nameLbl.setFont(ThemeColors.FONT_HEADER);
            nameLbl.setForeground(ThemeColors.TEXT_PRIMARY);

            metaLbl.setFont(ThemeColors.FONT_MUTED);
            metaLbl.setForeground(ThemeColors.TEXT_MUTED);

            add(nameLbl, BorderLayout.NORTH);
            add(metaLbl, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends SharedFileRecord> list, SharedFileRecord record, int index, boolean isSelected, boolean cellHasFocus) {
            setBackground(isSelected ? ThemeColors.BG_HOVER : ThemeColors.BG_PANEL);
            nameLbl.setText("📄 " + record.getFilename());
            metaLbl.setText(record.getFormattedSize() + " • " + record.getUploader());
            return this;
        }
    }
}
