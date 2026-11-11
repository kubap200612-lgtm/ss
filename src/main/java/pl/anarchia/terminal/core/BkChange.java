package pl.anarchia.terminal.core;

/** Zmiana cen w AutoRynku BK (za sztukę). */
public final class BkChange {
    public final long t;
    public final String key, name;
    public final double oldBuy, newBuy, oldSell, newSell;
    public final String mode, reason;

    public BkChange(long t, String key, String name, double oldBuy, double newBuy, double oldSell, double newSell,
                    String mode, String reason) {
        this.t = t;
        this.key = key;
        this.name = name;
        this.oldBuy = oldBuy;
        this.newBuy = newBuy;
        this.oldSell = oldSell;
        this.newSell = newSell;
        this.mode = mode;
        this.reason = reason;
    }
}
