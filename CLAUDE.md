# Boxanizer — kontekst dla Claude

## Cel projektu
Aplikacja na Androida do organizowania pudełek (skrzynek z narzędziami) oznaczonych kodami QR: co leży w którym pudełku i w jakiej ilości.
Pomysł należy do właściciela tego repozytorium, a pierwszą wersję kodu napisał kolega (oryginał: https://github.com/CookieDinner/Boxanizer).
Właściciel rozwija aplikację dalej na własnym forku.

## Struktura
Jeden moduł Gradle `:app`, pakiet `com.cookiedinner.boxanizer` w `app/src/main/java/com/cookiedinner/boxanizer/`.

- `Boxanizer.kt`: klasa `Application` (start Koin). `MainActivity.kt` to jedyna aktywność.
- `boxes/`, `items/`, `settings/`: funkcje aplikacji w układzie MVVM (`screens/`, `viewmodels/`, `components/`, `models/`).
- `core/data/DataProvider.kt`: jedyne miejsce dostępu do danych dla ViewModeli (baza i API).
- `core/data/DataStoreManager.kt`: ustawienia w DataStore Preferences.
- `core/database/`: `Database.kt` (zapytania) i `DatabaseDriverFactory.kt` (plik `boxanizer.db`).
- `core/api/ApiClient.kt`: Ktor, wyłącznie sprawdzanie aktualizacji przez GitHub Releases API.
- `core/di/AppModule.kt`: moduły Koin.
- `core/navigation/`, `core/theme/`, `core/components/`: nawigacja, motyw i wspólne elementy interfejsu (m.in. kamera).
- `core/utilities/BarcodeAnalyzer.kt`: skanowanie kodów przez CameraX i ML Kit.
- Zasoby: `res/values` (EN) i `res/values-pl` (PL).
- Testy: tylko szablonowe (`ExampleUnitTest`, `ExampleInstrumentedTest`).

## Dane
- Lokalnie w SQLite (`boxanizer.db`) przez **SQLDelight 2.0.1**, baza `BoxanizerDb`. Nie ma serwera ani synchronizacji.
- Schemat w `app/src/main/sqldelight/com/cookiedinner/boxanizer/database/*.sq`:
  - `Box`: id, code (UNIQUE, kod z etykiety), name, description, image (BLOB)
  - `Item`: id, name, description, image (BLOB), consumable, itemAmountToBuy
  - `BoxItem`: boxId, itemId, amountInBox, amountRemovedFromBox, amountToBuy
  - `ItemTag`: itemId, name
- Zdjęcia są zapisywane jako BLOB bezpośrednio w bazie.
- Migracje: `app/src/main/sqldelight/migrations/1–6.sqm`. Schemat referencyjny to `databases/1.db`, a `verifyMigrations = true`. **Nowa zmiana schematu = nowy plik `7.sqm`, …**
- Istnieje zaczątek listy zakupów: `BoxItem.amountToBuy` (akcje CONSUME/BUY) z migracji 6. `Item.itemAmountToBuy` jest martwa: nic jej nie zapisuje, a `Item.insert` (INSERT OR REPLACE) przy edycji przedmiotu zeruje ją.
- `BoxItem.amountRemovedFromBox` to sama liczba wyjętych/pożyczonych sztuk (akcje BORROW/RETURN), bez informacji kto i kiedy.
- Odczyty są jednorazowe (bez `Flow`), więc ekrany nie odświeżają się same po zmianie danych.

### Ograniczenia modelu ważne dla synchronizacji (analiza z 2026-11-21)
1. ID to lokalne AUTOINCREMENT, więc między urządzeniami będą kolizje. Potrzebne UUID albo mapowanie. Unikalne `Box.code` też może kolidować.
2. Usuwanie to twardy `DELETE`, bez znaczników usunięcia.
3. Brak `updatedAt` i wersji wierszy.
4. Ilości są zapisywane jako gotowy wynik (read-modify-write w `Database.editItemInBox`), więc przy „ostatni zapis wygrywa” zmiany mogą ginąć. Do rozważenia: zapis operacji/zdarzeń.
5. Kolejność list opiera się na `rowid` (celowe ponowne wstawianie wiersza). Po synchronizacji trzeba jawnej kolumny do sortowania.
6. Zdjęcia jako BLOB (JPEG 800 px wysokości, jakość 100) są ciężkie do synchronizacji. Lepiej trzymać je osobno i w bazie mieć tylko odnośnik.
7. Brak kluczy obcych, kaskady są robione ręcznie w transakcjach.
- Kopia zapasowa: na razie tylko systemowy backup Androida (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`). Eksport/import w aplikacji: do ustalenia (prawdopodobnie brak).

## Technologie
Kotlin 1.9.21, AGP 8.3.1, Gradle 8.4 (wrapper), JDK 17, compileSdk/targetSdk 34, minSdk 24.
Jetpack Compose (BOM 2024.05.00, Material 3, Navigation), Koin 3.5.6, SQLDelight 2.0.1, DataStore, CameraX 1.3.3 z ML Kit Barcode Scanning, Coil, Ktor 2.3.11 z kotlinx.serialization, Accompanist Permissions.
Wersje bibliotek: `gradle/libs.versions.toml`.

## Budowanie i uruchamianie (PowerShell, z katalogu projektu)
Wymagania: JDK 17 (`JAVA_HOME`) oraz Android SDK z platformą 34. Ścieżka do SDK jest w `local.properties` (`sdk.dir=...`), który tworzy Android Studio.

```powershell
.\gradlew.bat assembleDebug          # APK: app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat installDebug           # instalacja na podłączonym urządzeniu/emulatorze
adb shell am start -n com.cookiedinner.boxanizer/.MainActivity
.\gradlew.bat test                   # testy jednostkowe
.\gradlew.bat connectedAndroidTest   # testy na urządzeniu
.\gradlew.bat verifySqlDelightMigration   # sprawdzenie migracji bazy
```
Build release (`assembleRelease`) ma minifikację, a konfiguracja podpisu jest do ustalenia.
Build debug działa (2026-11-21, budowany przez „Run” w Android Studio, APK w `app\build\intermediates\apk\debug\`). SDK: `C:\Users\tech\AppData\Local\Android\Sdk`, `adb` w `platform-tools\`. Testowy telefon jest podłączony przez USB.

## Telefon z prawdziwymi danymi (sprawdzone 2026-11-21)
- OnePlus CPH2043, Android 12, adb serial `JBW4BYOVSO6TRW6H`.
- Aplikacja **v1.0.2 (versionCode 2), wersja release** pobrana z GitHub Releases kolegi. Podpis jest kluczem kolegi (`b810d190`). Nie jest debuggable, więc `run-as` nie działa. `adb backup` dla targetSdk 34 nie obejmuje danych aplikacji.
- **Nigdy nie instalować na nim naszych buildów i nie odinstalowywać aplikacji**, bo inny podpis wymaga odinstalowania, a to kasuje dane.
- **Wniosek:** nasze przyszłe wersje (inny klucz) nie zaktualizują tej aplikacji z zachowaniem danych. Dane trzeba przenieść eksportem i importem albo przez kopię w chmurze Google. Wybór sposobu jest do ustalenia.
- Usługa kopii zapasowej Androida jest włączona (transport Google), ale **kopia w chmurze nie działa**: `bmgr backupnow` zwraca „Size quota exceeded”, bo dane aplikacji mają około 36,8 MB, a limit Auto Backup to 25 MB (głównie zdjęcia BLOB). Przywracanie z chmury i tak sprawdza podpis, więc nie dałoby pliku `.db`.
- `adb backup` sprawdzony 2026-11-21: plik pusty (47 B, sam nagłówek), czego można było się spodziewać.
- Jedyna pewna droga do pliku bazy: kolega buduje wersję z eksportem bazy, podpisaną swoim kluczem (albo przekazuje klucz).
- **Gałąź `eksport-bazy`** (od `main`, commit `75b8451`, na forku): Ustawienia → Dane → „Eksportuj bazę danych”, wersja v1.0.3. Przetestowana na telefonie testowym: liczby pudełek i przedmiotów się zgadzają, plik to poprawne SQLite z 4 tabelami. Czeka na podpisany build od kolegi.
- Kopie z telefonów: `C:\projekty\Boxanizer-backup\` (poza repo, nigdy nie commitować). Uwaga: w Git Bash przy ścieżkach telefonu używać `MSYS_NO_PATHCONV=1`.

## Planowane zmiany (w tej kolejności)
1. **Synchronizacja stanu między kilkoma urządzeniami przez wspólną bazę.** Technologia (własny serwer, Firebase/Supabase itp.), rozwiązywanie konfliktów i obsługa zdjęć: do ustalenia.
2. **Notatki o wypożyczeniach przypisane do pudełka:** zdjęcie osoby, data, co zostało pożyczone. Szczegółowy model danych (np. data zwrotu): do ustalenia.
3. **Lista zakupów z ceną, dostawcą i przypomnieniami o uzupełnieniu stanu.** Punktem wyjścia są istniejące kolumny `amountToBuy`/`itemAmountToBuy`. Mechanizm przypomnień (np. WorkManager z powiadomieniami) i progi: do ustalenia.

## Zasady pracy
- Pracujemy na gałęzi **`sync-i-notatki`**.
- **Przed każdą większą zmianą pokaż plan i poczekaj na zgodę.**
- **Nie zmieniaj formatu bazy bez kopii zapasowej i migracji danych.** Zmiana schematu wymaga nowego pliku `.sqm`, przejścia `verifySqlDelightMigration` i wcześniejszej kopii danych użytkownika.
- **Po każdym etapie: commit i push na fork.**
- Remote'y: `origin` to fork https://github.com/KrzysztofTech/Boxanizer.git (tu pushujemy), a `upstream` to oryginał kolegi https://github.com/CookieDinner/Boxanizer.git (tylko fetch, **nigdy push**).

## Stan prac
_Aktualizować na koniec każdej sesji._

**Co zrobione** (2026-11-21):
- Środowisko: Git 2.56.1, Temurin JDK 17.0.21, Android Studio 2026.2.2.9 (zainstalowane przez winget).
- Repozytorium sklonowane do `C:\projekty\Boxanizer`, utworzona gałąź `sync-i-notatki` (z `main` @ `504ade2`).
- Utworzony ten plik CLAUDE.md.
- Fork podpięty jako `origin`, oryginał jako `upstream`. Gałąź `sync-i-notatki` wypchnięta na fork.
- Przeanalizowany obecny model danych (wnioski w sekcji „Dane” wyżej). Kod bez zmian.
- Android Studio skonfigurowane (SDK 34), `assembleDebug` przechodzi. Aplikacja działa na testowym telefonie: dodawanie pudełek i skanowanie kodów sprawdzone, a na telefonie są dane testowe.
- Hasło do GitHuba zmienione.
- Próby kopii z prawdziwego telefonu (`run-as`, chmura Google, `adb backup`) nieudane. Zrobiona i przetestowana funkcja eksportu na gałęzi `eksport-bazy`.

**Czeka na kolegę:** podpisany build v1.0.3 z gałęzi `eksport-bazy`. Potem instalacja na prawdziwym telefonie i eksport bazy do `Boxanizer-backup\`.

**Co w toku:**
- Wybór architektury synchronizacji (etap 1). Czeka na odpowiedzi właściciela:
  - Czy dane zmieniają tylko jego urządzenia, czy kilka osób jednocześnie?
  - Czy aplikacja ma w pełni działać offline i synchronizować się później?
  - Gotowa usługa (Firebase/Supabase) czy własny serwer?
  - Czy zdjęcia mają się synchronizować w pełnej jakości?

**Następny krok:**
- Po odpowiedziach: porównanie 2–3 architektur ze zmianami schematu i planem migracji, do akceptacji.
