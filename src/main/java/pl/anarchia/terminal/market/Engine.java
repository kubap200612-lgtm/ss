package pl.anarchia.terminal.market;

import pl.anarchia.terminal.bk.BkBridge;
import pl.anarchia.terminal.core.ItemData;
import pl.anarchia.terminal.core.ItemStats;
import pl.anarchia.terminal.core.Opportunity;
import pl.anarchia.terminal.core.OpportunityFinder;
import pl.anarchia.terminal.core.Offer;
import pl.anarchia.terminal.core.PricePoint;
import pl.anarchia.terminal.core.RecRecord;
import pl.anarchia.terminal.core.Recommendation;
import pl.anarchia.terminal.core.Recommender;
import pl.anarchia.terminal.core.Settings;
import pl.anarchia.terminal.core.StatsCalculator;
import pl.anarchia.terminal.db.MarketDb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Liczy analizy wszystkich przedmiotów, zapisuje historię cen i ocenia skuteczność rekomendacji. */
public final class Engine {
    /** Analiza jednego przedmiotu. */
    public static final class Analysis {
        public ItemData data;
        public ItemStats stats;
        public Recommendation rec;
        public List<Opportunity> opportunities;
        public BkBridge.BkItem bk;

        public double bkBuy() {
            return bk == null ? Double.NaN : bk.buyPerPiece();
        }

        public double bkSell() {
            return bk == null ? Double.NaN : bk.sellPerPiece();
        }
    }

    private final MarketDb db;
    private final Settings s;
    private Map<String, Analysis> cache = new HashMap<>();
    private List<Analysis> list = new ArrayList<>();
    private long cacheVersion = -1, cacheAt;
    private long lastPoints, lastRecs;

    public Engine(MarketDb db, Settings s) {
        this.db = db;
        this.s = s;
    }

    /** Wszystkie analizy (odświeżane po zmianie danych, najwyżej co 2 s). */
    public synchronized List<Analysis> all() {
        long now = System.currentTimeMillis();
        if ((db.version() != cacheVersion && now - cacheAt > 2000) || now - cacheAt > 15_000L) recompute(now);
        return list;
    }

    public synchronized Analysis get(String key) {
        all();
        return cache.get(key);
    }

    public synchronized void invalidate() {
        cacheAt = 0;
        cacheVersion = -1;
    }

    private void recompute(long now) {
        Map<String, Analysis> m = new HashMap<>();
        List<Analysis> l = new ArrayList<>();
        Map<String, BkBridge.BkItem> bkByKey = new HashMap<>();
        for (BkBridge.BkItem it : BkBridge.items()) {
            bkByKey.put(it.key, it);
            db.meta(it.key, it.name, it.material, true);
        }
        for (ItemData d : new ArrayList<>(db.all())) {
            BkBridge.BkItem bk = bkByKey.get(d.key);
            if (bk == null) continue; // tylko przedmioty z listy AutoRynku BK
            Analysis a = new Analysis();
            a.data = d;
            a.bk = bk;
            a.stats = StatsCalculator.compute(d, s, now);
            boolean stackable = false;
            for (Offer o : d.active.values()) if (o.count > 1) stackable = true;
            a.rec = Recommender.recommend(a.stats, s, a.bkBuy(), stackable, now);
            a.opportunities = OpportunityFinder.find(d, a.stats, a.rec, s, a.bkBuy());
            m.put(d.key, a);
            l.add(a);
        }
        cache = m;
        list = l;
        cacheVersion = db.version();
        cacheAt = now;
    }

    /** Okazje ze wszystkich przedmiotów; byRisk=true sortuje wg zysku do ryzyka. */
    public List<Opportunity> opportunities(boolean byRisk) {
        List<Opportunity> out = new ArrayList<>();
        for (Analysis a : all()) out.addAll(a.opportunities);
        if (byRisk) out.sort(Comparator.comparingDouble((Opportunity o) -> o.profit / (1 + o.risk)).reversed());
        else out.sort(Comparator.comparingDouble((Opportunity o) -> o.profit * Math.max(1, o.count)).reversed());
        return out.size() > 200 ? new ArrayList<>(out.subList(0, 200)) : out;
    }

    /** Co 5 min migawka cen; co 30 min zapis rekomendacji i ocena starszych. */
    public void tick(long now) {
        if (now - lastPoints >= 5 * 60_000L) {
            lastPoints = now;
            for (Analysis a : all()) {
                if (Double.isNaN(a.stats.market)) continue;
                if (a.stats.lastData < now - 30 * 60_000L) continue; // brak świeżych danych — bez sztucznych punktów
                db.addPoint(a.data.key, new PricePoint(now, a.stats.market, a.stats.activeMin, a.stats.activeMax,
                        a.stats.activeMean, a.stats.activeMedian, a.stats.activeCount, a.bk == null ? 0 : a.bkBuy()));
            }
            invalidate();
        }
        if (now - lastRecs >= 30 * 60_000L) {
            lastRecs = now;
            for (Analysis a : all()) {
                if (a.rec.valid && (a.bk != null || !a.opportunities.isEmpty())) {
                    db.addRec(new RecRecord(now, a.data.key, a.data.name, a.stats.market, a.rec.buy, a.rec.sell, a.rec.net,
                            a.rec.risk, a.rec.confidence));
                }
            }
            long horizon = s.evalHorizonH * 3_600_000L;
            for (RecRecord r : new ArrayList<>(db.recs)) {
                if (r.success != null || now - r.t < horizon) continue;
                Analysis a = get(r.key);
                if (a == null || Double.isNaN(a.stats.market)) {
                    if (now - r.t > horizon * 4) db.evalRec(r, false, Double.NaN, now);
                    continue;
                }
                double later = a.stats.market;
                boolean ok = later * (1 - s.fee()) - r.buy >= s.minProfitAbs;
                db.evalRec(r, ok, later, now);
            }
        }
    }

    /** Skuteczność ocenionych rekomendacji: {trafne, ocenione}. */
    public int[] effectiveness() {
        int ok = 0, n = 0;
        for (RecRecord r : db.recs) {
            if (r.success == null) continue;
            n++;
            if (r.success) ok++;
        }
        return new int[]{ok, n};
    }

    public List<Analysis> bkItems() {
        List<Analysis> out = new ArrayList<>();
        for (Analysis a : all()) if (a.bk != null) out.add(a);
        return out;
    }

    public static List<Analysis> sortedCopy(List<Analysis> in, Comparator<Analysis> c) {
        List<Analysis> out = new ArrayList<>(in);
        out.sort(c);
        return out;
    }

    public static final List<Analysis> EMPTY = Collections.emptyList();
}
