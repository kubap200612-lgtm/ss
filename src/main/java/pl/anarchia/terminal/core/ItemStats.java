package pl.anarchia.terminal.core;

/** Statystyki przedmiotu. Ceny za sztukę; NaN = brak danych. */
public final class ItemStats {
    public String key, name;
    public boolean bk;

    // oferty aktualnie wiszące
    public int activeCount;
    public double activeMin = Double.NaN, activeMax = Double.NaN, activeMean = Double.NaN, activeMedian = Double.NaN;
    /** najtańsza realna konkurencja (po odrzuceniu anomalii) */
    public double competitor = Double.NaN;

    // oferty zauważone w oknie analizy
    public int windowOffers;
    public double windowMin = Double.NaN, windowMax = Double.NaN, windowMean = Double.NaN, windowMedian = Double.NaN;

    /** cena rynkowa — odporna na pojedyncze skrajne oferty */
    public double market = Double.NaN;
    public String marketSource = "";

    // popyt / podaż
    public int sales;
    public double salesPerHour = Double.NaN;
    public double saleMedian = Double.NaN;
    public double saleMedianAgeMin = Double.NaN;
    public double newPerHour = Double.NaN;
    public double activeMedianAgeMin = Double.NaN;
    public boolean fastSelling, slowMoving;

    // dynamika
    public Trend trend = Trend.UNKNOWN;
    public double trendPctPerHour = Double.NaN;
    public double trendR2 = Double.NaN;
    /** {a, b} linii trendu w czasie: cena = a + b·(t - t0)/h */
    public double trendA = Double.NaN, trendB = Double.NaN;
    public long trendT0;
    public double change24h = Double.NaN;
    public double volatility = Double.NaN;

    /** histogram cen ofert w oknie: {granice, liczności} */
    public double[][] histogram;
    /** najczęstszy przedział cen */
    public double modeLo = Double.NaN, modeHi = Double.NaN;

    public int dataPoints;
    public long lastData;
}
