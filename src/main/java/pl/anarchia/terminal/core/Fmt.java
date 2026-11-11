package pl.anarchia.terminal.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formatowanie i parsowanie kwot. */
public final class Fmt {
    private Fmt() {}

    private static final Pattern PRICE_LINE = Pattern.compile(
            "(?i)(?:koszt|cena|price)[^0-9]*([0-9][0-9 .,\\u00a0\\u202f]*(?:mld|[kmbt])?)");

    /** 1 234 567$ (małe kwoty z częścią ułamkową: 12,5$). */
    public static String money(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "-";
        if (Math.abs(v) < 100 && Math.abs(v - Math.rint(v)) > 0.005) {
            return String.format(Locale.ROOT, "%.2f", v).replace('.', ',').replaceAll(",?0+$", "") + "$";
        }
        long r = Math.round(v);
        String s = Long.toString(Math.abs(r));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) sb.append(' ');
            sb.append(s.charAt(i));
        }
        return (r < 0 ? "-" : "") + sb + "$";
    }

    /** Krótko: 950, 1,2k, 15k, 1,5m. */
    public static String compact(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "-";
        double a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a >= 1e9) return sign + one(a / 1e9) + "mld";
        if (a >= 1e6) return sign + one(a / 1e6) + "m";
        if (a >= 1e4) return sign + Math.round(a / 1e3) + "k";
        if (a >= 1e3) return sign + one(a / 1e3) + "k";
        if (a >= 100) return sign + Math.round(a);
        return sign + one(a);
    }

    private static String one(double x) {
        String s = String.format(Locale.ROOT, "%.1f", x);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s.replace('.', ',');
    }

    /** Procent z ułamka: 0.2 → "20%"; signed dodaje "+". */
    public static String pct(double f, boolean signed) {
        if (Double.isNaN(f) || Double.isInfinite(f)) return "-";
        String s = one(f * 100);
        if (signed && f > 0) s = "+" + s;
        return s + "%";
    }

    public static String num1(double v) {
        if (Double.isNaN(v)) return "-";
        return one(v);
    }

    /** Cena z linii opisu ("Koszt: 1.5k$"), -1 gdy brak. */
    public static double priceFromLine(String plain) {
        if (plain == null) return -1;
        Matcher m = PRICE_LINE.matcher(plain);
        return m.find() ? parse(m.group(1)) : -1;
    }

    /** "1.5k", "1 000", "1,000", "1.000.000", "2,5mld", "12.50" → liczba, -1 gdy błąd. */
    public static double parse(String raw) {
        if (raw == null) return -1;
        String s = raw.toLowerCase(Locale.ROOT).replace("$", "").replace(" ", "").replace(" ", "")
                .replace(" ", "").trim();
        double mult = 1;
        if (s.endsWith("mld")) { mult = 1e9; s = s.substring(0, s.length() - 3); }
        else if (s.endsWith("k")) { mult = 1e3; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1e6; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("b")) { mult = 1e9; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("t")) { mult = 1e12; s = s.substring(0, s.length() - 1); }
        s = s.replaceAll("[^0-9.,]", "");
        while (!s.isEmpty() && (s.endsWith(".") || s.endsWith(","))) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return -1;
        int dots = count(s, '.'), commas = count(s, ',');
        String num;
        if (dots > 0 && commas > 0) {
            char dec = s.lastIndexOf('.') > s.lastIndexOf(',') ? '.' : ',';
            char thou = dec == '.' ? ',' : '.';
            num = s.replace(String.valueOf(thou), "").replace(dec, '.');
        } else if (dots + commas == 0) {
            num = s;
        } else {
            char sep = dots > 0 ? '.' : ',';
            int after = s.length() - s.lastIndexOf(sep) - 1;
            num = (Math.max(dots, commas) > 1 || (after == 3 && mult == 1)) ? s.replace(String.valueOf(sep), "") : s.replace(sep, '.');
        }
        try {
            double v = Double.parseDouble(num) * mult;
            return v > 0 && Double.isFinite(v) ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /** Krok cenowy ~0,1–1% ceny (999 → 1, 1500 → 10, 25k → 100). */
    public static double step(double p) {
        if (!(p > 0)) return 1;
        return Math.max(0.01, Math.pow(10, Math.floor(Math.log10(p)) - 2));
    }

    /** Zaokrąglenie w dół do "ładnej" kwoty (od 100$ pełne dolary). */
    public static double roundDown(double v) {
        if (!(v > 0)) return v;
        double g = Math.max(step(v) / 10, v >= 100 ? 1 : 0.01);
        return Math.round(Math.floor(v / g + 1e-9) * g * 100) / 100.0;
    }

    public static double roundUp(double v) {
        if (!(v > 0)) return v;
        double g = Math.max(step(v) / 10, v >= 100 ? 1 : 0.01);
        return Math.round(Math.ceil(v / g - 1e-9) * g * 100) / 100.0;
    }
}
