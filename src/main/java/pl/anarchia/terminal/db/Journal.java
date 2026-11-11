package pl.anarchia.terminal.db;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Lokalna baza w postaci dziennika: jeden plik JSON Lines na dzień (db/2026-11-11.jsonl).
 * Każde zdarzenie to jedna linia dopisywana na końcu — przerwanie gry w trakcie zapisu
 * może uszkodzić najwyżej ostatnią linię, która przy wczytywaniu jest pomijana.
 */
public final class Journal {
    private final Path dir;
    private LocalDate day;
    private BufferedWriter out;
    private boolean pending;

    public Journal(Path dir) {
        this.dir = dir;
    }

    public synchronized void write(JsonObject o) {
        try {
            LocalDate today = LocalDate.now();
            if (out == null || !today.equals(day)) {
                close();
                Files.createDirectories(dir);
                day = today;
                out = Files.newBufferedWriter(dir.resolve(today + ".jsonl"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            out.write(o.toString());
            out.write('\n');
            pending = true;
        } catch (IOException e) {
            pl.anarchia.terminal.TerminalMod.LOG.warn("[Rynek Terminal] Zapis bazy nieudany: {}", e.toString());
        }
    }

    public synchronized void flush() {
        if (out == null || !pending) return;
        try {
            out.flush();
            pending = false;
        } catch (IOException e) {
            pl.anarchia.terminal.TerminalMod.LOG.warn("[Rynek Terminal] Flush bazy nieudany: {}", e.toString());
        }
    }

    public synchronized void close() {
        if (out == null) return;
        try {
            out.flush();
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    /** Wczytuje zdarzenia z ostatnich {@code days} dni (chronologicznie). */
    public void load(int days, Consumer<JsonObject> sink) {
        if (!Files.isDirectory(dir)) return;
        LocalDate from = LocalDate.now().minusDays(days);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).forEach(p -> {
                LocalDate d = dateOf(p);
                if (d != null && !d.isBefore(from)) files.add(p);
            });
        } catch (IOException e) {
            return;
        }
        files.sort(null);
        for (Path p : files) {
            try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        sink.accept(JsonParser.parseString(line).getAsJsonObject());
                    } catch (RuntimeException bad) {
                        // uszkodzona linia (np. przerwany zapis) — pomijamy
                    }
                }
            } catch (IOException e) {
                pl.anarchia.terminal.TerminalMod.LOG.warn("[Rynek Terminal] Nie wczytano {}: {}", p, e.toString());
            }
        }
    }

    /** Usuwa pliki starsze niż {@code days} dni. */
    public void retention(int days) {
        if (!Files.isDirectory(dir)) return;
        LocalDate limit = LocalDate.now().minusDays(days);
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> {
                LocalDate d = dateOf(p);
                if (d != null && d.isBefore(limit)) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static LocalDate dateOf(Path p) {
        String n = p.getFileName().toString();
        if (!n.endsWith(".jsonl")) return null;
        try {
            return LocalDate.parse(n.substring(0, n.length() - 6));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
