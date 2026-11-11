package pl.anarchia.terminal.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import pl.anarchia.terminal.TerminalMod;
import pl.anarchia.terminal.bk.BkBridge;
import pl.anarchia.terminal.core.BkChange;
import pl.anarchia.terminal.core.Fmt;
import pl.anarchia.terminal.core.ItemStats;
import pl.anarchia.terminal.core.Opportunity;
import pl.anarchia.terminal.core.RecRecord;
import pl.anarchia.terminal.core.Recommendation;
import pl.anarchia.terminal.core.Settings;
import pl.anarchia.terminal.core.Status;
import pl.anarchia.terminal.core.Text2;
import pl.anarchia.terminal.core.Trend;
import pl.anarchia.terminal.market.Engine;
import pl.anarchia.terminal.market.PriceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Terminal rynku: Dashboard, Przedmioty, Wykresy, Okazje, AutoRynek BK, Historia, Ustawienia. */
public final class TerminalScreen extends Screen {
    private enum Tab {
        DASHBOARD("DASHBOARD"), ITEMS("PRZEDMIOTY"), CHARTS("WYKRESY"), OPPS("OKAZJE"), BK("AUTORYNEK BK"),
        HISTORY("HISTORIA"), SETTINGS("USTAWIENIA");
        final String label;

        Tab(String l) {
            label = l;
        }
    }

    private static final long H = 3_600_000L;
    private static final long[] RANGES = {15 * 60_000L, H, 6 * H, 24 * H, 7 * 24 * H};
    private static final String[] RANGE_LABELS = {"15 min", "1 h", "6 h", "24 h", "7 dni"};
    private static final String[] SORTS = {"zysk", "marża", "najniższa cena", "wzrost", "spadek", "liczba ofert", "nazwa"};
    private static final int ROW = 12;
    private static final int PAD = 6;

    private static Tab tab = Tab.DASHBOARD;
    private static String query = "";
    private static int sortIdx = 0;
    private static String selectedKey;
    private static int rangeIdx = 3;
    private static boolean oppByRisk;
    private static boolean histRecs;
    private static final double[] SCROLL = new double[Tab.values().length];

    private final List<int[]> hitBoxes = new ArrayList<>();
    private final List<Runnable> hitActions = new ArrayList<>();
    private List<String> tip = Collections.emptyList();
    private final List<Object[]> labels = new ArrayList<>();
    private String toast = "";
    private long toastUntil;
    private boolean toastOk;
    private int listTop, listBottom, listX1, listX2;

    public TerminalScreen() {
        super(Text.literal("Rynek Terminal"));
    }

    private Settings s() {
        return TerminalMod.SETTINGS;
    }

    private Engine engine() {
        return TerminalMod.ENGINE;
    }

    // ------------------------------------------------------------------ widgety

    @Override
    protected void init() {
        labels.clear();
        if (tab == Tab.ITEMS || tab == Tab.CHARTS || tab == Tab.BK) {
            int w = tab == Tab.CHARTS ? listWidth() - 4 : 150;
            TextFieldWidget f = new TextFieldWidget(textRenderer, PAD + 2, 28, w, 14, Text.literal("Szukaj"));
            f.setMaxLength(40);
            f.setText(query);
            f.setPlaceholder(Text.literal("szukaj przedmiotu…"));
            f.setChangedListener(t -> {
                query = t;
                SCROLL[tab.ordinal()] = 0;
            });
            addDrawableChild(f);
        }
        if (tab == Tab.SETTINGS) initSettings();
    }

    private int listWidth() {
        return Math.max(110, Math.min(170, width / 4));
    }

    private void initSettings() {
        Settings s = s();
        int colW = (width - 2 * PAD - 30) / 2;
        int[] cx = {PAD + 8, PAD + 8 + colW + 14};
        int y0 = 44;
        List<Object[]> f = new ArrayList<>();
        f.add(num("Opłata rynku (%)", s.feePct, v -> v >= 0 && v <= 50, v -> s.feePct = v));
        f.add(num("Min. zysk netto/szt ($)", s.minProfitAbs, v -> v >= 0, v -> s.minProfitAbs = v));
        f.add(num("Min. marża (%)", s.minMarginPct, v -> v >= 0 && v <= 500, v -> s.minMarginPct = v));
        f.add(num("Min. pewność (0-100)", s.minConfidence, v -> v >= 0 && v <= 100, v -> s.minConfidence = (int) Math.round(v)));
        f.add(num("Maks. zmiana ceny (%)", s.maxChangePct, v -> v >= 0.5 && v <= 100, v -> s.maxChangePct = v));
        f.add(num("Auto: co ile minut", s.autoIntervalMin, v -> v >= 1 && v <= 240, v -> s.autoIntervalMin = (int) Math.round(v)));
        f.add(num("Odstęp zmian 1 przedm. (min)", s.cooldownMin, v -> v >= 0 && v <= 1440, v -> s.cooldownMin = (int) Math.round(v)));
        f.add(num("Min. zmiana do propozycji (%)", s.minChangePct, v -> v >= 0 && v <= 50, v -> s.minChangePct = v));
        f.add(num("Okno analizy (h)", s.windowHours, v -> v >= 1 && v <= 168, v -> s.windowHours = (int) Math.round(v)));
        f.add(num("Pamiętaj ofertę (min)", s.offerTtlMin, v -> v >= 10 && v <= 4320, v -> s.offerTtlMin = (int) Math.round(v)));
        f.add(num("Okazja: taniej o (%)", s.discountPct, v -> v >= 1 && v <= 90, v -> s.discountPct = v));
        f.add(num("Okazja: drożej od BK do (%)", s.slightlyAbovePct, v -> v >= 0 && v <= 50, v -> s.slightlyAbovePct = v));
        f.add(num("Min. płynność (transakcje/h)", s.minLiquidity, v -> v >= 0 && v <= 100, v -> s.minLiquidity = v));
        f.add(num("Ocena rekomendacji po (h)", s.evalHorizonH, v -> v >= 1 && v <= 72, v -> s.evalHorizonH = (int) Math.round(v)));
        f.add(num("Historia w pamięci (dni)", s.memoryDays, v -> v >= 1 && v <= 30, v -> s.memoryDays = (int) Math.round(v)));
        f.add(num("Trzymaj pliki bazy (dni)", s.retentionDays, v -> v >= 1 && v <= 365, v -> s.retentionDays = (int) Math.round(v)));
        int rows = (f.size() + 1) / 2;
        int fieldW = 50;
        for (int i = 0; i < f.size(); i++) {
            Object[] o = f.get(i);
            int col = i / rows, row = i % rows;
            int lx = cx[col], fx = lx + colW - fieldW, y = y0 + row * 17;
            labels.add(new Object[]{lx, y + 4, o[0], fx - lx - 4});
            TextFieldWidget tf = new TextFieldWidget(textRenderer, fx, y, fieldW, 14, Text.literal((String) o[0]));
            tf.setMaxLength(12);
            tf.setText((String) o[1]);
            @SuppressWarnings("unchecked") Function<String, Boolean> ap = (Function<String, Boolean>) o[2];
            tf.setChangedListener(t -> {
                boolean ok;
                try {
                    ok = ap.apply(t);
                } catch (RuntimeException e) {
                    ok = false;
                }
                tf.setEditableColor(ok ? 0xFFE0E0E0 : 0xFFFF5555);
                if (ok) {
                    s.fix();
                    TerminalMod.saveSettings();
                    engine().invalidate();
                }
            });
            addDrawableChild(tf);
        }
        int by = y0 + rows * 17 + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("Tryb: " + s.mode.label), b -> {
            Settings.Mode[] m = Settings.Mode.values();
            s.mode = m[(s.mode.ordinal() + 1) % m.length];
            b.setMessage(Text.literal("Tryb: " + s.mode.label));
            TerminalMod.saveSettings();
        }).dimensions(cx[0], by, Math.min(190, colW), 16).build());
        labels.add(new Object[]{cx[0], by + 24, "#help", 0});
    }

    private static Object[] num(String label, double value, java.util.function.DoublePredicate valid, java.util.function.DoubleConsumer set) {
        String v = value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        Function<String, Boolean> ap = t -> {
            double d = Double.parseDouble(t.trim().replace(',', '.').replace("%", "").replace("$", ""));
            if (!valid.test(d)) return false;
            set.accept(d);
            return true;
        };
        return new Object[]{label, v, ap};
    }

    // ------------------------------------------------------------------ rysowanie

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Theme.BG);
        hitBoxes.clear();
        hitActions.clear();
        tip = Collections.emptyList();
        drawTabs(c, mx, my);
        int x1 = PAD, y1 = 24, x2 = width - PAD, y2 = height - 15;
        Theme.panel(c, x1, y1, x2, y2);
        switch (tab) {
            case DASHBOARD: dashboard(c, x1, y1, x2, y2, mx, my); break;
            case ITEMS: items(c, x1, y1, x2, y2, mx, my); break;
            case CHARTS: charts(c, x1, y1, x2, y2, mx, my); break;
            case OPPS: opportunities(c, x1, y1, x2, y2, mx, my); break;
            case BK: bk(c, x1, y1, x2, y2, mx, my); break;
            case HISTORY: history(c, x1, y1, x2, y2, mx, my); break;
            default: settings(c, x1, y1, x2, y2);
        }
        statusBar(c);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        PriceChart.tooltip(c, textRenderer, tip, mx, my, width, height);
    }

    private void drawTabs(DrawContext c, int mx, int my) {
        int x = PAD;
        if (width > 520) {
            Theme.text(c, textRenderer, "RYNEK TERMINAL", x, 8, Theme.ACCENT);
            x += textRenderer.getWidth("RYNEK TERMINAL") + 10;
        }
        int avail = width - x - PAD;
        int total = 0;
        for (Tab t : Tab.values()) total += textRenderer.getWidth(t.label) + 10;
        boolean compact = total > avail;
        for (Tab t : Tab.values()) {
            String l = compact ? t.label.substring(0, Math.min(t.label.length(), t == Tab.BK ? 6 : 5)) : t.label;
            int w = textRenderer.getWidth(l) + 10;
            boolean act = t == tab;
            boolean hov = mx >= x && mx < x + w && my >= 4 && my < 20;
            c.fill(x, 4, x + w, 20, act ? Theme.ROW_SEL : hov ? Theme.ROW_HOVER : Theme.PANEL);
            if (act) c.fill(x, 18, x + w, 20, Theme.ACCENT);
            Theme.text(c, textRenderer, l, x + 5, 8, act ? Theme.TEXT : Theme.TEXT_2);
            final Tab target = t;
            hit(x, 4, x + w, 20, () -> switchTab(target));
            x += w + 2;
        }
    }

    private void switchTab(Tab t) {
        tab = t;
        clearAndInit();
    }

    private void statusBar(DrawContext c) {
        Settings s = s();
        String bk = !BkBridge.ok() ? "BK: brak/niezgodny" : (BkBridge.running() ? "BK: działa" : "BK: zatrzymany");
        String obs = TerminalMod.OBSERVER.watching()
                ? "rynek: na żywo" + (TerminalMod.OBSERVER.orderedView() ? "" : " (włącz sortowanie Najnowsze)")
                : "rynek: " + Theme.ago(TerminalMod.OBSERVER.lastSeenMarket());
        String line = "Tryb: " + s.mode.label + "  •  " + bk + "  •  " + obs + "  •  sesja: +" + TerminalMod.OBSERVER.sessionOffers()
                + " ofert, " + TerminalMod.OBSERVER.sessionSales() + " transakcji";
        if (System.currentTimeMillis() < toastUntil) {
            Theme.fit(c, textRenderer, (toastOk ? "✔ " : "✖ ") + toast, PAD + 2, height - 11, width - 2 * PAD, toastOk ? Theme.GREEN : Theme.RED);
        } else {
            Theme.fit(c, textRenderer, line, PAD + 2, height - 11, width - 2 * PAD, Theme.MUTED);
        }
    }

    private void toast(String msg, boolean ok) {
        toast = msg;
        toastOk = ok;
        toastUntil = System.currentTimeMillis() + 5000;
    }

    private void hit(int x1, int y1, int x2, int y2, Runnable r) {
        hitBoxes.add(new int[]{x1, y1, x2, y2});
        hitActions.add(r);
    }

    private boolean button(DrawContext c, String label, int x, int y, int mx, int my, boolean active, Runnable r) {
        int w = textRenderer.getWidth(label) + 10;
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + 13;
        c.fill(x, y, x + w, y + 13, active ? Theme.ROW_SEL : hov ? Theme.ROW_HOVER : Theme.PANEL_2);
        c.fill(x, y + 12, x + w, y + 13, active ? Theme.ACCENT : Theme.BORDER);
        Theme.text(c, textRenderer, label, x + 5, y + 3, active ? Theme.TEXT : Theme.TEXT_2);
        hit(x, y, x + w, y + 13, r);
        return hov;
    }

    private int buttonW(String label) {
        return textRenderer.getWidth(label) + 10;
    }

    private void statusIcon(DrawContext c, Status st, int x, int y) {
        Theme.text(c, textRenderer, st.icon, x, y, Theme.color(st));
    }

    // ------------------------------------------------------------------ dane

    private List<Engine.Analysis> filtered(boolean bkOnly) {
        List<Engine.Analysis> out = new ArrayList<>();
        String q = Text2.norm(query);
        for (Engine.Analysis a : bkOnly ? engine().bkItems() : engine().all()) {
            if (!q.isEmpty() && !Text2.norm(a.data.name).contains(q)) continue;
            out.add(a);
        }
        out.sort(comparator());
        return out;
    }

    private Comparator<Engine.Analysis> comparator() {
        Comparator<Engine.Analysis> byName = Comparator.comparing(a -> Text2.norm(a.data.name));
        switch (sortIdx) {
            case 0: return Comparator.comparingDouble((Engine.Analysis a) -> desc(a.rec.valid ? a.rec.net : Double.NaN)).thenComparing(byName);
            case 1: return Comparator.comparingDouble((Engine.Analysis a) -> desc(a.rec.valid ? a.rec.margin : Double.NaN)).thenComparing(byName);
            case 2: return Comparator.comparingDouble((Engine.Analysis a) -> asc(a.stats.activeMin)).thenComparing(byName);
            case 3: return Comparator.comparingDouble((Engine.Analysis a) -> desc(change(a.stats))).thenComparing(byName);
            case 4: return Comparator.comparingDouble((Engine.Analysis a) -> asc(change(a.stats))).thenComparing(byName);
            case 5: return Comparator.comparingDouble((Engine.Analysis a) -> -a.stats.activeCount).thenComparing(byName);
            default: return byName;
        }
    }

    private static double change(ItemStats st) {
        return !Double.isNaN(st.change24h) ? st.change24h * 100 : st.trendPctPerHour;
    }

    private static double desc(double v) {
        return Double.isNaN(v) ? Double.MAX_VALUE : -v;
    }

    private static double asc(double v) {
        return Double.isNaN(v) ? Double.MAX_VALUE : v;
    }

    private void select(String key, boolean openChart) {
        selectedKey = key;
        if (openChart) switchTab(Tab.CHARTS);
    }

    // ------------------------------------------------------------------ DASHBOARD

    private void dashboard(DrawContext c, int x1, int y1, int x2, int y2, int mx, int my) {
        List<Engine.Analysis> all = engine().all();
        long now = System.currentTimeMillis();
        int bkN = 0, offers = 0, sales = 0;
        for (Engine.Analysis a : all) {
            if (a.bk != null) bkN++;
            offers += a.stats.windowOffers;
            sales += a.stats.sales;
        }
        int[] eff = engine().effectiveness();
        String[][] cards = {
                {"Przedmioty z AutoRynku BK", all.size() + "", "z ofertami na rynku: " + withOffers(all)},
                {"Oferty (" + s().windowHours + " h)", offers + "", "nowe oferty zauważone na /ah"},
                {"Transakcje (" + s().windowHours + " h)", sales + "", "oferty kupione przez graczy"},
                {"Skuteczność rekomendacji", eff[1] == 0 ? "-" : Fmt.pct((double) eff[0] / eff[1], false), eff[0] + " trafnych z " + eff[1]}
        };
        int cw = (x2 - x1 - 10 - 3 * 6) / 4;
        for (int i = 0; i < 4; i++) {
            int cx = x1 + 5 + i * (cw + 6);
            c.fill(cx, y1 + 5, cx + cw, y1 + 37, Theme.PANEL_2);
            c.fill(cx, y1 + 5, cx + 2, y1 + 37, Theme.ACCENT);
            Theme.fit(c, textRenderer, cards[i][0], cx + 6, y1 + 8, cw - 8, Theme.MUTED);
            Theme.fit(c, textRenderer, cards[i][1], cx + 6, y1 + 18, cw - 8, Theme.TEXT);
            Theme.fit(c, textRenderer, cards[i][2], cx + 6, y1 + 28, cw - 8, Theme.MUTED);
        }

        int top = y1 + 44;
        int mid = x1 + (x2 - x1) * 3 / 5;
        Theme.text(c, textRenderer, "Najlepsze okazje", x1 + 6, top, Theme.TEXT);
        List<Opportunity> ops = engine().opportunities(false);
        int y = top + 12;
        int maxRows = Math.max(1, (y2 - 50 - y) / ROW);
        if (ops.isEmpty()) Theme.text(c, textRenderer, "Brak - zbieram dane z rynku", x1 + 6, y, Theme.MUTED);
        for (int i = 0; i < Math.min(maxRows, ops.size()); i++) {
            Opportunity o = ops.get(i);
            boolean hov = mx >= x1 + 4 && mx < mid - 4 && my >= y - 1 && my < y + ROW - 1;
            if (hov) c.fill(x1 + 4, y - 2, mid - 4, y + ROW - 2, Theme.ROW_HOVER);
            Theme.text(c, textRenderer, "▲", x1 + 6, y, Theme.GREEN);
            Theme.fit(c, textRenderer, o.name, x1 + 16, y, mid - x1 - 150, Theme.TEXT);
            Theme.right(c, textRenderer, Fmt.money(o.offer), mid - 92, y, Theme.TEXT_2);
            Theme.right(c, textRenderer, "+" + Fmt.compact(o.profit), mid - 44, y, Theme.GREEN);
            Theme.right(c, textRenderer, Fmt.pct(o.margin, false), mid - 8, y, Theme.TEXT_2);
            if (hov) tip = List.of(o.kind, o.justification, "Ryzyko " + o.risk + " • pewność " + o.confidence + "%");
            final String k = o.key;
            hit(x1 + 4, y - 2, mid - 4, y + ROW - 2, () -> select(k, true));
            y += ROW;
        }

        Theme.text(c, textRenderer, "Trendy cen", mid + 6, top, Theme.TEXT);
        List<Engine.Analysis> rising = new ArrayList<>(), falling = new ArrayList<>();
        for (Engine.Analysis a : all) {
            if (a.stats.trend == Trend.RISING) rising.add(a);
            if (a.stats.trend == Trend.FALLING) falling.add(a);
        }
        rising.sort(Comparator.comparingDouble((Engine.Analysis a) -> -a.stats.trendPctPerHour));
        falling.sort(Comparator.comparingDouble((Engine.Analysis a) -> a.stats.trendPctPerHour));
        y = top + 12;
        int half = Math.max(1, maxRows / 2);
        for (int i = 0; i < Math.min(half, rising.size()); i++, y += ROW) trendRow(c, rising.get(i), mid + 6, x2 - 6, y, mx, my, true);
        if (rising.isEmpty()) {
            Theme.text(c, textRenderer, "▲ brak wyraźnych wzrostów", mid + 6, y, Theme.MUTED);
            y += ROW;
        }
        for (int i = 0; i < Math.min(half, falling.size()); i++, y += ROW) trendRow(c, falling.get(i), mid + 6, x2 - 6, y, mx, my, false);
        if (falling.isEmpty()) Theme.text(c, textRenderer, "▼ brak wyraźnych spadków", mid + 6, y, Theme.MUTED);

        int by = y2 - 44;
        c.fill(x1 + 4, by - 4, x2 - 4, by - 3, Theme.BORDER);
        Theme.text(c, textRenderer, "Ostatnie zmiany w AutoRynku BK", x1 + 6, by, Theme.TEXT);
        List<BkChange> ch = TerminalMod.DB.bkChanges;
        if (ch.isEmpty()) Theme.text(c, textRenderer, "brak", x1 + 6, by + 11, Theme.MUTED);
        for (int i = 0; i < Math.min(3, ch.size()); i++) {
            BkChange b = ch.get(ch.size() - 1 - i);
            Theme.fit(c, textRenderer, Theme.time(b.t, "dd.MM HH:mm") + "  " + b.name + ": skup " + Fmt.money(b.oldBuy) + " → "
                    + Fmt.money(b.newBuy) + ", sprzedaż " + Fmt.money(b.oldSell) + " → " + Fmt.money(b.newSell) + "  (" + b.mode + ")",
                    x1 + 6, by + 11 + i * 10, x2 - x1 - 12, Theme.TEXT_2);
        }
        if (now - TerminalMod.OBSERVER.lastSeenMarket() > 10 * 60_000L) {
            Theme.right(c, textRenderer, "Dane zbierają się, gdy okno /ah jest otwarte (Ty albo BK-Rynek)", x2 - 6, by, Theme.MUTED);
        }
    }

    private static int withOffers(List<Engine.Analysis> all) {
        int n = 0;
        for (Engine.Analysis a : all) if (a.stats.activeCount > 0 || a.stats.windowOffers > 0) n++;
        return n;
    }

    private void trendRow(DrawContext c, Engine.Analysis a, int x1, int x2, int y, int mx, int my, boolean up) {
        boolean hov = mx >= x1 - 2 && mx < x2 && my >= y - 1 && my < y + ROW - 1;
        if (hov) c.fill(x1 - 2, y - 2, x2, y + ROW - 2, Theme.ROW_HOVER);
        Theme.text(c, textRenderer, up ? "▲" : "▼", x1, y, up ? Theme.YELLOW : Theme.RED);
        Theme.fit(c, textRenderer, a.data.name, x1 + 10, y, x2 - x1 - 90, Theme.TEXT);
        Theme.right(c, textRenderer, Fmt.pct(a.stats.trendPctPerHour / 100, true) + "/h", x2 - 2, y, up ? Theme.YELLOW : Theme.RED);
        final String k = a.data.key;
        hit(x1 - 2, y - 2, x2, y + ROW - 2, () -> select(k, true));
    }

    // ------------------------------------------------------------------ PRZEDMIOTY

    private void sortControl(DrawContext c, int x, int y, int mx, int my) {
        String l = "Sortuj: " + SORTS[sortIdx] + " ▾";
        button(c, l, x, y, mx, my, false, () -> {
            sortIdx = (sortIdx + 1) % SORTS.length;
            SCROLL[tab.ordinal()] = 0;
        });
    }

    private void items(DrawContext c, int x1, int y1, int x2, int y2, int mx, int my) {
        sortControl(c, x1 + 162, y1 + 4, mx, my);
        List<Engine.Analysis> list = filtered(false);
        Theme.right(c, textRenderer, list.size() + " przedmiotów", x2 - 6, y1 + 7, Theme.MUTED);
        int ty = y1 + 22;
        boolean wide = x2 - x1 > 470;
        int[] colR = wide ? new int[]{x2 - 6, x2 - 50, x2 - 104, x2 - 152, x2 - 186, x2 - 236, x2 - 286, x2 - 336}
                : new int[]{x2 - 6, x2 - 46, x2 - 96, x2 - 140, x2 - 172, x2 - 216, x2 - 262};
        String[] head = wide ? new String[]{"Marża", "Zysk/szt", "Trend/h", "Oferty", "Mediana", "Najniższa", "Rynkowa"}
                : new String[]{"Marża", "Zysk/szt", "Trend/h", "Oferty", "Najniższa", "Rynkowa"};
        Theme.text(c, textRenderer, "Nazwa", x1 + 16, ty, Theme.MUTED);
        for (int i = 0; i < head.length; i++) Theme.right(c, textRenderer, head[i], colR[i], ty, Theme.MUTED);
        c.fill(x1 + 3, ty + 10, x2 - 3, ty + 11, Theme.BORDER);
        int rowsTop = ty + 13;
        int vis = Math.max(1, (y2 - 4 - rowsTop) / ROW);
        int sc = clampScroll(list.size(), vis);
        for (int i = 0; i < vis && i + sc < list.size(); i++) {
            Engine.Analysis a = list.get(i + sc);
            int y = rowsTop + i * ROW;
            boolean hov = mx >= x1 + 2 && mx < x2 - 2 && my >= y - 1 && my < y + ROW - 1;
            c.fill(x1 + 2, y - 2, x2 - 2, y + ROW - 2, hov ? Theme.ROW_HOVER : (i % 2 == 0 ? Theme.PANEL : Theme.ROW_ALT));
            statusIcon(c, a.rec.status, x1 + 5, y);
            Theme.fit(c, textRenderer, a.data.name + (a.bk != null ? " ⓑ" : ""), x1 + 16, y, colR[colR.length - 1] - x1 - 60, Theme.TEXT);
            ItemStats st = a.stats;
            Recommendation r = a.rec;
            String[] v = wide
                    ? new String[]{r.valid ? Fmt.pct(r.margin, false) : "-", r.valid ? Fmt.compact(r.net) : "-", trendTxt(st),
                    String.valueOf(st.activeCount), Fmt.compact(st.activeMedian), Fmt.compact(st.activeMin), Fmt.compact(st.market)}
                    : new String[]{r.valid ? Fmt.pct(r.margin, false) : "-", r.valid ? Fmt.compact(r.net) : "-", trendTxt(st),
                    String.valueOf(st.activeCount), Fmt.compact(st.activeMin), Fmt.compact(st.market)};
            for (int k = 0; k < v.length; k++) {
                int col = Theme.TEXT_2;
                if (k == 2) col = st.trend == Trend.RISING ? Theme.YELLOW : st.trend == Trend.FALLING ? Theme.RED : Theme.TEXT_2;
                if (k == 1 && r.valid) col = Theme.GREEN;
                Theme.right(c, textRenderer, v[k], colR[k], y, col);
            }
            if (hov) tip = List.of(a.data.name, a.rec.status.icon + " " + a.rec.status.label,
                    r.valid ? "Kup do " + Fmt.money(r.buy) + ", sprzedaj po " + Fmt.money(r.sell) : r.reason, "Kliknij: wykres");
            final String k = a.data.key;
            hit(x1 + 2, y - 2, x2 - 2, y + ROW - 2, () -> select(k, true));
        }
        scrollbar(c, x2 - 3, rowsTop, y2 - 4, list.size(), vis, sc);
        listTop = rowsTop;
        listBottom = y2;
        listX1 = x1;
        listX2 = x2;
    }

    private static String trendTxt(ItemStats st) {
        if (Double.isNaN(st.trendPctPerHour)) return "-";
        String a = st.trend == Trend.RISING ? "▲" : st.trend == Trend.FALLING ? "▼" : "■";
        return a + Fmt.pct(st.trendPctPerHour / 100, true);
    }

    private int clampScroll(int total, int vis) {
        int i = tab.ordinal();
        SCROLL[i] = Math.max(0, Math.min(SCROLL[i], Math.max(0, total - vis)));
        return (int) SCROLL[i];
    }

    private void scrollbar(DrawContext c, int x, int top, int bottom, int total, int vis, int sc) {
        if (total <= vis) return;
        int h = bottom - top;
        int bh = Math.max(10, h * vis / total);
        int by = top + (int) ((h - bh) * (sc / (double) Math.max(1, total - vis)));
        c.fill(x, by, x + 2, by + bh, Theme.BORDER);
    }

    // ------------------------------------------------------------------ WYKRESY

    private void charts(DrawContext c, int x1, int y1, int x2, int y2, int mx, int my) {
        int lw = listWidth();
        List<Engine.Analysis> list = filtered(false);
        int rowsTop = y1 + 22;
        int vis = Math.max(1, (y2 - 4 - rowsTop) / ROW);
        int sc = clampScroll(list.size(), vis);
        c.fill(x1 + lw + 4, y1 + 1, x1 + lw + 5, y2 - 1, Theme.BORDER);
        Engine.Analysis sel = null;
        for (Engine.Analysis a : list) if (a.data.key.equals(selectedKey)) sel = a;
        if (sel == null && selectedKey != null) sel = engine().get(selectedKey);
        if (sel == null && !list.isEmpty()) {
            sel = list.get(0);
            selectedKey = sel.data.key;
        }
        for (int i = 0; i < vis && i + sc < list.size(); i++) {
            Engine.Analysis a = list.get(i + sc);
            int y = rowsTop + i * ROW;
            boolean isSel = a == sel;
            boolean hov = mx >= x1 + 2 && mx < x1 + lw + 2 && my >= y - 1 && my < y + ROW - 1;
            if (isSel || hov) c.fill(x1 + 2, y - 2, x1 + lw + 2, y + ROW - 2, isSel ? Theme.ROW_SEL : Theme.ROW_HOVER);
            statusIcon(c, a.rec.status, x1 + 4, y);
            Theme.fit(c, textRenderer, a.data.name, x1 + 14, y, lw - 14, isSel ? Theme.TEXT : Theme.TEXT_2);
            final String k = a.data.key;
            hit(x1 + 2, y - 2, x1 + lw + 2, y + ROW - 2, () -> selectedKey = k);
        }
        scrollbar(c, x1 + lw + 1, rowsTop, y2 - 4, list.size(), vis, sc);
        listTop = rowsTop;
        listBottom = y2;
        listX1 = x1;
        listX2 = x1 + lw + 4;
        if (sel == null) {
            Theme.text(c, textRenderer, "Brak danych - otwórz /ah albo uruchom BK-Rynek.", x1 + lw + 12, y1 + 10, Theme.MUTED);
            return;
        }

        int cx1 = x1 + lw + 10, cx2 = x2 - 6;
        ItemStats st = sel.stats;
        Recommendation r = sel.rec;
        Theme.fit(c, textRenderer, sel.data.name, cx1, y1 + 6, cx2 - cx1 - 200, Theme.TEXT);
        String status = r.status.icon + " " + r.status.label;
        Theme.right(c, textRenderer, status, cx2, y1 + 6, Theme.color(r.status));
        int bx = cx1;
        for (int i = 0; i < RANGES.length; i++) {
            final int idx = i;
            button(c, RANGE_LABELS[i], bx, y1 + 18, mx, my, i == rangeIdx, () -> rangeIdx = idx);
            bx += buttonW(RANGE_LABELS[i]) + 3;
        }
        int lh = PriceChart.legendHeight(textRenderer, cx2 - cx1);
        PriceChart.legend(c, textRenderer, cx1, y1 + 35, cx2 - cx1);
        int chartTop = y1 + 37 + lh;
        int infoH = 52;
        int chartBottom = y2 - infoH - 4;
        List<String> t = PriceChart.draw(c, textRenderer, cx1, chartTop, cx2 - cx1, Math.max(40, chartBottom - chartTop), sel,
                TerminalMod.DB.bkChanges, RANGES[rangeIdx], mx, my);
        if (!t.isEmpty()) tip = t;

        int y = chartBottom + 4;
        int w = cx2 - cx1;
        Theme.fit(c, textRenderer, "Rynkowa " + Fmt.money(st.market) + " (" + st.marketSource + ")  •  najniższa " + Fmt.money(st.activeMin)
                + "  •  najwyższa " + Fmt.money(st.activeMax) + "  •  średnia " + Fmt.money(st.activeMean) + "  •  mediana "
                + Fmt.money(st.activeMedian) + "  •  ofert " + st.activeCount, cx1, y, w, Theme.TEXT_2);
        y += 10;
        String liquidity = st.fastSelling ? "schodzi szybko" : st.slowMoving ? "zalega na rynku" : "płynność normalna";
        Theme.fit(c, textRenderer, "Trend: " + st.trend.label + " " + trendTxt(st) + (Double.isNaN(st.trendR2) ? "" : " (R² " + Fmt.num1(st.trendR2) + ")")
                + "  •  24 h " + Fmt.pct(st.change24h, true) + "  •  zmienność " + Fmt.pct(st.volatility, false)
                + "  •  transakcje/h " + Fmt.num1(st.salesPerHour) + " (" + liquidity + ")"
                + (Double.isNaN(st.modeLo) ? "" : "  •  najczęściej " + Fmt.compact(st.modeLo) + "–" + Fmt.compact(st.modeHi)),
                cx1, y, w, Theme.TEXT_2);
        y += 10;
        int col = r.valid ? Theme.GREEN : Theme.MUTED;
        Theme.fit(c, textRenderer, r.valid
                ? "Rekomendacja: kup do " + Fmt.money(r.buy) + ", sprzedaj po " + Fmt.money(r.sell) + "  •  zysk " + Fmt.money(r.net)
                + "/szt (" + Fmt.pct(r.margin, false) + ")  •  opłata " + Fmt.money(r.fees) + "  •  ryzyko " + r.risk + " (" + r.riskLabel
                + ")  •  pewność " + r.confidence + "%"
                : "Brak rekomendacji: " + r.reason + "  •  ryzyko " + r.risk + "  •  pewność " + r.confidence + "%", cx1, y, w, col);
        y += 10;
        StringBuilder q = new StringBuilder("Zysk przy ilości: ");
        for (double[] qq : r.quantities) {
            q.append((int) qq[0]).append(" szt → ").append(Fmt.money(qq[1]));
            if (!Double.isNaN(qq[2])) q.append(" (~").append(Fmt.num1(qq[2])).append(" h)");
            q.append("   ");
        }
        Theme.fit(c, textRenderer, r.quantities.isEmpty() ? "" : q.toString(), cx1, y, w, Theme.TEXT_2);
        y += 10;
        Theme.fit(c, textRenderer, "Uzasadnienie: " + (r.why.isEmpty() ? "-" : String.join("; ", r.why)), cx1, y, w, Theme.MUTED);
    }

    // ------------------------------------------------------------------ OKAZJE

    private void opportunities(DrawContext c, int x1, int y1, int x2, int y2, int mx, int my) {
        Theme.text(c, textRenderer, "Okazje", x1 + 6, y1 + 7, Theme.TEXT);
        int bx = x1 + 60;
        button(c, "Sortuj: zysk", bx, y1 + 4, mx, my, !oppByRisk, () -> oppByRisk = false);
        bx += buttonW("Sortuj: zysk") + 3;
        button(c, "zysk ÷ ryzyko", bx, y1 + 4, mx, my, oppByRisk, () -> oppByRisk = true);
        List<Opportunity> ops = engine().opportunities(oppByRisk);
        Theme.right(c, textRenderer, ops.size() + " okazji", x2 - 6, y1 + 7, Theme.MUTED);
        int ty = y1 + 22;
        int[] colR = {x2 - 6, x2 - 46, x2 - 94, x2 - 146, x2 - 198, x2 - 250, x2 - 302};
        String[] head = {"Ryzyko", "Marża", "Zysk/szt", "Sprzedaj", "Kup do", "Oferta", "Rynkowa"};
        Theme.text(c, textRenderer, "Przedmiot / uzasadnienie", x1 + 16, ty, Theme.MUTED);
        for (int i = 0; i < head.length; i++) Theme.right(c, textRenderer, head[i], colR[i], ty, Theme.MUTED);
        c.fill(x1 + 3, ty + 10, x2 - 3, ty + 11, Theme.BORDER);
        int rowsTop = ty + 13, rh = 22;
        int vis = Math.max(1, (y2 - 4 - rowsTop) / rh);
        int sc = clampScroll(ops.size(), vis);
        if (ops.isEmpty()) Theme.text(c, textRenderer, "Brak okazji spełniających progi (zysk, marża, pewność) - zbieram dane.", x1 + 8, rowsTop + 4, Theme.MUTED);
        for (int i = 0; i < vis && i + sc < ops.size(); i++) {
            Opportunity o = ops.get(i + sc);
            int y = rowsTop + i * rh;
            boolean hov = mx >= x1 + 2 && mx < x2 - 2 && my >= y - 2 && my < y + rh - 2;
            c.fill(x1 + 2, y - 2, x2 - 2, y + rh - 2, hov ? Theme.ROW_HOVER : (i % 2 == 0 ? Theme.PANEL : Theme.ROW_ALT));
            Theme.text(c, textRenderer, "▲", x1 + 5, y, Theme.GREEN);
            Theme.fit(c, textRenderer, o.name + "  [" + o.kind + "]", x1 + 16, y, colR[6] - x1 - 60, Theme.TEXT);
            String[] v = {o.risk + "", Fmt.pct(o.margin, false), "+" + Fmt.compact(o.profit), Fmt.compact(o.sell), Fmt.compact(o.buy),
                    Fmt.compact(o.offer), Fmt.compact(o.market)};
            for (int k = 0; k < v.length; k++) {
                int col = k == 2 ? Theme.GREEN : k == 0 ? (o.risk >= 50 ? Theme.RED : o.risk >= 25 ? Theme.YELLOW : Theme.TEXT_2) : Theme.TEXT_2;
                Theme.right(c, textRenderer, v[k], colR[k], y, col);
            }
            Theme.fit(c, textRenderer, o.justification, x1 + 16, y + 10, x2 - x1 - 24, Theme.MUTED);
            if (hov) tip = List.of(o.name, o.justification, "Pewność " + o.confidence + "% • kliknij: wykres");
            final String k = o.key;
            hit(x1 + 2, y - 2, x2 - 2, y + rh - 2, () -> select(k, true));
        }
        scrollbar(c, x2 - 3, rowsTop, y2 - 4, ops.size(), vis, sc);
        listTop = rowsTop;
        listBottom = y2;
        listX1 = x1;
        listX2 = x2;
    }

    // ------------------------------------------------------------------ AUTORYNEK BK

    private void bk(DrawContext c, int x1, int y1, int x2, int y2, int mx, int my) {
        Settings s = s();
        PriceManager pm = TerminalMod.PRICES;
        int bx = x1 + 162;
        for (Settings.Mode m : Settings.Mode.values()) {
            final Settings.Mode mm = m;
            if (button(c, m.label, bx, y1 + 4, mx, my, s.mode == m, () -> {
                s.mode = mm;
                TerminalMod.saveSettings();
                toast("Tryb: " + mm.label + " - " + mm.desc, true);
            })) tip = List.of(m.label, m.desc);
            bx += buttonW(m.label) + 3;
        }
        if (!BkBridge.ok()) {
            Theme.text(c, textRenderer, "Nie wykryto zgodnego BK-Rynek 3.0 - integracja wyłączona.", x1 + 8, y1 + 26, Theme.RED);
            Theme.fit(c, textRenderer, BkBridge.error(), x1 + 8, y1 + 38, x2 - x1 - 16, Theme.MUTED);
            return;
        }
        List<Engine.Analysis> list = filtered(true);
        List<PriceManager.Proposal> props = new ArrayList<>();
        int ready = 0;
        for (Engine.Analysis a : list) {
            PriceManager.Proposal p = pm.propose(a, s.mode == Settings.Mode.AUTO);
            props.add(p);
            if (p != null && p.ok()) ready++;
        }
        int ay = y1 + 20;
        String info = s.mode == Settings.Mode.ANALIZA ? "Tryb analizy: ceny w BK nie są zmieniane."
                : s.mode == Settings.Mode.REKOMENDACJE ? "Propozycje do zatwierdzenia: " + ready
                : "Automatycznie co " + s.autoIntervalMin + " min • ostatnio: " + pm.lastAutoInfo();
        Theme.fit(c, textRenderer, info, x1 + 8, ay + 3, x2 - x1 - 200, Theme.TEXT_2);
        if (s.mode == Settings.Mode.REKOMENDACJE && ready > 0) {
            String l = "✔ Zatwierdź wszystkie (" + ready + ")";
            final List<PriceManager.Proposal> fp = props;
            button(c, l, x2 - 6 - buttonW(l), ay, mx, my, false, () -> {
                int n = 0;
                for (PriceManager.Proposal p : fp) {
                    if (p != null && p.ok()) {
                        String rr = pm.apply(p, "zatwierdzone");
                        if (!rr.startsWith("Nie") && !rr.startsWith("Zabl")) n++;
                    }
                }
                toast("Zatwierdzono zmiany: " + n, n > 0);
            });
        }
        int ty = ay + 18;
        int[] colR = {x2 - 6, x2 - 92, x2 - 118, x2 - 150, x2 - 196, x2 - 280};
        String[] head = {"Akcje", "Auto", "Pewn.", "Zysk/szt", "Sprzedaż BK → nowa", "Skup BK → nowy"};
        Theme.text(c, textRenderer, "Przedmiot", x1 + 16, ty, Theme.MUTED);
        for (int i = 0; i < head.length; i++) Theme.right(c, textRenderer, head[i], colR[i], ty, Theme.MUTED);
        c.fill(x1 + 3, ty + 10, x2 - 3, ty + 11, Theme.BORDER);
        int rowsTop = ty + 13;
        int vis = Math.max(1, (y2 - 4 - rowsTop) / ROW);
        int sc = clampScroll(list.size(), vis);
        for (int i = 0; i < vis && i + sc < list.size(); i++) {
            Engine.Analysis a = list.get(i + sc);
            PriceManager.Proposal p = props.get(i + sc);
            int y = rowsTop + i * ROW;
            boolean hov = mx >= x1 + 2 && mx < x2 - 2 && my >= y - 1 && my < y + ROW - 1;
            c.fill(x1 + 2, y - 2, x2 - 2, y + ROW - 2, hov ? Theme.ROW_HOVER : (i % 2 == 0 ? Theme.PANEL : Theme.ROW_ALT));
            statusIcon(c, a.rec.status, x1 + 5, y);
            Theme.fit(c, textRenderer, a.data.name, x1 + 16, y, colR[5] - x1 - 110, Theme.TEXT);
            String buy = Fmt.compact(a.bkBuy()) + (p != null && p.buyChange ? " → " + Fmt.compact(p.newBuy) : "");
            String sell = Fmt.compact(a.bkSell()) + (p != null && p.sellChange ? " → " + Fmt.compact(p.newSell) : "");
            Theme.right(c, textRenderer, buy, colR[5], y, p != null && p.buyChange ? (p.newBuy > a.bkBuy() ? Theme.YELLOW : Theme.BLUE) : Theme.TEXT_2);
            Theme.right(c, textRenderer, sell, colR[4], y, p != null && p.sellChange ? Theme.YELLOW : Theme.TEXT_2);
            Theme.right(c, textRenderer, a.rec.valid ? Fmt.compact(a.rec.net) : "-", colR[3], y, a.rec.valid ? Theme.GREEN : Theme.MUTED);
            Theme.right(c, textRenderer, a.rec.confidence + "%", colR[2], y, Theme.TEXT_2);
            boolean excluded = s.autoExcluded.contains(a.data.key);
            String auto = excluded ? "✖" : "✔";
            int ax = colR[1] - textRenderer.getWidth(auto);
            Theme.text(c, textRenderer, auto, ax, y, excluded ? Theme.RED : Theme.GREEN);
            final String key = a.data.key;
            hit(ax - 4, y - 2, colR[1] + 4, y + ROW - 2, () -> {
                if (!s.autoExcluded.remove(key)) s.autoExcluded.add(key);
                TerminalMod.saveSettings();
            });
            if (p != null && s.mode == Settings.Mode.REKOMENDACJE && p.ok()) {
                int okX = colR[0] - textRenderer.getWidth("✔ ✖") - 6;
                Theme.text(c, textRenderer, "✔", okX, y, Theme.GREEN);
                Theme.text(c, textRenderer, "✖", okX + 12, y, Theme.RED);
                final PriceManager.Proposal fp = p;
                hit(okX - 3, y - 2, okX + 8, y + ROW - 2, () -> {
                    String rr = pm.apply(fp, "zatwierdzone");
                    toast(rr, !rr.startsWith("Nie") && !rr.startsWith("Zabl"));
                });
                hit(okX + 9, y - 2, okX + 20, y + ROW - 2, () -> {
                    pm.reject(fp);
                    toast("Odrzucono propozycję: " + a.data.name, true);
                });
            } else if (p != null && !p.block.isEmpty()) {
                Theme.right(c, textRenderer, "⚠", colR[0], y, Theme.YELLOW);
            }
            if (hov) {
                List<String> tl = new ArrayList<>();
                tl.add(a.data.name);
                tl.add("Teraz: skup " + Fmt.money(a.bkBuy()) + ", sprzedaż " + Fmt.money(a.bkSell()));
                tl.add(a.rec.valid ? "Rekomendacja: kup do " + Fmt.money(a.rec.buy) + ", sprzedaj po " + Fmt.money(a.rec.sell) : "Brak rekomendacji: " + a.rec.reason);
                if (p != null) {
                    tl.add("Propozycja: skup " + Fmt.money(p.newBuy) + ", sprzedaż " + Fmt.money(p.newSell)
                            + (p.limited ? " (ograniczona do " + Fmt.num1(s.maxChangePct) + "%)" : ""));
                    if (!p.block.isEmpty()) tl.add("Zablokowane: " + p.block);
                }
                tl.add("Auto dla przedmiotu: " + (excluded ? "wyłączone" : "włączone") + " (kliknij ✔/✖)");
                tip = tl;
            }
        }
        scrollbar(c, x2 - 3, rowsTop, y2 - 4, list.size(), vis, sc);
        listTop = rowsTop;
        listBottom = y2;
        listX1 = x1;
        listX2 = x2;
    }

    // ------------------------------------------------------------------ HISTORIA

    private void history(DrawContext c, int x1, int y1, int x2, int y2, int mx, int my) {
        int bx = x1 + 6;
        button(c, "Zmiany w BK", bx, y1 + 4, mx, my, !histRecs, () -> histRecs = false);
        bx += buttonW("Zmiany w BK") + 3;
        button(c, "Rekomendacje", bx, y1 + 4, mx, my, histRecs, () -> histRecs = true);
        String exp = "Eksport CSV";
        int ex = x2 - 6 - buttonW(exp);
        button(c, exp, ex, y1 + 4, mx, my, false, () -> {
            try {
                java.nio.file.Path dir = TerminalMod.DB.exportCsv();
                toast("Zapisano CSV: " + dir, true);
            } catch (Exception e) {
                toast("Eksport nieudany: " + e.getMessage(), false);
            }
        });
        String rb = "↶ Cofnij ostatnią zmianę";
        button(c, rb, ex - 4 - buttonW(rb), y1 + 4, mx, my, false, () -> {
            String r = TerminalMod.PRICES.rollbackLast();
            toast(r, r.startsWith("Cofnięto"));
        });
        int ty = y1 + 22;
        int rowsTop = ty + 13;
        int vis = Math.max(1, (y2 - 4 - rowsTop) / ROW);
        if (!histRecs) {
            List<BkChange> ch = TerminalMod.DB.bkChanges;
            Theme.text(c, textRenderer, "Czas / przedmiot", x1 + 6, ty, Theme.MUTED);
            Theme.right(c, textRenderer, "Skup przed → po      Sprzedaż przed → po      Tryb", x2 - 6, ty, Theme.MUTED);
            c.fill(x1 + 3, ty + 10, x2 - 3, ty + 11, Theme.BORDER);
            int sc = clampScroll(ch.size(), vis);
            if (ch.isEmpty()) Theme.text(c, textRenderer, "Brak zmian.", x1 + 8, rowsTop, Theme.MUTED);
            for (int i = 0; i < vis && i + sc < ch.size(); i++) {
                BkChange b = ch.get(ch.size() - 1 - (i + sc));
                int y = rowsTop + i * ROW;
                Theme.fit(c, textRenderer, Theme.time(b.t, "dd.MM HH:mm") + "  " + b.name, x1 + 6, y, x2 - x1 - 280, Theme.TEXT);
                Theme.right(c, textRenderer, Fmt.compact(b.oldBuy) + " → " + Fmt.compact(b.newBuy) + "      " + Fmt.compact(b.oldSell) + " → "
                        + Fmt.compact(b.newSell) + "      " + b.mode, x2 - 6, y, Theme.TEXT_2);
                if (mx >= x1 && mx < x2 && my >= y - 1 && my < y + ROW - 1) tip = List.of(b.name, "Powód: " + b.reason);
            }
            scrollbar(c, x2 - 3, rowsTop, y2 - 4, ch.size(), vis, sc);
        } else {
            List<RecRecord> rs = TerminalMod.DB.recs;
            int[] eff = engine().effectiveness();
            Theme.fit(c, textRenderer, "Skuteczność: " + (eff[1] == 0 ? "brak ocenionych" : Fmt.pct((double) eff[0] / eff[1], false)
                    + " (" + eff[0] + "/" + eff[1] + ")") + " • ocena po " + s().evalHorizonH + " h: czy po tym czasie sprzedaż po cenie rynkowej "
                    + "(minus opłata) pokryłaby zakup z zyskiem", x1 + 6, ty, x2 - x1 - 12, Theme.MUTED);
            c.fill(x1 + 3, ty + 10, x2 - 3, ty + 11, Theme.BORDER);
            int sc = clampScroll(rs.size(), vis);
            if (rs.isEmpty()) Theme.text(c, textRenderer, "Rekomendacje zapisują się co 30 min.", x1 + 8, rowsTop, Theme.MUTED);
            for (int i = 0; i < vis && i + sc < rs.size(); i++) {
                RecRecord r = rs.get(rs.size() - 1 - (i + sc));
                int y = rowsTop + i * ROW;
                String res = r.success == null ? "…" : r.success ? "✔" : "✖";
                int rc = r.success == null ? Theme.MUTED : r.success ? Theme.GREEN : Theme.RED;
                Theme.text(c, textRenderer, res, x1 + 6, y, rc);
                Theme.fit(c, textRenderer, Theme.time(r.t, "dd.MM HH:mm") + "  " + r.name, x1 + 16, y, x2 - x1 - 260, Theme.TEXT);
                Theme.right(c, textRenderer, "kup " + Fmt.compact(r.buy) + "  sprzedaj " + Fmt.compact(r.sell) + "  zysk " + Fmt.compact(r.net)
                        + "  ryz. " + r.risk + (Double.isNaN(r.laterMarket) ? "" : "  później " + Fmt.compact(r.laterMarket)), x2 - 6, y, Theme.TEXT_2);
            }
            scrollbar(c, x2 - 3, rowsTop, y2 - 4, rs.size(), vis, sc);
        }
        listTop = rowsTop;
        listBottom = y2;
        listX1 = x1;
        listX2 = x2;
    }

    // ------------------------------------------------------------------ USTAWIENIA

    private void settings(DrawContext c, int x1, int y1, int x2, int y2) {
        Theme.text(c, textRenderer, "Ustawienia (zapisują się od razu)", x1 + 8, y1 + 7, Theme.TEXT);
        for (Object[] l : labels) {
            if ("#help".equals(l[2])) {
                int y = (Integer) l[1];
                String[] help = {
                        "Integracja z AutoRynkiem BK: BK nie ma oficjalnego API. Mod zmienia tylko lokalną listę cen BK (to samo, co edycja",
                        "w oknie BK) i zapisuje ją metodą BK. Do serwera nic nie jest wysyłane. Sprawdź regulamin Anarchia.gg dot. modów/botów",
                        "zanim włączysz tryb automatyczny. Odczyt rynku jest pasywny: tylko to, co wyświetla okno /ah (Ty albo BK).",
                        "Zabezpieczenia auto: maks. zmiana na raz, odstęp między zmianami, min. pewność, świeże dane, zakup zawsze poniżej",
                        "ceny sprzedaży minus opłata i zysk, wstrzymanie przy gwałtownej zmianie większości cen, cofanie zmian (Historia)."
                };
                for (String h : help) {
                    if (y > y2 - 10) break;
                    Theme.fit(c, textRenderer, h, x1 + 8, y, x2 - x1 - 16, Theme.MUTED);
                    y += 10;
                }
                continue;
            }
            Theme.fit(c, textRenderer, (String) l[2], (Integer) l[0], (Integer) l[1], (Integer) l[3], Theme.TEXT_2);
        }
    }

    // ------------------------------------------------------------------ wejście

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        for (int i = hitBoxes.size() - 1; i >= 0; i--) {
            int[] b = hitBoxes.get(i);
            if (mx >= b[0] && mx < b[2] && my >= b[1] && my < b[3]) {
                setFocused(null);
                hitActions.get(i).run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (my >= listTop && my < listBottom && mx >= listX1 && mx < listX2) {
            SCROLL[tab.ordinal()] = Math.max(0, SCROLL[tab.ordinal()] - vertical * 3);
            return true;
        }
        if (tab == Tab.CHARTS && vertical != 0) {
            rangeIdx = (int) Math.max(0, Math.min(RANGES.length - 1, rangeIdx - Math.signum(vertical)));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean typing = getFocused() instanceof TextFieldWidget;
        if (!typing && TerminalMod.OPEN_KEY.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        if (!typing && keyCode >= 49 && keyCode <= 55) { // klawisze 1–7 = zakładki
            switchTab(Tab.values()[keyCode - 49]);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        TerminalMod.saveSettings();
        super.removed();
    }
}
