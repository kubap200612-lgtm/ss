# Rynek Terminal (Fabric 1.21.4)

Terminal analizy rynku dla serwera **Anarchia.gg**. Analizuje **wyłącznie przedmioty z Twojej listy w AutoRynku BK**
(BK-Rynek 3.0): zbiera ich ceny z okna `/ah`, liczy statystyki i trendy, rysuje wykresy, wykrywa okazje
i **sam podnosi lub obniża ceny skupu i sprzedaży w BK** zależnie od rynku.

## 1. Analiza techniczna (co jest możliwe)

| Pytanie | Ustalenie |
|---|---|
| Wersja i loader | **Minecraft 1.21.4 + Fabric** — ta sama wersja co BK-Rynek 3.0 (z niego korzystasz), więc oba mody działają razem. |
| Zgodność z Anarchia.gg | Mod działa wyłącznie po stronie klienta. Nie wysyła żadnych pakietów, komend ani kliknięć. |
| Skąd dane rynku | Serwer nie ma publicznego API rynku. Jedyne źródło to **okno `/ah` wyświetlane w grze**. Mod czyta tylko to, co widać na ekranie (otwartym przez Ciebie albo przez BK-Rynek). Nie przewija stron i nie klika za Ciebie. |
| Transakcje | Serwer nie pokazuje historii sprzedaży. Mod wykrywa je pośrednio: oferta, która **znika ze środka** pierwszej strony posortowanej „Najnowsze”, została kupiona. Zakupy samego BK są odfiltrowywane. |
| Integracja z AutoRynkiem BK | BK **nie ma oficjalnego API** dla innych modów. Mod zmienia lokalną listę cen BK — te same pola co w oknie BK — i zapisuje ją metodą BK (refleksja). Do serwera nic nie trafia. Gdy BK zmieni wersję, integracja sama się wyłącza i zostaje tryb analizy. |
| Regulamin | Nie znalazłem publicznego regulaminu Anarchia.gg dotyczącego modów i botów. **Sprawdź go sam.** Domyślny tryb to *Automatyczny*; w razie potrzeby przełączysz na *Rekomendacje* (zatwierdzasz każdą zmianę) albo *Analizę*. |

## 2. Architektura

```
core/     analiza (czysta Java, testowalna)
          StatsCalculator – min/max/średnia/mediana, cena rynkowa odporna na anomalie, trend (regresja),
                            zmienność, histogram przedziałów cen, płynność (transakcje/h, czas sprzedaży)
          Recommender     – rekomendowane ceny zakupu/sprzedaży, zysk, marża, ryzyko, pewność, ilości
          OpportunityFinder – okazje (taniej od typowej ceny, lekko drożej od BK ale opłacalne, rosnące ceny)
          Profit          – algorytm opłacalności (zakup, sprzedaż, koszty → zysk netto, marża)
db/       Journal – lokalna baza: dziennik JSON Lines, 1 plik na dzień, odporny na przerwanie zapisu
          MarketDb – dane w pamięci + zapis zdarzeń + eksport CSV
market/   MarketObserver – pasywny odczyt /ah; PurchaseWatcher – zakupy BK;
          Engine – analizy, historia cen co 5 min, ocena rekomendacji; PriceManager – 3 tryby cen BK
bk/       BkBridge – integracja z BK-Rynek 3.0 (opcjonalna)
gui/      TerminalScreen (7 zakładek), PriceChart (wykres + histogram), Theme
```

## 3. Jak liczone są ceny

- **Cena rynkowa** nie opiera się na najtańszej ofercie. To średnia z mediany realnych transakcji i mediany tańszej połowy
  wiszących ofert. Skrajne oferty są odrzucane metodą IQR.
- **Sprzedaż**: o krok taniej niż najtańsza realna konkurencja, w przedziale 97–110% ceny rynkowej, z korektą o trend (~3 h).
- **Zakup**: najwyższa cena, przy której po opłacie rynku zostaje min. zysk ($) i min. marża (%) oraz bufor na ryzyko.
- **Opłacalność**: `zysk netto = sprzedaż − koszty − zakup`, `marża = zysk / zakup`
  (zakup 100 000$, sprzedaż 125 000$, koszty 5 000$ → zysk 20 000$, marża 20%).
- **Ryzyko (0–100)**: zmienność, trend spadkowy, mała płynność, zaleganie ofert, nieświeże dane.
- **Pewność (0–100)**: liczba ofert, transakcji, długość historii, świeżość danych.
- Bez rekomendacji, gdy zysk jest ujemny lub poniżej progu albo danych jest za mało.

**Oznaczenia**: ▲ zielony – opłacalny do kupna, ✖ czerwony – za drogo / ryzyko straty, $ żółty – potencjał sprzedaży,
■ niebieski – stabilna cena.

## 4. Tryby AutoRynku BK

1. **Analiza** – tylko zbieranie danych.
2. **Rekomendacje** – zakładka *AutoRynek BK* pokazuje propozycje. Każdą zatwierdzasz ✔ albo odrzucasz ✖
   (jest też „Zatwierdź wszystkie”).
3. **Automatyczna** (domyślny) – co 5 minut sam podnosi lub obniża ceny skupu i sprzedaży w BK. Wprowadza tylko zmiany,
   które przejdą zabezpieczenia:
   - maks. zmiana ceny na raz (domyślnie ±10%); wyjątek: gdy obecny skup daje stratę, obniżka wchodzi od razu w całości,
   - min. odstęp między zmianami jednego przedmiotu,
   - min. pewność,
   - świeże dane (do 30 min),
   - zakup zawsze poniżej `sprzedaż × (1 − opłata) − min. zysk` i poniżej ceny rynkowej po opłacie,
   - wyłączanie wybranych przedmiotów (✔/✖ w kolumnie Auto),
   - wstrzymanie cyklu, gdy cena rynkowa większości przedmiotów skoczy naraz o ponad 30% w godzinę (podejrzany odczyt),
   - cofanie ostatniej zmiany (*Historia*).

## 5. Instalacja

1. Wrzuć ten folder do repozytorium na GitHubie. Plik `.github/workflows/build.yml` musi się tam znaleźć.
   Przeglądarka pomija foldery z kropką — wtedy dodaj go przez *Add file → Create new file*.
2. W zakładce **Actions**, po zielonym ✔, pobierz artefakt `rynek-terminal` (`rynek-terminal-1.1.0.jar`).
   Możesz też zbudować lokalnie: `./gradlew build`, plik powstanie w `build/libs/`.
3. Wrzuć jar do `.minecraft/mods/` obok **Fabric API** i (opcjonalnie) **BK-Rynek 3.0**.

## 6. Konfiguracja i obsługa

- **J** – otwiera terminal. Klawisze **1–7** przełączają zakładki, kółko myszy na wykresie zmienia zakres.
- Komendy: `/terminal`, `/terminal eksport`, `/terminal analiza|rekomendacje|auto`.
- **Ustawienia** (zakładka *Ustawienia*, plik `config/rynekterminal/settings.json`):
  - opłata rynku (domyślnie 3%), min. zysk/szt, min. marża, min. pewność,
  - maks. zmiana ceny, interwał automatu, odstęp zmian,
  - okno analizy, czas pamiętania ofert, progi okazji, płynność,
  - horyzont oceny rekomendacji, retencja danych.
- **Dane** zapisują się w `config/rynekterminal/db/*.jsonl` i nie znikają po wyłączeniu gry.
  Eksport CSV trafia do `config/rynekterminal/export/<data>/`:
  oferty, transakcje, historia cen, zmiany BK, rekomendacje ze skutecznością, zakupy BK.
- **Zbieranie danych**: włącz BK-Rynek (stoi na `/ah` posortowanym „Najnowsze”) albo przeglądaj `/ah` sam.
  Pierwsze rekomendacje pojawią się, gdy pewność przekroczy próg (domyślnie 50%).

## 7. Ograniczenia

- Widoczne są tylko oferty, które pojawiły się w oknie `/ah`. Starych ofert z dalszych stron mod nie zna,
  dopóki ich nie przejrzysz.
- Wykrywanie transakcji działa na 1. stronie z sortowaniem „Najnowsze” (tak pracuje BK). Zdjęcie oferty przez sprzedawcę
  wygląda tak samo jak sprzedaż.
- Mod nie został jeszcze uruchomiony w grze — przy pierwszym buildzie lub teście mogą wyjść drobne poprawki.
