package pl.anarchia.terminal.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;

/** Operacje na tekstach. */
public final class Text2 {
    private Text2() {}

    /** Usuwa kody kolorów §x / &x i hex. */
    public static String strip(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)[§&]x([§&][0-9a-f]){6}", "").replaceAll("(?i)§[0-9a-fk-orx]", "").trim();
    }

    /** Małe litery, bez polskich znaków. */
    public static String norm(String s) {
        String x = strip(s).toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    public static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    public static String csv(String s) {
        if (s == null) return "";
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
