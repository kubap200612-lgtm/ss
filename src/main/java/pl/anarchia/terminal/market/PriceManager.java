package pl.anarchia.terminal.market;

import pl.anarchia.terminal.TerminalMod;
import pl.anarchia.terminal.bk.BkBridge;
import pl.anarchia.terminal.core.BkChange;
import pl.anarchia.terminal.core.Fmt;
import pl.anarchia.terminal.core.Settings;
import pl.anarchia.terminal.db.MarketDb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ceny w AutoRynku BK — trzy tryby:
 *  ANALIZA       – nic nie zmienia,
 *  REKOMENDACJE  – propozycje do ręcznego zatwierdzenia,
 *  AUTO          – sam wprowadza zmiany, z zabezpieczeniami.
 */
public final class PriceManager {
    /** Propozycja zmiany cen (za sztukę). */
    public static final class Proposal {
        public Engine.Analysis a;
        public double oldBuy, newBuy, oldSell, newSell;
        public boolean buyChange, sellChange;
        public boolean limited;
        public String block = "";

        public boolean ok() {
            return block.isEmpty() && (buyChange || sellChange);
        }
    }

    private final MarketDb db;
    private final Engine engine;
    private final Settings s;
    private final Map<String, Long> lastChange = new HashMap<>();
    private final Map<String, double[]> rejected = new HashMap<>(); // klucz → {kupno, sprzedaż, czas}
    private long lastAuto;
    private String lastAutoInfo = "";

    public PriceManager(MarketDb db, Engine engine, Settings s) {
        this.db = db;
        this.engine = engine;
        this.s = s;
    }

    public String lastAutoInfo() {
        return lastAutoInfo;
    }

    public long nextAuto() {
        return lastAuto + s.autoIntervalMin * 60_000L;
    }

    /** Propozycja dla jednej pozycji BK albo null, gdy nie ma czego zmieniać. */
    public Proposal propose(Engine.Analysis a, boolean auto) {
        if (a.bk == null || !a.rec.valid) return null;
        long now = System.currentTimeMillis();
        Proposal p = new Proposal();
        p.a = a;
        p.oldBuy = a.bkBuy();
        p.oldSell = a.bkSell();
        double m = s.maxChangePct / 100.0;
        double fee = s.fee();

        double nb = a.rec.buy;
        double ns = a.rec.sell;
        // obecny skup daje stratę (droższy niż sprzedaż po opłacie) → obniżka od razu, bez limitu kroku
        boolean losing = p.oldBuy > 0 && p.oldBuy > a.rec.sell * (1 - fee) - s.minProfitAbs;
        if (p.oldBuy > 0 && !(losing && nb < p.oldBuy)) {
            double c = clamp(nb, p.oldBuy * (1 - m), p.oldBuy * (1 + m));
            if (c != nb) p.limited = true;
            nb = c;
        }
        if (p.oldSell > 0) {
            double c = clamp(ns, p.oldSell * (1 - m), p.oldSell * (1 + m));
            if (c != ns) p.limited = true;
            ns = c;
        }
        nb = Fmt.roundDown(nb);
        ns = Fmt.roundUp(ns);

        // --- zabezpieczenia
        double maxBuyForSell = ns * (1 - fee) - s.minProfitAbs;
        if (nb > maxBuyForSell) nb = Fmt.roundDown(maxBuyForSell);
        double maxBuyForMarket = a.stats.market * (1 - fee);
        if (nb > maxBuyForMarket) nb = Fmt.roundDown(maxBuyForMarket);
        if (!(nb > 0)) p.block = "cena zakupu ≤ 0";
        else if (ns * (1 - fee) - nb < s.minProfitAbs) p.block = "brak min. zysku";
        else if (a.rec.confidence < s.minConfidence) p.block = "za mała pewność";
        else if (auto && now - a.stats.lastData > 30 * 60_000L) p.block = "brak świeżych danych";
        else if (auto && s.autoExcluded.contains(a.data.key)) p.block = "automatyka wyłączona dla przedmiotu";
        else if (auto && now - lastChange.getOrDefault(a.data.key, 0L) < s.cooldownMin * 60_000L) p.block = "odstęp między zmianami";

        p.newBuy = nb;
        p.newSell = ns;
        p.buyChange = changed(p.oldBuy, nb);
        p.sellChange = changed(p.oldSell, ns);
        if (!p.buyChange && !p.sellChange) return null;

        double[] rej = rejected.get(a.data.key);
        if (rej != null && !auto) {
            boolean same = Math.abs(rej[0] - nb) <= nb * 0.02 && Math.abs(rej[1] - ns) <= ns * 0.02;
            if (same && now - rej[2] < 3_600_000L) return null;
        }
        return p;
    }

    private boolean changed(double old, double nw) {
        if (Double.isNaN(nw)) return false;
        if (!(old > 0)) return true;
        return Math.abs(nw - old) / old * 100 >= s.minChangePct;
    }

    public List<Proposal> proposals(boolean auto) {
        List<Proposal> out = new ArrayList<>();
        for (Engine.Analysis a : engine.bkItems()) {
            Proposal p = propose(a, auto);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** Wprowadza propozycję do BK. Zwraca komunikat. */
    public String apply(Proposal p, String mode) {
        if (!p.ok()) return "Zablokowane: " + p.block;
        BkBridge.BkItem it = p.a.bk;
        boolean ok = BkBridge.setPrices(it, p.buyChange ? p.newBuy : Double.NaN, p.sellChange ? p.newSell : Double.NaN);
        if (!ok) return "Nie udało się zapisać w BK";
        long now = System.currentTimeMillis();
        lastChange.put(p.a.data.key, now);
        rejected.remove(p.a.data.key);
        db.addBkChange(new BkChange(now, p.a.data.key, it.name, p.oldBuy, it.buyPerPiece(), p.oldSell, it.sellPerPiece(), mode,
                p.a.rec.reason));
        engine.invalidate();
        return it.name + ": skup " + Fmt.money(p.oldBuy) + " → " + Fmt.money(it.buyPerPiece()) + ", sprzedaż "
                + Fmt.money(p.oldSell) + " → " + Fmt.money(it.sellPerPiece());
    }

    public void reject(Proposal p) {
        rejected.put(p.a.data.key, new double[]{p.newBuy, p.newSell, System.currentTimeMillis()});
    }

    /** Cofa ostatnią zmianę cen w BK. */
    public String rollbackLast() {
        if (db.bkChanges.isEmpty()) return "Brak zmian do cofnięcia";
        BkChange c = db.bkChanges.get(db.bkChanges.size() - 1);
        BkBridge.BkItem it = null;
        for (BkBridge.BkItem b : BkBridge.items()) if (b.key.equals(c.key)) it = b;
        if (it == null) return "Pozycja nie istnieje już w BK";
        double curBuy = it.buyPerPiece(), curSell = it.sellPerPiece();
        if (!BkBridge.setPrices(it, c.oldBuy > 0 ? c.oldBuy : Double.NaN, c.oldSell > 0 ? c.oldSell : Double.NaN)) {
            return "Nie udało się zapisać w BK";
        }
        db.addBkChange(new BkChange(System.currentTimeMillis(), c.key, it.name, curBuy, it.buyPerPiece(), curSell,
                it.sellPerPiece(), "cofnięcie", "cofnięto zmianę z " + new java.util.Date(c.t)));
        engine.invalidate();
        return "Cofnięto: " + it.name;
    }

    /** Tryb automatyczny: co X minut wprowadza bezpieczne propozycje. */
    public void tick(long now) {
        if (s.mode != Settings.Mode.AUTO || !BkBridge.ok()) return;
        if (now - lastAuto < s.autoIntervalMin * 60_000L) return;
        lastAuto = now;
        List<Proposal> ps = proposals(true);
        List<Proposal> good = new ArrayList<>();
        for (Proposal p : ps) if (p.ok()) good.add(p);
        // zabezpieczenie: jeśli cena rynkowa większości przedmiotów skoczyła naraz o > 30% w godzinę,
        // to raczej błąd odczytu niż rynek — wstrzymaj ten cykl
        int jumps = 0, measured = 0;
        for (Engine.Analysis a : engine.bkItems()) {
            double before = Double.NaN;
            for (pl.anarchia.terminal.core.PricePoint pt : a.data.points) {
                if (pt.t <= now - 3_600_000L && pt.t >= now - 2 * 3_600_000L) before = pt.market;
            }
            if (Double.isNaN(before) || Double.isNaN(a.stats.market) || before <= 0) continue;
            measured++;
            if (Math.abs(a.stats.market / before - 1) > 0.30) jumps++;
        }
        if (measured >= 4 && jumps > measured / 2) {
            lastAutoInfo = "Wstrzymano: cena rynkowa " + jumps + " z " + measured + " przedmiotów zmieniła się o >30% w godzinę";
            TerminalMod.chat("§e" + lastAutoInfo + " - sprawdź zakładkę AutoRynek BK.");
            return;
        }
        int applied = 0, up = 0, down = 0;
        for (Proposal p : good) {
            String r = apply(p, "auto");
            if (r.startsWith("Nie") || r.startsWith("Zablok")) continue;
            applied++;
            if (p.buyChange && p.newBuy > p.oldBuy) up++;
            else if (p.buyChange) down++;
        }
        lastAutoInfo = applied == 0 ? "Brak zmian (" + Theme_time() + ")" : "Zmieniono ceny: " + applied + " (" + Theme_time() + ")";
        if (applied > 0) {
            TerminalMod.chat("§7AutoRynek BK: zmieniono ceny " + applied + " przedmiotów (skup §a▲" + up + " §c▼" + down
                    + "§7) - szczegóły: J → Historia");
        }
    }

    private static String Theme_time() {
        return java.time.LocalTime.now().withNano(0).toString();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
