import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class Banner {
    static final int W = 1280, H = 800;
    static Font mono, monoBold, sans, sansBold;

    public static void main(String[] args) throws Exception {
        String fonts = System.getProperty("user.home") + "/Library/Fonts/";
        mono = Font.createFont(Font.TRUETYPE_FONT, new File(fonts + "JetBrainsMono-Regular.ttf"));
        monoBold = Font.createFont(Font.TRUETYPE_FONT, new File(fonts + "JetBrainsMono-Bold.ttf"));
        sans = new Font("Helvetica Neue", Font.PLAIN, 10);
        sansBold = new Font("Helvetica Neue", Font.BOLD, 10);

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        // Background: deep navy with soft color glows.
        g.setColor(new Color(0x0E1220));
        g.fillRect(0, 0, W, H);
        glow(g, 180, 120, 520, new Color(255, 77, 141, 70));
        glow(g, 1150, 760, 620, new Color(61, 123, 255, 80));
        glow(g, 900, 80, 420, new Color(124, 92, 255, 50));

        // Header: icon + title + tagline.
        icon(g, 80, 72, 88);
        g.setColor(Color.WHITE);
        g.setFont(sansBold.deriveFont(64f));
        g.drawString("Chromabox", 196, 136);
        g.setColor(new Color(0xB8C0D9));
        g.setFont(sans.deriveFont(28f));
        g.drawString("See hex colors in any file. Click a swatch to change it.", 82, 222);

        // Mode chips.
        int cx = 82;
        for (String chip : new String[]{"Swatch only", "Swatch + hex", "Off"}) cx = chip(g, cx, 252, chip) + 14;
        g.setColor(new Color(0x7E87A6));
        g.setFont(sans.deriveFont(20f));
        g.drawString("Markdown · CSV · plain text · any file type", cx + 10, 283);

        editor(g, 82, 340, 1116, 400);

        g.dispose();
        ImageIO.write(img, "png", new File(args[0]));
    }

    static void glow(Graphics2D g, int x, int y, int r, Color c) {
        g.setPaint(new RadialGradientPaint(new Point2D.Float(x, y), r, new float[]{0f, 1f},
            new Color[]{c, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
        g.fillRect(x - r, y - r, 2 * r, 2 * r);
    }

    // Same design as META-INF/pluginIcon.svg, scaled from its 40px grid.
    static void icon(Graphics2D g, int x, int y, int size) {
        double s = size / 40.0;
        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        g.scale(s, s);
        g.setPaint(new LinearGradientPaint(3, 3, 37, 37, new float[]{0f, .45f, .75f, 1f},
            new Color[]{new Color(0xFF4D8D), new Color(0xFF8A3D), new Color(0x7C5CFF), new Color(0x3D7BFF)}));
        g.fill(new RoundRectangle2D.Double(3, 3, 34, 34, 18, 18));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(17.5, 10.5, 15, 29.5));
        g.draw(new Line2D.Double(26, 10.5, 23.5, 29.5));
        g.draw(new Line2D.Double(11, 16.5, 30, 16.5));
        g.draw(new Line2D.Double(10, 23.5, 29, 23.5));
        g.setTransform(saved);
    }

    static int chip(Graphics2D g, int x, int y, String text) {
        g.setFont(sansBold.deriveFont(19f));
        int w = g.getFontMetrics().stringWidth(text) + 36;
        g.setColor(new Color(255, 255, 255, 22));
        g.fill(new RoundRectangle2D.Double(x, y, w, 42, 42, 42));
        g.setColor(new Color(255, 255, 255, 50));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new RoundRectangle2D.Double(x, y, w, 42, 42, 42));
        g.setColor(new Color(0xE6E9F2));
        g.drawString(text, x + 18, y + 28);
        return x + w;
    }

    static void editor(Graphics2D g, int x, int y, int w, int h) {
        // Shadow + window.
        for (int i = 0; i < 18; i++) {
            g.setColor(new Color(0, 0, 0, 7));
            g.fill(new RoundRectangle2D.Double(x - i, y - i + 14, w + 2 * i, h + 2 * i, 28 + i, 28 + i));
        }
        g.setColor(new Color(0x1E1F22));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 24, 24));
        g.setColor(new Color(0x2B2D30));
        g.fill(new RoundRectangle2D.Double(x, y, w, 52, 24, 24));
        g.fillRect(x, y + 30, w, 22);
        g.setColor(new Color(0x393B40));
        g.fillRect(x, y + 52, w, 1);
        Color[] lights = {new Color(0xFF5F57), new Color(0xFEBC2E), new Color(0x28C840)};
        for (int i = 0; i < 3; i++) {
            g.setColor(lights[i]);
            g.fill(new Ellipse2D.Double(x + 22 + i * 22, y + 20, 13, 13));
        }
        // Tab.
        g.setColor(new Color(0x1E1F22));
        g.fill(new RoundRectangle2D.Double(x + 110, y + 10, 190, 43, 12, 12));
        g.setFont(sans.deriveFont(17f));
        g.setColor(new Color(0xDFE1E5));
        g.drawString("palette.md", x + 150, y + 38);
        g.setColor(new Color(0x548AF7));
        g.setFont(monoBold.deriveFont(15f));
        g.drawString("M↓", x + 124, y + 37);

        // Code lines.
        Font code = mono.deriveFont(22f);
        g.setFont(code);
        FontMetrics fm = g.getFontMetrics();
        int lineH = 40, top = y + 100, gutter = x + 28, textX = x + 92;
        Color text = new Color(0xBCBEC4), dim = new Color(0x6F737A), head = new Color(0xCF8E6D);

        String[][] rows = {
            {"Primary", "#3D7BFF"},
            {"Accent", "#FF4D8D"},
            {"Success", "#22C55E"},
            {"Warning", "#F5A524"},
            {"Overlay", "#0F172A80"},
        };
        int line = 0;
        drawLine(g, fm, gutter, top, ++line, dim);
        g.setColor(head);
        g.setFont(monoBold.deriveFont(22f));
        g.drawString("## Brand palette", textX, top);
        g.setFont(code);

        int y0 = top + lineH;
        drawLine(g, fm, gutter, y0, ++line, dim);
        g.setColor(dim);
        g.drawString("| Token   | Color         |", textX, y0);
        int[] swatchCenters = new int[rows.length];
        int accentSwatchX = 0, accentRowEnd = 0;
        for (int i = 0; i < rows.length; i++) {
            int ly = y0 + lineH * (i + 1);
            drawLine(g, fm, gutter, ly, ++line, dim);
            int px = textX;
            g.setColor(dim);
            g.drawString("| ", px, ly);
            px += fm.stringWidth("| ");
            g.setColor(text);
            String name = String.format("%-8s", rows[i][0]);
            g.drawString(name, px, ly);
            px += fm.stringWidth(name);
            g.setColor(dim);
            g.drawString("| ", px, ly);
            px += fm.stringWidth("| ");
            // Swatch inlay.
            int sw = 20;
            int sy = ly - fm.getAscent() + (fm.getAscent() - sw) / 2 + 3;
            swatch(g, px + 3, sy, sw, Color.decode(rows[i][1].substring(0, 7)),
                rows[i][1].length() == 9 ? Integer.parseInt(rows[i][1].substring(7), 16) : 255);
            swatchCenters[i] = sy + sw / 2;
            if (i == 1) accentSwatchX = px + 3;
            px += sw + 12;
            g.setColor(new Color(0x6AAB73));
            String hex = String.format("%-10s", rows[i][1]);
            g.drawString(hex, px, ly);
            px += fm.stringWidth(hex);
            g.setColor(dim);
            g.drawString("|", px, ly);
            if (i == 1) accentRowEnd = px + fm.stringWidth("|");
        }

        // Caret-ish pointer + picker popup next to the "Accent" swatch.
        int pickX = x + 640, pickY = y + 96;
        int anchorY = swatchCenters[1];
        g.setColor(new Color(255, 255, 255, 200));
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(accentSwatchX - 4, anchorY - 14, 28, 28, 10, 10));
        g.setColor(new Color(255, 255, 255, 70));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{2f, 7f}, 0f));
        g.draw(new Line2D.Double(accentRowEnd + 16, anchorY, pickX - 12, anchorY));
        picker(g, pickX, pickY, 430, 280);
    }

    static void drawLine(Graphics2D g, FontMetrics fm, int x, int y, int n, Color c) {
        Font f = g.getFont();
        g.setFont(f.deriveFont(18f));
        g.setColor(new Color(0x4B5059));
        String s = String.valueOf(n);
        g.drawString(s, x + 30 - g.getFontMetrics().stringWidth(s), y);
        g.setFont(f);
    }

    static void swatch(Graphics2D g, int x, int y, int size, Color c, int alpha) {
        Shape r = new RoundRectangle2D.Double(x, y, size, size, 7, 7);
        Shape clip = g.getClip();
        g.clip(r);
        if (alpha < 255) {
            double cell = size / 2.0;
            for (int row = 0; row < 2; row++) for (int col = 0; col < 2; col++) {
                g.setColor((row + col) % 2 == 0 ? Color.WHITE : Color.LIGHT_GRAY);
                g.fill(new Rectangle2D.Double(x + col * cell, y + row * cell, cell, cell));
            }
        }
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
        g.fill(r);
        g.setClip(clip);
        g.setColor(new Color(255, 255, 255, 90));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(r);
    }

    static void picker(Graphics2D g, int x, int y, int w, int h) {
        for (int i = 0; i < 14; i++) {
            g.setColor(new Color(0, 0, 0, 10));
            g.fill(new RoundRectangle2D.Double(x - i, y - i + 10, w + 2 * i, h + 2 * i, 20 + i, 20 + i));
        }
        g.setColor(new Color(0x2B2D30));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 18, 18));
        g.setColor(new Color(0x43454A));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 18, 18));

        // Saturation/brightness square for the accent hue.
        int sx = x + 20, sy = y + 20, ss = 200;
        Shape sq = new RoundRectangle2D.Double(sx, sy, ss, ss, 10, 10);
        Shape clip = g.getClip();
        g.clip(sq);
        g.setPaint(new GradientPaint(sx, 0, Color.WHITE, sx + ss, 0, Color.getHSBColor(0.94f, 1f, 1f)));
        g.fill(sq);
        g.setPaint(new GradientPaint(0, sy, new Color(0, 0, 0, 0), 0, sy + ss, Color.BLACK));
        g.fill(sq);
        g.setClip(clip);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(3f));
        g.draw(new Ellipse2D.Double(sx + 140, sy + 40, 18, 18));

        // Hue bar.
        int hx = sx + ss + 18, hw = 22;
        float[] fr = new float[7];
        Color[] cs = new Color[7];
        for (int i = 0; i < 7; i++) { fr[i] = i / 6f; cs[i] = Color.getHSBColor(i / 6f, 1f, 1f); }
        Shape hb = new RoundRectangle2D.Double(hx, sy, hw, ss, 10, 10);
        g.setPaint(new LinearGradientPaint(0, sy, 0, sy + ss, fr, cs));
        g.fill(hb);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(3f));
        g.draw(new RoundRectangle2D.Double(hx - 3, sy + ss * 0.94 - 5, hw + 6, 10, 6, 6));

        // Preview + values.
        int vx = hx + hw + 22;
        swatch(g, vx, sy, 92, new Color(0xFF4D8D), 255);
        g.setFont(sans.deriveFont(15f));
        g.setColor(new Color(0x8C9099));
        g.drawString("HEX", vx, sy + 126);
        g.setFont(mono.deriveFont(19f));
        g.setColor(new Color(0xDFE1E5));
        g.drawString("FF4D8D", vx, sy + 152);
        g.setFont(sans.deriveFont(15f));
        g.setColor(new Color(0x8C9099));
        g.drawString("Opacity", vx, sy + 186);
        g.setFont(mono.deriveFont(19f));
        g.setColor(new Color(0xDFE1E5));
        g.drawString("100%", vx, sy + 210);

        // Recent colors.
        int[] recent = {0x3D7BFF, 0x22C55E, 0xF5A524, 0x7C5CFF, 0xFF8A3D, 0x14B8A6, 0xE11D48, 0x64748B};
        for (int i = 0; i < recent.length; i++) swatch(g, sx + i * 30, sy + ss + 18, 22, new Color(recent[i]), 255);
    }
}
