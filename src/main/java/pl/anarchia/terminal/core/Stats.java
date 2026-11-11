package pl.anarchia.terminal.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Statystyka opisowa. */
public final class Stats {
    private Stats() {}

    public static double mean(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        double s = 0;
        for (double x : v) s += x;
        return s / v.size();
    }

    public static List<Double> sorted(List<Double> v) {
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        return c;
    }

    /** Kwantyl (0..1) z listy posortowanej, interpolacja liniowa. */
    public static double quantileSorted(List<Double> s, double q) {
        if (s.isEmpty()) return Double.NaN;
        if (s.size() == 1) return s.get(0);
        double pos = q * (s.size() - 1);
        int i = (int) Math.floor(pos);
        int j = Math.min(s.size() - 1, i + 1);
        return s.get(i) + (s.get(j) - s.get(i)) * (pos - i);
    }

    public static double median(List<Double> v) {
        return quantileSorted(sorted(v), 0.5);
    }

    public static double stdev(List<Double> v) {
        if (v.size() < 2) return Double.NaN;
        double m = mean(v), s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / (v.size() - 1));
    }

    /**
     * Odrzuca anomalie: przy ≥ 4 wartościach metodą IQR (1,5×), przy mniejszej liczbie —
     * wartości poniżej połowy / powyżej 3× mediany. Zwraca posortowaną listę.
     */
    public static List<Double> withoutOutliers(List<Double> v) {
        List<Double> s = sorted(v);
        if (s.size() < 2) return s;
        double lo, hi;
        if (s.size() >= 4) {
            double q1 = quantileSorted(s, 0.25), q3 = quantileSorted(s, 0.75);
            double iqr = Math.max(q3 - q1, q1 * 0.02);
            lo = q1 - 1.5 * iqr;
            hi = q3 + 1.5 * iqr;
        } else {
            double m = quantileSorted(s, 0.5);
            lo = m * 0.5;
            hi = m * 3;
        }
        List<Double> out = new ArrayList<>();
        for (double x : s) if (x >= lo && x <= hi) out.add(x);
        return out.isEmpty() ? s : out;
    }

    /** Regresja liniowa y = a + b·x. Zwraca {b, a, r2} albo null. */
    public static double[] regression(List<double[]> pts) {
        int n = pts.size();
        if (n < 3) return null;
        double sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0;
        for (double[] p : pts) {
            sx += p[0];
            sy += p[1];
            sxx += p[0] * p[0];
            sxy += p[0] * p[1];
            syy += p[1] * p[1];
        }
        double den = n * sxx - sx * sx;
        if (Math.abs(den) < 1e-12) return null;
        double b = (n * sxy - sx * sy) / den;
        double a = (sy - b * sx) / n;
        double ssTot = syy - sy * sy / n;
        double ssRes = 0;
        for (double[] p : pts) {
            double e = p[1] - (a + b * p[0]);
            ssRes += e * e;
        }
        double r2 = ssTot <= 1e-12 ? 0 : Math.max(0, 1 - ssRes / ssTot);
        return new double[]{b, a, r2};
    }

    /** Histogram: {granice[bins+1], liczności[bins]}. */
    public static double[][] histogram(List<Double> v, int bins) {
        if (v.isEmpty() || bins < 1) return null;
        double lo = Collections.min(v), hi = Collections.max(v);
        if (hi - lo < 1e-9) {
            lo = lo * 0.98;
            hi = hi * 1.02 + 1e-6;
        }
        double[] edges = new double[bins + 1];
        double[] counts = new double[bins];
        for (int i = 0; i <= bins; i++) edges[i] = lo + (hi - lo) * i / bins;
        for (double x : v) {
            int b = (int) ((x - lo) / (hi - lo) * bins);
            counts[Math.max(0, Math.min(bins - 1, b))]++;
        }
        return new double[][]{edges, counts};
    }
}
