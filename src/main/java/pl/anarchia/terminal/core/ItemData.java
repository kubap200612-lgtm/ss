package pl.anarchia.terminal.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wszystkie dane jednego przedmiotu w pamięci. */
public final class ItemData {
    public final String key;
    public String name;
    public String material;
    /** czy przedmiot jest na liście AutoRynku BK */
    public boolean bk;
    /** oferty aktualnie wiszące na rynku (id → oferta) */
    public final Map<String, Offer> active = new LinkedHashMap<>();
    /** wszystkie zauważone oferty (historia) */
    public final Deque<Offer> seen = new ArrayDeque<>();
    public final Deque<Sale> sales = new ArrayDeque<>();
    public final Deque<PricePoint> points = new ArrayDeque<>();
    public final Deque<Purchase> purchases = new ArrayDeque<>();
    public long lastActivity;

    public ItemData(String key) {
        this.key = key;
    }

    public List<Offer> activeList() {
        return new ArrayList<>(active.values());
    }

    /** Usuwa dane starsze niż keepMs i oferty niewidziane dłużej niż ttlMs. */
    public void prune(long now, long keepMs, long ttlMs) {
        while (!seen.isEmpty() && now - seen.peekFirst().first > keepMs) seen.pollFirst();
        while (!sales.isEmpty() && now - sales.peekFirst().t > keepMs) sales.pollFirst();
        while (!points.isEmpty() && now - points.peekFirst().t > keepMs) points.pollFirst();
        while (!purchases.isEmpty() && now - purchases.peekFirst().t > keepMs) purchases.pollFirst();
        active.values().removeIf(o -> now - o.last > ttlMs);
    }
}
