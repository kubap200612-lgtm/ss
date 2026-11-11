package pl.anarchia.terminal.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Szuka okazji: oferty tańsze od typowej ceny, lekko droższe od ceny BK ale opłacalne, rosnące ceny. */
public final class OpportunityFinder {
    private OpportunityFinder() {}

    public static List<Opportunity> find(ItemData d, ItemStats st, Recommendation r, Settings s, double bkBuy) {
        List<Opportunity> out = new ArrayList<>();
        if (Double.isNaN(st.market) || Double.isNaN(r.sell) || r.confidence < s.minConfidence) return out;
        double netSell = r.sell * (1 - s.fee());
        boolean liquid = !Double.isNaN(st.salesPerHour) && st.salesPerHour >= s.minLiquidity;
        for (Offer o : d.active.values()) {
            double profit = netSell - o.unit;
            double margin = profit / o.unit;
            if (profit < s.minProfitAbs || margin * 100 < s.minMarginPct) continue;
            Opportunity op = base(st, r, o.unit, profit, margin);
            op.count = o.count;
            double disc = 1 - o.unit / st.market;
            if (disc * 100 >= s.discountPct) {
                op.kind = "Tańsza od typowej ceny";
                op.justification = "Oferta " + Fmt.money(o.unit) + " jest o " + Fmt.pct(disc, false) + " tańsza niż cena rynkowa "
                        + Fmt.money(st.market) + "; odsprzedaż po " + Fmt.money(r.sell) + " daje " + Fmt.money(profit) + "/szt.";
            } else if (!Double.isNaN(bkBuy) && bkBuy > 0 && o.unit > bkBuy && o.unit <= bkBuy * (1 + s.slightlyAbovePct / 100)) {
                op.kind = "Lekko drożej niż cena BK";
                op.justification = "Oferta o " + Fmt.pct(o.unit / bkBuy - 1, false) + " droższa niż skup w BK ("
                        + Fmt.money(bkBuy) + "), ale po opłatach nadal zysk " + Fmt.money(profit) + "/szt.";
            } else {
                op.kind = "Opłacalna odsprzedaż";
                op.justification = "Kup po " + Fmt.money(o.unit) + ", sprzedaj po " + Fmt.money(r.sell) + " (po opłacie "
                        + Fmt.pct(s.fee(), false) + " zostaje " + Fmt.money(profit) + ").";
            }
            if (!liquid) op.justification += " Uwaga: mała płynność.";
            out.add(op);
        }
        if (st.trend == Trend.RISING && r.valid && !Double.isNaN(st.competitor)) {
            double profit = netSell - st.competitor;
            if (profit > 0 && st.competitor <= r.sell * 0.97) {
                Opportunity op = base(st, r, st.competitor, profit, profit / st.competitor);
                op.kind = "Cena rośnie";
                op.count = 1;
                op.justification = "Cena rynkowa rośnie " + Fmt.pct(st.trendPctPerHour / 100, true) + "/h (R² "
                        + Fmt.num1(st.trendR2) + "); najtańsza oferta " + Fmt.money(st.competitor) + " ma potencjał wzrostu.";
                out.add(op);
            }
        }
        out.sort(Comparator.comparingDouble(Opportunity::score).reversed());
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    private static Opportunity base(ItemStats st, Recommendation r, double offer, double profit, double margin) {
        Opportunity op = new Opportunity();
        op.key = st.key;
        op.name = st.name;
        op.market = st.market;
        op.offer = offer;
        op.buy = r.buy;
        op.sell = r.sell;
        op.profit = profit;
        op.margin = margin;
        op.risk = r.risk;
        op.confidence = r.confidence;
        return op;
    }
}
