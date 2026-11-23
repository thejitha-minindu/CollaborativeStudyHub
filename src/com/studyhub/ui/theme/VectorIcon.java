package com.studyhub.ui.theme;

import java.awt.*;
import java.awt.geom.*;
import javax.swing.Icon;

/** Resolution-independent icons with no font or emoji dependency. */
public final class VectorIcon implements Icon {
    public enum Kind { BRAND, LOCK, UNLOCK, FOLDER, FILE, SETTINGS, WIFI, SEARCH, PIN, CLOSE, CHECK, INFO, WARNING, ACTIVITY }
    private final Kind kind;
    private final int size;
    private final Color color;
    public VectorIcon(Kind kind, int size, Color color) { this.kind = kind; this.size = size; this.color = color; }
    public int getIconWidth() { return size; }
    public int getIconHeight() { return size; }
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D p = (Graphics2D) g.create();
        p.translate(x, y); p.scale(size / 24.0, size / 24.0);
        p.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        p.setColor(color); p.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case LOCK, UNLOCK -> {
                p.draw(new RoundRectangle2D.Double(5, 10, 14, 11, 3, 3));
                p.draw(new Arc2D.Double(kind == Kind.LOCK ? 8 : 12, 3, 8, 13, 0, 180, Arc2D.OPEN));
                p.draw(new Line2D.Double(12, 14, 12, 17));
            }
            case FOLDER -> {
                Path2D path = new Path2D.Double(); path.moveTo(3, 7); path.lineTo(3, 20); path.lineTo(21, 20);
                path.lineTo(21, 7); path.lineTo(12, 7); path.lineTo(9, 4); path.lineTo(3, 4); path.closePath(); p.draw(path);
            }
            case FILE -> {
                Path2D path = new Path2D.Double(); path.moveTo(6, 3); path.lineTo(14, 3); path.lineTo(19, 8);
                path.lineTo(19, 21); path.lineTo(6, 21); path.closePath(); p.draw(path);
                p.drawLine(14, 3, 14, 8); p.drawLine(14, 8, 19, 8); p.drawLine(9, 13, 16, 13); p.drawLine(9, 17, 16, 17);
            }
            case SETTINGS -> {
                p.draw(new Ellipse2D.Double(5, 5, 14, 14)); p.draw(new Ellipse2D.Double(9, 9, 6, 6));
                for (int i = 0; i < 8; i++) { p.rotate(Math.PI / 4, 12, 12); p.drawLine(12, 2, 12, 5); }
            }
            case WIFI -> {
                p.draw(new Arc2D.Double(2, 5, 20, 20, 45, 90, Arc2D.OPEN));
                p.draw(new Arc2D.Double(6, 10, 12, 12, 45, 90, Arc2D.OPEN)); p.fill(new Ellipse2D.Double(10.5, 18, 3, 3));
            }
            case SEARCH -> { p.draw(new Ellipse2D.Double(3, 3, 12, 12)); p.drawLine(14, 14, 21, 21); }
            case CLOSE -> { p.drawLine(6, 6, 18, 18); p.drawLine(18, 6, 6, 18); }
            case CHECK -> { p.drawLine(4, 12, 9, 17); p.drawLine(9, 17, 20, 6); }
            case INFO -> { p.drawOval(3, 3, 18, 18); p.drawLine(12, 11, 12, 17); p.fillOval(11, 6, 2, 2); }
            case WARNING -> {
                Path2D path = new Path2D.Double(); path.moveTo(12, 3); path.lineTo(22, 21); path.lineTo(2, 21); path.closePath(); p.draw(path);
                p.drawLine(12, 9, 12, 14); p.fillOval(11, 17, 2, 2);
            }
            case PIN -> { p.drawLine(8, 4, 16, 4); p.drawLine(9, 4, 9, 11); p.drawLine(15, 4, 15, 11); p.drawLine(6, 14, 18, 14); p.drawLine(9, 11, 6, 14); p.drawLine(15, 11, 18, 14); p.drawLine(12, 14, 12, 22); }
            case ACTIVITY -> { p.drawArc(4, 4, 16, 16, 30, 285); p.drawLine(19, 3, 20, 8); p.drawLine(20, 8, 15, 7); }
            case BRAND -> {
                p.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                p.draw(new RoundRectangle2D.Double(3, 3, 18, 18, 6, 6));
                p.drawLine(8, 9, 16, 9); p.drawLine(8, 13, 13, 13); p.drawLine(8, 17, 16, 17);
            }
        }
        p.dispose();
    }
}
