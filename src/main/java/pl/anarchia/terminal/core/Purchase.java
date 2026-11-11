package pl.anarchia.terminal.core;

/** Zakup wykonany przez BK-Rynek. Cena za sztukę. */
public final class Purchase {
    public final long t;
    public final double unit;
    public final int count;

    public Purchase(long t, double unit, int count) {
        this.t = t;
        this.unit = unit;
        this.count = count;
    }
}
