package pl.anarchia.terminal.core;

/**
 * Wylicza ceny zakupu i sprzedaży z sytuacji na rynku (konkurencja, historia, trend, podaż, popyt, opłaty).
 * Zasada nadrzędna: nigdy nie proponujemy zakupu drożej, niż da się sprzedać z zyskiem.
 */
public final class Recommender {
    private Recommender() {}

    /**
     * @param bkBuy  aktualna cena skupu w BK za sztukę (NaN gdy brak)
     * @param stackable czy przedmiot się stackuje (do wyliczeń dla ilości)
     */
    public static Recommendation recommend(ItemStats st, Settings s, double bkBuy, boolean stackable, long now) {
        Recommendation r = new Recommendation();
        double fee = s.fee();

        // --- pewność danych
        double c = 30 * Math.min(1, st.activeCount / 5.0)
                + 30 * Math.min(1, st.sales / 5.0)
                + 25 * Math.min(1, st.dataPoints / 12.0)
                + 15 * (st.lastData > 0 && now - st.lastData < 15 * 60_000L ? 1 : (now - st.lastData < 3_600_000L ? 0.5 : 0.1));
        if (st.activeCount == 0 && st.windowOffers > 0) c += 10 * Math.min(1, st.windowOffers / 10.0);
        r.confidence = (int) Math.round(Math.max(0, Math.min(100, c)));

        // --- ryzyko
        double risk = 0;
        if (!Double.isNaN(st.volatility)) risk += Math.min(35, st.volatility * 400);
        else risk += 15;
        if (st.trend == Trend.FALLING) risk += Math.min(25, Math.abs(st.trendPctPerHour) * 5);
        if (Double.isNaN(st.salesPerHour) || st.salesPerHour < s.minLiquidity) risk += 20;
        else if (st.salesPerHour < 0.5) risk += 10;
        if (st.slowMoving) risk += 10;
        if (st.lastData == 0 || now - st.lastData > 3_600_000L) risk += 10;
        r.risk = (int) Math.round(Math.max(0, Math.min(100, risk)));
        r.riskLabel = r.risk < 25 ? "niskie" : r.risk < 50 ? "średnie" : r.risk < 70 ? "wysokie" : "bardzo wysokie";

        if (Double.isNaN(st.market)) {
            r.reason = "Za mało danych - brak ofert tego przedmiotu";
            r.status = Status.GRAY;
            return r;
        }

        // --- cena sprzedaży: sprzedać szybko, ale nie tanio
        double trendAdj = 0;
        if (st.trend == Trend.RISING || st.trend == Trend.FALLING) {
            trendAdj = Math.max(-0.08, Math.min(0.05, st.trendPctPerHour / 100 * 3)); // oczekiwana zmiana w ~3 h
        }
        double fair = st.market * (1 + trendAdj);
        double sell;
        if (!Double.isNaN(st.competitor)) {
            double undercut = st.competitor - Fmt.step(st.competitor);
            sell = Math.max(fair * 0.97, Math.min(undercut, fair * 1.10));
            if (undercut < fair * 0.97) r.why.add("najtańsza konkurencja " + Fmt.money(st.competitor) + " poniżej typowej ceny");
            else if (undercut > fair * 1.10) r.why.add("oferty wiszą drogo (od " + Fmt.money(st.competitor) + "), sprzedaż blisko ceny transakcyjnej");
            else r.why.add("o krok taniej niż najtańsza konkurencja (" + Fmt.money(st.competitor) + ")");
        } else {
            sell = fair * 1.05;
            r.why.add("brak wiszącej konkurencji");
        }
        if (trendAdj > 0.005) r.why.add("trend wzrostowy " + Fmt.pct(st.trendPctPerHour / 100, true) + "/h");
        if (trendAdj < -0.005) r.why.add("trend spadkowy " + Fmt.pct(st.trendPctPerHour / 100, true) + "/h");
        if (st.sales >= 3) r.why.add("realne transakcje: mediana " + Fmt.money(st.saleMedian));
        sell = Fmt.roundDown(sell);

        // --- cena zakupu: po sprzedaży i opłatach musi zostać min. zysk i bufor na ryzyko
        double riskBuffer = sell * (r.risk / 100.0) * 0.10;
        double buy = Profit.maxBuy(sell, s.feePct, s.minProfitAbs, s.minMarginPct, riskBuffer);
        buy = Fmt.roundDown(buy);

        Profit p = Profit.withFeePct(buy, sell, s.feePct);
        r.sell = sell;
        r.buy = buy;
        r.fees = p.fees;
        r.net = p.net;
        r.margin = p.margin;

        // --- zysk dla różnych ilości
        int[] qs = stackable ? new int[]{1, 16, 64} : new int[]{1, 3, 10};
        for (int q : qs) {
            double hours = (Double.isNaN(st.salesPerHour) || st.salesPerHour <= 0) ? Double.NaN : q / st.salesPerHour;
            r.quantities.add(new double[]{q, r.net * q, hours});
        }

        // --- czy rekomendować
        if (!(buy > 0)) {
            r.reason = "Cena zakupu wychodzi ≤ 0 - przedmiot nieopłacalny";
        } else if (r.net < s.minProfitAbs || r.margin * 100 < s.minMarginPct - 1e-9) {
            r.reason = "Zysk poniżej progu (" + Fmt.money(r.net) + ", " + Fmt.pct(r.margin, false) + ")";
        } else if (r.confidence < s.minConfidence) {
            r.reason = "Za mało danych (pewność " + r.confidence + "%, wymagane " + s.minConfidence + "%)";
        } else {
            r.valid = true;
            r.reason = String.join("; ", r.why);
        }

        // --- oznaczenie
        boolean lossRisk = !Double.isNaN(bkBuy) && bkBuy > 0 && bkBuy >= sell * (1 - fee);
        if (lossRisk || r.risk >= 70 || (r.valid == false && r.net < 0)) {
            r.status = Status.RED;
            if (lossRisk) r.why.add(0, "cena skupu w BK (" + Fmt.money(bkBuy) + ") daje stratę");
        } else if (r.valid && !Double.isNaN(st.activeMin) && st.activeMin <= buy) {
            r.status = Status.GREEN;
        } else if (st.trend == Trend.RISING || (!Double.isNaN(st.competitor) && st.competitor >= st.market * 1.08)) {
            r.status = Status.YELLOW;
        } else if (st.trend == Trend.STABLE && !Double.isNaN(st.volatility) && st.volatility < 0.03) {
            r.status = Status.BLUE;
        } else {
            r.status = r.valid ? Status.BLUE : Status.GRAY;
        }
        return r;
    }
}
