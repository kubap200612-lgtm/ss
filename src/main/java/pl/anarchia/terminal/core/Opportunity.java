package pl.anarchia.terminal.core;

/** Okazja do zarobku. Ceny za sztukę. */
public final class Opportunity {
    public String key, name, kind, justification;
    public double market, offer, buy, sell, profit, margin;
    public int risk, confidence, count;

    public double score() {
        return profit * Math.max(1, count) / (1 + risk / 25.0);
    }
}
