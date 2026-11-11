package pl.anarchia.terminal.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import pl.anarchia.terminal.core.Status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Wygląd terminala: ciemne tło, stonowane linie, kolory statusów zawsze z ikoną. */
public final class Theme {
    private Theme() {}

    public static final int BG = 0xF00E0F12;
    public static final int PANEL = 0xFF16171B;
    public static final int PANEL_2 = 0xFF1C1E23;
    public static final int BORDER = 0xFF2C2F36;
    public static final int GRID = 0x802C2F36;
    public static final int ROW_ALT = 0xFF191A1F;
    public static final int ROW_HOVER = 0xFF23252B;
    public static final int ROW_SEL = 0xFF27303D;
    public static final int TEXT = 0xFFEDEDED;
    public static final int TEXT_2 = 0xFFB9BCC4;
    public static final int MUTED = 0xFF7D818B;
    public static final int ACCENT = 0xFF3987E5;

    // statusy
    public static final int GREEN = 0xFF2DBE60;
    public static final int RED = 0xFFE5484D;
    public static final int YELLOW = 0xFFF5B83D;
    public static final int BLUE = 0xFF4C9AFF;

    // serie wykresu
    public static final int S_MARKET = 0xFF3987E5;
    public static final int S_BK = 0xFFD95926;
    public static final int S_MIN = 0xFF199E70;
    public static final int S_MEDIAN = 0xFF9085E9;
    public static final int S_MEAN = 0xFFB9BCC4;
    public static final int S_TREND = 0xFFEDEDED;
    public static final int S_SALE = 0xFFD55181;
    public static final int S_OFFER = 0xFF6B6F79;

    public static int color(Status s) {
        switch (s) {
            case GREEN: return GREEN;
            case RED: return RED;
            case YELLOW: return YELLOW;
            case BLUE: return BLUE;
            default: return MUTED;
        }
    }

    public static void panel(DrawContext c, int x1, int y1, int x2, int y2) {
        c.fill(x1, y1, x2, y2, PANEL);
        c.fill(x1, y1, x2, y1 + 1, BORDER);
        c.fill(x1, y2 - 1, x2, y2, BORDER);
        c.fill(x1, y1, x1 + 1, y2, BORDER);
        c.fill(x2 - 1, y1, x2, y2, BORDER);
    }

    public static void text(DrawContext c, TextRenderer tr, String s, int x, int y, int color) {
        c.drawText(tr, s, x, y, color, false);
    }

    public static void fit(DrawContext c, TextRenderer tr, String s, int x, int y, int w, int color) {
        c.drawText(tr, fitStr(tr, s, w), x, y, color, false);
    }

    public static String fitStr(TextRenderer tr, String s, int w) {
        if (w <= 0) return "";
        if (tr.getWidth(s) <= w) return s;
        return tr.trimToWidth(s, Math.max(0, w - tr.getWidth("…"))) + "…";
    }

    public static void right(DrawContext c, TextRenderer tr, String s, int r, int y, int color) {
        c.drawText(tr, s, r - tr.getWidth(s), y, color, false);
    }

    public static void dashed(DrawContext c, int x1, int x2, int y, int color) {
        for (int x = x1; x < x2; x += 6) c.fill(x, y, Math.min(x + 3, x2), y + 1, color);
    }

    public static String time(long t, String pattern) {
        return DateTimeFormatter.ofPattern(pattern).format(LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()));
    }

    public static String ago(long t) {
        if (t <= 0) return "nigdy";
        long s = (System.currentTimeMillis() - t) / 1000;
        if (s < 60) return "przed chwilą";
        if (s < 3600) return (s / 60) + " min temu";
        if (s < 86400) return (s / 3600) + " h temu";
        return (s / 86400) + " d temu";
    }
}
