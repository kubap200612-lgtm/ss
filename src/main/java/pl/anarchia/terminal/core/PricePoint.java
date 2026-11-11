package pl.anarchia.terminal.core;

/** Migawka statystyk przedmiotu (co kilka minut) — historia cen do wykresów i trendu. */
public final class PricePoint {
    public final long t;
    public final double market, min, max, mean, median;
    public final int offers;
    public final double bkBuy;

    public PricePoint(long t, double market, double min, double max, double mean, double median, int offers, double bkBuy) {
        this.t = t;
        this.market = market;
        this.min = min;
        this.max = max;
        this.mean = mean;
        this.median = median;
        this.offers = offers;
        this.bkBuy = bkBuy;
    }
}
