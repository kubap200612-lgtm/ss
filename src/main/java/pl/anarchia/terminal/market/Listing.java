package pl.anarchia.terminal.market;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import pl.anarchia.terminal.bk.BkBridge;
import pl.anarchia.terminal.core.Fmt;
import pl.anarchia.terminal.core.Text2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Jedna oferta odczytana z okna rynku. */
public final class Listing {
    private static final Pattern DIGIT = Pattern.compile("\\d");

    public final String id;
    public final double total;
    public final int count;
    public final double unit;
    public final String seller;
    public final boolean own;
    /** klucze przedmiotów: pozycje BK (bk:...) albo ogólny klucz przedmiotu (mc:...) */
    public final List<String> keys;
    public final List<String> names;
    public final List<BkBridge.BkItem> bkItems;
    public final String material;

    private Listing(String id, double total, int count, String seller, boolean own, List<String> keys, List<String> names,
                    List<BkBridge.BkItem> bkItems, String material) {
        this.id = id;
        this.total = total;
        this.count = count;
        this.unit = total / count;
        this.seller = seller;
        this.own = own;
        this.keys = keys;
        this.names = names;
        this.bkItems = bkItems;
        this.material = material;
    }

    private static final Map<String, Object[]> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) {
            return size() > 3000;
        }
    };
    private static Object cacheOwner;

    /** Odczyt oferty; null gdy slot nie jest ofertą (brak ceny). */
    public static Listing read(ItemStack st) {
        if (st == null || st.isEmpty()) return null;
        List<String> lore = new ArrayList<>();
        LoreComponent lc = st.get(DataComponentTypes.LORE);
        if (lc != null) for (Text t : lc.lines()) lore.add(Text2.strip(t.getString()));
        double total = BkBridge.extractPrice(st);
        if (total <= 0) {
            for (String l : lore) {
                total = Fmt.priceFromLine(l);
                if (total > 0) break;
            }
        }
        if (total <= 0) return null;
        int count = Math.max(1, st.getCount());
        String seller = seller(lore);
        String me = playerName();
        boolean own = !me.isEmpty() && seller.equalsIgnoreCase(me);
        String path = Registries.ITEM.getId(st.getItem()).getPath();
        String name = Text2.strip(st.getName().getString());

        StringBuilder stable = new StringBuilder();
        for (String l : lore) if (!DIGIT.matcher(l).find() && !isSellerLine(l)) stable.append(l).append('|');
        String ench = String.valueOf(st.getEnchantments()) + String.valueOf(st.get(DataComponentTypes.STORED_ENCHANTMENTS));
        String id = path + "|" + count + "|" + name + "|" + Math.round(total * 100) + "|" + seller + "|"
                + Integer.toHexString((stable.toString() + ench).hashCode());

        Object owner = BkBridge.items();
        if (owner != cacheOwner) {
            CACHE.clear();
            cacheOwner = owner;
        }
        String sig = path + "|" + name + "|" + stable + "|" + ench + "|" + String.valueOf(st.getComponentChanges()).hashCode();
        Object[] m = CACHE.get(sig);
        if (m == null) {
            List<BkBridge.BkItem> bk = BkBridge.match(st);
            List<String> keys = new ArrayList<>();
            List<String> names = new ArrayList<>();
            if (!bk.isEmpty()) {
                for (BkBridge.BkItem it : bk) {
                    keys.add(it.key);
                    names.add(it.name);
                }
            } else {
                keys.add("mc:" + Text2.sha(path + "|" + name + "|" + stable + "|" + ench));
                String extra = "";
                if (path.equals("enchanted_book") && !stable.toString().isEmpty()) {
                    String first = stable.toString().split("\\|")[0].trim();
                    if (!first.isEmpty()) extra = " · " + first;
                }
                names.add(name + extra);
            }
            m = new Object[]{keys, names, bk};
            CACHE.put(sig, m);
        }
        @SuppressWarnings("unchecked") List<String> keys = (List<String>) m[0];
        @SuppressWarnings("unchecked") List<String> names = (List<String>) m[1];
        @SuppressWarnings("unchecked") List<BkBridge.BkItem> bk = (List<BkBridge.BkItem>) m[2];
        return new Listing(id, total, count, seller, own, keys, names, bk, path);
    }

    private static boolean isSellerLine(String l) {
        String n = Text2.norm(l);
        return n.contains("wystaw") || n.contains("sprzedaj") || n.contains("sprzedawca") || n.contains("seller")
                || n.contains("wlasciciel") || n.contains("koszt") || n.contains("cena");
    }

    static String seller(List<String> lore) {
        for (String raw : lore) {
            String n = Text2.norm(raw);
            if (n.contains("wystaw") || n.contains("sprzedaj") || n.contains("sprzedawca") || n.contains("seller")
                    || n.contains("wlasciciel")) {
                int i = raw.indexOf(':');
                String rest = (i >= 0 ? raw.substring(i + 1) : raw).trim();
                String[] parts = rest.split("\\s+");
                return parts.length == 0 ? "" : parts[0].replaceAll("[^A-Za-z0-9_]", "");
            }
        }
        return "";
    }

    static String playerName() {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            return mc.getSession().getUsername();
        } catch (Throwable t) {
            return mc.player == null ? "" : mc.player.getName().getString();
        }
    }
}
