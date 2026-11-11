package pl.anarchia.terminal.core;

public enum Trend {
    RISING("rośnie"), FALLING("spada"), STABLE("stabilna"), UNKNOWN("brak danych");

    public final String label;

    Trend(String label) {
        this.label = label;
    }
}
