package pl.anarchia.terminal.market;

import net.minecraft.item.ItemStack;
import pl.anarchia.terminal.bk.BkBridge;
import pl.anarchia.terminal.core.Purchase;
import pl.anarchia.terminal.db.MarketDb;

import java.util.List;

/** Zapisuje zakupy wykonane przez AutoRynek BK (z jego historii zakupów). */
public final class PurchaseWatcher {
    private final MarketDb db;
    private Object lastTop;
    private boolean primed;

    public PurchaseWatcher(MarketDb db) {
        this.db = db;
    }

    public void tick() {
        if (!BkBridge.ok()) return;
        List<Object> h = BkBridge.purchaseHistory();
        if (!primed) {
            lastTop = h.isEmpty() ? null : h.get(0);
            primed = true;
            return;
        }
        if (h.isEmpty()) {
            lastTop = null;
            return;
        }
        if (h.get(0) == lastTop) return;
        int idx = -1;
        for (int i = 0; i < h.size(); i++) if (h.get(i) == lastTop) idx = i;
        int fresh = idx < 0 ? h.size() : idx;
        for (int i = fresh - 1; i >= 0; i--) record(h.get(i));
        lastTop = h.get(0);
    }

    private void record(Object e) {
        double total = BkBridge.hePrice(e);
        int count = Math.max(1, BkBridge.heCount(e));
        ItemStack st = BkBridge.heStack(e);
        if (total <= 0 || st.isEmpty()) return;
        List<BkBridge.BkItem> m = BkBridge.match(st);
        if (m.isEmpty()) return;
        BkBridge.BkItem best = m.get(0);
        for (BkBridge.BkItem it : m) {
            double cmp = it.buyMultiplier() == 1 ? total / count : total;
            double lim = it.buyPerPiece() * it.buyMultiplier();
            if (lim > 0 && cmp <= lim + 0.01 && lim < best.buyPerPiece() * best.buyMultiplier()) best = it;
        }
        db.meta(best.key, best.name, best.material, true);
        db.addPurchase(best.key, new Purchase(System.currentTimeMillis(), total / count, count));
    }
}
