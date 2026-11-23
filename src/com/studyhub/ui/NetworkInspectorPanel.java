package com.studyhub.ui;

import com.studyhub.common.PacketLogEvent;
import com.studyhub.ui.theme.ModernScrollBarUI;
import com.studyhub.ui.theme.ThemeColors;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicTabbedPaneUI;

/**
 * [NETWORK ENGINEERING INSPECTOR DRAWER]
 * Collapsible real-time network protocol diagnostics & telemetry inspector.
 * Designed for 2-minute live university evaluations across all 5 group member domains.
 */
public class NetworkInspectorPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JTextArea nioLogArea = createTerminalLogArea();
    private final JTextArea fileLogArea = createTerminalLogArea();
    private final JTextArea tlsLogArea = createTerminalLogArea();
    private final JTextArea udpLogArea = createTerminalLogArea();
    private final JTextArea httpLogArea = createTerminalLogArea();

    // Live Metrics Badges
    private final JLabel nioMetricsBadge = createMetricBadge("NIO: Single-Thread EventLoop [Active]");
    private final JLabel fileMetricsBadge = createMetricBadge("FileChannel: Standby (16KB Chunks)");
    private final JLabel tlsMetricsBadge = createMetricBadge("TLS: Not Connected");
    private final JLabel udpMetricsBadge = createMetricBadge("UDP: Discovery Port 9876 Active");
    private final JLabel httpMetricsBadge = createMetricBadge("Pool: 5 Threads Ready");

    private final javax.swing.JCheckBox tamper = new javax.swing.JCheckBox("Simulate MITM File Tampering");
    public void onTamperChanged(java.util.function.Consumer<Boolean> callback) {
        for (java.awt.event.ActionListener l : tamper.getActionListeners()) tamper.removeActionListener(l);
        tamper.setSelected(false);
        tamper.addActionListener(e -> callback.accept(tamper.isSelected()));
    }
    public void resetTamper() { tamper.setSelected(false); }

    public NetworkInspectorPanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(850, 240));
        setBackground(ThemeColors.BG_RAIL);
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeColors.BORDER_SUBTLE));

        // Top Drawer Title Bar
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBackground(ThemeColors.BG_RAIL);
        titleBar.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));

        JLabel titleLbl = new JLabel("Developer Diagnostics");
        titleLbl.setFont(ThemeColors.FONT_BOLD);
        titleLbl.setForeground(ThemeColors.ACCENT_PRIMARY);

        JLabel hintLbl = new JLabel("Ctrl+Shift+I to close · 5 networking modules");
        hintLbl.setFont(ThemeColors.FONT_MUTED);
        hintLbl.setForeground(ThemeColors.TEXT_MUTED);

        titleBar.add(titleLbl, BorderLayout.WEST);
        titleBar.add(hintLbl, BorderLayout.EAST);

        // Modern Tabbed Pane
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setBackground(ThemeColors.BG_RAIL);
        tabbedPane.setForeground(ThemeColors.TEXT_MUTED);
        tabbedPane.setFont(ThemeColors.FONT_BOLD);
        tabbedPane.setUI(new CustomTabbedPaneUI());

        tabbedPane.addTab("1. NIO Selector (9000)", createTab(nioMetricsBadge, nioLogArea));
        tabbedPane.addTab("2. TCP FileChannel (9001)", createTab(fileMetricsBadge, fileLogArea));
        JPanel cryptoTab = createTab(tlsMetricsBadge, tlsLogArea);
        tamper.setOpaque(false); tamper.setForeground(ThemeColors.TEXT_MUTED); tamper.setFont(ThemeColors.FONT_BODY);
        tamper.setToolTipText("Evaluation only: the next upload flips a byte so SHA-256 verification rejects it.");
        cryptoTab.add(tamper, BorderLayout.SOUTH);
        tabbedPane.addTab("3. TLS & Crypto (9443)", cryptoTab);
        tabbedPane.addTab("4. UDP Beacons & Pulse (9876/9877)", createTab(udpMetricsBadge, udpLogArea));
        tabbedPane.addTab("5. ThreadPool & HTTP", createTab(httpMetricsBadge, httpLogArea));

        add(titleBar, BorderLayout.NORTH);
        add(tabbedPane, BorderLayout.CENTER);
    }

    private JPanel createTab(JLabel metricBadge, JTextArea logArea) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(ThemeColors.BG_PANEL);
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        badgeRow.setOpaque(false);
        badgeRow.add(metricBadge);

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(ThemeColors.BORDER_SUBTLE, 1));
        scroll.getVerticalScrollBar().setUI(new ModernScrollBarUI(6));
        scroll.getViewport().setBackground(ThemeColors.BG_CARD);

        panel.add(badgeRow, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JTextArea createTerminalLogArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setBackground(ThemeColors.BG_CARD);
        area.setForeground(new Color(0xD8, 0xDE, 0xE9));
        area.setCaretColor(ThemeColors.TEXT_PRIMARY);
        area.setFont(ThemeColors.FONT_MONO);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setMargin(new Insets(6, 8, 6, 8));
        return area;
    }

    private JLabel createMetricBadge(String text) {
        JLabel label = new JLabel("  " + text + "  ");
        label.setFont(ThemeColors.FONT_BOLD);
        label.setForeground(ThemeColors.TEXT_PRIMARY);
        label.setOpaque(true);
        label.setBackground(ThemeColors.BG_INPUT);
        label.setBorder(BorderFactory.createLineBorder(ThemeColors.BORDER_SUBTLE, 1));
        return label;
    }

    public void logEvent(PacketLogEvent event) {
        SwingUtilities.invokeLater(() -> {
            String entry = String.format("[%s] [%s] %s: %s %s\n",
                event.getTimestamp(),
                event.getDirection(),
                event.getSummary(),
                event.getDetails(),
                event.getBufferHexState().isEmpty() ? "" : "(Hex: " + event.getBufferHexState() + ")"
            );

            for (JTextArea area : new JTextArea[]{nioLogArea, fileLogArea, tlsLogArea, udpLogArea, httpLogArea}) {
                if (area.getDocument().getLength() > 120000) area.replaceRange("", 0, 30000);
            }
            switch (event.getModule()) {
                case NIO_SELECTOR:
                    nioLogArea.append(entry);
                    nioLogArea.setCaretPosition(nioLogArea.getDocument().getLength());
                    if (!event.getBufferHexState().isEmpty()) {
                        nioMetricsBadge.setText("  NIO: " + event.getBufferHexState() + "  ");
                    }
                    break;
                case TCP_FILECHANNEL:
                    fileLogArea.append(entry);
                    fileLogArea.setCaretPosition(fileLogArea.getDocument().getLength());
                    fileMetricsBadge.setText("  " + event.getSummary() + "  ");
                    break;
                case TLS_SECURITY:
                    tlsLogArea.append(entry);
                    tlsLogArea.setCaretPosition(tlsLogArea.getDocument().getLength());
                    tlsMetricsBadge.setText("  " + event.getSummary() + "  ");
                    break;
                case UDP_BEACON:
                    udpLogArea.append(entry);
                    udpLogArea.setCaretPosition(udpLogArea.getDocument().getLength());
                    udpMetricsBadge.setText("  " + event.getSummary() + "  ");
                    break;
                case CONCURRENT_HTTP:
                    httpLogArea.append(entry);
                    httpLogArea.setCaretPosition(httpLogArea.getDocument().getLength());
                    httpMetricsBadge.setText("  " + event.getSummary() + "  ");
                    break;
            }
        });
    }

    public void updateTlsDetails(String cipherSuite, String protocol, String subject, String fingerprint) {
        SwingUtilities.invokeLater(() -> {
            tlsMetricsBadge.setText("  TLS: " + protocol + " | " + cipherSuite + "  ");
            tlsLogArea.append(String.format("[TLS CERTIFICATE] Subject: %s\n[TLS CIPHER] %s (%s)\n[SHA-256 FINGERPRINT] %s\n",
                subject, cipherSuite, protocol, fingerprint));
            tlsLogArea.setCaretPosition(tlsLogArea.getDocument().getLength());
        });
    }

    private static class CustomTabbedPaneUI extends BasicTabbedPaneUI {
        @Override
        protected void installDefaults() {
            super.installDefaults();
            tabInsets = new Insets(6, 12, 6, 12);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
        }

        @Override
        protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (isSelected) {
                g2.setColor(ThemeColors.BG_PANEL);
                g2.fillRect(x, y, w, h);
                g2.setColor(ThemeColors.ACCENT_PRIMARY);
                g2.fillRect(x, y + h - 2, w, 2);
            } else {
                g2.setColor(ThemeColors.BG_RAIL);
                g2.fillRect(x, y, w, h);
            }
            g2.dispose();
        }

        @Override
        protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
            // Minimalist borderless tabs
        }

        @Override
        protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
            // Clean border
            g.setColor(ThemeColors.BORDER_SUBTLE);
            g.drawRect(0, 0, tabPane.getWidth() - 1, tabPane.getHeight() - 1);
        }
    }
}
