package com.studyhub.ui.theme;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JButton;

/**
 * Modern pill-shaped button with smooth hover/press animations and antialiased rendering.
 */
public class PillButton extends JButton {

    private static final long serialVersionUID = 1L;

    private Color normalBg;
    private Color hoverBg;
    private Color pressedBg;
    private Color textColor;
    private Color borderColor;
    private int cornerRadius;
    private boolean isHovered = false;
    private boolean isPressed = false;

    public PillButton(String text) {
        this(text, ThemeColors.ACCENT_PRIMARY, ThemeColors.ACCENT_HOVER, ThemeColors.TEXT_PRIMARY, 16);
    }

    public PillButton(String text, Color bg, Color hover, Color fg, int radius) {
        super(text);
        this.normalBg = bg;
        this.hoverBg = hover;
        this.pressedBg = hover.darker();
        this.textColor = fg;
        this.cornerRadius = radius;
        this.borderColor = null;

        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setOpaque(false);
        setFont(ThemeColors.FONT_BOLD);
        setForeground(fg);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                isHovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                isHovered = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                isPressed = true;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                isPressed = false;
                repaint();
            }
        });
    }

    public void setColors(Color bg, Color hover, Color fg) {
        this.normalBg = bg;
        this.hoverBg = hover;
        this.pressedBg = hover.darker();
        this.textColor = fg;
        setForeground(fg);
        repaint();
    }

    public void setBorderColor(Color border) {
        this.borderColor = border;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // Determine current background
        Color currentBg;
        if (!isEnabled()) {
            currentBg = ThemeColors.BG_INPUT;
        } else if (isPressed) {
            currentBg = pressedBg;
        } else if (isHovered) {
            currentBg = hoverBg;
        } else {
            currentBg = normalBg;
        }

        g2.setColor(currentBg);
        g2.fillRoundRect(0, 0, w, h, cornerRadius, cornerRadius);

        if (borderColor != null) {
            g2.setColor(borderColor);
            g2.drawRoundRect(0, 0, w - 1, h - 1, cornerRadius, cornerRadius);
        }

        // Draw text centered
        g2.setFont(getFont());
        g2.setColor(isEnabled() ? textColor : ThemeColors.TEXT_MUTED);
        FontMetrics fm = g2.getFontMetrics();
        int stringWidth = fm.stringWidth(getText());
        int stringHeight = fm.getAscent();
        int x = (w - stringWidth) / 2;
        int y = (h + stringHeight) / 2 - 2;
        g2.drawString(getText(), x, y);

        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width + 16, Math.max(30, d.height));
    }
}
