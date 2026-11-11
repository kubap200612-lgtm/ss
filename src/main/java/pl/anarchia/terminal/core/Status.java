package pl.anarchia.terminal.core;

/** Oznaczenie przedmiotu (zawsze pokazywane z ikoną i tekstem, nie samym kolorem). */
public enum Status {
    GREEN("▲", "Opłacalny do kupna"),
    RED("✖", "Za drogo / ryzyko straty"),
    YELLOW("$", "Potencjał sprzedaży"),
    BLUE("■", "Stabilna cena"),
    GRAY("•", "Za mało danych / bez sygnału");

    public final String icon, label;

    Status(String icon, String label) {
        this.icon = icon;
        this.label = label;
    }
}
