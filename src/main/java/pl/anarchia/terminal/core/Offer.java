package pl.anarchia.terminal.core;

/** Oferta z rynku. Ceny za 1 sztukę. */
public final class Offer {
    public final String id;
    public final double unit;
    public final int count;
    public final String seller;
    public final long first;
    public long last;

    public Offer(String id, double unit, int count, String seller, long first) {
        this.id = id;
        this.unit = unit;
        this.count = count;
        this.seller = seller == null ? "" : seller;
        this.first = first;
        this.last = first;
    }
}
