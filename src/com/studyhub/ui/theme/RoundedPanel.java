package com.studyhub.ui.theme;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import javax.swing.JPanel;

/**
 * Custom-painted JPanel with antialiased rounded corners and optional borders.
 */
public class RoundedPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private int cornerRadius;
    private Color backgroundColor;
    private Color borderColor;
    private int borderWidth;

    public RoundedPanel(int radius) {
        this(radius, ThemeColors.BG_PANEL, null, 0);
    }

    public RoundedPanel(int radius, Color bg) {
        this(radius, bg, null, 0);
    }

    public RoundedPanel(int radius, Color bg, Color border, int borderWidth) {
        super();
        this.cornerRadius = radius;
        this.backgroundColor = bg;
        this.borderColor = border;
        this.borderWidth = borderWidth;
        setOpaque(false);
    }

    public RoundedPanel(LayoutManager layout, int radius, Color bg) {
        super(layout);
        this.cornerRadius = radius;
        this.backgroundColor = bg;
        this.borderColor = null;
        this.borderWidth = 0;
        setOpaque(false);
    }

    public void setBackgroundColor(Color bg) {
        this.backgroundColor = bg;
        repaint();
    }

    public void setBorderColor(Color border, int width) {
        this.borderColor = border;
        this.borderWidth = width;
        repaint();
    }

    public void setBorderColor(Color border) {
        setBorderColor(border, this.borderWidth > 0 ? this.borderWidth : 1);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // Draw background
        if (backgroundColor != null) {
            g2.setColor(backgroundColor);
            g2.fillRoundRect(0, 0, w, h, cornerRadius, cornerRadius);
        }

        // Draw border if set
        if (borderColor != null && borderWidth > 0) {
            g2.setColor(borderColor);
            g2.setStroke(new java.awt.BasicStroke(borderWidth));
            g2.drawRoundRect(borderWidth / 2, borderWidth / 2,
                w - borderWidth, h - borderWidth, cornerRadius, cornerRadius);
        }

        g2.dispose();
    }
}
