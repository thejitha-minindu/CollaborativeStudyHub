package com.studyhub.ui.theme;

import java.awt.Color;
import java.awt.Font;

/**
 * Modern dark collaboration theme color palette (Discord/Slack-inspired).
 * Pure Java SE standard colors with high-contrast accessibility and sleek aesthetics.
 */
public final class ThemeColors {

    private ThemeColors() {}

    // Canvas & Structural Backgrounds
    public static final Color BG_RAIL = new Color(0x1E, 0x1F, 0x22);         // Navigation rail / left sidebar
    public static final Color BG_PANEL = new Color(0x2B, 0x2D, 0x31);        // Secondary panels / right sidebar / cards
    public static final Color BG_CHAT = new Color(0x31, 0x33, 0x38);         // Main chat feed canvas
    public static final Color BG_INPUT = new Color(0x38, 0x3A, 0x40);        // Input composer & hover states
    public static final Color BG_CARD = new Color(0x23, 0x24, 0x28);         // Embedded cards & inner containers
    public static final Color BG_HOVER = new Color(0x35, 0x37, 0x3C);        // Interactive row hover

    // Accent & Status Colors
    public static final Color ACCENT_PRIMARY = new Color(0x58, 0x65, 0xF2);  // Discord Blurple / primary buttons / links
    public static final Color ACCENT_HOVER = new Color(0x47, 0x52, 0xC4);    // Accent hover state
    public static final Color STATUS_ONLINE = new Color(0x23, 0xA5, 0x5A);   // Green: Online, TLS verified, SHA-256 match
    public static final Color STATUS_TYPING = new Color(0xF0, 0xB2, 0x32);   // Amber: Typing, pulse active
    public static final Color STATUS_DANGER = new Color(0xF2, 0x3F, 0x43);   // Red: Tamper alert, error, disconnect
    public static final Color BADGE_MOD = new Color(0xF0, 0xB2, 0x32);       // Moderator gold badge
    public static final Color BADGE_STUDENT = new Color(0x58, 0x65, 0xF2);   // Student blurple badge

    // Typography & Text
    public static final Color TEXT_PRIMARY = new Color(0xF2, 0xF3, 0xF5);    // High-contrast primary text
    public static final Color TEXT_MUTED = new Color(0x94, 0x9B, 0xA4);      // Secondary / muted descriptions
    public static final Color TEXT_DARK = new Color(0x11, 0x12, 0x14);       // Dark text on bright badge
    public static final Color BORDER_SUBTLE = new Color(0x3F, 0x41, 0x47);   // Hairline dividers and card borders
    public static final Color BORDER_ACTIVE = new Color(0x58, 0x65, 0xF2);   // Focus border

    // Standard Typography
    public static final Font FONT_TITLE = new Font("Dialog", Font.BOLD, 15);
    public static final Font FONT_HEADER = new Font("Dialog", Font.BOLD, 13);
    public static final Font FONT_BODY = new Font("Dialog", Font.PLAIN, 13);
    public static final Font FONT_MUTED = new Font("Dialog", Font.PLAIN, 11);
    public static final Font FONT_BOLD = new Font("Dialog", Font.BOLD, 11);
    public static final Font FONT_MONO = new Font("Monospaced", Font.PLAIN, 11);
    public static final Font FONT_AVATAR = new Font("Dialog", Font.BOLD, 12);
    public static void installDefaults() {
        javax.swing.UIManager.put("Panel.background", BG_PANEL);
        javax.swing.UIManager.put("OptionPane.background", BG_PANEL);
        javax.swing.UIManager.put("OptionPane.messageForeground", TEXT_PRIMARY);
        javax.swing.UIManager.put("Label.foreground", TEXT_PRIMARY);
        javax.swing.UIManager.put("TextField.background", BG_INPUT);
        javax.swing.UIManager.put("TextField.foreground", TEXT_PRIMARY);
        javax.swing.UIManager.put("TextField.caretForeground", TEXT_PRIMARY);
        javax.swing.UIManager.put("PasswordField.background", BG_INPUT);
        javax.swing.UIManager.put("PasswordField.foreground", TEXT_PRIMARY);
        javax.swing.UIManager.put("PasswordField.caretForeground", TEXT_PRIMARY);
        javax.swing.UIManager.put("CheckBox.background", BG_PANEL);
        javax.swing.UIManager.put("CheckBox.foreground", TEXT_PRIMARY);
        javax.swing.UIManager.put("ComboBox.background", BG_INPUT);
        javax.swing.UIManager.put("ComboBox.foreground", TEXT_PRIMARY);
        javax.swing.UIManager.put("ToolTip.background", BG_CARD);
        javax.swing.UIManager.put("ToolTip.foreground", TEXT_PRIMARY);
        javax.swing.UIManager.put("Button.font", FONT_BODY);
        javax.swing.UIManager.put("Label.font", FONT_BODY);
    }
}
