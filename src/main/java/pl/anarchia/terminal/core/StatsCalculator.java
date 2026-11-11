package pl.anarchia.terminal.core;

import java.util.ArrayList;
import java.util.List;

/** Liczy statystyki przedmiotu z danych w pamięci. */
public final class StatsCalculator {
    private StatsCalculator() {}

    public static ItemStats compute(ItemData d, Settings s, long now) {
        ItemStats st = new ItemStats();
        st.key = d.key;
        st.name = d.name;
        st.bk = d.bk;
        long w0 = now - s.windowMs();

        // --- aktywne oferty
        List<Double> act = new ArrayList<>();
        List<Double> ages = new ArrayList<>();
        for (Offer o : d.active.values()) {
            act.add(o.unit);
            ages.add((now - o.first) / 60_000.0);
            st.lastData = Math.max(st.lastData, o.last);
        }
        st.activeCount = act.size();
        if (!act.isEmpty()) {
            List<Double> sa = Stats.sorted(act);
            st.activeMin = sa.get(0);
            st.activeMax = sa.get(sa.size() - 1);
            st.activeMean = Stats.mean(sa);
            st.activeMedian = Stats.quantileSorted(sa, 0.5);
            List<Double> f = Stats.withoutOutliers(act);
            st.competitor = f.get(0);
            st.activeMedianAgeMin = Stats.median(ages);
        }

        // --- oferty w oknie
        List<Double> win = new ArrayList<>();
        long winFirst = Long.MAX_VALUE;
        for (Offer o : d.seen) {
            if (o.first < w0) continue;
            win.add(o.unit);
            winFirst = Math.min(winFirst, o.first);
            st.lastData = Math.max(st.lastData, o.first);
        }
        st.windowOffers = win.size();
        if (!win.isEmpty()) {
            List<Double> sw = Stats.sorted(win);
            st.windowMin = sw.get(0);
            st.windowMax = sw.get(sw.size() - 1);
            st.windowMean = Stats.mean(sw);
            st.windowMedian = Stats.quantileSorted(sw, 0.5);
            st.newPerHour = win.size() / Math.max(1.0, (now - winFirst) / 3_600_000.0);
            List<Double> fw = Stats.withoutOutliers(win);
            st.histogram = Stats.histogram(fw, 10);
            if (st.histogram != null) {
                int best = 0;
                for (int i = 1; i < st.histogram[1].length; i++) if (st.histogram[1][i] > st.histogram[1][best]) best = i;
                st.modeLo = st.histogram[0][best];
                st.modeHi = st.histogram[0][best + 1];
            }
        }

        // --- transakcje (oferty kupione przez innych)
        List<Double> sp = new ArrayList<>();
        List<Double> sAge = new ArrayList<>();
        long sFirst = Long.MAX_VALUE;
        for (Sale x : d.sales) {
            if (x.t < w0) continue;
            sp.add(x.unit);
            sAge.add(x.age / 60_000.0);
            sFirst = Math.min(sFirst, x.t);
            st.lastData = Math.max(st.lastData, x.t);
        }
        st.sales = sp.size();
        if (!sp.isEmpty()) {
            st.saleMedian = Stats.median(Stats.withoutOutliers(sp));
            st.saleMedianAgeMin = Stats.median(sAge);
            st.salesPerHour = sp.size() / Math.max(1.0, (now - sFirst) / 3_600_000.0);
        } else if (st.windowOffers > 0) {
            st.salesPerHour = 0;
        }

        // --- cena rynkowa: nie tylko najtańsza oferta
        double activeLower = lowerHalfMedian(act);
        double windowLower = lowerHalfMedian(win);
        if (st.sales >= 3 && !Double.isNaN(activeLower)) {
            st.market = (st.saleMedian + activeLower) / 2;
            st.marketSource = "transakcje + oferty";
        } else if (st.sales >= 3) {
            st.market = st.saleMedian;
            st.marketSource = "transakcje";
        } else if (!Double.isNaN(activeLower)) {
            st.market = activeLower;
            st.marketSource = "wiszące oferty";
        } else if (!Double.isNaN(windowLower)) {
            st.market = windowLower;
            st.marketSource = "oferty z okna";
        }

        // --- historia: trend, zmienność, zmiana 24h
        long trendFrom = now - Math.min(s.windowMs(), 6 * 3_600_000L);
        List<double[]> pts = new ArrayList<>();
        List<Double> dayVals = new ArrayList<>();
        PricePoint first24 = null;
        for (PricePoint p : d.points) {
            if (Double.isNaN(p.market)) continue;
            st.dataPoints++;
            if (p.t >= trendFrom) pts.add(new double[]{(p.t - trendFrom) / 3_600_000.0, p.market});
            if (p.t >= now - 24 * 3_600_000L) {
                dayVals.add(p.market);
                if (first24 == null) first24 = p;
            }
        }
        if (!Double.isNaN(st.market)) {
            pts.add(new double[]{(now - trendFrom) / 3_600_000.0, st.market});
            dayVals.add(st.market);
        }
        double[] reg = Stats.regression(pts);
        if (reg != null && pts.size() >= 4) {
            double mean = 0;
            for (double[] p : pts) mean += p[1];
            mean /= pts.size();
            st.trendPctPerHour = mean > 0 ? reg[0] / mean * 100 : Double.NaN;
            st.trendR2 = reg[2];
            st.trendA = reg[1];
            st.trendB = reg[0];
            st.trendT0 = trendFrom;
            if (Math.abs(st.trendPctPerHour) < 0.5 || st.trendR2 < 0.25) st.trend = Trend.STABLE;
            else st.trend = st.trendPctPerHour > 0 ? Trend.RISING : Trend.FALLING;
        }
        if (first24 != null && !Double.isNaN(st.market) && now - first24.t >= 3_600_000L) {
            st.change24h = st.market / first24.market - 1;
        }
        if (dayVals.size() >= 3) {
            double m = Stats.mean(dayVals);
            st.volatility = m > 0 ? Stats.stdev(dayVals) / m : Double.NaN;
        }

        // --- szybko schodzi / zalega
        st.fastSelling = st.sales >= 3 && !Double.isNaN(st.saleMedianAgeMin) && st.saleMedianAgeMin <= 15;
        st.slowMoving = st.activeCount >= 3 && !Double.isNaN(st.activeMedianAgeMin) && st.activeMedianAgeMin >= 360
                && (Double.isNaN(st.salesPerHour) || st.salesPerHour < 0.2);
        return st;
    }

    /** Mediana tańszej połowy (po odrzuceniu anomalii) — realny poziom cen, odporny na pojedyncze oferty. */
    static double lowerHalfMedian(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> f = Stats.withoutOutliers(v);
        int half = Math.max(1, (f.size() + 1) / 2);
        return Stats.quantileSorted(f.subList(0, half), 0.5);
    }
}
