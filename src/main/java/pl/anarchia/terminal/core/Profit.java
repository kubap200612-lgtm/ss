package pl.anarchia.terminal.core;

/**
 * Algorytm opłacalności transakcji.
 * Przykład: zakup 100 000$, sprzedaż 125 000$, koszty 5 000$ → zysk netto 20 000$, marża 20% (zysk / koszt zakupu).
 */
public final class Profit {
    public final double buy, sell, fees, net, margin;

    private Profit(double buy, double sell, double fees) {
        this.buy = buy;
        this.sell = sell;
        this.fees = fees;
        this.net = sell - fees - buy;
        this.margin = buy > 0 ? net / buy : Double.NaN;
    }

    /** Koszty podane kwotowo. */
    public static Profit of(double buy, double sell, double fees) {
        return new Profit(buy, sell, fees);
    }

    /** Koszty jako % ceny sprzedaży (opłata rynku). */
    public static Profit withFeePct(double buy, double sell, double feePct) {
        return new Profit(buy, sell, sell * feePct / 100.0);
    }

    /** Najwyższa cena zakupu, przy której zostaje min. zysk (kwotowy) i min. marża (%). */
    public static double maxBuy(double sell, double feePct, double minProfitAbs, double minMarginPct, double extraBuffer) {
        double netSell = sell * (1 - feePct / 100.0) - extraBuffer;
        double byAbs = netSell - minProfitAbs;
        double byPct = netSell / (1 + minMarginPct / 100.0);
        return Math.min(byAbs, byPct);
    }
}
