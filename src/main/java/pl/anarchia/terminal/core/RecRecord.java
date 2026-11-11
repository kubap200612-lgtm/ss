package pl.anarchia.terminal.core;

/** Zapisana rekomendacja + późniejsza ocena jej skuteczności. */
public final class RecRecord {
    public final long t;
    public final String key, name;
    public final double market, buy, sell, net;
    public final int risk, confidence;
    /** null = jeszcze nie oceniona */
    public Boolean success;
    public double laterMarket = Double.NaN;
    public long evalT;

    public RecRecord(long t, String key, String name, double market, double buy, double sell, double net, int risk, int confidence) {
        this.t = t;
        this.key = key;
        this.name = name;
        this.market = market;
        this.buy = buy;
        this.sell = sell;
        this.net = net;
        this.risk = risk;
        this.confidence = confidence;
    }
}
