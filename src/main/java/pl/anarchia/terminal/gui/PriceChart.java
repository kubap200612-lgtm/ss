package pl.anarchia.terminal.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import pl.anarchia.terminal.core.BkChange;
import pl.anarchia.terminal.core.Fmt;
import pl.anarchia.terminal.core.ItemStats;
import pl.anarchia.terminal.core.Offer;
import pl.anarchia.terminal.core.PricePoint;
import pl.anarchia.terminal.core.Purchase;
import pl.anarchia.terminal.core.Sale;
import pl.anarchia.terminal.core.Stats;
import pl.anarchia.terminal.market.Engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleToIntFunction;
import java.util.function.LongToIntFunction;

/**
 * Wykres ceny przedmiotu: cena rynkowa, cena w BK, najniższa, średnia, mediana, linia trendu,
 * oferty (kropki), transakcje (krzyżyki), Twoje zakupy (kwadraty) + histogram przedziałów cen z prawej.
 */
public final class PriceChart {
    private PriceChart() {}

    public static final String[] LEGEND = {"Rynkowa", "Cena BK", "Najniższa", "Średnia", "Mediana", "Trend",
            "Oferty", "Transakcje", "Twoje zakupy"};
    public static final int[] COLORS = {Theme.S_MARKET, Theme.S_BK, Theme.S_MIN, Theme.S_MEAN, Theme.S_MEDIAN, Theme.S_TREND,
            Theme.S_OFFER, Theme.S_SALE, Theme.GREEN};

    public static int legend(DrawContext c, TextRenderer tr, int x, int y, int maxW) {
        int cx = x, cy = y;
        for (int i = 0; i < LEGEND.length; i++) {
            int w = 12 + tr.getWidth(LEGEND[i]) + 8;
            if (cx + w > x + maxW && cx > x) {
                cx = x;
                cy += 10;
            }
            swatch(c, i, cx, cy + 3, COLORS[i]);
            c.drawText(tr, LEGEND[i], cx + 12, cy, Theme.TEXT_2, false);
            cx += w;
        }
        return cy - y + 10;
    }

    public static int legendHeight(TextRenderer tr, int maxW) {
        int cx = 0, lines = 1;
        for (String l : LEGEND) {
            int w = 12 + tr.getWidth(l) + 8;
            if (cx + w > maxW && cx > 0) {
                cx = 0;
                lines++;
            }
            cx += w;
        }
        return lines * 10;
    }

    private static void swatch(DrawContext c, int i, int x, int y, int col) {
        switch (i) {
            case 0: case 1: c.fill(x, y, x + 9, y + 2, col); break;
            case 2: c.fill(x, y + 1, x + 9, y + 2, col); break;
            case 3: case 5: Theme.dashed(c, x, x + 9, y + 1, col); break;
            case 4: c.fill(x, y + 1, x + 9, y + 2, col); c.fill(x + 4, y - 1, x + 5, y + 4, col); break;
            case 6: c.fill(x + 3, y, x + 5, y + 2, col); break;
            case 7: cross(c, x + 4, y + 1, col); break;
            default: c.fill(x + 1, y - 2, x + 8, y + 5, Theme.TEXT); c.fill(x + 2, y - 1, x + 7, y + 4, col);
        }
    }

    /** Zwraca linie dymka pod kursorem. */
    public static List<String> draw(DrawContext c, TextRenderer tr, int x, int y, int w, int h, Engine.Analysis a,
                                    List<BkChange> bkChanges, long rangeMs, int mx, int my) {
        List<String> tip = new ArrayList<>();
        c.fill(x, y, x + w, y + h, Theme.PANEL_2);
        long now = System.currentTimeMillis();
        long t0 = now - rangeMs;
        ItemStats st = a.stats;
        String fmt = rangeMs > 24 * 3_600_000L ? "dd.MM" : (rangeMs <= 3_600_000L ? "HH:mm:ss" : "HH:mm");

        List<PricePoint> pts = new ArrayList<>();
        PricePoint before = null;
        for (PricePoint p : a.data.points) {
            if (p.t < t0) before = p;
            else pts.add(p);
        }
        if (before != null) pts.add(0, before);
        List<Offer> offers = new ArrayList<>();
        for (Offer o : a.data.seen) if (o.first >= t0) offers.add(o);
        List<Sale> sales = new ArrayList<>();
        for (Sale s : a.data.sales) if (s.t >= t0) sales.add(s);
        List<Purchase> buys = new ArrayList<>();
        for (Purchase p : a.data.purchases) if (p.t >= t0) buys.add(p);
        List<double[]> bk = new ArrayList<>(); // {t, cena}
        for (PricePoint p : a.data.points) if (p.bkBuy > 0) bk.add(new double[]{p.t, p.bkBuy});
        for (BkChange ch : bkChanges) if (ch.key.equals(a.data.key) && ch.newBuy > 0) bk.add(new double[]{ch.t, ch.newBuy});
        if (a.bk != null && a.bkBuy() > 0) bk.add(new double[]{now, a.bkBuy()});
        bk.sort((p, q) -> Double.compare(p[0], q[0]));

        // zakres Y (bez skrajnych ofert)
        List<Double> vals = new ArrayList<>();
        for (PricePoint p : pts) {
            add(vals, p.market);
            add(vals, p.min);
            add(vals, p.median);
            add(vals, p.mean);
        }
        add(vals, st.market);
        for (double[] b : bk) if (b[0] >= t0) vals.add(b[1]);
        for (Sale s : sales) vals.add(s.unit);
        for (Purchase p : buys) vals.add(p.unit);
        List<Double> ov = new ArrayList<>();
        for (Offer o : offers) ov.add(o.unit);
        List<Double> ovf = Stats.withoutOutliers(ov);
        vals.addAll(ovf);
        if (vals.isEmpty()) {
            String msg = "Brak danych w tym okresie - otwórz /ah albo uruchom BK";
            c.drawText(tr, msg, x + (w - tr.getWidth(msg)) / 2, y + h / 2 - 4, Theme.MUTED, false);
            return tip;
        }
        double lo = vals.stream().mapToDouble(d -> d).min().orElse(0);
        double hi = vals.stream().mapToDouble(d -> d).max().orElse(1);
        if (hi - lo < hi * 0.02) {
            lo *= 0.97;
            hi *= 1.03;
        }
        double pad = (hi - lo) * 0.08;
        lo = Math.max(0, lo - pad);
        hi += pad;

        int histW = Math.min(70, w / 5);
        int labelW = 0;
        double[] ticks = new double[5];
        for (int i = 0; i < 5; i++) {
            ticks[i] = lo + (hi - lo) * i / 4.0;
            labelW = Math.max(labelW, tr.getWidth(Fmt.compact(ticks[i])));
        }
        int px1 = x + labelW + 6, px2 = x + w - histW - 8, py1 = y + 6, py2 = y + h - 13;
        if (px2 - px1 < 30 || py2 - py1 < 20) return tip;
        final double flo = lo, fhi = hi;
        final int a1 = px1, a2 = px2, b1 = py1, b2 = py2;
        LongToIntFunction X = t -> a1 + (int) Math.round((double) (Math.max(t, t0) - t0) / rangeMs * (a2 - a1));
        DoubleToIntFunction Y = v -> b2 - (int) Math.round((v - flo) / (fhi - flo) * (b2 - b1));

        for (int i = 0; i < 5; i++) {
            int gy = Y.applyAsInt(ticks[i]);
            c.fill(px1, gy, px2, gy + 1, i == 0 ? Theme.BORDER : Theme.GRID);
            String l = Fmt.compact(ticks[i]);
            c.drawText(tr, l, px1 - 4 - tr.getWidth(l), gy - 4, Theme.MUTED, false);
        }
        for (int i = 0; i <= 4; i++) {
            long t = t0 + rangeMs * i / 4;
            String l = Theme.time(t, fmt);
            int lx = Math.max(px1, Math.min(px2 - tr.getWidth(l), X.applyAsInt(t) - tr.getWidth(l) / 2));
            c.drawText(tr, l, lx, py2 + 3, Theme.MUTED, false);
        }

        c.enableScissor(px1, py1 - 3, px2 + 1, py2 + 3);
        // oferty (tło), transakcje
        for (Offer o : offers) {
            if (o.unit > fhi || o.unit < flo) continue;
            int ox = X.applyAsInt(o.first), oy = Y.applyAsInt(o.unit);
            c.fill(ox, oy, ox + 2, oy + 2, Theme.S_OFFER);
        }
        for (Sale s : sales) cross(c, X.applyAsInt(s.t), Y.applyAsInt(s.unit), Theme.S_SALE);

        // serie z historii
        long gap = Math.max(20 * 60_000L, rangeMs / 12);
        series(c, pts, X, Y, 3, gap, Theme.S_MEAN, true);
        series(c, pts, X, Y, 2, gap, Theme.S_MEDIAN, false);
        series(c, pts, X, Y, 1, gap, Theme.S_MIN, false);
        series(c, pts, X, Y, 0, gap, Theme.S_MARKET, false);
        // cena BK (schodki)
        double prev = Double.NaN;
        int prevX = px1;
        for (double[] b : bk) {
            int bx = X.applyAsInt((long) b[0]);
            if (!Double.isNaN(prev)) {
                int py = Y.applyAsInt(prev);
                c.fill(prevX, py - 1, Math.max(prevX + 1, bx), py + 1, Theme.S_BK);
                int ny = Y.applyAsInt(b[1]);
                c.fill(bx - 1, Math.min(py, ny) - 1, bx + 1, Math.max(py, ny) + 1, Theme.S_BK);
            }
            prev = b[1];
            prevX = bx;
        }
        if (!Double.isNaN(prev)) {
            int py = Y.applyAsInt(prev);
            c.fill(prevX, py - 1, px2, py + 1, Theme.S_BK);
        }
        // linia trendu
        if (!Double.isNaN(st.trendB)) {
            long ta = Math.max(t0, st.trendT0);
            double va = st.trendA + st.trendB * (ta - st.trendT0) / 3_600_000.0;
            double vb = st.trendA + st.trendB * (now - st.trendT0) / 3_600_000.0;
            int xa = X.applyAsInt(ta), xb = X.applyAsInt(now);
            int ya = Y.applyAsInt(va), yb = Y.applyAsInt(vb);
            for (int px = xa; px <= xb; px += 4) {
                int py = ya + (int) Math.round((double) (yb - ya) * (px - xa) / Math.max(1, xb - xa));
                c.fill(px, py, px + 2, py + 1, Theme.S_TREND);
            }
        }
        for (Purchase p : buys) {
            int bx = X.applyAsInt(p.t), by = Y.applyAsInt(p.unit);
            c.fill(bx - 4, by - 4, bx + 4, by + 4, Theme.TEXT);
            c.fill(bx - 3, by - 3, bx + 3, by + 3, Theme.GREEN);
        }
        c.disableScissor();

        // histogram przedziałów cen (wspólna oś Y)
        int hx1 = px2 + 6, hx2 = x + w - 2;
        c.fill(hx1, py1, hx1 + 1, py2, Theme.BORDER);
        if (!ovf.isEmpty()) {
            double[][] hg = Stats.histogram(ovf, Math.max(4, Math.min(14, (py2 - py1) / 9)));
            double maxC = 1;
            int best = 0;
            for (int i = 0; i < hg[1].length; i++) {
                if (hg[1][i] > maxC) maxC = hg[1][i];
                if (hg[1][i] > hg[1][best]) best = i;
            }
            for (int i = 0; i < hg[1].length; i++) {
                int ya = Y.applyAsInt(hg[0][i + 1]), yb = Y.applyAsInt(hg[0][i]);
                int len = (int) Math.round((hx2 - hx1 - 3) * hg[1][i] / maxC);
                if (len <= 0) continue;
                int top = Math.max(py1, Math.min(ya, yb) + 1), bot = Math.min(py2, Math.max(ya, yb));
                c.fill(hx1 + 2, top, hx1 + 2 + len, Math.max(top + 1, bot), i == best ? Theme.S_MARKET : 0xFF3A3E47);
            }
            c.drawText(tr, "oferty", hx1 + 3, py2 + 3, Theme.MUTED, false);
        }

        // podpisy przy prawej krawędzi
        if (!Double.isNaN(st.market)) label(c, tr, px2, Y.applyAsInt(st.market), "rynek " + Fmt.compact(st.market), Theme.S_MARKET, py1, py2);
        if (a.bk != null && a.bkBuy() > 0) label(c, tr, px2, Y.applyAsInt(a.bkBuy()) + 10, "BK " + Fmt.compact(a.bkBuy()), Theme.S_BK, py1, py2);

        // dymek
        if (mx >= px1 && mx <= px2 && my >= py1 && my <= py2) {
            c.fill(mx, py1, mx + 1, py2, 0x60B9BCC4);
            long t = t0 + (long) ((double) (mx - px1) / (px2 - px1) * rangeMs);
            int best = 6;
            String[] near = null;
            for (Sale s : sales) {
                int d = Math.abs(X.applyAsInt(s.t) - mx) + Math.abs(Y.applyAsInt(s.unit) - my);
                if (d < best) {
                    best = d;
                    near = new String[]{Theme.time(s.t, "dd.MM HH:mm:ss"), "Transakcja: " + Fmt.money(s.unit) + "/szt × " + s.count,
                            "oferta wisiała " + Math.max(1, Math.round(s.age / 60_000.0)) + " min"};
                }
            }
            for (Purchase p : buys) {
                int d = Math.abs(X.applyAsInt(p.t) - mx) + Math.abs(Y.applyAsInt(p.unit) - my);
                if (d < best) {
                    best = d;
                    near = new String[]{Theme.time(p.t, "dd.MM HH:mm:ss"), "Twój zakup (BK): " + Fmt.money(p.unit) + "/szt × " + p.count};
                }
            }
            for (Offer o : offers) {
                int d = Math.abs(X.applyAsInt(o.first) - mx) + Math.abs(Y.applyAsInt(o.unit) - my);
                if (d < best) {
                    best = d;
                    near = new String[]{Theme.time(o.first, "dd.MM HH:mm:ss"), "Oferta: " + Fmt.money(o.unit) + "/szt × " + o.count,
                            o.seller.isEmpty() ? "" : "sprzedawca " + o.seller};
                }
            }
            if (near != null) {
                for (String s : near) if (!s.isEmpty()) tip.add(s);
            } else {
                tip.add(Theme.time(t, "dd.MM HH:mm"));
                PricePoint p = null;
                for (PricePoint q : pts) if (q.t <= t) p = q;
                if (p != null) {
                    tip.add("Rynkowa: " + Fmt.money(p.market));
                    tip.add("Najniższa: " + Fmt.money(p.min) + "  •  mediana " + Fmt.money(p.median));
                    tip.add("Średnia: " + Fmt.money(p.mean) + "  •  ofert " + p.offers);
                    if (p.bkBuy > 0) tip.add("Cena BK: " + Fmt.money(p.bkBuy));
                } else {
                    tip.add("Cena: " + Fmt.money(flo + (double) (py2 - my) / (py2 - py1) * (fhi - flo)));
                }
            }
        }
        return tip;
    }

    private static void add(List<Double> l, double v) {
        if (!Double.isNaN(v) && v > 0) l.add(v);
    }

    /** field: 0 rynkowa, 1 min, 2 mediana, 3 średnia */
    private static void series(DrawContext c, List<PricePoint> pts, LongToIntFunction X, DoubleToIntFunction Y, int field,
                               long gap, int color, boolean dashed) {
        PricePoint prev = null;
        for (PricePoint p : pts) {
            double v = val(p, field);
            if (Double.isNaN(v) || v <= 0) {
                prev = null;
                continue;
            }
            int x1 = X.applyAsInt(p.t), y1 = Y.applyAsInt(v);
            if (prev != null && p.t - prev.t <= gap) {
                int x0 = X.applyAsInt(prev.t), y0 = Y.applyAsInt(val(prev, field));
                int thick = field == 0 ? 2 : 1;
                for (int cx = x0; cx <= x1; cx++) {
                    if (dashed && ((cx - x0) / 3) % 2 == 1) continue;
                    int cy = x1 == x0 ? y1 : y0 + (int) Math.round((double) (y1 - y0) * (cx - x0) / (x1 - x0));
                    int py = cx == x0 ? y0 : y0 + (int) Math.round((double) (y1 - y0) * (cx - 1 - x0) / Math.max(1, x1 - x0));
                    c.fill(cx, Math.min(py, cy), cx + 1, Math.max(py, cy) + thick, color);
                }
            } else if (field == 0) {
                c.fill(x1 - 1, y1 - 1, x1 + 2, y1 + 2, color);
            }
            prev = p;
        }
    }

    private static double val(PricePoint p, int f) {
        switch (f) {
            case 0: return p.market;
            case 1: return p.min;
            case 2: return p.median;
            default: return p.mean;
        }
    }

    private static void label(DrawContext c, TextRenderer tr, int right, int y, String s, int col, int minY, int maxY) {
        int ty = Math.max(minY, Math.min(maxY - 9, y - 4));
        int tw = tr.getWidth(s);
        c.fill(right - tw - 5, ty - 1, right, ty + 9, 0xE0101114);
        c.fill(right - tw - 5, ty - 1, right - tw - 3, ty + 9, col);
        c.drawText(tr, s, right - tw - 1, ty, Theme.TEXT, false);
    }

    static void cross(DrawContext c, int cx, int cy, int col) {
        for (int i = -2; i <= 2; i++) {
            c.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, col);
            c.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, col);
        }
    }

    public static void tooltip(DrawContext c, TextRenderer tr, List<String> lines, int mx, int my, int sw, int sh) {
        if (lines.isEmpty()) return;
        int w = 0;
        for (String l : lines) w = Math.max(w, tr.getWidth(l));
        int h = lines.size() * 10 + 4;
        int tx = mx + 10, ty = my - h - 4;
        if (tx + w + 8 > sw) tx = mx - w - 16;
        if (ty < 2) ty = my + 12;
        if (ty + h > sh) ty = sh - h - 2;
        c.getMatrices().push();
        c.getMatrices().translate(0f, 0f, 400f);
        c.fill(tx - 1, ty - 1, tx + w + 7, ty + h + 1, Theme.BORDER);
        c.fill(tx, ty, tx + w + 6, ty + h, 0xF20B0C0F);
        for (int i = 0; i < lines.size(); i++) {
            c.drawText(tr, lines.get(i), tx + 3, ty + 3 + i * 10, i == 0 ? Theme.MUTED : Theme.TEXT, false);
        }
        c.getMatrices().pop();
    }
}
