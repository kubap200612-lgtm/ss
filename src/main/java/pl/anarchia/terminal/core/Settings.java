package pl.anarchia.terminal.core;

import java.util.HashSet;
import java.util.Set;

/** Ustawienia (config/rynekterminal/settings.json). */
public final class Settings {
    public enum Mode {
        ANALIZA("Analiza", "tylko zbiera dane, nie zmienia cen"),
        REKOMENDACJE("Rekomendacje", "proponuje ceny, Ty zatwierdzasz każdą zmianę"),
        AUTO("Automatyczna", "sam aktualizuje ceny w AutoRynku BK z zabezpieczeniami");

        public final String label, desc;

        Mode(String label, String desc) {
            this.label = label;
            this.desc = desc;
        }
    }

    public Mode mode = Mode.AUTO;
    /** wersja pliku ustawień (migracja) */
    public int settingsVersion = 0;
    /** opłata od sprzedaży na rynku, % */
    public double feePct = 3.0;
    /** minimalny zysk netto na sztuce, $ */
    public double minProfitAbs = 1;
    /** minimalna marża netto, % ceny zakupu */
    public double minMarginPct = 5;
    /** minimalna pewność rekomendacji (0–100) */
    public int minConfidence = 50;
    /** maksymalna zmiana ceny w BK na raz, % */
    public double maxChangePct = 10;
    /** co ile minut tryb automatyczny sprawdza ceny */
    public int autoIntervalMin = 5;
    /** minimalny odstęp między zmianami ceny jednego przedmiotu, min */
    public int cooldownMin = 10;
    /** zmiany mniejsze niż ten próg nie są proponowane, % */
    public double minChangePct = 1;
    /** okno analizy (h) */
    public int windowHours = 24;
    /** jak długo pamiętać ofertę niewidzianą na rynku, min */
    public int offerTtlMin = 360;
    /** ile dni historii trzymać w pamięci (wykresy) */
    public int memoryDays = 7;
    /** ile dni trzymać pliki bazy */
    public int retentionDays = 60;
    /** okazja: oferta tańsza o tyle % od typowej ceny */
    public double discountPct = 10;
    /** okazja: oferta droższa od ceny BK najwyżej o tyle %, ale nadal opłacalna */
    public double slightlyAbovePct = 10;
    /** minimalna płynność (sprzedaże / h), poniżej — wysokie ryzyko */
    public double minLiquidity = 0.1;
    /** po ilu godzinach oceniać skuteczność rekomendacji */
    public int evalHorizonH = 6;
    /** przedmioty wyłączone z automatyzacji (klucze) */
    public Set<String> autoExcluded = new HashSet<>();

    public double fee() {
        return Math.max(0, Math.min(50, feePct)) / 100.0;
    }

    public long windowMs() {
        return Math.max(1, windowHours) * 3_600_000L;
    }

    public void fix() {
        if (mode == null) mode = Mode.AUTO;
        if (settingsVersion < 2) {
            // 1.1: mod sam reguluje ceny w BK (tryb automatyczny) i analizuje tylko przedmioty z listy BK
            mode = Mode.AUTO;
            settingsVersion = 2;
        }
        if (autoExcluded == null) autoExcluded = new HashSet<>();
        feePct = clamp(feePct, 0, 50);
        minProfitAbs = clamp(minProfitAbs, 0, 1e12);
        minMarginPct = clamp(minMarginPct, 0, 500);
        minConfidence = (int) clamp(minConfidence, 0, 100);
        maxChangePct = clamp(maxChangePct, 0.5, 100);
        autoIntervalMin = (int) clamp(autoIntervalMin, 1, 240);
        cooldownMin = (int) clamp(cooldownMin, 0, 1440);
        minChangePct = clamp(minChangePct, 0, 50);
        windowHours = (int) clamp(windowHours, 1, 168);
        offerTtlMin = (int) clamp(offerTtlMin, 10, 4320);
        memoryDays = (int) clamp(memoryDays, 1, 30);
        retentionDays = (int) clamp(retentionDays, memoryDays, 365);
        discountPct = clamp(discountPct, 1, 90);
        slightlyAbovePct = clamp(slightlyAbovePct, 0, 50);
        minLiquidity = clamp(minLiquidity, 0, 100);
        evalHorizonH = (int) clamp(evalHorizonH, 1, 72);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
