package pl.anarchia.terminal.market;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import pl.anarchia.terminal.core.ItemData;
import pl.anarchia.terminal.core.Offer;
import pl.anarchia.terminal.core.PageDiff;
import pl.anarchia.terminal.core.Purchase;
import pl.anarchia.terminal.core.Settings;
import pl.anarchia.terminal.db.MarketDb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pasywny odczyt rynku: czyta wyłącznie to, co jest wyświetlane w oknie /ah (otwartym przez Ciebie
 * albo przez BK). Nie klika, nie wysyła komend. Porównuje kolejne widoki strony "Najnowsze":
 * nowe oferty → zapis; oferta znika ze środka strony → transakcja (ktoś kupił).
 */
public final class MarketObserver {
    private static final long GAP = 10_000L;
    private static final long PENDING = 6_000L;

    private static final class Seen {
        final Listing l;

        Seen(Listing l) {
            this.l = l;
        }
    }

    private final MarketDb db;
    private final Settings settings;
    private List<String> prevIds;
    private Map<String, Seen> prevById = new HashMap<>();
    private boolean prevOrdered;
    private long prevAt;
    private String pendingSig, processedSig;
    private int stable;
    private final List<Object[]> pendingSold = new ArrayList<>(); // {Seen, czas}
    private final Set<String> known = new HashSet<>();
    private long lastSeenMarket;
    private int sessionOffers, sessionSales;
    private boolean ordered;

    public MarketObserver(MarketDb db, Settings settings) {
        this.db = db;
        this.settings = settings;
    }

    public boolean watching() {
        return System.currentTimeMillis() - lastSeenMarket < 60_000L;
    }

    public long lastSeenMarket() {
        return lastSeenMarket;
    }

    public boolean orderedView() {
        return ordered;
    }

    public int sessionOffers() {
        return sessionOffers;
    }

    public int sessionSales() {
        return sessionSales;
    }

    public void tick(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        confirmPending(now);
        Screen scr = mc.currentScreen;
        if (mc.player == null || !MarketScreens.isMarket(scr)) {
            if (prevIds != null && now - prevAt > GAP) prevIds = null;
            return;
        }
        ScreenHandler h = MarketScreens.handler(scr);
        if (h == null) return;
        List<Slot> slots = MarketScreens.listingSlots(h);
        String sig = MarketScreens.signature(slots);
        lastSeenMarket = now;
        if (!sig.equals(pendingSig)) {
            pendingSig = sig;
            stable = 0;
            return;
        }
        if (++stable < 1 || sig.equals(processedSig)) return;
        processedSig = sig;

        int[] pg = MarketScreens.page(scr);
        ordered = (pg == null || pg[0] == 1) && MarketScreens.newestSelected(h);

        List<String> raw = new ArrayList<>();
        List<Listing> ls = new ArrayList<>();
        for (Slot s : slots) {
            ItemStack st = s.getStack();
            if (st.isEmpty()) continue;
            Listing l = Listing.read(st);
            if (l == null) continue;
            raw.add(l.id);
            ls.add(l);
        }
        List<String> ids = PageDiff.withOccurrence(raw);
        if (prevIds != null && prevIds.size() >= 6 && ids.size() < prevIds.size() / 2) return; // niedoładowana strona

        Map<String, Seen> byId = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Listing l = ls.get(i);
            byId.put(id, new Seen(l));
            if (l.own) continue;
            boolean isBk = !l.bkItems.isEmpty();
            if (!isBk) continue; // analizujemy tylko przedmioty z listy AutoRynku BK
            for (int k = 0; k < l.keys.size(); k++) {
                String key = l.keys.get(k);
                db.meta(key, l.names.get(k), l.material, isBk);
                ItemData d = db.item(key);
                Offer existing = d.active.get(id);
                if (existing != null) {
                    existing.last = now;
                    continue;
                }
                if (!known.add(key + "|" + id)) continue; // już obsłużona w tej sesji (np. sprzedana)
                Offer prior = null;
                for (Offer o : d.seen) if (o.id.equals(id)) prior = o;
                if (prior != null) {
                    if (now - prior.first < settings.offerTtlMin * 60_000L) {
                        prior.last = now;
                        d.active.put(id, prior);
                    }
                } else {
                    db.addOffer(key, new Offer(id, l.unit, l.count, l.seller, now));
                    if (k == 0) sessionOffers++;
                }
            }
        }

        if (ordered && prevOrdered && prevIds != null && now - prevAt <= GAP) {
            PageDiff d = PageDiff.diff(prevIds, ids, slots.size());
            for (String rid : d.removed) {
                Seen s = prevById.get(rid);
                if (s == null || s.l.own) continue;
                pendingSold.add(new Object[]{rid, s, now});
            }
        }
        prevIds = ids;
        prevById = byId;
        prevOrdered = ordered;
        prevAt = now;
        if (known.size() > 50_000) known.clear();
    }

    /** Zniknięcie potwierdzamy po kilku sekundach — jeśli kupił to BK (Ty), nie liczy się jako popyt rynku. */
    private void confirmPending(long now) {
        Iterator<Object[]> it = pendingSold.iterator();
        while (it.hasNext()) {
            Object[] p = it.next();
            long at = (Long) p[2];
            if (now - at < PENDING) continue;
            it.remove();
            String id = (String) p[0];
            Seen s = (Seen) p[1];
            boolean ours = false;
            for (String key : s.l.keys) {
                ItemData d = db.get(key);
                if (d == null) continue;
                for (Purchase pu : d.purchases) {
                    if (Math.abs(pu.t - at) <= 30_000L && Math.abs(pu.unit - s.l.unit) <= Math.max(0.01, s.l.unit * 0.005)) ours = true;
                }
            }
            for (String key : s.l.keys) db.removeOffer(key, id, at, !ours);
            if (!ours) sessionSales++;
        }
    }
}
