package pl.anarchia.terminal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.anarchia.terminal.core.Settings;
import pl.anarchia.terminal.db.MarketDb;
import pl.anarchia.terminal.gui.TerminalScreen;
import pl.anarchia.terminal.market.Engine;
import pl.anarchia.terminal.market.MarketObserver;
import pl.anarchia.terminal.market.PriceManager;
import pl.anarchia.terminal.market.PurchaseWatcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Rynek Terminal — analiza rynku Anarchia.gg i optymalizacja cen w AutoRynku BK. */
public final class TerminalMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Rynek Terminal");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path ROOT;
    public static Settings SETTINGS = new Settings();
    public static MarketDb DB;
    public static Engine ENGINE;
    public static PriceManager PRICES;
    public static MarketObserver OBSERVER;
    public static PurchaseWatcher PURCHASES;
    public static KeyBinding OPEN_KEY;
    private static boolean openNextTick;

    @Override
    public void onInitializeClient() {
        ROOT = FabricLoader.getInstance().getConfigDir().resolve("rynekterminal");
        loadSettings();
        DB = new MarketDb(ROOT);
        try {
            DB.load(SETTINGS);
        } catch (RuntimeException e) {
            LOG.error("[Rynek Terminal] Błąd wczytywania bazy", e);
        }
        ENGINE = new Engine(DB, SETTINGS);
        PRICES = new PriceManager(DB, ENGINE, SETTINGS);
        OBSERVER = new MarketObserver(DB, SETTINGS);
        PURCHASES = new PurchaseWatcher(DB);

        OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rynekterminal.open", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J, "category.rynekterminal"));
        ClientTickEvents.END_CLIENT_TICK.register(TerminalMod::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
            saveSettings();
            DB.close();
        });
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> d.register(ClientCommandManager.literal("terminal")
                .executes(ctx -> {
                    openNextTick = true;
                    return 1;
                })
                .then(ClientCommandManager.literal("eksport").executes(ctx -> {
                    try {
                        chat("§aZapisano CSV: " + DB.exportCsv());
                    } catch (Exception e) {
                        chat("§cEksport nieudany: " + e.getMessage());
                    }
                    return 1;
                }))
                .then(ClientCommandManager.literal("analiza").executes(ctx -> mode(Settings.Mode.ANALIZA)))
                .then(ClientCommandManager.literal("rekomendacje").executes(ctx -> mode(Settings.Mode.REKOMENDACJE)))
                .then(ClientCommandManager.literal("auto").executes(ctx -> mode(Settings.Mode.AUTO)))));
        LOG.info("[Rynek Terminal] Załadowano");
    }

    private static int mode(Settings.Mode m) {
        SETTINGS.mode = m;
        saveSettings();
        chat("§7Tryb: §f" + m.label + " §7- " + m.desc);
        return 1;
    }

    private static void tick(MinecraftClient mc) {
        while (OPEN_KEY.wasPressed()) openNextTick = true;
        if (openNextTick && mc.player != null) {
            openNextTick = false;
            mc.setScreen(new TerminalScreen());
        }
        long now = System.currentTimeMillis();
        try {
            if (mc.player != null) {
                PURCHASES.tick();
                OBSERVER.tick(mc);
            }
            ENGINE.tick(now);
            PRICES.tick(now);
        } catch (RuntimeException e) {
            LOG.warn("[Rynek Terminal] Błąd: {}", e.toString());
        }
        DB.tick(SETTINGS, now);
    }

    public static void chat(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(Text.literal("§9[Terminal] §r" + msg));
    }

    private static void loadSettings() {
        try {
            Path p = ROOT.resolve("settings.json");
            if (Files.exists(p)) {
                Settings s = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Settings.class);
                if (s != null) SETTINGS = s;
            }
        } catch (Exception e) {
            LOG.warn("[Rynek Terminal] Ustawienia domyślne ({})", e.toString());
        }
        SETTINGS.fix();
    }

    public static void saveSettings() {
        try {
            SETTINGS.fix();
            Files.createDirectories(ROOT);
            Path tmp = ROOT.resolve("settings.json.tmp");
            Files.writeString(tmp, GSON.toJson(SETTINGS), StandardCharsets.UTF_8);
            Files.move(tmp, ROOT.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.warn("[Rynek Terminal] Zapis ustawień nieudany: {}", e.toString());
        }
    }
}
