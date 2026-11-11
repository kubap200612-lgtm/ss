package pl.anarchia.terminal.core;

import java.util.ArrayList;
import java.util.List;

/** Rekomendacja cen dla przedmiotu (za sztukę). */
public final class Recommendation {
    public boolean valid;
    /** dlaczego brak rekomendacji / uzasadnienie */
    public String reason = "";
    public final List<String> why = new ArrayList<>();

    public double buy = Double.NaN;
    public double sell = Double.NaN;
    public double fees = Double.NaN;
    public double net = Double.NaN;
    public double margin = Double.NaN;
    /** 0–100 */
    public int risk;
    /** 0–100 */
    public int confidence;
    public String riskLabel = "";
    /** {ilość, zysk, szacowany czas sprzedaży w h (NaN = nieznany)} */
    public final List<double[]> quantities = new ArrayList<>();
    public Status status = Status.GRAY;
}
