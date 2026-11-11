package pl.anarchia.terminal.market;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import pl.anarchia.terminal.bk.BkBridge;
import pl.anarchia.terminal.core.Text2;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rozpoznawanie okna rynku /ah i jego elementów (tylko odczyt). */
public final class MarketScreens {
    private MarketScreens() {}

    private static final Pattern PAGE = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    public static boolean isMarket(Screen s) {
        if (!(s instanceof HandledScreen<?>)) return false;
        String t = Text2.norm(s.getTitle().getString());
        if (t.contains("potwierd") || t.contains("confirm") || t.contains("anuluj")) return false;
        String custom = BkBridge.ok() ? BkBridge.marketTitle() : "";
        if (!custom.isEmpty() && t.contains(custom)) return true;
        return t.contains("rynek") || t.contains("aukcj") || t.contains("auction") || t.contains("market");
    }

    public static ScreenHandler handler(Screen s) {
        return s instanceof HandledScreen<?> ? ((HandledScreen<?>) s).getScreenHandler() : null;
    }

    /** {strona, wszystkie} z tytułu "Rynek (2/9)" albo null. */
    public static int[] page(Screen s) {
        Matcher m = PAGE.matcher(s.getTitle().getString());
        int[] out = null;
        while (m.find()) {
            try {
                int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
                if (a >= 1 && b >= a) out = new int[]{a, b};
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static List<Slot> containerSlots(ScreenHandler h) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : h.slots) if (!(s.inventory instanceof PlayerInventory)) out.add(s);
        return out;
    }

    /** Sloty z ofertami (bez dolnego rzędu przycisków). */
    public static List<Slot> listingSlots(ScreenHandler h) {
        List<Slot> c = containerSlots(h);
        return c.size() >= 18 ? new ArrayList<>(c.subList(0, c.size() - 9)) : c;
    }

    public static List<Slot> bottomRow(ScreenHandler h) {
        List<Slot> c = containerSlots(h);
        return c.size() >= 18 ? new ArrayList<>(c.subList(c.size() - 9, c.size())) : new ArrayList<>();
    }

    /** Czy przycisk sortowania ma wybraną opcję "Najnowsze" (podświetloną na żółto/złoto). */
    public static boolean newestSelected(ScreenHandler h) {
        for (Slot s : bottomRow(h)) {
            ItemStack st = s.getStack();
            if (st.isEmpty()) continue;
            LoreComponent lore = st.get(DataComponentTypes.LORE);
            if (lore == null) continue;
            boolean isSort = false;
            for (Text line : lore.lines()) {
                String n = Text2.norm(line.getString());
                if (n.contains("nizsz") || n.contains("wyzsz") || n.contains("sortow")) isSort = true;
            }
            if (!isSort) continue;
            for (Text line : lore.lines()) {
                if (Text2.norm(line.getString()).contains("nowsz") && highlighted(line)) return true;
            }
        }
        return false;
    }

    public static boolean highlighted(Text t) {
        TextColor c = t.getStyle().getColor();
        if (c != null) {
            int rgb = c.getRgb() & 0xFFFFFF;
            if (rgb == 0xFFFF55 || rgb == 0xFFAA00) return true;
        }
        for (Text sib : t.getSiblings()) if (!sib.getString().isBlank() && highlighted(sib)) return true;
        return false;
    }

    public static String loreText(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Text line : lore.lines()) sb.append(line.getString()).append('\n');
        return sb.toString();
    }

    public static String signature(List<Slot> slots) {
        StringBuilder sb = new StringBuilder();
        for (Slot s : slots) {
            ItemStack st = s.getStack();
            if (st.isEmpty()) continue;
            sb.append(s.id).append(':').append(Registries.ITEM.getId(st.getItem()).getPath()).append('x').append(st.getCount())
                    .append(':').append(st.getName().getString().hashCode()).append(':').append(loreText(st).hashCode()).append(';');
        }
        return sb.toString();
    }
}
