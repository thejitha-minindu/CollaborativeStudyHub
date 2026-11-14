package com.studyhub.ui.theme;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Non-blocking in-app toast notification overlay for file transfers,
 * integrity verification, and security events.
 */
public class ToastNotification extends JPanel {

    private static final long serialVersionUID = 1L;

    public enum Type {
        SUCCESS(ThemeColors.STATUS_ONLINE, "✓"),
        WARNING(ThemeColors.STATUS_TYPING, "⚠"),
        ERROR(ThemeColors.STATUS_DANGER, "✖"),
        INFO(ThemeColors.ACCENT_PRIMARY, "ℹ");

        final Color color;
        final String icon;

        Type(Color color, String icon) {
            this.color = color;
            this.icon = icon;
        }
    }

    private final Type type;

    private ToastNotification(String title, String message, Type type) {
        this.type = type;
        setLayout(new BorderLayout(10, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 16));

        JLabel iconLabel = new JLabel(type.icon);
        iconLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        iconLabel.setForeground(type.color);

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new javax.swing.BoxLayout(textPanel, javax.swing.BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(ThemeColors.FONT_BOLD);
        titleLbl.setForeground(ThemeColors.TEXT_PRIMARY);

        JLabel msgLbl = new JLabel("<html><body style='width: 240px;'>" + message + "</body></html>");
        msgLbl.setFont(ThemeColors.FONT_MUTED);
        msgLbl.setForeground(ThemeColors.TEXT_MUTED);

        textPanel.add(titleLbl);
        textPanel.add(msgLbl);

        add(iconLabel, BorderLayout.WEST);
        add(textPanel, BorderLayout.CENTER);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // Dark card background
        g2.setColor(ThemeColors.BG_CARD);
        g2.fillRoundRect(0, 0, w, h, 12, 12);

        // Left accent indicator
        g2.setColor(type.color);
        g2.fillRoundRect(0, 0, 5, h, 6, 6);

        // Subtle border
        g2.setColor(ThemeColors.BORDER_SUBTLE);
        g2.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);

        g2.dispose();
    }

    /**
     * Shows a toast notification overlay inside the given frame.
     */
    public static void show(JFrame frame, String title, String message, Type type) {
        if (frame == null) return;
        SwingUtilities.invokeLater(() -> {
            JLayeredPane layeredPane = frame.getLayeredPane();
            ToastNotification toast = new ToastNotification(title, message, type);
            Dimension size = toast.getPreferredSize();
            int margin = 20;
            int x = frame.getWidth() - size.width - margin - 15;
            int y = margin + 30;

            toast.setBounds(x, y, size.width, size.height);
            layeredPane.add(toast, JLayeredPane.POPUP_LAYER);
            layeredPane.repaint();

            // Auto dismiss after 3500ms
            Timer timer = new Timer(3500, e -> {
                layeredPane.remove(toast);
                layeredPane.repaint();
            });
            timer.setRepeats(false);
            timer.start();
        });
    }
}
