package com.studyhub.ui.theme;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * Modern, sleek, thin scrollbar UI resembling Discord/Slack.
 * Replaces bulky default OS arrows with a minimalist rounded thumb.
 */
public class ModernScrollBarUI extends BasicScrollBarUI {

    private final int width;

    public ModernScrollBarUI() {
        this(8);
    }

    public ModernScrollBarUI(int width) {
        this.width = width;
    }

    @Override
    protected Dimension getMinimumThumbSize() {
        return new Dimension(width, 30);
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        return new Dimension(width, width);
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return createZeroButton();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return createZeroButton();
    }

    private JButton createZeroButton() {
        JButton btn = new JButton();
        btn.setPreferredSize(new Dimension(0, 0));
        btn.setMinimumSize(new Dimension(0, 0));
        btn.setMaximumSize(new Dimension(0, 0));
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusable(false);
        return btn;
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
        // Transparent / subtle track
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
        if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (isDragging) {
            g2.setColor(ThemeColors.ACCENT_PRIMARY);
        } else if (isThumbRollover()) {
            g2.setColor(ThemeColors.TEXT_MUTED);
        } else {
            g2.setColor(new java.awt.Color(0x4E, 0x50, 0x58));
        }

        int arc = width;
        int inset = 2;
        g2.fillRoundRect(thumbBounds.x + inset, thumbBounds.y + inset,
            thumbBounds.width - (inset * 2), thumbBounds.height - (inset * 2), arc, arc);

        g2.dispose();
    }
}
