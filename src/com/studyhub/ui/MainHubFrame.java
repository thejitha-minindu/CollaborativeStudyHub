package com.studyhub.ui;

import com.studyhub.common.DataPersistenceManager;
import com.studyhub.common.NetworkConfig;
import com.studyhub.common.PacketLogEvent;
import com.studyhub.common.ProtocolConstants;
import com.studyhub.concurrent.ResourceConcurrentEngine;
import com.studyhub.nio.MessageFramer;
import com.studyhub.security.CryptoIntegrityService;
import com.studyhub.security.TlsAuthClient;
import com.studyhub.transfer.FileNameUtils;
import com.studyhub.transfer.FileTransferClient;
import com.studyhub.transfer.SharedFileRecord;
import com.studyhub.udp.PresencePulseService;
import com.studyhub.ui.theme.AvatarComponent;
import com.studyhub.ui.theme.ModernScrollBarUI;
import com.studyhub.ui.theme.PillButton;
import com.studyhub.ui.theme.RoundedPanel;
import com.studyhub.ui.theme.ThemeColors;
import com.studyhub.ui.theme.VectorIcon;
import com.studyhub.ui.theme.ToastNotification;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * [COLLABORATIVE STUDY HUB - MODERN PERSISTENT CLIENT]
 * Discord/Slack-inspired 3-pane collaboration workspace with custom Java2D rendering,
 * rich message cards (reply/quote + moderator pinning), unread room badges, toast alerts,
 * drag-and-drop file sharing with a local downloads manager, live chat/vault search and
 * an auto-reconnecting NIO SocketChannel session.
 */
public class MainHubFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private final LoginDiscoveryDialog.SessionData session;
    private final NetworkInspectorPanel inspectorPanel;
    private final MessageFramer framer = new MessageFramer();
    private final AtomicLong pulseSeq = new AtomicLong(System.currentTimeMillis());

    /** Dedicated pool for the asynchronous {@code FileSearchCallable} vault filter. */
    private final ResourceConcurrentEngine searchEngine = new ResourceConcurrentEngine(2);

    // NIO Client Channel + resilience
    private SocketChannel nioChannel;
    private Thread nioReaderThread;
    private volatile boolean connected = false;
    private volatile boolean intentionalDisconnect = false;
    private volatile boolean reconnectInProgress = false;
    private Timer reconnectTimer;
    private int reconnectAttempt = 0;

    // Room & Navigation State
    private String currentRoom = ProtocolConstants.DEFAULT_ROOMS[0];
    private final List<String> roomList = new ArrayList<>();
    private final Map<String, String> roomTopics = new LinkedHashMap<>();
    private final Map<String, Integer> unreadByRoom = new LinkedHashMap<>();
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
    private final List<MessageCard> messageCards = new ArrayList<>();
    private final Component chatFiller = Box.createVerticalGlue();
    private final JTextField messageInput = new JTextField();
    private final PillButton sendBtn = new PillButton("Send");
    private final PillButton aesToggleBtn = new PillButton("", ThemeColors.BG_INPUT, ThemeColors.BG_HOVER, ThemeColors.STATUS_ONLINE, 12);
    private final JLabel typingIndicatorLabel = new JLabel(" ");
    private final JLabel currentRoomHeaderLabel = new JLabel("#general");
    private final JLabel currentRoomTopicLabel = new JLabel("General study discussion and resource sharing");
    private PillButton newMessagesFloatBtn;

    // Banners
    private final JPanel topBannerHost = new JPanel();
    private final JPanel reconnectBanner = new JPanel(new BorderLayout(10, 0));
    private final JLabel reconnectLabel = new JLabel("Reconnecting to Study Hub...");
    private final RoundedPanel pinnedBanner = new RoundedPanel(10, ThemeColors.BG_CARD, ThemeColors.ACCENT_PRIMARY, 1);
    private final JLabel pinnedLabel = new JLabel(" ");
    private final JButton unpinBtn = new JButton(new VectorIcon(VectorIcon.Kind.CLOSE, 14, ThemeColors.TEXT_MUTED));
    private String pinnedPayload = "";

    // Reply / quote state
    private ReplyReference pendingReply = null;
    private final JPanel replyBanner = new JPanel(new BorderLayout(10, 0));
    private final JLabel replyLabel = new JLabel(" ");

    // Search (Ctrl+F)
    private final JPanel searchPanel = new JPanel(new BorderLayout(8, 0));
    private final JTextField searchField = new JTextField();
    private final JLabel searchCountLabel = new JLabel("");
    private String activeSearchQuery = "";

    // Vault search (FileSearchCallable)
    private String pendingVaultQuery = "";
    private Timer vaultSearchTimer;
    private long vaultSearchGeneration;

    // Collapsible Inspector Drawer
    private JSplitPane verticalDrawerSplit;
    private boolean inspectorExpanded = false;
    private Timer drawerAnimation;
    private JLabel profileNameLabel;
    private final JLabel emptyRoom = new JLabel("<html><h2>Welcome to the conversation</h2>Drop files here or start the conversation</html>");
    private final Map<String, String> memberNames = new LinkedHashMap<>();
    private final java.util.concurrent.ExecutorService writes = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Workspace-Writer"); t.setDaemon(true); return t;
    });
    private final java.util.concurrent.ExecutorService pulses = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Workspace-Presence"); t.setDaemon(true); return t;
    });

    // Encryption & Tamper States
    private boolean aesEncryptionEnabled = true;
    private boolean simulateTamperEnabled = false;
    private Timer idlePulseTimer;
    private Timer typingDebounceTimer;
    private String lastSender = "";
    private String currentStatus = ProtocolConstants.STATUS_AVAILABLE;

    public static class MemberItem {
        public final String username;
        public final String status;
        public final String role;
        public final String displayName;

        public MemberItem(String username, String status, String role) { this(username, status, role, username); }
        public MemberItem(String username, String status, String role, String displayName) {
            this.displayName = displayName;
            this.username = username;
            this.status = status != null ? status : ProtocolConstants.STATUS_ONLINE;
            this.role = role != null ? role : ProtocolConstants.ROLE_STUDENT;
        }
    }

    /** Immutable snapshot of the message a composer reply refers to. */
    private static final class ReplyReference {
        final String sender;
        final String snippet;
        final String time;

        ReplyReference(String sender, String snippet, String time) {
            this.sender = sender;
            this.snippet = snippet;
            this.time = time != null ? time : "";
        }

        /** Wire format (fields escaped so it stays a single protocol field). */
        String toWire() {
            return DataPersistenceManager.encodeField(sender)
                + "|" + DataPersistenceManager.encodeField(snippet)
                + "|" + DataPersistenceManager.encodeField(time);
        }

        static ReplyReference fromWire(String wire) {
            if (wire == null || wire.trim().isEmpty()) return null;
            String[] parts = wire.split(ProtocolConstants.DELIMITER_REGEX, -1);
            String sender = parts.length > 0 ? DataPersistenceManager.decodeField(parts[0]) : "";
            String snippet = parts.length > 1 ? DataPersistenceManager.decodeField(parts[1]) : "";
            String time = parts.length > 2 ? DataPersistenceManager.decodeField(parts[2]) : "";
            return sender.isEmpty() ? null : new ReplyReference(sender, snippet, time);
        }
    }

    public MainHubFrame(LoginDiscoveryDialog.SessionData session, NetworkInspectorPanel inspectorPanel) {
        super(session.workspaceName + " · StudyHub");
        this.session = session;
        this.inspectorPanel = inspectorPanel;

        for (String r : ProtocolConstants.DEFAULT_ROOMS) {
            roomList.add(r);
            roomTopics.put(r, DataPersistenceManager.defaultRoomTopic(r));
        }

        inspectorPanel.onTamperChanged(enabled -> {
            simulateTamperEnabled = enabled;
            if (enabled) ToastNotification.show(this, "Tamper simulation enabled", "Uploads will be rejected after a byte is changed. Disable in TLS & Crypto to resume.", ToastNotification.Type.WARNING);
        });
        buildDiscordLikeUI();
        connectToNioServer();
        startPresenceHeartbeat();
    }

    // =========================================================================
    // ROOT LAYOUT
    // =========================================================================
    private void buildDiscordLikeUI() {
        java.awt.Rectangle usable = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1280, usable.width - 24), Math.min(860, usable.height - 24));
        setMinimumSize(new Dimension(Math.min(1040, usable.width - 24), Math.min(620, usable.height - 24)));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        getContentPane().setBackground(ThemeColors.BG_CHAT);
        setLayout(new BorderLayout());

        // 3-Pane Workspace
        JPanel leftPane = buildLeftSidebar();
        JPanel centerPane = buildCenterStage();
        JPanel rightPane = buildRightSidebar();

        JSplitPane centerRightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerPane, rightPane);
        centerRightSplit.setDividerLocation(780);
        centerRightSplit.setDividerSize(1);
        centerRightSplit.setBorder(null);

        JSplitPane mainHorizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, centerRightSplit);
        mainHorizontalSplit.setDividerLocation(240);
        mainHorizontalSplit.setDividerSize(1);
        mainHorizontalSplit.setBorder(null);

        // Bottom Collapsible Inspector Drawer Split
        verticalDrawerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainHorizontalSplit, inspectorPanel);
        verticalDrawerSplit.setDividerLocation(860); // Collapsed by default
        verticalDrawerSplit.setDividerSize(1);
        verticalDrawerSplit.setBorder(null);
        inspectorPanel.setVisible(false);

        add(buildTopBannerHost(), BorderLayout.NORTH);
        add(verticalDrawerSplit, BorderLayout.CENTER);

        installGlobalShortcuts();
        installWindowHooks();
    }

    private JPanel buildTopBannerHost() {
        topBannerHost.setLayout(new BoxLayout(topBannerHost, BoxLayout.Y_AXIS));
        topBannerHost.setBackground(ThemeColors.BG_RAIL);

        // --- Reconnecting banner (non-blocking, only shown while the link is down) ---
        reconnectBanner.setBackground(new Color(0x3A, 0x2F, 0x12));
        reconnectBanner.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeColors.STATUS_TYPING),
            BorderFactory.createEmptyBorder(8, 18, 8, 18)
        ));
        reconnectLabel.setFont(ThemeColors.FONT_BOLD);
        reconnectLabel.setForeground(ThemeColors.STATUS_TYPING);

        JLabel spinner = new JLabel(new VectorIcon(VectorIcon.Kind.ACTIVITY, 18, ThemeColors.STATUS_TYPING));
        spinner.setFont(new Font("Dialog", Font.BOLD, 15));
        spinner.setForeground(ThemeColors.STATUS_TYPING);

        reconnectBanner.add(spinner, BorderLayout.WEST);
        reconnectBanner.add(reconnectLabel, BorderLayout.CENTER);
        reconnectBanner.setVisible(false);

        // --- Pinned message banner ---
        pinnedBanner.setLayout(new BorderLayout(10, 0));
        pinnedBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        pinnedBanner.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(6, 14, 6, 14),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)
        ));

        JLabel pinIcon = new JLabel("Pinned message", new VectorIcon(VectorIcon.Kind.PIN, 16, ThemeColors.ACCENT_PRIMARY), JLabel.LEFT);
        pinIcon.setFont(ThemeColors.FONT_BOLD);
        pinIcon.setForeground(ThemeColors.ACCENT_PRIMARY);

        pinnedLabel.setFont(ThemeColors.FONT_BODY);
        pinnedLabel.setForeground(ThemeColors.TEXT_PRIMARY);

        unpinBtn.setFont(ThemeColors.FONT_BOLD);
        unpinBtn.setForeground(ThemeColors.TEXT_MUTED);
        unpinBtn.setContentAreaFilled(false);
        unpinBtn.setBorderPainted(false);
        unpinBtn.setFocusPainted(false);
        unpinBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        unpinBtn.setToolTipText("Unpin (moderators only)");
        unpinBtn.addActionListener(e -> clearPinnedMessage());

        pinnedBanner.add(pinIcon, BorderLayout.WEST);
        pinnedBanner.add(pinnedLabel, BorderLayout.CENTER);
        pinnedBanner.add(unpinBtn, BorderLayout.EAST);
        pinnedBanner.setVisible(false);

        topBannerHost.add(reconnectBanner);
        topBannerHost.add(pinnedBanner);
        return topBannerHost;
    }

    /**
     * Chat canvas layered pane: keeps the message scroll pane full-bleed while floating
     * the "jump to latest messages" pill above it.
     */
    private class ChatLayerPane extends JLayeredPane {
        private static final long serialVersionUID = 1L;

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            chatScrollPane.setBounds(0, 0, w, h);
            Dimension d = newMessagesFloatBtn.getPreferredSize();
            newMessagesFloatBtn.setBounds((w - d.width) / 2, h - d.height - 14, d.width, d.height);
        }

        @Override
        public Dimension getPreferredSize() {
            return chatScrollPane.getPreferredSize();
        }
    }

    private void installGlobalShortcuts() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_I, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), "diagnostics");
        getRootPane().getActionMap().put("diagnostics", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { toggleInspectorDrawer(); }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "focusMessageSearch");
        getRootPane().getActionMap().put("focusMessageSearch", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                toggleMessageSearch(true);
            }
        });
    }

    private void installWindowHooks() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdownNetworking();
                dispose();
                System.exit(0);
            }
        });
    }

    /** Stops every client-side networking task before the window goes away. */
    private void shutdownNetworking() {
        intentionalDisconnect = true;
        connected = false;
        if (idlePulseTimer != null) idlePulseTimer.stop();
        if (typingDebounceTimer != null) typingDebounceTimer.stop();
        if (reconnectTimer != null) reconnectTimer.stop();
        if (vaultSearchTimer != null) vaultSearchTimer.stop();
        closeChannel();
        if (drawerAnimation != null) drawerAnimation.stop();
        simulateTamperEnabled = false;
        inspectorPanel.resetTamper();
        writes.shutdownNow(); pulses.shutdownNow();
        searchEngine.shutdown();
    }

    private void closeChannel() {
        try {
            if (nioChannel != null && nioChannel.isOpen()) {
                nioChannel.close();
            }
        } catch (IOException ignored) {}
    }

    // =========================================================================
    // 1. LEFT SIDEBAR (Rooms + Unread Badges, Security Status, Profile)
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

        JPanel titleRow = new JPanel(new BorderLayout(8, 0)); titleRow.setOpaque(false);
        JLabel hubTitle = new JLabel(session.workspaceName);
        hubTitle.setToolTipText(session.workspaceName);
        hubTitle.setFont(ThemeColors.FONT_TITLE); hubTitle.setForeground(ThemeColors.TEXT_PRIMARY);
        PillButton connection = new PillButton("", ThemeColors.BG_RAIL, ThemeColors.BG_HOVER, ThemeColors.STATUS_ONLINE, 10);
        connection.setIcon(new VectorIcon(VectorIcon.Kind.LOCK, 17, ThemeColors.STATUS_ONLINE));
        connection.setToolTipText("Encrypted connection • Click for details");
        connection.getAccessibleContext().setAccessibleName("Connection security");
        connection.setPreferredSize(new Dimension(32, 32));
        connection.addActionListener(e -> showTlsCertDialog());
        titleRow.add(hubTitle, BorderLayout.CENTER); titleRow.add(connection, BorderLayout.EAST);
        topHubHeader.add(titleRow);
        if (session.hosting) {
            PillButton invite = new PillButton("Invite to workspace", ThemeColors.BG_RAIL, ThemeColors.BG_HOVER, ThemeColors.TEXT_MUTED, 10);
            invite.setIcon(new VectorIcon(VectorIcon.Kind.WIFI, 16, ThemeColors.TEXT_MUTED));
            invite.addActionListener(e -> showInviteDialog());
            topHubHeader.add(Box.createVerticalStrut(8)); topHubHeader.add(invite);
        }

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
        addRoomBtn.setFont(new Font("Dialog", Font.BOLD, 14));
        addRoomBtn.setForeground(ThemeColors.TEXT_MUTED);
        addRoomBtn.setContentAreaFilled(false);
        addRoomBtn.setBorderPainted(false);
        addRoomBtn.setFocusPainted(false);
        addRoomBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addRoomBtn.setToolTipText("Create a persistent study room");
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
        JPanel bar = new JPanel(new BorderLayout(0, 8));
        bar.setBackground(ThemeColors.BG_CARD);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeColors.BORDER_SUBTLE),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));

        // User Avatar + Name + Role
        JPanel userPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        userPanel.setOpaque(false);

        AvatarComponent avatar = new AvatarComponent(session.username, 34, ThemeColors.STATUS_ONLINE, true);

        JPanel namePanel = new JPanel();
        namePanel.setLayout(new BoxLayout(namePanel, BoxLayout.Y_AXIS));
        namePanel.setOpaque(false);

        String display = (session.displayName != null && !session.displayName.isEmpty()) ? session.displayName : session.username;
        JLabel usernameLabel = new JLabel(display);
        profileNameLabel = usernameLabel;
        usernameLabel.setFont(ThemeColors.FONT_HEADER);
        usernameLabel.setForeground(ThemeColors.TEXT_PRIMARY);

        JLabel roleBadge = new JLabel(ProtocolConstants.ROLE_MODERATOR.equalsIgnoreCase(session.role) ? "Moderator" : "Member");
        roleBadge.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        roleBadge.setFont(new Font("Dialog", Font.BOLD, 9));
        roleBadge.setForeground(ProtocolConstants.ROLE_MODERATOR.equalsIgnoreCase(session.role) ? ThemeColors.BADGE_MOD : ThemeColors.BADGE_STUDENT);

        namePanel.add(usernameLabel);
        namePanel.add(roleBadge);

        userPanel.add(avatar);
        userPanel.add(namePanel);

        PillButton settings = new PillButton("Settings", ThemeColors.BG_CARD, ThemeColors.BG_HOVER, ThemeColors.TEXT_MUTED, 10);
        settings.setIcon(new VectorIcon(VectorIcon.Kind.SETTINGS, 18, ThemeColors.TEXT_MUTED));
        settings.addActionListener(e -> showSettingsDialog());
        JPanel southPanel = new JPanel(new BorderLayout()); southPanel.setOpaque(false); southPanel.add(settings);

        bar.add(userPanel, BorderLayout.NORTH);
        bar.add(southPanel, BorderLayout.SOUTH);

        return bar;
    }

    private void refreshRoomsList() {
        roomsContainer.removeAll();
        for (String room : roomList) {
            boolean isActive = room.equalsIgnoreCase(currentRoom);
            Integer unread = unreadByRoom.get(room);

            RoundedPanel roomCard = new RoundedPanel(8, isActive ? ThemeColors.BG_HOVER : ThemeColors.BG_RAIL);
            roomCard.setLayout(new BorderLayout(8, 0));
            roomCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            roomCard.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
            roomCard.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel hash = new JLabel("#");
            hash.setFont(new Font("Dialog", Font.BOLD, 14));
            hash.setForeground(isActive ? ThemeColors.ACCENT_PRIMARY : ThemeColors.TEXT_MUTED);

            JLabel name = new JLabel(room.replace("#", ""));
            name.setFont(isActive ? ThemeColors.FONT_BOLD : ThemeColors.FONT_BODY);
            name.setForeground(isActive ? ThemeColors.TEXT_PRIMARY : ThemeColors.TEXT_MUTED);

            roomCard.add(hash, BorderLayout.WEST);
            roomCard.add(name, BorderLayout.CENTER);

            if (unread != null && unread > 0 && !isActive) {
                JLabel badge = new JLabel(" " + unread + " ");
                badge.setFont(new Font("Dialog", Font.BOLD, 10));
                badge.setForeground(ThemeColors.TEXT_PRIMARY);
                badge.setOpaque(true);
                badge.setBackground(ThemeColors.STATUS_DANGER);
                badge.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
                roomCard.add(badge, BorderLayout.EAST);
            }

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
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JTextField nameField = new JTextField(18);
        JTextField topicField = new JTextField(18);
        panel.add(new JLabel("Room name (e.g. #operating-systems):"));
        panel.add(nameField);
        panel.add(Box.createVerticalStrut(6));
        panel.add(new JLabel("Room topic / description:"));
        panel.add(topicField);

        int res = JOptionPane.showConfirmDialog(this, panel, "Create Persistent Study Room",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (res == JOptionPane.OK_OPTION && !nameField.getText().trim().isEmpty()) {
            String raw = nameField.getText().trim();
            String roomFormatted = raw.startsWith("#") ? raw : "#" + raw;
            String topic = topicField.getText().trim();

            // Ask the server to persist the room - it answers with a fresh ROOM_LIST.
            sendNioFrame(ProtocolConstants.CMD_CREATE_ROOM + ProtocolConstants.DELIMITER
                + roomFormatted + ProtocolConstants.DELIMITER
                + DataPersistenceManager.encodeField(topic));

            if (!containsIgnoreCase(roomList, roomFormatted)) {
                roomList.add(roomFormatted);
                roomTopics.put(roomFormatted, topic.isEmpty() ? DataPersistenceManager.defaultRoomTopic(roomFormatted) : topic);
                refreshRoomsList();
            }
            ToastNotification.show(this, "Study Room Created",
                roomFormatted + " is now stored on the server and survives restarts.", ToastNotification.Type.SUCCESS);
        }
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private void toggleInspectorDrawer() { setDiagnostics(!inspectorExpanded); }

    private void setDiagnostics(boolean expanded) {
        inspectorExpanded = expanded;
        if (drawerAnimation != null) drawerAnimation.stop();
        if (expanded) inspectorPanel.setVisible(true);
        if (!expanded) { simulateTamperEnabled = false; inspectorPanel.resetTamper(); }
        int target = Math.max(200, verticalDrawerSplit.getHeight() - (expanded ? 275 : 0));
        drawerAnimation = new Timer(15, e -> {
            int current = verticalDrawerSplit.getDividerLocation();
            int distance = target - current;
            if (Math.abs(distance) < 4) {
                verticalDrawerSplit.setDividerLocation(target);
                if (!expanded) inspectorPanel.setVisible(false);
                ((Timer)e.getSource()).stop();
            } else verticalDrawerSplit.setDividerLocation(current + distance / 3 + Integer.signum(distance));
        });
        drawerAnimation.start();
    }

    // =========================================================================
    // 2. CENTER STAGE (Rich Message Stream, Pinned Banner, Search & Composer)
    // =========================================================================
    private JPanel buildCenterStage() {
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(ThemeColors.BG_CHAT);

        // Top Room Header
        JPanel topHeader = new JPanel(new BorderLayout(12, 0));
        topHeader.setBackground(ThemeColors.BG_CHAT);
        topHeader.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeColors.BORDER_SUBTLE),
            BorderFactory.createEmptyBorder(10, 20, 10, 20)
        ));

        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        titlePanel.setOpaque(false);

        currentRoomHeaderLabel.setFont(new Font("Dialog", Font.BOLD, 15));
        currentRoomHeaderLabel.setForeground(ThemeColors.TEXT_PRIMARY);

        JLabel divider = new JLabel("|");
        divider.setForeground(ThemeColors.TEXT_MUTED);

        currentRoomTopicLabel.setFont(ThemeColors.FONT_MUTED);
        currentRoomTopicLabel.setForeground(ThemeColors.TEXT_MUTED);

        titlePanel.add(currentRoomHeaderLabel);
        titlePanel.add(divider);
        titlePanel.add(currentRoomTopicLabel);

        // Action Pills (Search + Share File + Tamper Toggle)
        JPanel actionPills = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionPills.setOpaque(false);

        PillButton searchBtn = new PillButton("Search", ThemeColors.BG_INPUT, ThemeColors.BG_HOVER, ThemeColors.TEXT_MUTED, 14);
        searchBtn.setFont(ThemeColors.FONT_MUTED);
        searchBtn.setIcon(new VectorIcon(VectorIcon.Kind.SEARCH, 16, ThemeColors.TEXT_MUTED));
        searchBtn.setToolTipText("Search messages (Ctrl+F)");
        searchBtn.addActionListener(e -> toggleMessageSearch(!searchPanel.isVisible()));

        PillButton shareFileTopBtn = new PillButton("Share File", ThemeColors.ACCENT_PRIMARY, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY, 14);
        shareFileTopBtn.setIcon(new VectorIcon(VectorIcon.Kind.FOLDER, 16, ThemeColors.TEXT_PRIMARY));
        shareFileTopBtn.addActionListener(e -> chooseAndUploadFile());

        actionPills.add(searchBtn);
        actionPills.add(shareFileTopBtn);

        JPanel titles = new JPanel(new BorderLayout(0, 4)); titles.setOpaque(false);
        titles.add(currentRoomHeaderLabel, BorderLayout.NORTH); titles.add(currentRoomTopicLabel, BorderLayout.CENTER);
        topHeader.add(titles, BorderLayout.CENTER);
        topHeader.add(actionPills, BorderLayout.EAST);

        // Message search bar (Ctrl+F)
        searchPanel.setOpaque(false);
        searchPanel.setBorder(BorderFactory.createEmptyBorder(6, 20, 6, 20));
        searchField.setFont(ThemeColors.FONT_BODY);
        searchField.setBackground(ThemeColors.BG_INPUT);
        searchField.setForeground(ThemeColors.TEXT_PRIMARY);
        searchField.setCaretColor(ThemeColors.TEXT_PRIMARY);
        searchField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ThemeColors.BORDER_SUBTLE, 1),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        ));
        searchField.setToolTipText("Filter and highlight messages in this room (Esc to close)");
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    toggleMessageSearch(false);
                    return;
                }
                applyMessageSearch(searchField.getText().trim());
            }
        });

        JButton closeSearchBtn = new JButton(new VectorIcon(VectorIcon.Kind.CLOSE, 14, ThemeColors.TEXT_MUTED));
        closeSearchBtn.setFont(ThemeColors.FONT_BOLD);
        closeSearchBtn.setForeground(ThemeColors.TEXT_MUTED);
        closeSearchBtn.setContentAreaFilled(false);
        closeSearchBtn.setBorderPainted(false);
        closeSearchBtn.setFocusPainted(false);
        closeSearchBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeSearchBtn.addActionListener(e -> toggleMessageSearch(false));

        searchCountLabel.setFont(ThemeColors.FONT_MUTED);
        searchCountLabel.setForeground(ThemeColors.TEXT_MUTED);
        searchCountLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.setOpaque(false);
        searchRow.add(searchField, BorderLayout.CENTER);
        searchRow.add(searchCountLabel, BorderLayout.EAST);
        searchRow.add(closeSearchBtn, BorderLayout.WEST);

        searchPanel.add(searchRow, BorderLayout.CENTER);
        searchPanel.setVisible(false);

        JPanel headerHost = new JPanel(new BorderLayout());
        headerHost.setBackground(ThemeColors.BG_CHAT);
        headerHost.add(topHeader, BorderLayout.NORTH);
        headerHost.add(searchPanel, BorderLayout.SOUTH);

        // Chat Message Feed (layered so the "jump to latest" pill can float)
        chatFeedPanel.setLayout(new BoxLayout(chatFeedPanel, BoxLayout.Y_AXIS));
        chatFeedPanel.setBackground(ThemeColors.BG_CHAT);
        chatFeedPanel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        if (chatFeedPanel.getComponentCount() == 0) {
            emptyRoom.setForeground(ThemeColors.TEXT_MUTED);
            emptyRoom.setBorder(BorderFactory.createEmptyBorder(28, 20, 28, 20));
            chatFeedPanel.add(emptyRoom);
        }
        chatFeedPanel.add(chatFiller);

        chatScrollPane.setBackground(ThemeColors.BG_CHAT);
        chatScrollPane.getViewport().setBackground(ThemeColors.BG_CHAT);
        chatScrollPane.setBorder(null);
        chatScrollPane.getVerticalScrollBar().setUI(new ModernScrollBarUI(6));

        newMessagesFloatBtn = new PillButton("↓ New messages", ThemeColors.ACCENT_PRIMARY, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY, 14);
        newMessagesFloatBtn.setFont(ThemeColors.FONT_MUTED);
        newMessagesFloatBtn.setVisible(false);
        newMessagesFloatBtn.addActionListener(e -> scrollToBottom());

        JLayeredPane chatLayer = new ChatLayerPane();
        chatLayer.add(chatScrollPane, JLayeredPane.DEFAULT_LAYER);
        chatLayer.add(newMessagesFloatBtn, JLayeredPane.PALETTE_LAYER);

        installDropTarget(chatScrollPane);

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

        center.add(headerHost, BorderLayout.NORTH);
        center.add(chatLayer, BorderLayout.CENTER);
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

        // Reply / quote banner
        replyBanner.setOpaque(false);
        replyBanner.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        replyBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

        replyLabel.setFont(ThemeColors.FONT_MUTED);
        replyLabel.setForeground(ThemeColors.TEXT_MUTED);

        JButton cancelReplyBtn = new JButton(new VectorIcon(VectorIcon.Kind.CLOSE, 14, ThemeColors.TEXT_MUTED));
        cancelReplyBtn.setFont(ThemeColors.FONT_BOLD);
        cancelReplyBtn.setForeground(ThemeColors.TEXT_MUTED);
        cancelReplyBtn.setContentAreaFilled(false);
        cancelReplyBtn.setBorderPainted(false);
        cancelReplyBtn.setFocusPainted(false);
        cancelReplyBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cancelReplyBtn.addActionListener(e -> clearPendingReply());

        replyBanner.add(replyLabel, BorderLayout.CENTER);
        replyBanner.add(cancelReplyBtn, BorderLayout.EAST);
        replyBanner.setVisible(false);

        // Input pill container
        RoundedPanel inputCard = new RoundedPanel(18, ThemeColors.BG_INPUT, ThemeColors.BORDER_SUBTLE, 1);
        inputCard.setLayout(new BorderLayout(8, 0));
        inputCard.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));

        // + Attach File Button
        JButton attachBtn = new JButton("+");
        attachBtn.setFont(new Font("Dialog", Font.BOLD, 18));
        attachBtn.setForeground(ThemeColors.TEXT_MUTED);
        attachBtn.setContentAreaFilled(false);
        attachBtn.setBorderPainted(false);
        attachBtn.setFocusPainted(false);
        attachBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        attachBtn.setToolTipText("Share a file (or just drag files onto the chat)");
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

        aesToggleBtn.setIcon(new VectorIcon(VectorIcon.Kind.LOCK, 17, ThemeColors.STATUS_ONLINE));
        aesToggleBtn.setPreferredSize(new Dimension(34, 32));
        aesToggleBtn.setToolTipText("Message encryption enabled (workspace key)");
        aesToggleBtn.getAccessibleContext().setAccessibleName("Toggle message encryption");
        aesToggleBtn.addActionListener(e -> {
            aesEncryptionEnabled = !aesEncryptionEnabled;
            aesToggleBtn.setIcon(new VectorIcon(aesEncryptionEnabled ? VectorIcon.Kind.LOCK : VectorIcon.Kind.UNLOCK,
                17, aesEncryptionEnabled ? ThemeColors.STATUS_ONLINE : ThemeColors.STATUS_TYPING));
            aesToggleBtn.setToolTipText(aesEncryptionEnabled ? "Message encryption enabled (workspace key)" : "Message encryption disabled");
        });

        sendBtn.addActionListener(e -> sendMessage());

        rightTools.add(aesToggleBtn);
        rightTools.add(sendBtn);

        inputCard.add(attachBtn, BorderLayout.WEST);
        inputCard.add(messageInput, BorderLayout.CENTER);
        inputCard.add(rightTools, BorderLayout.EAST);

        container.add(typingRow);
        container.add(replyBanner);
        container.add(Box.createVerticalStrut(4));
        container.add(inputCard);

        return container;
    }

    // =========================================================================
    // 3. RIGHT SIDEBAR (Online Members & Searchable Room Study Vault)
    // =========================================================================
    private JPanel buildRightSidebar() {
        JPanel right = new JPanel(new GridLayout(2, 1, 0, 1));
        right.setBackground(ThemeColors.BG_PANEL);
        right.setPreferredSize(new Dimension(250, 0));
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

        JTextField vaultSearchField = new JTextField();
        vaultSearchField.setFont(ThemeColors.FONT_MUTED);
        vaultSearchField.setBackground(ThemeColors.BG_INPUT);
        vaultSearchField.setForeground(ThemeColors.TEXT_MUTED);
        vaultSearchField.setCaretColor(ThemeColors.TEXT_PRIMARY);
        vaultSearchField.setText("Search files...");
        vaultSearchField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ThemeColors.BORDER_SUBTLE, 1),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        ));
        vaultSearchField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                if ("Search files...".equals(vaultSearchField.getText())) {
                    vaultSearchField.setText("");
                    vaultSearchField.setForeground(ThemeColors.TEXT_PRIMARY);
                }
            }
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                if (vaultSearchField.getText().trim().isEmpty()) {
                    vaultSearchField.setText("Search files...");
                    vaultSearchField.setForeground(ThemeColors.TEXT_MUTED);
                }
            }
        });
        vaultSearchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                String text = vaultSearchField.getText();
                pendingVaultQuery = "Search files...".equals(text) ? "" : text.trim();
                scheduleVaultSearch();
            }
        });

        vaultList.setBackground(ThemeColors.BG_PANEL);
        vaultList.setCellRenderer(new VaultCellRenderer());
        vaultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = vaultList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                SharedFileRecord record = vaultModel.getElementAt(idx);
                if (record == null) return;
                if (e.getClickCount() == 2) {
                    downloadAndOpen(record.getRoom(), record.getFilename());
                } else if (e.getClickCount() == 1) {
                    downloadFile(record.getRoom(), record.getFilename(), null);
                }
            }
        });

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
    // NETWORKING: NIO SocketChannel Multiplexing + Auto-Reconnect
    // =========================================================================
    private void connectToNioServer() {
        new Thread(() -> {
            try {
                nioChannel = SocketChannel.open();
                nioChannel.socket().connect(new InetSocketAddress(session.host, session.nioPort), NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
                connected = true;

                inspectorPanel.logEvent(new PacketLogEvent(
                    PacketLogEvent.Module.NIO_SELECTOR,
                    PacketLogEvent.Direction.TX,
                    "NIO SocketChannel Connected",
                    "Connected to " + session.host + ":" + session.nioPort
                ));

                sendHelloAndJoin();
                startNioReader();

            } catch (Exception e) {
                inspectorPanel.logEvent(new PacketLogEvent(
                    PacketLogEvent.Module.NIO_SELECTOR,
                    PacketLogEvent.Direction.INTERNAL,
                    "NIO Connect Failed",
                    e.getMessage()
                ));
                SwingUtilities.invokeLater(this::handleDisconnected);
            }
        }, "NIO-Client-Worker").start();
    }

    private void sendHelloAndJoin() {
        // Send HELLO handshake
        String hello = ProtocolConstants.CMD_HELLO + ProtocolConstants.DELIMITER +
            session.sessionToken + ProtocolConstants.DELIMITER + session.username;
        sendNioFrame(hello);

        // Re-enter the room we were in (critical after an auto-reconnect)
        String join = ProtocolConstants.CMD_JOIN + ProtocolConstants.DELIMITER + currentRoom;
        if (!currentRoom.equalsIgnoreCase(ProtocolConstants.DEFAULT_ROOMS[0])) sendNioFrame(join);
    }

    private void startNioReader() {
        if (nioReaderThread != null && nioReaderThread.isAlive()) {
            return;
        }
        nioReaderThread = new Thread(() -> {
            ByteBuffer readBuf = ByteBuffer.allocate(NetworkConfig.NIO_BUFFER_CAPACITY);
            while (connected && nioChannel != null && nioChannel.isOpen()) {
                try {
                    readBuf = MessageFramer.ensureWritable(readBuf);
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
            if (!intentionalDisconnect) {
                SwingUtilities.invokeLater(this::handleDisconnected);
            }
        }, "NIO-Client-Reader");
        nioReaderThread.setDaemon(true);
        nioReaderThread.start();
    }

    private void handleDisconnected() {
        if (intentionalDisconnect) {
            return;
        }
        connected = false;
        reconnectAttempt = 0;
        showReconnectBanner(true);

        if (reconnectTimer == null) {
            reconnectTimer = new Timer(NetworkConfig.RECONNECT_INTERVAL_MS, e -> attemptReconnect());
        }
        if (!reconnectTimer.isRunning()) {
            reconnectTimer.start();
        }
    }

    private void showReconnectBanner(boolean visible) {
        reconnectBanner.setVisible(visible);
        topBannerHost.revalidate();
        topBannerHost.repaint();
    }

    /** Re-authenticates over TLS and re-establishes the NIO session without closing the window. */
    private void attemptReconnect() {
        if (reconnectInProgress || intentionalDisconnect) {
            return;
        }
        reconnectInProgress = true;
        reconnectAttempt++;
        reconnectLabel.setText("Reconnecting to Study Hub... (attempt " + reconnectAttempt + ")");

        new Thread(() -> {
            boolean restored = false;
            try {
                TlsAuthClient authClient = new TlsAuthClient(session.host, session.tlsPort, inspectorPanel::logEvent);
                TlsAuthClient.AuthResult result = (session.passwordSha256 == null || session.passwordSha256.isEmpty())
                    ? null
                    : authClient.authenticateHashed(session.username, session.passwordSha256);

                if (result != null && result.success && !intentionalDisconnect) {
                    session.certificateDetails = result.certificateDetails;
                    // Refresh the volatile session material issued by the auth server
                    session.sessionToken = result.sessionToken;
                    session.aesKeyBase64 = result.aesKeyBase64;

                    SocketChannel fresh = SocketChannel.open();
                    fresh.socket().connect(new InetSocketAddress(session.host, session.nioPort), NetworkConfig.TCP_SOCKET_TIMEOUT_MS);
                    if (intentionalDisconnect) { fresh.close(); return; }
                    closeChannel();
                    nioChannel = fresh;
                    connected = true;
                    restored = true;

                    inspectorPanel.logEvent(new PacketLogEvent(
                        PacketLogEvent.Module.NIO_SELECTOR,
                        PacketLogEvent.Direction.TX,
                        "NIO Session Restored",
                        "Re-authenticated over TLS 9443 and re-opened SocketChannel on "
                            + session.host + ":" + session.nioPort
                    ));
                }
            } catch (Exception e) {
                inspectorPanel.logEvent(new PacketLogEvent(
                    PacketLogEvent.Module.NIO_SELECTOR,
                    PacketLogEvent.Direction.INTERNAL,
                    "Reconnect Attempt Failed",
                    e.getMessage()
                ));
            }

            final boolean ok = restored;
            SwingUtilities.invokeLater(() -> {
                reconnectInProgress = false;
                if (intentionalDisconnect) { closeChannel(); return; }
                if (ok) {
                    if (reconnectTimer != null) reconnectTimer.stop();
                    showReconnectBanner(false);
                    sendHelloAndJoin();
                    startNioReader();
                    ToastNotification.show(MainHubFrame.this, "Reconnected",
                        "Study Hub session restored - you are back in " + currentRoom, ToastNotification.Type.SUCCESS);
                }
            });
        }, "NIO-Reconnect").start();
    }

    private void handleInboundNioFrame(String frame) {
        String[] parts = frame.split(ProtocolConstants.DELIMITER_REGEX, -1);
        if (parts.length == 0) return;

        String type = parts[0];
        SwingUtilities.invokeLater(() -> {
            switch (type) {
                case ProtocolConstants.EVT_CHAT: {
                    // CHAT|<room>|<sender>|<encFlag>|<content>|<time>[|<replyRef>]
                    if (parts.length >= 6) {
                        boolean hasReply = parts.length >= 7;
                        String room = parts[1];
                        String sender = parts[2];
                        String encFlag = parts[3];
                        String content = joinRange(parts, 4, hasReply ? parts.length - 2 : parts.length - 1);
                        String time = hasReply ? parts[parts.length - 2] : parts[parts.length - 1];
                        String replyRef = hasReply ? parts[parts.length - 1] : "";

                        String displayContent = content;
                        if (ProtocolConstants.ENC_AES.equalsIgnoreCase(encFlag)) {
                            try {
                                displayContent = CryptoIntegrityService.decryptAes(content, session.aesKeyBase64);
                            } catch (Exception e) {
                                displayContent = "This older encrypted message is unavailable with the current workspace key.";
                            }
                        }

                        onChatArrived(room, sender, displayContent, time,
                            ProtocolConstants.ENC_AES.equalsIgnoreCase(encFlag), replyRef);
                    }
                    break;
                }

                case ProtocolConstants.EVT_USER_LIST: {
                    // USER_LIST|<room>|<user:status>|...
                    if (parts.length >= 2) {
                        String room = parts[1];
                        if (room.equalsIgnoreCase(currentRoom)) {
                            membersModel.clear();
                            for (int i = 2; i < parts.length; i++) {
                                String[] us = parts[i].split(":", -1);
                                String u = us[0];
                                String st = (us.length > 1) ? us[1] : ProtocolConstants.STATUS_ONLINE;
                                String role = us.length > 2 ? us[2] : ProtocolConstants.ROLE_STUDENT;
                                if (us.length > 3) memberNames.put(u, new String(java.util.Base64.getDecoder().decode(us[3]), java.nio.charset.StandardCharsets.UTF_8));
                                membersModel.addElement(new MemberItem(u, st, role, memberNames.getOrDefault(u, u)));
                            }
                        }
                    }
                    break;
                }

                case ProtocolConstants.EVT_PRESENCE_UPDATE:
                    if (parts.length >= 4) {
                        updateMemberStatus(parts[2], parts[3]);
                    }
                    break;

                case ProtocolConstants.EVT_LINK_CARD:
                    // LINK_CARD|<room>|<url>|<ip>|<status>|<title>
                    if (parts.length >= 6) {
                        renderLinkPreviewCard(parts[2], parts[3], parts[4], parts[5]);
                    }
                    break;

                case ProtocolConstants.EVT_FILE_ADDED: {
                    // FILE_ADDED|<room>|<filename>|<size>|<uploader>|<sha256>
                    if (parts.length >= 6) {
                        String room = parts[1];
                        String filename = parts[2];
                        long size = Long.parseLong(parts[3]);
                        String uploader = parts[4];
                        String sha256 = parts[5];

                        if (room.equalsIgnoreCase(currentRoom)) {
                            SharedFileRecord rec = new SharedFileRecord("vault_" + filename, room, filename, size, sha256, uploader);
                            if (!containsFile(allRoomFiles, filename)) {
                                allRoomFiles.add(rec);
                            }
                            refreshVaultModel();
                            renderFileVaultCard(room, filename, size, uploader, sha256);
                        }
                        if (!uploader.equalsIgnoreCase(session.username)) {
                            ToastNotification.show(MainHubFrame.this, "New File Shared",
                                filename + " in " + room + " by " + uploader, ToastNotification.Type.SUCCESS);
                            Toolkit.getDefaultToolkit().beep();
                        }
                    }
                    break;
                }

                case ProtocolConstants.EVT_VAULT_LIST: {
                    // VAULT_LIST|<room>|<fileId>|<original>|<stored>|<size>|<sha>|<uploader>|<timestamp>
                    if (parts.length >= 9) {
                        String room = parts[1];
                        if (room.equalsIgnoreCase(currentRoom)) {
                            String fileId = DataPersistenceManager.decodeField(parts[2]);
                            String original = DataPersistenceManager.decodeField(parts[3]);
                            String stored = DataPersistenceManager.decodeField(parts[4]);
                            long size = Long.parseLong(parts[5]);
                            String sha = DataPersistenceManager.decodeField(parts[6]);
                            String uploader = DataPersistenceManager.decodeField(parts[7]);
                            String ts = DataPersistenceManager.decodeField(parts[8]);

                            if (!containsFile(allRoomFiles, original)) {
                                allRoomFiles.add(new SharedFileRecord(fileId, room, original, stored, size, sha, uploader, ts));
                                refreshVaultModel();
                            }
                        }
                    }
                    break;
                }

                case ProtocolConstants.EVT_HISTORY_BATCH: {
                    // HISTORY_BATCH|<room>|<count>|<entry>|<entry>|...
                    if (parts.length >= 3) {
                        String room = parts[1];
                        int count = Integer.parseInt(parts[2]);
                        if (room.equalsIgnoreCase(currentRoom) && count > 0) {
                            renderSystemNotice("Restored " + count + " previous message(s) from this room");
                            for (int i = 3; i < parts.length; i++) {
                                renderHistoryEntry(parts[i]);
                            }
                        }
                    }
                    break;
                }

                case ProtocolConstants.EVT_ROOM_LIST: {
                    // ROOM_LIST|<room>:<topic>|...
                    List<String> refreshed = new ArrayList<>();
                    for (int i = 1; i < parts.length; i++) {
                        String[] rt = parts[i].split(":", 2);
                        String room = DataPersistenceManager.decodeField(rt[0]);
                        String topic = rt.length > 1 ? DataPersistenceManager.decodeField(rt[1]) : "";
                        if (room.isEmpty()) continue;
                        refreshed.add(room);
                        roomTopics.put(room, topic);
                        if (room.equalsIgnoreCase(currentRoom) && !topic.isEmpty()) {
                            currentRoomTopicLabel.setText(topic);
                        }
                    }
                    if (!refreshed.isEmpty()) {
                        roomList.clear();
                        roomList.addAll(refreshed);
                        refreshRoomsList();
                    }
                    break;
                }

                case ProtocolConstants.EVT_PINNED: {
                    // PINNED|<room>|<payload>
                    if (parts.length >= 2) {
                        String room = parts[1];
                        String payload = parts.length >= 3 ? parts[2] : "";
                        if (room.equalsIgnoreCase(currentRoom)) {
                            showPinnedBanner(payload);
                        }
                    }
                    break;
                }

                case "PROFILE_UPDATED":
                    if (parts.length >= 2) {
                        session.displayName = DataPersistenceManager.decodeField(parts[1]);
                        profileNameLabel.setText(session.displayName);
                        ToastNotification.show(this, "Profile updated", "Your display name has been saved.", ToastNotification.Type.SUCCESS);
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

    private static String joinRange(String[] parts, int from, int to) {
        if (to <= from) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < parts.length; i++) {
            if (i > from) sb.append(ProtocolConstants.DELIMITER);
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static boolean containsFile(List<SharedFileRecord> records, String filename) {
        for (SharedFileRecord r : records) {
            if (r.getFilename().equalsIgnoreCase(filename)) return true;
        }
        return false;
    }

    /** Renders one replayed {@code data/history_<room>.log} line into the feed. */
    private void renderHistoryEntry(String line) {
        DataPersistenceManager.HistoryEntry entry = DataPersistenceManager.HistoryEntry.fromLine(line);
        if (entry == null) return;

        if (ProtocolConstants.HIST_MSG.equalsIgnoreCase(entry.getKind())) {
            String text = entry.getPayload();
            if (ProtocolConstants.ENC_AES.equalsIgnoreCase(entry.getEncFlag())) {
                try {
                    text = CryptoIntegrityService.decryptAes(entry.getPayload(), session.aesKeyBase64);
                } catch (Exception e) {
                    text = "This older encrypted message is unavailable with the current workspace key.";
                }
            }
            renderChatMessageCard(entry.getSender(), text, entry.getTimestamp(),
                ProtocolConstants.ENC_AES.equalsIgnoreCase(entry.getEncFlag()), entry.getReplyRef(), true);

        } else if (ProtocolConstants.HIST_LINK.equalsIgnoreCase(entry.getKind())) {
            String[] f = DataPersistenceManager.split(entry.getPayload());
            if (f.length >= 4) {
                renderLinkPreviewCard(
                    DataPersistenceManager.decodeField(f[0]),
                    DataPersistenceManager.decodeField(f[1]),
                    DataPersistenceManager.decodeField(f[2]),
                    DataPersistenceManager.decodeField(f[3]));
            }

        } else if (ProtocolConstants.HIST_FILE.equalsIgnoreCase(entry.getKind())) {
            String[] f = DataPersistenceManager.split(entry.getPayload());
            if (f.length >= 4) {
                renderFileVaultCard(currentRoom,
                    DataPersistenceManager.decodeField(f[0]),
                    Long.parseLong(f[1]),
                    DataPersistenceManager.decodeField(f[2]),
                    DataPersistenceManager.decodeField(f[3]));
            }
        }
    }

    /** Dispatches an incoming chat message: render, badge, mention alert. */
    private void onChatArrived(String room, String sender, String text, String time, boolean isAes, String replyRef) {
        boolean directMessage = room.startsWith("DM:");
        if (directMessage || room.equalsIgnoreCase(currentRoom)) {
            renderChatMessageCard(sender, text, time, isAes, replyRef, false);
        } else {
            Integer count = unreadByRoom.get(room);
            unreadByRoom.put(room, (count == null ? 0 : count) + 1);
            refreshRoomsList();
        }

        if (!sender.equalsIgnoreCase(session.username)) {
            if (directMessage) {
                ToastNotification.show(this, "Direct message from " + sender,
                    stripToLength(text, 120), ToastNotification.Type.INFO);
                Toolkit.getDefaultToolkit().beep();
            } else if (mentionsMe(text)) {
                ToastNotification.show(this, "Mentioned by " + sender,
                    stripToLength(text, 120), ToastNotification.Type.INFO);
                Toolkit.getDefaultToolkit().beep();
            }
        }
    }

    private boolean mentionsMe(String text) {
        if (text == null || text.isEmpty()) return false;
        String mention = "@" + session.username;
        int idx = text.toLowerCase().indexOf(mention.toLowerCase());
        if (idx < 0) return false;
        int after = idx + mention.length();
        return after >= text.length() || !Character.isLetterOrDigit(text.charAt(after));
    }

    private static String stripToLength(String text, int max) {
        String flat = text.replace("\n", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    private void sendNioFrame(String payload) {
        SocketChannel channel = nioChannel;
        if (!connected || intentionalDisconnect || channel == null || !channel.isOpen()) return;
        ByteBuffer buffer = framer.frameMessage(payload);
        try { writes.execute(() -> {
            try {
                if (channel != nioChannel || intentionalDisconnect) return;
                while (buffer.hasRemaining()) channel.write(buffer);
                inspectorPanel.logEvent(new PacketLogEvent(PacketLogEvent.Module.NIO_SELECTOR,
                    PacketLogEvent.Direction.TX, "Sent Framed Packet", payload.startsWith("HELLO|") ? "HELLO|[session redacted]" : payload,
                    framer.getBufferInspectionSummary()));
            } catch (IOException e) {
                SwingUtilities.invokeLater(this::handleDisconnected);
            }
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) { /* Window is closing. */ }
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

        String replyWire = (pendingReply != null) ? pendingReply.toWire() : "";

        String cmd = ProtocolConstants.CMD_MSG + ProtocolConstants.DELIMITER +
            currentRoom + ProtocolConstants.DELIMITER + encFlag + ProtocolConstants.DELIMITER +
            payloadToSend + ProtocolConstants.DELIMITER + replyWire;

        sendNioFrame(cmd);
        messageInput.setText("");
        clearPendingReply();
        emitPresencePulse(currentStatus);
    }

    private void switchRoom(String newRoom) {
        currentRoom = newRoom;
        currentRoomHeaderLabel.setText(newRoom);
        String topic = roomTopics.get(newRoom);
        currentRoomTopicLabel.setText((topic == null || topic.isEmpty())
            ? DataPersistenceManager.defaultRoomTopic(newRoom) : topic);

        chatFeedPanel.removeAll();
        if (chatFeedPanel.getComponentCount() == 0) {
            emptyRoom.setForeground(ThemeColors.TEXT_MUTED);
            emptyRoom.setBorder(BorderFactory.createEmptyBorder(28, 20, 28, 20));
            chatFeedPanel.add(emptyRoom);
        }
        chatFeedPanel.add(chatFiller);
        chatFeedPanel.revalidate();
        chatFeedPanel.repaint();
        messageCards.clear();
        lastSender = "";
        vaultModel.clear();
        allRoomFiles.clear();

        // Entering a room clears its unread badge
        unreadByRoom.remove(newRoom);
        pinnedPayload = "";
        pinnedBanner.setVisible(false);
        refreshRoomsList();
        applyMessageSearch(activeSearchQuery);

        String cmd = ProtocolConstants.CMD_JOIN + ProtocolConstants.DELIMITER + newRoom;
        sendNioFrame(cmd);
        emitPresencePulse(currentStatus);
    }

    // =========================================================================
    // REPLY / QUOTE + MODERATOR PINNING
    // =========================================================================
    private void setPendingReply(String sender, String snippet, String time) {
        pendingReply = new ReplyReference(sender, stripToLength(snippet, 70), time);
        replyLabel.setText("<html>↪ Replying to <b style='color:#5865F2;'>" + escapeHtml(sender)
            + "</b>: " + escapeHtml(pendingReply.snippet) + "</html>");
        replyBanner.setVisible(true);
        replyBanner.revalidate();
        replyBanner.repaint();
        messageInput.requestFocusInWindow();
    }

    private void clearPendingReply() {
        pendingReply = null;
        replyBanner.setVisible(false);
        replyBanner.revalidate();
        replyBanner.repaint();
    }

    private void pinMessage(String sender, String snippet, String time) {
        String payload = DataPersistenceManager.encodeField(session.username)
            + "|" + DataPersistenceManager.encodeField(time)
            + "|" + DataPersistenceManager.encodeField(sender)
            + "|" + DataPersistenceManager.encodeField(stripToLength(snippet, 160));
        String cmd = ProtocolConstants.CMD_PIN + ProtocolConstants.DELIMITER + currentRoom
            + ProtocolConstants.DELIMITER + payload;
        sendNioFrame(cmd);
    }

    private void clearPinnedMessage() {
        if (!ProtocolConstants.ROLE_MODERATOR.equalsIgnoreCase(session.role)) {
            ToastNotification.show(this, "Moderators Only",
                "Only moderators can unpin a room message.", ToastNotification.Type.WARNING);
            return;
        }
        String cmd = ProtocolConstants.CMD_PIN + ProtocolConstants.DELIMITER + currentRoom
            + ProtocolConstants.DELIMITER + "";
        sendNioFrame(cmd);
    }

    private void showPinnedBanner(String payload) {
        pinnedPayload = payload == null ? "" : payload;
        if (pinnedPayload.isEmpty()) {
            pinnedBanner.setVisible(false);
            topBannerHost.revalidate();
            topBannerHost.repaint();
            return;
        }
        String[] f = DataPersistenceManager.split(pinnedPayload);
        String by = f.length > 0 ? DataPersistenceManager.decodeField(f[0]) : "";
        String time = f.length > 1 ? DataPersistenceManager.decodeField(f[1]) : "";
        String sender = f.length > 2 ? DataPersistenceManager.decodeField(f[2]) : "";
        String text = f.length > 3 ? DataPersistenceManager.decodeField(f[3]) : "";

        pinnedLabel.setText("<html><b style='color:#F2F3F5;'>" + escapeHtml(sender) + "</b> "
            + escapeHtml(text) + " <font color='#949BA4'>— pinned by " + escapeHtml(by)
            + (time.isEmpty() ? "" : " at " + escapeHtml(time)) + "</font></html>");
        pinnedBanner.setVisible(true);
        topBannerHost.revalidate();
        topBannerHost.repaint();
    }

    // =========================================================================
    // CHAT SEARCH (Ctrl+F)
    // =========================================================================
    private void toggleMessageSearch(boolean visible) {
        searchPanel.setVisible(visible);
        if (visible) {
            searchField.requestFocusInWindow();
            searchField.selectAll();
        } else {
            searchField.setText("");
            applyMessageSearch("");
        }
        searchPanel.revalidate();
        searchPanel.repaint();
    }

    private void applyMessageSearch(String query) {
        activeSearchQuery = query == null ? "" : query.trim();
        int hits = 0;
        for (MessageCard card : messageCards) {
            boolean hit = card.applyHighlight(activeSearchQuery);
            if (hit) hits++;
        }
        searchCountLabel.setText(activeSearchQuery.isEmpty() ? "" : hits + " match" + (hits == 1 ? "" : "es"));
        chatFeedPanel.revalidate();
        chatFeedPanel.repaint();
    }

    // =========================================================================
    // VAULT SEARCH (FileSearchCallable on the concurrent engine)
    // =========================================================================
    private void scheduleVaultSearch() {
        if (vaultSearchTimer == null) {
            vaultSearchTimer = new Timer(150, e -> runVaultSearch());
            vaultSearchTimer.setRepeats(false);
        }
        vaultSearchTimer.restart();
    }

    private void runVaultSearch() {
        final String query = pendingVaultQuery;
        final List<SharedFileRecord> snapshot = new ArrayList<>(allRoomFiles);
        final String room = currentRoom;
        final long generation = ++vaultSearchGeneration;
        searchEngine.submit(() -> {
            try {
                final List<SharedFileRecord> matches = new com.studyhub.concurrent.FileSearchCallable(snapshot, query, room).call();
                SwingUtilities.invokeLater(() -> {
                    if (intentionalDisconnect || generation != vaultSearchGeneration || !room.equals(currentRoom)) return;
                    vaultModel.clear();
                    for (SharedFileRecord record : matches) {
                        vaultModel.addElement(record);
                    }
                });
            } catch (Exception ignored) {}
            return null;
        });
    }

    private void refreshVaultModel() {
        pendingVaultQuery = "";
        runVaultSearch();
    }

    // =========================================================================
    // UDP PRESENCE & HEARTBEAT PULSES (Port 9877)
    // =========================================================================
    private void handleComposerTyping() {
        emitPresencePulse(ProtocolConstants.STATUS_TYPING);
        if (typingDebounceTimer != null && typingDebounceTimer.isRunning()) {
            typingDebounceTimer.restart();
        } else {
            typingDebounceTimer = new Timer(2500, e -> emitPresencePulse(currentStatus));
            typingDebounceTimer.setRepeats(false);
            typingDebounceTimer.start();
        }
    }

    private void emitPresencePulse(String status) {
        long seq = pulseSeq.getAndIncrement();
        if (intentionalDisconnect || !connected) return;
        String room = currentRoom;
        try { pulses.execute(() -> PresencePulseService.sendClientPulse(session.host, session.pulsePort, seq, session.username, room, status)); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { return; }

        inspectorPanel.logEvent(new PacketLogEvent(
            PacketLogEvent.Module.UDP_BEACON,
            PacketLogEvent.Direction.TX,
            "Sent UDP Pulse #" + seq,
            "User: " + session.username + " | Status: " + status + " | Room: " + currentRoom
        ));
    }

    private void startPresenceHeartbeat() {
        idlePulseTimer = new Timer(NetworkConfig.UDP_PULSE_INTERVAL_MS, e -> emitPresencePulse(currentStatus));
        idlePulseTimer.start();
    }

    private void updateMemberStatus(String user, String status) {
        if (ProtocolConstants.STATUS_TYPING.equalsIgnoreCase(status) && !user.equalsIgnoreCase(session.username)) {
            typingIndicatorLabel.setText(user + " is typing…");
            Timer resetTyping = new Timer(3000, e -> typingIndicatorLabel.setText(" "));
            resetTyping.setRepeats(false);
            resetTyping.start();
        }

        for (int i = 0; i < membersModel.size(); i++) {
            MemberItem m = membersModel.get(i);
            if (m.username.equalsIgnoreCase(user)) {
                membersModel.set(i, new MemberItem(user, status, m.role, m.displayName));
                return;
            }
        }
        membersModel.addElement(new MemberItem(user, status, ProtocolConstants.ROLE_STUDENT));
    }

    // =========================================================================
    // DRAG & DROP FILE SHARING (Port 9001)
    // =========================================================================
    private void installDropTarget(Component component) {
        new DropTarget(component, new DropTargetAdapter() {
            @Override
            public void dragEnter(java.awt.dnd.DropTargetDragEvent dtde) {
                component.setBackground(ThemeColors.BG_HOVER);
                chatFeedPanel.setBackground(ThemeColors.BG_HOVER);
                chatFeedPanel.repaint();
            }

            @Override
            public void dragExit(java.awt.dnd.DropTargetEvent dte) {
                component.setBackground(ThemeColors.BG_CHAT);
                chatFeedPanel.setBackground(ThemeColors.BG_CHAT);
                chatFeedPanel.repaint();
            }

            @Override
            public void drop(DropTargetDropEvent event) {
                component.setBackground(ThemeColors.BG_CHAT);
                chatFeedPanel.setBackground(ThemeColors.BG_CHAT);
                try {
                    Transferable transferable = event.getTransferable();
                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        event.acceptDrop(DnDConstants.ACTION_COPY);
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
                        event.dropComplete(true);
                        if (files != null && !files.isEmpty()) {
                            uploadFiles(files);
                        }
                    } else {
                        event.rejectDrop();
                    }
                } catch (Exception e) {
                    event.dropComplete(false);
                }
            }
        });
    }

    // =========================================================================
    // TCP FILE VAULT, UPLOADS & DOWNLOADS MANAGER (Port 9001)
    // =========================================================================
    private void chooseAndUploadFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File[] selected = chooser.getSelectedFiles();
            List<File> files = new ArrayList<>();
            for (File f : selected) {
                if (f.isFile()) files.add(f);
            }
            if (!files.isEmpty()) {
                uploadFiles(files);
            }
        }
    }

    /** Uploads one or more files with a live progress card in the chat feed. */
    private void uploadFiles(List<File> files) {
        final String uploadRoom = currentRoom;
        final boolean tamper = simulateTamperEnabled;
        for (File file : files) {
            // The card is built on the EDT (it may arrive from the drag & drop thread).
            final TransferCard[] holder = new TransferCard[1];
            runOnEdt(() -> {
                holder[0] = new TransferCard(file.getName(), file.length(), true);
                appendCard(holder[0]);
            });
            final TransferCard card = holder[0];

            new Thread(() -> {
                FileTransferClient client = new FileTransferClient(session.host, session.filePort,
                    session.sessionToken, inspectorPanel::logEvent);
                FileTransferClient.TransferResult result = client.uploadFile(uploadRoom, file, tamper,
                    (transferred, total) -> {
                        if (card != null) {
                            SwingUtilities.invokeLater(() -> card.setProgress(transferred, total));
                        }
                    });

                SwingUtilities.invokeLater(() -> {
                    if (card == null) return;
                    if (result.success) {
                        card.complete("Uploaded · " + result.storedFilename,
                            ThemeColors.STATUS_ONLINE);
                        ToastNotification.show(MainHubFrame.this, "File Upload Verified",
                            file.getName() + " saved to the server vault.", ToastNotification.Type.SUCCESS);
                    } else if (result.tampered) {
                        card.complete("Integrity check failed — file rejected", ThemeColors.STATUS_DANGER);
                        ToastNotification.show(MainHubFrame.this, "TAMPER DETECTED",
                            "Server rejected upload! Computed: " + result.actualSha, ToastNotification.Type.ERROR);
                    } else {
                        card.complete("Upload failed: " + result.message, ThemeColors.STATUS_DANGER);
                        ToastNotification.show(MainHubFrame.this, "Upload Failed", result.message, ToastNotification.Type.ERROR);
                    }
                });
            }, "File-Uploader").start();
        }
    }

    private void downloadFile(String room, String filename, TransferCard card) {
        File dir = FileTransferClient.getUserDownloadDir(session.username);
        File destination = FileNameUtils.uniqueFile(dir, filename);

        new Thread(() -> {
            FileTransferClient client = new FileTransferClient(session.host, session.filePort,
                session.sessionToken, inspectorPanel::logEvent);
            final FileTransferClient.TransferResult result = client.downloadToFile(room, filename, destination,
                (transferred, total) -> {
                    if (card != null) {
                        SwingUtilities.invokeLater(() -> card.setProgress(transferred, total));
                    }
                });

            SwingUtilities.invokeLater(() -> {
                if (result.success) {
                    if (card != null) {
                        card.complete("Saved to downloads/" + session.username + "/"
                            + result.savedFile.getName(), ThemeColors.STATUS_ONLINE);
                        card.enableOpenActions(result.savedFile);
                    }
                    ToastNotification.show(MainHubFrame.this, "Download Complete",
                        "Saved to " + result.savedFile.getAbsolutePath(), ToastNotification.Type.SUCCESS);
                } else {
                    if (card != null) {
                        card.complete("Download failed: " + result.message, ThemeColors.STATUS_DANGER);
                    }
                    ToastNotification.show(MainHubFrame.this, "Download Failed", result.message, ToastNotification.Type.ERROR);
                }
            });
        }, "File-Downloader").start();
    }

    private void downloadAndOpen(String room, String filename) {
        File dir = FileTransferClient.getUserDownloadDir(session.username);
        File destination = FileNameUtils.uniqueFile(dir, filename);
        new Thread(() -> {
            FileTransferClient client = new FileTransferClient(session.host, session.filePort,
                session.sessionToken, inspectorPanel::logEvent);
            final FileTransferClient.TransferResult result = client.downloadToFile(room, filename, destination, null);
            SwingUtilities.invokeLater(() -> {
                if (result.success) {
                    openFile(result.savedFile);
                } else {
                    ToastNotification.show(MainHubFrame.this, "Download Failed", result.message, ToastNotification.Type.ERROR);
                }
            });
        }, "File-Downloader").start();
    }

    private void openFile(File file) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file);
            } else {
                ToastNotification.show(this, "Open File", file.getAbsolutePath(), ToastNotification.Type.INFO);
            }
        } catch (Exception e) {
            ToastNotification.show(this, "Open Failed", e.getMessage(), ToastNotification.Type.ERROR);
        }
    }

    private void openDownloadsFolder() {
        try {
            File dir = FileTransferClient.getUserDownloadDir(session.username);
            if (!dir.exists()) dir.mkdirs();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            } else {
                ToastNotification.show(this, "Downloads Folder", dir.getAbsolutePath(), ToastNotification.Type.INFO);
            }
        } catch (Exception e) {
            ToastNotification.show(this, "Open Failed", e.getMessage(), ToastNotification.Type.ERROR);
        }
    }

    // =========================================================================
    // RICH MESSAGE CARDS & JAVA2D RENDERING
    // =========================================================================

    /**
     * A single chat bubble: avatar + author row + text, with hover actions for
     * Reply / Pin to Room and Ctrl+F hit highlighting.
     */
    private class MessageCard extends JPanel {

        private static final long serialVersionUID = 1L;
        private final String sender;
        private final String text;
        private final String time;
        private final boolean aes;
        private final ReplyReference reply;
        private final JLabel textLabel;
        private final JPanel actionBox;

        MessageCard(String sender, String text, String time, boolean aes, ReplyReference reply, boolean consecutive) {
            this.sender = sender;
            this.text = text;
            this.time = time;
            this.aes = aes;
            this.reply = reply;

            setLayout(new BorderLayout(12, 4));
            setBackground(ThemeColors.BG_CHAT);
            setBorder(BorderFactory.createEmptyBorder(consecutive ? 2 : 8, 8, 2, 8));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 420));

            JPanel contentBox = new JPanel();
            contentBox.setLayout(new BoxLayout(contentBox, BoxLayout.Y_AXIS));
            contentBox.setOpaque(false);

            if (!consecutive) {
                AvatarComponent avatar = new AvatarComponent(sender, 38, null, false);
                add(avatar, BorderLayout.WEST);

                JPanel metaRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
                metaRow.setOpaque(false);

                JLabel nameLbl = new JLabel(memberNames.getOrDefault(sender, sender));
                nameLbl.setFont(ThemeColors.FONT_BOLD);
                nameLbl.setForeground(sender.equalsIgnoreCase(session.username) ? ThemeColors.ACCENT_PRIMARY : ThemeColors.TEXT_PRIMARY);

                String role = sender.equalsIgnoreCase("admin") ? "MOD" : "STUDENT";
                JLabel roleTag = new JLabel(" " + role + " ");
                roleTag.setFont(new Font("Dialog", Font.BOLD, 9));
                roleTag.setForeground(ThemeColors.TEXT_DARK);
                roleTag.setOpaque(true);
                roleTag.setBackground(role.equals("MOD") ? ThemeColors.BADGE_MOD : ThemeColors.BADGE_STUDENT);

                JLabel timeLbl = new JLabel(time);
                timeLbl.setFont(ThemeColors.FONT_MUTED);
                timeLbl.setForeground(ThemeColors.TEXT_MUTED);

                metaRow.add(nameLbl);
                if (role.equals("MOD")) metaRow.add(roleTag);
                metaRow.add(timeLbl);

                if (aes) {
                    JLabel aesPill = new JLabel(new VectorIcon(VectorIcon.Kind.LOCK, 12, ThemeColors.STATUS_ONLINE));
                    aesPill.setToolTipText("Encrypted workspace message");
                    aesPill.setFont(new Font("Dialog", Font.BOLD, 9));
                    aesPill.setForeground(ThemeColors.STATUS_ONLINE);
                    aesPill.setOpaque(true);
                    aesPill.setBackground(ThemeColors.BG_INPUT);
                    metaRow.add(aesPill);
                }

                // Hover actions (Reply / Pin)
                actionBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                actionBox.setOpaque(false);
                actionBox.setVisible(false);

                JButton replyBtn = new JButton("Reply");
                styleHoverAction(replyBtn);
                replyBtn.addActionListener(e -> setPendingReply(sender, text, time));

                JButton pinBtn = new JButton("Pin to Room");
                styleHoverAction(pinBtn);
                pinBtn.addActionListener(e -> pinMessage(sender, text, time));

                actionBox.add(replyBtn);
                actionBox.add(pinBtn);
                metaRow.add(actionBox);

                contentBox.add(metaRow);

                if (reply != null) {
                    contentBox.add(Box.createVerticalStrut(2));
                    JLabel quoteLbl = new JLabel("<html><div style='border-left:3px solid #5865F2; padding-left:8px; color:#949BA4;'>"
                        + "<b style='color:#5865F2;'>" + escapeHtml(reply.sender) + "</b> "
                        + escapeHtml(reply.snippet) + "</div></html>");
                    quoteLbl.setFont(ThemeColors.FONT_MUTED);
                    quoteLbl.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
                    contentBox.add(quoteLbl);
                }
            } else {
                actionBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                actionBox.setOpaque(false);
                actionBox.setVisible(false);

                JPanel indentBox = new JPanel(new BorderLayout());
                indentBox.setOpaque(false);
                indentBox.setBorder(BorderFactory.createEmptyBorder(0, 50, 0, 0));
                textLabel = new JLabel();
                indentBox.add(textLabel, BorderLayout.CENTER);
                add(indentBox, BorderLayout.CENTER);
                installInteractions();
                applyTooltip();
                renderText("");
                return;
            }

            textLabel = new JLabel();
            contentBox.add(Box.createVerticalStrut(3));
            contentBox.add(textLabel);
            add(contentBox, BorderLayout.CENTER);

            installInteractions();
            applyTooltip();
            renderText("");
        }

        private void applyTooltip() {
            setToolTipText(sender + " \u2022 " + time
                + (aes ? " \u2022 AES-128 encrypted" : " \u2022 plaintext")
                + (reply != null ? " \u2022 reply to " + reply.sender : ""));
        }

        private void styleHoverAction(JButton button) {
            button.setFont(ThemeColors.FONT_MUTED);
            button.setForeground(ThemeColors.TEXT_MUTED);
            button.setContentAreaFilled(false);
            button.setBorderPainted(false);
            button.setFocusPainted(false);
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        private void installInteractions() {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    actionBox.setVisible(true);
                    setBackground(ThemeColors.BG_CARD);
                    revalidate();
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    actionBox.setVisible(false);
                    setBackground(ThemeColors.BG_CHAT);
                    revalidate();
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    maybeShowPopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybeShowPopup(e);
                }
            });
        }

        private void maybeShowPopup(MouseEvent e) {
            if (!e.isPopupTrigger()) return;
            JPopupMenu menu = new JPopupMenu();
            JMenuItem replyItem = new JMenuItem("Reply to " + sender);
            replyItem.addActionListener(ev -> setPendingReply(sender, text, time));
            JMenuItem pinItem = new JMenuItem("Pin to Room");
            pinItem.addActionListener(ev -> pinMessage(sender, text, time));
            menu.add(replyItem);
            menu.add(pinItem);
            menu.show(e.getComponent(), e.getX(), e.getY());
        }

        private void renderText(String query) {
            textLabel.setText("<html><body style='width: 560px; color: #f2f3f5; font-family: Dialog; font-size: 13px;'>"
                + highlightHtml(text, query) + "</body></html>");
        }

        /** Applies the Ctrl+F query; returns true when this card contains a hit. */
        boolean applyHighlight(String query) {
            boolean hit = !query.isEmpty() && text != null && text.toLowerCase().contains(query.toLowerCase());
            renderText(query);
            return hit;
        }
    }

    private void renderChatMessageCard(String sender, String message, String time, boolean isAes,
                                       String replyWire, boolean fromHistory) {
        boolean isConsecutive = !fromHistory && sender.equalsIgnoreCase(lastSender);
        lastSender = sender;

        ReplyReference reply = ReplyReference.fromWire(replyWire);
        MessageCard card = new MessageCard(sender, message, time, isAes, reply, isConsecutive);
        card.applyHighlight(activeSearchQuery);
        messageCards.add(card);
        appendCard(card);
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
        badge.setFont(new Font("Dialog", Font.BOLD, 9));
        badge.setForeground(ThemeColors.ACCENT_PRIMARY);
        badge.setOpaque(true);
        badge.setBackground(ThemeColors.BG_INPUT);

        JLabel statusPill = new JLabel(" " + status + " ");
        statusPill.setFont(new Font("Dialog", Font.BOLD, 9));
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

        appendCard(card);
    }

    private void renderFileVaultCard(String room, String filename, long size, String uploader, String sha256) {
        TransferCard card = new TransferCard(filename, size, false);
        card.showFileDetails(uploader, sha256);
        card.enableDownload(() -> downloadFile(room, filename, card));

        // Offer "Open File" straight away when the file is already in this user's downloads folder.
        File local = new File(FileTransferClient.getUserDownloadDir(session.username), filename);
        if (local.exists()) {
            card.enableOpenActions(local);
        }
        appendCard(card);
    }

    private void renderSystemNotice(String text) {
        JPanel notice = new JPanel(new FlowLayout(FlowLayout.CENTER));
        notice.setOpaque(false);
        notice.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        JLabel l = new JLabel("— " + text + " —");
        l.setFont(ThemeColors.FONT_MUTED);
        l.setForeground(ThemeColors.TEXT_MUTED);
        notice.add(l);
        appendCard(notice);
    }

    /** Runs {@code action} on the Swing event dispatch thread, waiting when called off-EDT. */
    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception e) {
            SwingUtilities.invokeLater(action);
        }
    }

    /** Adds a card just above the bottom glue so cards keep their natural height. */
    private void appendCard(JComponent card) {
        if (card instanceof MessageCard || card instanceof TransferCard) chatFeedPanel.remove(emptyRoom);
        chatFeedPanel.remove(chatFiller);
        chatFeedPanel.add(card);
        if (chatFeedPanel.getComponentCount() == 0) {
            emptyRoom.setForeground(ThemeColors.TEXT_MUTED);
            emptyRoom.setBorder(BorderFactory.createEmptyBorder(28, 20, 28, 20));
            chatFeedPanel.add(emptyRoom);
        }
        chatFeedPanel.add(chatFiller);
        chatFeedPanel.revalidate();
        scrollToBottom();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = chatScrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private String highlightHtml(String text, String query) {
        String escaped = escapeHtml(text).replace("\n", "<br/>");
        if (query == null || query.isEmpty()) return escaped;
        return escaped.replaceAll("(?i)" + Pattern.quote(escapeHtml(query)),
            "<span style='background-color:#F0B232;color:#111214;'>$0</span>");
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // =========================================================================
    // FILE / TRANSFER CARD WITH LIVE PROGRESS
    // =========================================================================
    private class TransferCard extends RoundedPanel {

        private static final long serialVersionUID = 1L;
        private final JLabel statusLabel = new JLabel(" ");
        private final JProgressBar progressBar = new JProgressBar(0, 100);
        private final JButton actionBtn = new JButton("Download");
        private final JButton openBtn = new JButton("Open File");
        private File localFile;

        TransferCard(String filename, long size, boolean upload) {
            super(10, ThemeColors.BG_CARD, ThemeColors.STATUS_ONLINE, 1);
            setLayout(new BorderLayout(12, 0));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 50, 4, 16),
                BorderFactory.createEmptyBorder(8, 14, 8, 14)
            ));

            JLabel iconLbl = new JLabel(new VectorIcon(VectorIcon.Kind.FILE, 28, ThemeColors.TEXT_MUTED));
            iconLbl.setFont(new Font("Dialog", Font.PLAIN, 28));

            JPanel infoPanel = new JPanel();
            infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
            infoPanel.setOpaque(false);

            String sizeStr = (size < 1024) ? size + " B" : String.format("%.1f KB", size / 1024.0);
            JLabel nameLbl = new JLabel(filename + " (" + sizeStr + ")");
            nameLbl.setFont(ThemeColors.FONT_HEADER);
            nameLbl.setForeground(ThemeColors.TEXT_PRIMARY);

            statusLabel.setFont(ThemeColors.FONT_MUTED);
            statusLabel.setForeground(ThemeColors.TEXT_MUTED);
            statusLabel.setText(upload ? "Uploading…" : "Shared in this room");

            progressBar.setStringPainted(true);
            progressBar.setFont(ThemeColors.FONT_MUTED);
            progressBar.setForeground(ThemeColors.STATUS_ONLINE);
            progressBar.setBackground(ThemeColors.BG_INPUT);
            progressBar.setPreferredSize(new Dimension(150, 14));
            progressBar.setValue(0);
            progressBar.setVisible(upload);

            infoPanel.add(nameLbl);
            infoPanel.add(Box.createVerticalStrut(2));
            infoPanel.add(statusLabel);
            infoPanel.add(Box.createVerticalStrut(3));
            infoPanel.add(progressBar);

            JPanel buttonBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            buttonBox.setOpaque(false);
            actionBtn.setFont(ThemeColors.FONT_BOLD);
            actionBtn.setForeground(ThemeColors.TEXT_PRIMARY);
            actionBtn.setBackground(ThemeColors.STATUS_ONLINE);
            actionBtn.setBorderPainted(false);
            actionBtn.setFocusPainted(false);
            actionBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            actionBtn.setPreferredSize(new Dimension(100, 30));
            actionBtn.setVisible(!upload);

            openBtn.setFont(ThemeColors.FONT_BOLD);
            openBtn.setForeground(ThemeColors.TEXT_MUTED);
            openBtn.setBackground(ThemeColors.BG_INPUT);
            openBtn.setBorderPainted(false);
            openBtn.setFocusPainted(false);
            openBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            openBtn.setPreferredSize(new Dimension(96, 30));
            openBtn.setVisible(false);

            buttonBox.add(openBtn);
            buttonBox.add(actionBtn);

            add(iconLbl, BorderLayout.WEST);
            add(infoPanel, BorderLayout.CENTER);
            add(buttonBox, BorderLayout.EAST);
        }

        void enableDownload(Runnable action) {
            actionBtn.addActionListener(e -> {
                actionBtn.setEnabled(false);
                progressBar.setVisible(true);
                statusLabel.setText("Downloading…");
                action.run();
            });
        }

        void showFileDetails(String uploader, String sha256) {
            String shortSha = sha256.length() > 16 ? sha256.substring(0, 16) + "…" : sha256;
            statusLabel.setText("SHA-256: " + shortSha + " • Uploaded by " + uploader);
        }

        void setProgress(long transferred, long total) {
            int pct = total <= 0 ? 0 : (int) Math.min(100, (transferred * 100) / total);
            progressBar.setValue(pct);
            progressBar.setString(pct + "%  (" + (transferred / 1024) + " KB / " + Math.max(1, total / 1024) + " KB)");
        }

        void complete(String message, Color color) {
            progressBar.setVisible(false);
            statusLabel.setText(message);
            statusLabel.setForeground(color);
            setBorderColor(color);
            revalidate();
            repaint();
        }

        void enableOpenActions(File file) {
            this.localFile = file;
            openBtn.setVisible(true);
            openBtn.addActionListener(e -> {
                if (localFile != null) {
                    openFile(localFile);
                }
            });
            revalidate();
            repaint();
        }
    }

    // =========================================================================
    // SWITCH USER / LOG OUT
    // =========================================================================
    private void switchUser() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Log out of '" + session.username + "' and switch to another account?",
            "Switch User / Log Out", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        shutdownNetworking();
        dispose();
        StudyHubClientMain.launchSession(inspectorPanel);
    }

    // =========================================================================
    // TLS CERTIFICATE DIALOG
    // =========================================================================
    private void showInviteDialog() {
        JDialog dialog = new JDialog(this, "Invite to " + session.workspaceName, true);
        JPanel body = new JPanel(new BorderLayout(12, 16)); body.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        body.add(new JLabel("Ask your friend to join this workspace on the same Wi-Fi."), BorderLayout.NORTH);
        JTextField ip = new JTextField(com.studyhub.common.LanAddresses.preferredAddress()); ip.setEditable(false); ip.setFont(ThemeColors.FONT_TITLE);
        body.add(ip, BorderLayout.CENTER);
        PillButton copy = new PillButton("Copy Address");
        copy.addActionListener(e -> { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(ip.getText()), null); copy.setText("Copied"); });
        body.add(copy, BorderLayout.SOUTH); dialog.setContentPane(body); dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    private void showSettingsDialog() {
        JDialog dialog = new JDialog(this, "Workspace Settings", true);
        JPanel body = new JPanel(); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JLabel title = new JLabel("Your profile"); title.setFont(ThemeColors.FONT_TITLE.deriveFont(20f)); body.add(title);
        body.add(Box.createVerticalStrut(16));
        JLabel name = new JLabel("Display name"); body.add(name);
        JTextField display = new JTextField(session.displayName, 30); name.setLabelFor(display); display.setFont(ThemeColors.FONT_BODY);
        display.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36)); body.add(display);
        body.add(Box.createVerticalStrut(12)); body.add(new JLabel("Study status"));
        JComboBox<String> status = new JComboBox<>(new String[]{"Available", "Focusing / Do Not Disturb", "In Lab"});
        for (int i = 0; i < ProtocolConstants.SELECTABLE_STATUSES.length; i++) if (currentStatus.equals(ProtocolConstants.SELECTABLE_STATUSES[i])) status.setSelectedIndex(i);
        status.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36)); body.add(status);
        JLabel error = new JLabel(" "); error.setForeground(ThemeColors.STATUS_DANGER); body.add(error);
        PillButton save = new PillButton("Save Profile");
        save.addActionListener(e -> {
            String value = display.getText().trim();
            if (value.isEmpty() || value.length() > 48) { error.setText("Use a display name with 1–48 characters."); return; }
            if (!connected) { error.setText("Reconnect to the workspace before saving."); return; }
            sendNioFrame("UPDATE_PROFILE|" + DataPersistenceManager.encodeField(value));
            currentStatus = ProtocolConstants.SELECTABLE_STATUSES[status.getSelectedIndex()]; emitPresencePulse(currentStatus); dialog.dispose();
        }); body.add(save); body.add(Box.createVerticalStrut(20));
        PillButton downloads = new PillButton("Open Downloads", ThemeColors.BG_INPUT, ThemeColors.BG_HOVER, ThemeColors.TEXT_PRIMARY, 10);
        downloads.setIcon(new VectorIcon(VectorIcon.Kind.FOLDER, 16, ThemeColors.TEXT_MUTED)); downloads.addActionListener(e -> openDownloadsFolder()); body.add(downloads);
        body.add(Box.createVerticalStrut(8));
        PillButton security = new PillButton("Connection Security", ThemeColors.BG_INPUT, ThemeColors.BG_HOVER, ThemeColors.TEXT_PRIMARY, 10);
        security.setIcon(new VectorIcon(VectorIcon.Kind.LOCK, 16, ThemeColors.STATUS_ONLINE)); security.addActionListener(e -> showTlsCertDialog()); body.add(security);
        body.add(Box.createVerticalStrut(16));
        javax.swing.JCheckBox diagnostics = new javax.swing.JCheckBox("Developer Diagnostics", inspectorExpanded);
        diagnostics.addActionListener(e -> setDiagnostics(diagnostics.isSelected())); body.add(diagnostics);
        body.add(Box.createVerticalStrut(16));
        PillButton logout = new PillButton("Log Out / Switch Workspace", ThemeColors.BG_INPUT, ThemeColors.BG_HOVER, ThemeColors.STATUS_DANGER, 10);
        logout.addActionListener(e -> { dialog.dispose(); switchUser(); }); body.add(logout);
        for (Component child : body.getComponents()) if (child instanceof JComponent component) component.setAlignmentX(0);
        dialog.setContentPane(body); dialog.getRootPane().setDefaultButton(save); dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    private void showTlsCertDialog() {
        TlsAuthClient.CertificateDetails cert = session.certificateDetails;
        if (cert == null) return;

        JDialog dialog = new JDialog(this, "Connection & Certificate Info", true);
        dialog.setSize(720, 460);
        dialog.setLocationRelativeTo(this);
        dialog.getContentPane().setBackground(ThemeColors.BG_PANEL);
        dialog.setLayout(new BorderLayout());

        JPanel p = new JPanel(new GridLayout(9, 1, 6, 6));
        p.setBackground(ThemeColors.BG_PANEL);
        p.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        p.add(createCertField("Workspace address:", session.host));
        p.add(createCertField("Trust:", "TOFU · saved certificate pin · compare with host on first connection"));
        p.add(createCertField("Message encryption:", "Shared workspace AES key (host-managed, not end-to-end)"));
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

        JTextField v = new JTextField(val); v.setEditable(false); v.setBorder(null);
        v.setBackground(ThemeColors.BG_PANEL); v.setToolTipText(val);
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

        private static final long serialVersionUID = 1L;
        private final AvatarComponent avatar = new AvatarComponent("?", 28);
        private final JLabel nameLbl = new JLabel();
        private final JLabel roleLbl = new JLabel();
        private final JLabel statusLbl = new JLabel();

        public MemberCellRenderer() {
            setLayout(new BorderLayout(8, 0));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

            nameLbl.setFont(ThemeColors.FONT_HEADER);
            roleLbl.setFont(new Font("Dialog", Font.BOLD, 9));
            statusLbl.setFont(ThemeColors.FONT_MUTED);

            JPanel textP = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            textP.setOpaque(false);
            textP.add(nameLbl);
            textP.add(roleLbl);

            JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            south.setOpaque(false);
            south.add(statusLbl);

            JPanel center = new JPanel(new BorderLayout());
            center.setOpaque(false);
            center.add(textP, BorderLayout.NORTH);
            center.add(south, BorderLayout.SOUTH);

            add(avatar, BorderLayout.WEST);
            add(center, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends MemberItem> list, MemberItem item, int index, boolean isSelected, boolean cellHasFocus) {
            setBackground(isSelected ? ThemeColors.BG_HOVER : ThemeColors.BG_PANEL);
            nameLbl.setText(item.displayName);
            nameLbl.setForeground(ThemeColors.TEXT_PRIMARY);

            Color dotColor;
            if (ProtocolConstants.STATUS_TYPING.equalsIgnoreCase(item.status)) {
                dotColor = ThemeColors.STATUS_TYPING;
            } else if (ProtocolConstants.STATUS_FOCUS.equalsIgnoreCase(item.status)
                || ProtocolConstants.STATUS_LAB.equalsIgnoreCase(item.status)) {
                dotColor = ThemeColors.BADGE_MOD;
            } else {
                dotColor = ThemeColors.STATUS_ONLINE;
            }
            avatar.setStatusColor(dotColor);

            boolean customStatus = ProtocolConstants.STATUS_FOCUS.equalsIgnoreCase(item.status)
                || ProtocolConstants.STATUS_LAB.equalsIgnoreCase(item.status)
                || ProtocolConstants.STATUS_AVAILABLE.equalsIgnoreCase(item.status)
                || ProtocolConstants.STATUS_IDLE.equalsIgnoreCase(item.status);
            statusLbl.setText(customStatus ? ProtocolConstants.describeStatus(item.status) : "");
            statusLbl.setForeground(ThemeColors.TEXT_MUTED);

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

        private static final long serialVersionUID = 1L;
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
            nameLbl.setText(record.getFilename());
            nameLbl.setIcon(new VectorIcon(VectorIcon.Kind.FILE, 16, ThemeColors.TEXT_MUTED));
            metaLbl.setText(record.getFormattedSize() + " • " + record.getUploader() + " • " + record.getTimestamp());
            String ext = record.getExtension();
            setToolTipText("Click to download • Double-click to download and open"
                + (ext.isEmpty() ? "" : " • Type: ." + ext));
            return this;
        }
    }
}
