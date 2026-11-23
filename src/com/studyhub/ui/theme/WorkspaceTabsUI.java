package com.studyhub.ui.theme;

import java.awt.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;

/** Flat, keyboard-accessible workspace mode tabs. */
public final class WorkspaceTabsUI extends BasicTabbedPaneUI {
    protected void installDefaults() {
        super.installDefaults(); tabInsets = new Insets(10, 16, 10, 16);
        selectedTabPadInsets = new Insets(0, 0, 0, 0); contentBorderInsets = new Insets(1, 1, 1, 1);
    }
    protected void paintTabBackground(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {
        g.setColor(selected ? ThemeColors.BG_PANEL : ThemeColors.BG_CHAT); g.fillRect(x, y, w, h);
        if (selected) { g.setColor(ThemeColors.ACCENT_PRIMARY); g.fillRect(x, y + h - 3, w, 3); }
    }
    protected void paintTabBorder(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {}
    protected void paintContentBorder(Graphics g, int placement, int selected) {
        g.setColor(ThemeColors.BORDER_SUBTLE);
        int top = calculateTabAreaHeight(placement, runCount, maxTabHeight);
        g.drawRect(0, top, tabPane.getWidth() - 1, tabPane.getHeight() - top - 1);
    }
}
