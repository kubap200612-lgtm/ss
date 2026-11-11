package pl.anarchia.terminal.db;

import com.google.gson.JsonObject;
import pl.anarchia.terminal.core.BkChange;
import pl.anarchia.terminal.core.ItemData;
import pl.anarchia.terminal.core.Offer;
import pl.anarchia.terminal.core.PricePoint;
import pl.anarchia.terminal.core.Purchase;
import pl.anarchia.terminal.core.RecRecord;
import pl.anarchia.terminal.core.Sale;
import pl.anarchia.terminal.core.Settings;
import pl.anarchia.terminal.core.Text2;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dane rynku w pamięci + trwały zapis w dzienniku. */
public final class MarketDb {
    private final Journal journal;
    private final Path exportDir;
    private final Map<String, ItemData> items = new LinkedHashMap<>();
    public final List<BkChange> bkChanges = new ArrayList<>();
    public final List<RecRecord> recs = new ArrayList<>();
    private long lastFlush, lastPrune, lastRetention;
    private long version;

    public MarketDb(Path root) {
        this.journal = new Journal(root.resolve("db"));
        this.exportDir = root.resolve("export");
    }

    public long version() {
        return version;
    }

    // ------------------------------------------------------------------ wczytanie

    public void load(Settings s) {
        long now = System.currentTimeMillis();
        long ttl = s.offerTtlMin * 60_000L;
        Map<String, RecRecord> recIdx = new HashMap<>();
        journal.load(s.memoryDays + 1, o -> {
            String e = str(o, "e");
            String k = str(o, "k");
            long t = o.has("t") ? o.get("t").getAsLong() : 0;
            switch (e) {
                case "meta": {
                    ItemData d = item(k);
                    d.name = str(o, "n");
                    d.material = str(o, "m");
                    d.bk = o.has("bk") && o.get("bk").getAsBoolean();
                    break;
                }
                case "offer": {
                    ItemData d = item(k);
                    Offer of = new Offer(str(o, "id"), num(o, "u"), (int) num(o, "c"), str(o, "s"), t);
                    d.seen.add(of);
                    if (now - t < ttl) d.active.put(of.id, of);
                    d.lastActivity = Math.max(d.lastActivity, t);
                    break;
                }
                case "gone": {
                    ItemData d = item(k);
                    d.active.remove(str(o, "id"));
                    d.sales.add(new Sale(t, num(o, "u"), (int) num(o, "c"), (long) num(o, "age")));
                    break;
                }
                case "pt":
                    item(k).points.add(new PricePoint(t, num(o, "mk"), num(o, "mn"), num(o, "mx"), num(o, "me"),
                            num(o, "md"), (int) num(o, "n"), num(o, "bk")));
                    break;
                case "buy":
                    item(k).purchases.add(new Purchase(t, num(o, "u"), (int) num(o, "c")));
                    break;
                case "bk":
                    bkChanges.add(new BkChange(t, k, str(o, "n"), num(o, "ob"), num(o, "nb"), num(o, "os"), num(o, "ns"),
                            str(o, "mode"), str(o, "why")));
                    break;
                case "rec": {
                    RecRecord r = new RecRecord(t, k, str(o, "n"), num(o, "mk"), num(o, "b"), num(o, "s"), num(o, "net"),
                            (int) num(o, "r"), (int) num(o, "c"));
                    recs.add(r);
                    recIdx.put(k + "@" + t, r);
                    break;
                }
                case "eval": {
                    RecRecord r = recIdx.get(k + "@" + (long) num(o, "rt"));
                    if (r != null) {
                        r.success = o.get("ok").getAsBoolean();
                        r.laterMarket = num(o, "later");
                        r.evalT = t;
                    }
                    break;
                }
                default:
            }
        });
        trimLists();
        prune(s, now);
        journal.retention(s.retentionDays);
        version++;
    }

    // ------------------------------------------------------------------ zapis zdarzeń

    public ItemData item(String key) {
        return items.computeIfAbsent(key, ItemData::new);
    }

    public ItemData get(String key) {
        return items.get(key);
    }

    public Collection<ItemData> all() {
        return items.values();
    }

    /** Opis przedmiotu (zapisywany tylko gdy się zmienił). */
    public ItemData meta(String key, String name, String material, boolean bk) {
        ItemData d = item(key);
        if (!name.equals(d.name) || d.bk != bk || (material != null && !material.equals(d.material))) {
            d.name = name;
            d.material = material;
            d.bk = bk;
            JsonObject o = ev("meta", key, 0);
            o.addProperty("n", name);
            o.addProperty("m", material == null ? "" : material);
            o.addProperty("bk", bk);
            journal.write(o);
        }
        return d;
    }

    public void addOffer(String key, Offer of) {
        ItemData d = item(key);
        d.seen.add(of);
        d.active.put(of.id, of);
        d.lastActivity = Math.max(d.lastActivity, of.first);
        JsonObject o = ev("offer", key, of.first);
        o.addProperty("id", of.id);
        o.addProperty("u", of.unit);
        o.addProperty("c", of.count);
        o.addProperty("s", of.seller);
        journal.write(o);
        version++;
    }

    /** Oferta zniknęła; sold=true → ktoś ją kupił (transakcja). */
    public void removeOffer(String key, String id, long t, boolean sold) {
        ItemData d = items.get(key);
        if (d == null) return;
        Offer of = d.active.remove(id);
        if (of == null || !sold) return;
        Sale x = new Sale(t, of.unit, of.count, t - of.first);
        d.sales.add(x);
        d.lastActivity = Math.max(d.lastActivity, t);
        JsonObject o = ev("gone", key, t);
        o.addProperty("id", id);
        o.addProperty("u", of.unit);
        o.addProperty("c", of.count);
        o.addProperty("age", x.age);
        journal.write(o);
        version++;
    }

    public void addPoint(String key, PricePoint p) {
        item(key).points.add(p);
        JsonObject o = ev("pt", key, p.t);
        o.addProperty("mk", p.market);
        o.addProperty("mn", p.min);
        o.addProperty("mx", p.max);
        o.addProperty("me", p.mean);
        o.addProperty("md", p.median);
        o.addProperty("n", p.offers);
        o.addProperty("bk", p.bkBuy);
        journal.write(o);
        version++;
    }

    public void addPurchase(String key, Purchase p) {
        item(key).purchases.add(p);
        JsonObject o = ev("buy", key, p.t);
        o.addProperty("u", p.unit);
        o.addProperty("c", p.count);
        journal.write(o);
        version++;
    }

    public void addBkChange(BkChange c) {
        bkChanges.add(c);
        JsonObject o = ev("bk", c.key, c.t);
        o.addProperty("n", c.name);
        o.addProperty("ob", c.oldBuy);
        o.addProperty("nb", c.newBuy);
        o.addProperty("os", c.oldSell);
        o.addProperty("ns", c.newSell);
        o.addProperty("mode", c.mode);
        o.addProperty("why", c.reason);
        journal.write(o);
        journal.flush();
        trimLists();
        version++;
    }

    public void addRec(RecRecord r) {
        recs.add(r);
        JsonObject o = ev("rec", r.key, r.t);
        o.addProperty("n", r.name);
        o.addProperty("mk", r.market);
        o.addProperty("b", r.buy);
        o.addProperty("s", r.sell);
        o.addProperty("net", r.net);
        o.addProperty("r", r.risk);
        o.addProperty("c", r.confidence);
        journal.write(o);
        trimLists();
        version++;
    }

    public void evalRec(RecRecord r, boolean ok, double later, long t) {
        r.success = ok;
        r.laterMarket = later;
        r.evalT = t;
        JsonObject o = ev("eval", r.key, t);
        o.addProperty("rt", r.t);
        o.addProperty("ok", ok);
        o.addProperty("later", later);
        journal.write(o);
        version++;
    }

    private static JsonObject ev(String e, String key, long t) {
        JsonObject o = new JsonObject();
        o.addProperty("e", e);
        o.addProperty("k", key);
        if (t > 0) o.addProperty("t", t);
        return o;
    }

    private void trimLists() {
        if (bkChanges.size() > 5000) bkChanges.subList(0, bkChanges.size() - 5000).clear();
        if (recs.size() > 20000) recs.subList(0, recs.size() - 20000).clear();
    }

    // ------------------------------------------------------------------ utrzymanie

    public void tick(Settings s, long now) {
        if (now - lastFlush > 3000) {
            lastFlush = now;
            journal.flush();
        }
        if (now - lastPrune > 60_000L) {
            lastPrune = now;
            prune(s, now);
        }
        if (now - lastRetention > 6 * 3_600_000L) {
            lastRetention = now;
            journal.retention(s.retentionDays);
        }
    }

    private void prune(Settings s, long now) {
        long keep = s.memoryDays * 24 * 3_600_000L;
        for (ItemData d : items.values()) d.prune(now, keep, s.offerTtlMin * 60_000L);
    }

    public void close() {
        journal.close();
    }

    // ------------------------------------------------------------------ eksport CSV

    /** Eksportuje dane do config/rynekterminal/export/&lt;data&gt;/. Zwraca katalog. */
    public Path exportCsv() throws IOException {
        String stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now());
        Path dir = exportDir.resolve(stamp);
        Files.createDirectories(dir);
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("oferty.csv"), StandardCharsets.UTF_8)) {
            w.write("czas;przedmiot;cena_szt;ilosc;sprzedawca\n");
            for (ItemData d : items.values()) for (Offer o : d.seen) {
                w.write(ts(o.first) + ";" + Text2.csv(d.name) + ";" + dec(o.unit) + ";" + o.count + ";" + Text2.csv(o.seller) + "\n");
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("transakcje.csv"), StandardCharsets.UTF_8)) {
            w.write("czas;przedmiot;cena_szt;ilosc;czas_na_rynku_min\n");
            for (ItemData d : items.values()) for (Sale x : d.sales) {
                w.write(ts(x.t) + ";" + Text2.csv(d.name) + ";" + dec(x.unit) + ";" + x.count + ";" + dec(x.age / 60_000.0) + "\n");
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("historia_cen.csv"), StandardCharsets.UTF_8)) {
            w.write("czas;przedmiot;cena_rynkowa;min;max;srednia;mediana;liczba_ofert;cena_bk\n");
            for (ItemData d : items.values()) for (PricePoint p : d.points) {
                w.write(ts(p.t) + ";" + Text2.csv(d.name) + ";" + dec(p.market) + ";" + dec(p.min) + ";" + dec(p.max) + ";"
                        + dec(p.mean) + ";" + dec(p.median) + ";" + p.offers + ";" + dec(p.bkBuy) + "\n");
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("zmiany_bk.csv"), StandardCharsets.UTF_8)) {
            w.write("czas;przedmiot;skup_przed;skup_po;sprzedaz_przed;sprzedaz_po;tryb;powod\n");
            for (BkChange c : bkChanges) {
                w.write(ts(c.t) + ";" + Text2.csv(c.name) + ";" + dec(c.oldBuy) + ";" + dec(c.newBuy) + ";" + dec(c.oldSell) + ";"
                        + dec(c.newSell) + ";" + c.mode + ";" + Text2.csv(c.reason) + "\n");
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("rekomendacje.csv"), StandardCharsets.UTF_8)) {
            w.write("czas;przedmiot;cena_rynkowa;zakup;sprzedaz;zysk_netto;ryzyko;pewnosc;trafna;cena_pozniej\n");
            for (RecRecord r : recs) {
                w.write(ts(r.t) + ";" + Text2.csv(r.name) + ";" + dec(r.market) + ";" + dec(r.buy) + ";" + dec(r.sell) + ";"
                        + dec(r.net) + ";" + r.risk + ";" + r.confidence + ";" + (r.success == null ? "" : r.success ? "tak" : "nie")
                        + ";" + dec(r.laterMarket) + "\n");
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("zakupy_bk.csv"), StandardCharsets.UTF_8)) {
            w.write("czas;przedmiot;cena_szt;ilosc\n");
            for (ItemData d : items.values()) for (Purchase p : d.purchases) {
                w.write(ts(p.t) + ";" + Text2.csv(d.name) + ";" + dec(p.unit) + ";" + p.count + "\n");
            }
        }
        return dir;
    }

    private static String ts(long t) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()));
    }

    private static String dec(double v) {
        if (Double.isNaN(v)) return "";
        return String.format(java.util.Locale.ROOT, "%.2f", v).replace('.', ',');
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static double num(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : Double.NaN;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
