package com.studyhub.ui.theme;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/**
 * Modern circular user avatar component with deterministic color generation,
 * centered initials, and an optional presence badge (Online/Typing).
 */
public class AvatarComponent extends JComponent {

    private static final Color[] PALETTE = {
        new Color(0x58, 0x65, 0xF2), // Blurple
        new Color(0x57, 0xF2, 0x87), // Green
        new Color(0xFE, 0xE7, 0x5C), // Yellow
        new Color(0xEB, 0x45, 0x9E), // Fuchsia
        new Color(0xED, 0x42, 0x45), // Red
        new Color(0x9B, 0x59, 0xB6), // Purple
        new Color(0x1A, 0xBC, 0x9C), // Turquoise
        new Color(0xE6, 0x7E, 0x22)  // Orange
    };

    private final String username;
    private final int size;
    private Color statusColor;
    private boolean showStatus;

    public AvatarComponent(String username, int size) {
        this(username, size, ThemeColors.STATUS_ONLINE, true);
    }

    public AvatarComponent(String username, int size, Color statusColor, boolean showStatus) {
        this.username = username != null ? username : "?";
        this.size = size;
        this.statusColor = statusColor;
        this.showStatus = showStatus;
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(new Dimension(size, size));
        setMaximumSize(new Dimension(size, size));
        setOpaque(false);
    }

    public void setStatusColor(Color c) {
        this.statusColor = c;
        repaint();
    }

    public void setShowStatus(boolean show) {
        this.showStatus = show;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Deterministic background color
        int hash = Math.abs(username.toLowerCase().hashCode());
        Color avatarBg = PALETTE[hash % PALETTE.length];

        // Draw circular avatar
        g2.setColor(avatarBg);
        g2.fillOval(0, 0, size, size);

        // Draw initials
        String initials = extractInitials(username);
        g2.setColor(Color.WHITE);
        g2.setFont(ThemeColors.FONT_AVATAR.deriveFont((float) (size * 0.42)));
        FontMetrics fm = g2.getFontMetrics();
        int tx = (size - fm.stringWidth(initials)) / 2;
        int ty = (size + fm.getAscent()) / 2 - 2;
        g2.drawString(initials, tx, ty);

        // Draw status dot indicator (bottom right)
        if (showStatus && statusColor != null) {
            int dotSize = (int) (size * 0.32);
            int dotX = size - dotSize - 1;
            int dotY = size - dotSize - 1;

            // Dark outline for contrast
            g2.setColor(ThemeColors.BG_RAIL);
            g2.fillOval(dotX - 2, dotY - 2, dotSize + 4, dotSize + 4);

            g2.setColor(statusColor);
            g2.fillOval(dotX, dotY, dotSize, dotSize);
        }

        g2.dispose();
    }

    private String extractInitials(String name) {
        if (name == null || name.isEmpty()) return "?";
        String clean = name.trim().replaceAll("[^a-zA-Z0-9]", "");
        if (clean.length() <= 2) return clean.toUpperCase();
        return clean.substring(0, 2).toUpperCase();
    }
}
