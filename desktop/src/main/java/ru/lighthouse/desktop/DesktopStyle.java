package ru.lighthouse.desktop;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;

/** Small, dependency-free visual system; all dimensions are Swing logical pixels. */
final class DesktopStyle {
    final boolean dark;
    final Color background, surface, raised, border, text, muted, accent, accentText, good, warning, bad;

    DesktopStyle(boolean dark) {
        this.dark = dark;
        background = hex(dark ? 0x10191F : 0xF1F5F6);
        surface = hex(dark ? 0x18242C : 0xFFFFFF);
        raised = hex(dark ? 0x21313B : 0xEAF0F2);
        border = hex(dark ? 0x30414B : 0xDCE5E8);
        text = hex(dark ? 0xEDF4F6 : 0x172D38);
        muted = hex(dark ? 0xA0B3BE : 0x526D7B);
        accent = hex(dark ? 0x78DBC8 : 0x087B6C);
        accentText = hex(dark ? 0x102D29 : 0xFFFFFF);
        good = hex(dark ? 0x78DBC8 : 0x087B6C);
        warning = hex(dark ? 0xF1CA7C : 0x8C5908);
        bad = hex(dark ? 0xFF9A9F : 0xBC394B);
    }

    static Color hex(int rgb) { return new Color(rgb); }
    static Font font(int style, int size) { return new Font("Segoe UI", style, size); }
    static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
    static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel;
    }
    static JLabel label(String text, int size, boolean bold, String tone) {
        // Never interpret service names or remote diagnostics as Swing HTML.
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.putClientProperty("tone", tone);
        label.setFont(font(bold ? Font.BOLD : Font.PLAIN, size));
        return label;
    }
    static JTextArea paragraph(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(font(Font.PLAIN, 13)); area.setOpaque(false);
        area.setBorder(null);
        return area;
    }
    static JScrollPane scroll(Component child) {
        JScrollPane pane = new JScrollPane(child);
        pane.setBorder(null); pane.setOpaque(false);
        pane.getVerticalScrollBar().setUnitIncrement(24);
        return pane;
    }

    static final class WidthPanel extends JPanel implements Scrollable {
        WidthPanel(LayoutManager layout) { super(layout); setOpaque(false); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24, visible.height - 24); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    void apply(Component component) {
        component.setForeground(text);
        component.setBackground(surface);
        if (component instanceof JComponent jc && "muted".equals(jc.getClientProperty("tone")))
            component.setForeground(muted);
        if (component instanceof Surface card) card.palette = this;
        if (component instanceof ActionButton button) button.palette = this;
        if (component instanceof JTextField field) {
            field.setFont(font(Font.PLAIN, 13)); field.setCaretColor(text);
            field.setSelectionColor(raised);
            field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(border),
                new EmptyBorder(8, 10, 8, 10)));
        }
        if (component instanceof JTextArea area) { area.setCaretColor(text); area.setSelectionColor(raised); }
        if (component instanceof JComboBox<?> combo) {
            combo.setFont(font(Font.PLAIN, 13));
            combo.setBorder(BorderFactory.createLineBorder(border));
            combo.setUI(new BasicComboBoxUI() {
                @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                    g.setColor(surface); g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                }
                @Override public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
                    Component renderer = comboBox.getRenderer().getListCellRendererComponent(listBox,
                        comboBox.getSelectedItem(), -1, false, false);
                    renderer.setBackground(surface); renderer.setForeground(text);
                    currentValuePane.paintComponent(g, renderer, comboBox, bounds.x, bounds.y, bounds.width, bounds.height, true);
                }
                @Override protected JButton createArrowButton() {
                    JButton arrow = new JButton() {
                        @Override protected void paintComponent(Graphics graphics) {
                            Graphics2D g = (Graphics2D) graphics.create(); smooth(g);
                            g.setColor(isEnabled() ? muted : border);
                            int x = getWidth() / 2, y = getHeight() / 2;
                            g.setStroke(new BasicStroke(1.5f));
                            g.drawLine(x - 4, y - 2, x, y + 2); g.drawLine(x, y + 2, x + 4, y - 2); g.dispose();
                        }
                    };
                    arrow.setPreferredSize(new Dimension(28, 28)); arrow.setBorder(null);
                    arrow.setContentAreaFilled(false); return arrow;
                }
            });
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                         boolean selected, boolean focus) {
                    super.getListCellRendererComponent(list, value, index, selected, focus);
                    putClientProperty("html.disable", true);
                    setFont(font(Font.PLAIN, 13)); setForeground(text);
                    setBackground(selected ? raised : surface); setBorder(new EmptyBorder(7, 9, 7, 9));
                    return this;
                }
            });
        }
        if (component instanceof JTable table) {
            table.setSelectionBackground(raised); table.setSelectionForeground(text); table.setGridColor(border);
            table.getTableHeader().setBackground(surface); table.getTableHeader().setForeground(muted);
        }
        if (component instanceof JScrollBar bar) {
            bar.setPreferredSize(new Dimension(10, 10));
            bar.setUI(new BasicScrollBarUI() {
                @Override protected void configureScrollBarColors() { thumbColor = border; trackColor = surface; }
                private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension()); return b; }
                @Override protected JButton createDecreaseButton(int orientation) { return zero(); }
                @Override protected JButton createIncreaseButton(int orientation) { return zero(); }
                @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                    Graphics2D copy = (Graphics2D) g.create(); smooth(copy); copy.setColor(border);
                    copy.fillRoundRect(r.x + 2, r.y + 2, Math.max(4, r.width - 4), Math.max(4, r.height - 4), 8, 8);
                    copy.dispose();
                }
            });
        }
        if (component instanceof Container container)
            for (Component child : container.getComponents()) apply(child);
    }

    static final class Surface extends JPanel {
        DesktopStyle palette;
        Surface(LayoutManager layout, int padding) {
            super(layout); setOpaque(false); setBorder(new EmptyBorder(padding, padding, padding, padding));
        }
        @Override protected void paintComponent(Graphics graphics) {
            if (palette == null) return;
            Graphics2D g = (Graphics2D) graphics.create(); smooth(g);
            Object signal = getClientProperty("signalColor");
            g.setColor(signal instanceof Color ? (Color) signal
                : Boolean.TRUE.equals(getClientProperty("blackout")) ? Color.BLACK : palette.surface);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
            g.setColor(palette.border); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20); g.dispose();
        }
    }

    static class ActionButton extends JButton {
        DesktopStyle palette;
        final boolean primary;
        ActionButton(String text, boolean primary) {
            super(text); this.primary = primary;
            putClientProperty("html.disable", true);
            setFont(font(Font.BOLD, 13)); setBorder(new EmptyBorder(9, 15, 9, 15));
            setContentAreaFilled(false); setFocusPainted(false); setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
        }
        @Override protected void paintComponent(Graphics graphics) {
            if (palette != null) {
                Graphics2D g = (Graphics2D) graphics.create(); smooth(g);
                Color fill = primary || isSelected() ? palette.accent : palette.surface;
                if (!isEnabled()) fill = palette.raised;
                else if (getModel().isPressed()) fill = fill.darker();
                else if (getModel().isRollover()) fill = primary || isSelected() ? fill.darker() : palette.raised;
                g.setColor(fill); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g.setColor(hasFocus() ? palette.accent : palette.border);
                g.setStroke(new BasicStroke(hasFocus() ? 2 : 1));
                if (!(primary || isSelected()) || hasFocus())
                    g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 12, 12);
                g.dispose();
                setForeground(!isEnabled() ? palette.muted : primary || isSelected() ? palette.accentText : palette.text);
            }
            super.paintComponent(graphics);
        }
    }
}
