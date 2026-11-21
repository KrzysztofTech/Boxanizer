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
- Istnieje zaczątek listy zakupów: kolumny `amountToBuy` i `itemAmountToBuy` (migracja 6).
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
Te komendy nie zostały jeszcze uruchomione na tej maszynie.

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

**Co w toku:**
- Nic.

**Następny krok:**
- Ręczna konfiguracja Android Studio (SDK 34) i pierwszy udany `assembleDebug`.
- Następnie plan etapu 1 (synchronizacja) do akceptacji.
