package pl.anarchia.terminal.bk;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import pl.anarchia.terminal.TerminalMod;
import pl.anarchia.terminal.core.Text2;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Integracja z AutoRynkiem BK (BK-Rynek 3.0).
 *
 * BK nie ma oficjalnego API dla innych modów. Jedyna możliwa integracja to odczyt i zmiana jego
 * listy cen w pamięci klienta (te same pola, które edytujesz w oknie BK) + zapis jego własną
 * metodą save(). Nic nie jest wysyłane do serwera. Jeśli BK zmieni wersję i pola znikną,
 * most się wyłącza, a mod działa dalej w trybie samej analizy.
 */
public final class BkBridge {
    private BkBridge() {}

    private static final String P = "pl.bkubiak.bkrynek.";
    private static boolean initDone, ok;
    private static String error = "";

    private static Method mActiveProfile, mList, mVersion, mMarkDirty, mFindServer, mAllEntries, mCleanMaterial,
            mMatches, mExtractPrice, mToLegacy, mShared, mRunning, mSave, mHistInstance, mHistory, mHePrice, mHeCount, mHeStack;
    private static Field fName, fLore, fMaterial, fEnchants, fEnchantMap, fComponents, fCmd, fDye, fHead, fMax, fSell,
            fReq, fSellCount, fBuyEnabled, fSellEnabled, fUnit, fSrvUnit, fSrvCommands, fSrvTitle, fScanner, fOriginals;
    private static Class<?> cEngine;

    public static synchronized boolean ok() {
        if (initDone) return ok;
        initDone = true;
        try {
            Class<?> plm = Class.forName(P + "core.manager.ClientPriceListManager");
            mActiveProfile = plm.getMethod("getActiveProfile");
            mList = plm.getMethod("getPriceListForProfile", String.class);
            mVersion = plm.getMethod("getVersion");
            mMarkDirty = plm.getDeclaredMethod("markDirty");
            mMarkDirty.setAccessible(true);
            Class<?> ce = Class.forName(P + "core.config.PriceEntry");
            fName = ce.getField("name");
            fLore = ce.getField("lore");
            fMaterial = ce.getField("material");
            fEnchants = ce.getField("enchants");
            fEnchantMap = ce.getField("enchantments");
            fComponents = ce.getField("componentCount");
            fCmd = ce.getField("customModelData");
            fDye = ce.getField("dyeColor");
            fHead = ce.getField("headTextureValue");
            fMax = ce.getField("maxPrice");
            fSell = ce.getField("sellPrice");
            fReq = ce.getField("requiredCount");
            fSellCount = ce.getField("sellCount");
            fBuyEnabled = ce.getField("buyEnabled");
            fSellEnabled = ce.getField("sellEnabled");
            fUnit = ce.getField("unitMode");
            Class<?> client = Class.forName(P + "client.BkRynekClient");
            mFindServer = client.getMethod("findServerEntryByProfile", String.class);
            Class<?> se = Class.forName(P + "core.config.ServerEntry");
            fSrvUnit = se.getField("unitMode");
            fSrvCommands = se.getField("marketCommands");
            fSrvTitle = se.getField("marketGuiTitle");
            cEngine = Class.forName(P + "client.market.CleanMarketEngine");
            mAllEntries = cEngine.getMethod("allEntries");
            fScanner = cEngine.getDeclaredField("SCANNER");
            fScanner.setAccessible(true);
            fOriginals = cEngine.getDeclaredField("originals");
            fOriginals.setAccessible(true);
            Class<?> clean = Class.forName(P + "client.market.clean.PriceEntry");
            mCleanMaterial = clean.getMethod("getMaterial");
            Class<?> scanner = Class.forName(P + "client.market.clean.AutoMarketScanner");
            mMatches = scanner.getMethod("matchesEntry", ItemStack.class, clean);
            mExtractPrice = scanner.getMethod("extractPrice", List.class);
            mToLegacy = Class.forName(P + "client.market.clean.ItemStackExtractor").getMethod("toLegacy", Text.class);
            Class<?> service = Class.forName(P + "client.market.MarketService");
            mShared = service.getMethod("shared");
            mRunning = service.getMethod("running");
            mSave = service.getMethod("save");
            Class<?> hist = Class.forName(P + "client.market.clean.AutoMarketHistoryManager");
            mHistInstance = hist.getMethod("getInstance");
            mHistory = hist.getMethod("getHistory");
            Class<?> he = Class.forName(P + "client.market.clean.AutoMarketHistoryManager$HistoryEntry");
            mHePrice = he.getMethod("price");
            mHeCount = he.getMethod("count");
            mHeStack = he.getMethod("stack");
            ok = true;
        } catch (Throwable t) {
            ok = false;
            error = "Nie znaleziono zgodnego BK-Rynek 3.0 (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
            TerminalMod.LOG.warn("[Rynek Terminal] {}", error);
        }
        return ok;
    }

    public static String error() {
        return error;
    }

    // ------------------------------------------------------------------ przedmioty z AutoRynku

    /** Pozycja z listy BK (żywy obiekt). Ceny przeliczane na sztukę. */
    public static final class BkItem {
        public final Object core;
        public final String key;
        public final String name;
        public final String material;

        BkItem(Object core, String key, String name, String material) {
            this.core = core;
            this.key = key;
            this.name = name;
            this.material = material;
        }

        public boolean buyEnabled() {
            return !Boolean.FALSE.equals(get(fBuyEnabled, core));
        }

        public boolean sellEnabled() {
            return Boolean.TRUE.equals(get(fSellEnabled, core));
        }

        public int buyMultiplier() {
            if (Boolean.TRUE.equals(get(fUnit, core)) || serverUnitMode()) return 1;
            Object rc = get(fReq, core);
            return rc instanceof Integer ? Math.max(1, (Integer) rc) : 1;
        }

        public int sellCount() {
            Object sc = get(fSellCount, core);
            return sc instanceof Integer ? Math.max(1, (Integer) sc) : 1;
        }

        public double buyPerPiece() {
            double raw = getD(fMax, core);
            return raw > 0 ? raw / buyMultiplier() : 0;
        }

        public double sellPerPiece() {
            double raw = getD(fSell, core);
            return raw > 0 ? raw / sellCount() : 0;
        }
    }

    private static long cachedVersion = Long.MIN_VALUE;
    private static String cachedProfile;
    private static List<BkItem> cached = Collections.emptyList();
    private static IdentityHashMap<Object, BkItem> byCore = new IdentityHashMap<>();
    private static Map<String, List<Object[]>> index = new HashMap<>();
    private static Object scanner;

    public static String activeProfile() {
        if (!ok()) return null;
        try {
            return (String) mActiveProfile.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static synchronized List<BkItem> items() {
        if (!ok()) return Collections.emptyList();
        String profile = activeProfile();
        if (profile == null) return Collections.emptyList();
        long v;
        List<?> live;
        try {
            v = (long) mVersion.invoke(null);
            Object l = mList.invoke(null, profile);
            live = l instanceof List ? (List<?>) l : Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        if (v == cachedVersion && profile.equals(cachedProfile) && live.size() == cached.size()) return cached;
        List<BkItem> out = new ArrayList<>();
        IdentityHashMap<Object, BkItem> map = new IdentityHashMap<>();
        Map<String, Integer> names = new HashMap<>();
        List<Object[]> raw = new ArrayList<>();
        for (Object core : new ArrayList<>(live)) {
            if (core == null) continue;
            String mat = normMaterial(str(fMaterial, core));
            String name = Text2.strip(str(fName, core));
            if (name.isEmpty()) name = mat.replace('_', ' ');
            raw.add(new Object[]{core, mat, name});
            names.merge(name, 1, Integer::sum);
        }
        for (Object[] r : raw) {
            String name = (String) r[2];
            if (names.get(name) > 1) {
                String hint = firstLore(str(fLore, r[0]));
                if (!hint.isEmpty()) name += " · " + hint;
            }
            BkItem it = new BkItem(r[0], "bk:" + keyOf(r[0]), name, (String) r[1]);
            out.add(it);
            map.put(r[0], it);
        }
        cached = Collections.unmodifiableList(out);
        byCore = map;
        cachedVersion = v;
        cachedProfile = profile;
        buildIndex();
        return cached;
    }

    public static synchronized void invalidate() {
        cachedVersion = Long.MIN_VALUE;
    }

    private static String keyOf(Object core) {
        StringBuilder sb = new StringBuilder();
        sb.append(normMaterial(str(fMaterial, core))).append('|').append(str(fName, core)).append('|')
                .append(str(fLore, core)).append('|').append(str(fEnchants, core)).append('|');
        Object em = get(fEnchantMap, core);
        if (em instanceof Map) {
            TreeMap<String, String> s = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) em).entrySet()) s.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            sb.append(s);
        }
        sb.append('|').append(get(fComponents, core)).append('|').append(get(fCmd, core)).append('|')
                .append(get(fDye, core)).append('|').append(Text2.sha(str(fHead, core)));
        return Text2.sha(sb.toString());
    }

    private static String firstLore(String lore) {
        for (String part : lore.split("[;\\n]")) {
            String p = Text2.strip(part).replaceAll("^[»>\\-•\\s]+", "").trim();
            if (!p.isEmpty()) return p;
        }
        return "";
    }

    static String normMaterial(String m) {
        if (m == null) return "*";
        String s = m.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) s = s.substring(10);
        return s.isEmpty() ? "*" : s;
    }

    private static void buildIndex() {
        Map<String, List<Object[]>> idx = new HashMap<>();
        try {
            List<?> clean;
            Map<?, ?> originals;
            synchronized (cEngine) {
                clean = new ArrayList<>((List<?>) mAllEntries.invoke(null));
                originals = new IdentityHashMap<>((Map<?, ?>) fOriginals.get(null));
                scanner = fScanner.get(null);
            }
            for (Object c : clean) {
                BkItem it = byCore.get(originals.get(c));
                if (it == null) continue;
                idx.computeIfAbsent(normMaterial((String) mCleanMaterial.invoke(c)), k -> new ArrayList<>()).add(new Object[]{c, it});
            }
        } catch (Throwable t) {
            TerminalMod.LOG.warn("[Rynek Terminal] Indeks BK: {}", t.toString());
        }
        index = idx;
    }

    /** Pozycje BK, do których pasuje stack — rozpoznawanie silnikiem BK (te same przedmioty, które BK kupuje). */
    public static List<BkItem> match(ItemStack st) {
        if (st == null || st.isEmpty() || !ok()) return Collections.emptyList();
        items();
        Object sc = scanner;
        if (sc == null) return Collections.emptyList();
        String path = Registries.ITEM.getId(st.getItem()).getPath().toLowerCase(Locale.ROOT);
        List<BkItem> out = null;
        for (String k : new String[]{path, "*"}) {
            List<Object[]> cands = index.get(k);
            if (cands == null) continue;
            for (Object[] c : cands) {
                try {
                    if ((Boolean) mMatches.invoke(sc, st, c[0])) {
                        if (out == null) out = new ArrayList<>(2);
                        if (!out.contains(c[1])) out.add((BkItem) c[1]);
                    }
                } catch (Throwable t) {
                    return Collections.emptyList();
                }
            }
        }
        return out == null ? Collections.emptyList() : out;
    }

    /** Cena całej oferty z opisu (parser BK), -1 gdy brak. */
    public static double extractPrice(ItemStack st) {
        if (!ok() || scanner == null) {
            items();
            if (scanner == null) return -1;
        }
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        List<String> lines = new ArrayList<>();
        try {
            for (Text t : lore.lines()) lines.add((String) mToLegacy.invoke(null, t));
            double p = (double) mExtractPrice.invoke(scanner, lines);
            return p > 0 ? p : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ zapis cen

    /** Ustawia ceny (za sztukę; NaN = bez zmiany) i zapisuje profil BK. */
    public static boolean setPrices(BkItem it, double buyPerPiece, double sellPerPiece) {
        if (!ok()) return false;
        try {
            if (!Double.isNaN(buyPerPiece) && buyPerPiece > 0) {
                double v = buyPerPiece * it.buyMultiplier();
                v = v >= 100 ? Math.floor(v) : Math.floor(v * 100) / 100.0;
                fMax.setDouble(it.core, Math.max(0.01, v));
            }
            if (!Double.isNaN(sellPerPiece) && sellPerPiece > 0) {
                fSell.setDouble(it.core, Math.max(1, Math.round(sellPerPiece * it.sellCount())));
            }
            mMarkDirty.invoke(null);
            mSave.invoke(mShared.invoke(null));
            invalidate();
            return true;
        } catch (Throwable t) {
            TerminalMod.LOG.warn("[Rynek Terminal] Zapis do BK nieudany: {}", t.toString());
            return false;
        }
    }

    public static boolean running() {
        if (!ok()) return false;
        try {
            return (Boolean) mRunning.invoke(mShared.invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object server() {
        String p = activeProfile();
        if (p == null) return null;
        try {
            return mFindServer.invoke(null, p);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean serverUnitMode() {
        Object se = server();
        return se != null && Boolean.TRUE.equals(get(fSrvUnit, se));
    }

    /** Własny tytuł okna rynku z profilu BK (znormalizowany, może być pusty). */
    public static String marketTitle() {
        Object se = server();
        Object t = se == null ? null : get(fSrvTitle, se);
        return t == null ? "" : Text2.norm(String.valueOf(t));
    }

    // ------------------------------------------------------------------ zakupy BK

    public static List<Object> purchaseHistory() {
        if (!ok()) return Collections.emptyList();
        try {
            Object l = mHistory.invoke(mHistInstance.invoke(null));
            return l instanceof List ? new ArrayList<Object>((List<?>) l) : Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public static double hePrice(Object e) {
        try {
            return (double) mHePrice.invoke(e);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int heCount(Object e) {
        try {
            return (int) mHeCount.invoke(e);
        } catch (Throwable t) {
            return 1;
        }
    }

    public static ItemStack heStack(Object e) {
        try {
            Object s = mHeStack.invoke(e);
            return s instanceof ItemStack ? (ItemStack) s : ItemStack.EMPTY;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static Object get(Field f, Object o) {
        try {
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static double getD(Field f, Object o) {
        try {
            return f.getDouble(o);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String str(Field f, Object o) {
        Object v = get(f, o);
        return v == null ? "" : String.valueOf(v);
    }
}
