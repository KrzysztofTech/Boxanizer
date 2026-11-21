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
- Migracje: `app/src/main/sqldelight/migrations/1–6.sqm`. Schemat referencyjny to `databases/1.db`, a `verifyMigrations = true`. Obecny schemat to wersja 7 (`PRAGMA user_version`). **Następna zmiana schematu = plik `7.sqm`, który daje schemat w wersji 8 („migracja 8”).**
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
- Kopia zapasowa: w `main` tylko systemowy backup Androida, który przy około 37 MB danych przekracza limit 25 MB kopii w chmurze. Eksport bazy jest na gałęzi `eksport-bazy` (v1.0.3). Import jest planowany jako etap 0.

### Przenoszenie danych z wersji kolegi
Wydania z GitHuba kolegi są podpisane jego kluczem, a nasze buildy innym. Android nie zaktualizuje jego aplikacji naszą z zachowaniem danych: wymaga odinstalowania, które kasuje dane. Dane z jego wersji nie wyjdą też przez `run-as`, `adb backup` ani kopię w chmurze. Jedyna droga to **eksport w podpisanej przez kolegę v1.0.3 i import w naszej wersji**.

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
Pliki bazy sprawdzamy narzędziem `sqlite3`, zawsze z `-readonly`. Szczegóły środowiska i urządzeń są w `CLAUDE.local.md` (lokalnie, poza gitem).

## Planowane zmiany (w tej kolejności)
0. **Import bazy z eksportu** (`boxanizer.db` ze schematem ≤ 7) do naszej wersji. To warunek przeniesienia prawdziwych danych (około 37 MB, głównie zdjęcia). Musi być gotowy **przed** jakąkolwiek migracją schematu. Plan czeka na akceptację.
1. **Synchronizacja między urządzeniami.** Wstępne kierunki, **do omówienia**:
   - Serwer: PocketBase na Debianie, dostęp przez Tailscale.
   - **Offline-first:** lokalna baza SQLite zostaje podstawą, a synchronizacja działa w tle.
   - **Model hybrydowy:** katalog (pudełka, przedmioty, tagi) ma UUID, `updatedAt` i znaczniki usunięcia. Zmiany ilości są zapisywane jako operacje (delty), a nie gotowe wyniki.
   - **Zdjęcia:** jako osobne pliki JPEG (jakość około 80), poza bazą.
   - Migrację 8 (UUID i inne) testujemy na **kopii prawdziwych danych**, nie tylko na testowych.
2. **Notatki o wypożyczeniach przypisane do pudełka:** zdjęcie osoby, data, co zostało pożyczone. Model danych (np. data zwrotu): do ustalenia.
3. **Lista zakupów z ceną, dostawcą i przypomnieniami o uzupełnieniu stanu.** Punktem wyjścia jest `BoxItem.amountToBuy`. Przypomnienia (np. WorkManager) i progi: do ustalenia.

## Zasady pracy
- Pracujemy na gałęzi **`sync-i-notatki`**. Gałąź `eksport-bazy` (od `main`) to mała zmiana dla kolegi do podpisanego buildu v1.0.3. Nie dodawać do niej nic innego.
- **Repo jest publiczne.** Nie commitować numerów seryjnych urządzeń, ścieżek z nazwą użytkownika, informacji o kontach ani danych użytkownika. To trafia do `CLAUDE.local.md`.
- **Na telefonie z prawdziwymi danymi nie instalować naszych buildów i nie odinstalowywać aplikacji**, dopóki nie ma zweryfikowanej kopii bazy na komputerze.
- **Przed każdą większą zmianą pokaż plan i poczekaj na zgodę.**
- **Nie zmieniaj formatu bazy bez kopii zapasowej i migracji danych.** Zmiana schematu wymaga nowego pliku `.sqm`, przejścia `verifySqlDelightMigration` i wcześniejszej kopii danych użytkownika.
- **Po każdym etapie: commit i push na fork.**
- **Instalacje programów:** najpierw krótko napisz, co i po co instalujesz. Właściciel potwierdza każdą instalację w oknie uprawnień (reguła `ask` dla `winget install` w `.claude/settings.local.json`, plik lokalny, poza gitem).
- Remote'y: `origin` to fork https://github.com/KrzysztofTech/Boxanizer.git (tu pushujemy), a `upstream` to oryginał kolegi https://github.com/CookieDinner/Boxanizer.git (tylko fetch, **nigdy push**).

## Stan prac
Prowadzony w `CLAUDE.local.md` (lokalnie, poza gitem). Aktualizować na koniec każdej sesji.
