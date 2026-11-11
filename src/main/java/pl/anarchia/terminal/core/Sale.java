package pl.anarchia.terminal.core;

/** Oferta, która zniknęła z rynku (kupiona przez kogoś). Cena za sztukę. */
public final class Sale {
    public final long t;
    public final double unit;
    public final int count;
    public final long age;

    public Sale(long t, double unit, int count, long age) {
        this.t = t;
        this.unit = unit;
        this.count = count;
        this.age = age;
    }
}
