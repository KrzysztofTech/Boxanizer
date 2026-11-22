# Boxanizer — kontekst dla Claude

## Cel projektu
Aplikacja na Androida do organizowania pudełek (skrzynek z narzędziami) oznaczonych kodami QR: co leży w którym pudełku i w jakiej ilości.
Pomysł należy do właściciela tego repozytorium, a pierwszą wersję kodu napisał kolega (oryginał: https://github.com/CookieDinner/Boxanizer, licencja MIT).
Właściciel rozwija aplikację dalej na własnym forku.

## Kierunek (od 2026-11-22)
Zamiast rozwijać aplikację kolegi i przenosić jego dane, budujemy **nową aplikację** na bazie tego kodu:
- **Czysty spis:** dane wprowadzamy od nowa. Nie przenosimy danych z aplikacji kolegi, więc nie potrzebujemy eksportu v1.0.3, importu starej bazy ani migracji starego schematu.
- **Nowa aplikacja:** nowy `applicationId` i nowa nazwa (**do ustalenia**). Instaluje się obok aplikacji kolegi i niczego w niej nie zmienia. `applicationId` po publikacji w Google Play jest nie do zmiany.
- **Osobna gałąź** od stanu `punkt-przed-nowa-aplikacja` (nazwa gałęzi do zatwierdzenia, propozycja: `nowa-aplikacja`).
- **Cel API 36** (`compileSdk`/`targetSdk` 36). Wymaga nowszych AGP, Gradle i Kotlina (2.x z wtyczką kompilatora Compose).
- **Schemat bazy projektujemy od zera**, od razu pod synchronizację. Wnioski z analizy starego modelu i z audytu (niżej) są wymaganiami dla nowego schematu.
- **Kreator wyboru backendu:** przy pierwszym uruchomieniu i w ustawieniach użytkownik wybiera pracę tylko lokalną albo **własny serwer** (bez usług chmurowych firm trzecich, Supabase odrzucony). Rodzaj serwera (PocketBase czy własna usługa) jest do ustalenia.
- **Gotowość pod Google Play:** AAB, podpis kluczem uploadu (Play App Signing), polityka prywatności, formularz bezpieczeństwa danych, minimalne uprawnienia, zgodność z 16 KB stronami pamięci (natywne biblioteki ML Kit/CameraX), edge-to-edge.

## Struktura (obecny kod, przed zmianą kierunku)
Jeden moduł Gradle `:app`, pakiet `com.cookiedinner.boxanizer` w `app/src/main/java/com/cookiedinner/boxanizer/`.

- `Boxanizer.kt`: klasa `Application` (start Koin). `MainActivity.kt` to jedyna aktywność.
- `boxes/`, `items/`, `settings/`: funkcje aplikacji w układzie MVVM (`screens/`, `viewmodels/`, `components/`, `models/`).
- `core/data/DataProvider.kt`: jedyne miejsce dostępu do danych dla ViewModeli (baza i API).
- `core/data/DataStoreManager.kt`: ustawienia w DataStore Preferences.
- `core/database/`: `Database.kt` (zapytania), `DatabaseDriverFactory.kt` (plik `boxanizer.db`), `DatabaseImporter.kt` (import z eksportu).
- `core/api/ApiClient.kt`: Ktor, wyłącznie sprawdzanie aktualizacji przez GitHub Releases API.
- `core/di/AppModule.kt`: moduły Koin.
- `core/navigation/`, `core/theme/`, `core/components/`: nawigacja, motyw i wspólne elementy interfejsu (m.in. kamera).
- `core/utilities/BarcodeAnalyzer.kt`: skanowanie kodów przez CameraX i ML Kit.
- Zasoby: `res/values` (EN) i `res/values-pl` (PL).
- Testy: tylko szablonowe (`ExampleUnitTest`, `ExampleInstrumentedTest`).

## Stary model danych (punkt odniesienia, nie do migracji)
- SQLite (`boxanizer.db`) przez **SQLDelight 2.0.1**, baza `BoxanizerDb`, schemat w wersji 7 (migracje `1–6.sqm`).
  - `Box`: id, code (UNIQUE), name, description, image (BLOB)
  - `Item`: id, name, description, image (BLOB), consumable, itemAmountToBuy (martwa kolumna)
  - `BoxItem`: boxId, itemId, amountInBox, amountRemovedFromBox, amountToBuy
  - `ItemTag`: itemId, name
- Odczyty są jednorazowe (bez `Flow`), więc ekrany nie odświeżają się same po zmianie danych.

### Wymagania dla nowego schematu (z analizy 2026-11-21 i audytu 2026-11-22)
1. Tożsamość wierszy: UUID od początku. Tabele łączące (pudełko–przedmiot, przedmiot–tag) identyfikowane parą kluczy, bez własnego UUID (inaczej dwa urządzenia offline tworzą duplikat pary).
2. Usuwanie przez znacznik (`deletedAt`). Unikalność kodu pudełka tylko wśród aktywnych (indeks częściowy). Ponowne dodanie usuniętego = przywrócenie wiersza.
3. `updatedAt` i wersja wiersza w każdej synchronizowanej tabeli.
4. Ilości zmieniane operacjami (delty) w transakcji, liczone w SQL (`amount = amount + :delta`), z pilnowaniem reguł: `amountInBox ≥ 1`, `0 ≤ wypożyczone ≤ amountInBox`, `doKupienia ≥ 0`. Nigdy wartość z obiektu na ekranie.
5. Kolejność jawna. Stara aplikacja sortowała pozycje w pudełku według „ostatniej zmiany stanu” (przez ponowne wstawianie wiersza i `rowid`), a listy pudełek nie miały `ORDER BY`. Nowy schemat: kolumna czasu/licznika zmiany stanu i `ORDER BY` w każdym zapytaniu listy.
6. Zdjęcia jako pliki poza bazą (pełne lokalnie, miniatury do synchronizacji), w bazie tylko odnośnik. Stare: JPEG 800 px, jakość 100.
7. Edycja przez `UPDATE`, nie `INSERT OR REPLACE` (zeruje pominięte kolumny).
8. Kolejka operacji synchronizacji zapisywana w tej samej transakcji co zmiana danych. Zmiany zdalne nie tworzą nowych operacji.
9. Kopia w chmurze Androida: wykluczyć pliki kopii i pełne zdjęcia (limit 25 MB), reguły dla Androida ≤ 11 (`backup_rules.xml`) i ≥ 12 (`data_extraction_rules.xml`) spójne.
10. Nowe funkcje uwzględnione w projekcie schematu: wypożyczenia (kto, kiedy, co, zdjęcie osoby, termin zwrotu) i lista zakupów (cena, dostawca, próg uzupełnienia).

## Technologie (obecny kod; do podniesienia w kroku 1)
Kotlin 1.9.21, AGP 8.3.1, Gradle 8.4 (wrapper), JDK 17, compileSdk/targetSdk 34, minSdk 24.
Jetpack Compose (BOM 2024.05.00, Material 3, Navigation), Koin 3.5.6, SQLDelight 2.0.1, DataStore, CameraX 1.3.3 z ML Kit Barcode Scanning, Coil, Ktor 2.3.11 z kotlinx.serialization, Accompanist Permissions.
Wersje bibliotek: `gradle/libs.versions.toml`.

## Budowanie i uruchamianie (PowerShell, z katalogu projektu)
Wymagania: JDK 17 (`JAVA_HOME`) oraz Android SDK z platformą 34 (po kroku 1: 36). Ścieżka do SDK jest w `local.properties` (`sdk.dir=...`), który tworzy Android Studio.

```powershell
.\gradlew.bat assembleDebug          # APK: app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat installDebug           # instalacja na podłączonym urządzeniu/emulatorze
adb shell am start -n com.cookiedinner.boxanizer/.MainActivity   # po kroku 1: nowy applicationId
.\gradlew.bat test                   # testy jednostkowe
.\gradlew.bat lintDebug              # lint (musi przechodzić bez błędów)
.\gradlew.bat connectedAndroidTest   # testy na urządzeniu
.\gradlew.bat verifySqlDelightMigration   # sprawdzenie migracji bazy
```
Pliki bazy sprawdzamy narzędziem `sqlite3`, zawsze z `-readonly`. Szczegóły środowiska i urządzeń są w `CLAUDE.local.md` (lokalnie, poza gitem).

## Planowane kroki (szczegóły po akceptacji planu)
1. **Nowa aplikacja i narzędzia:** gałąź, nowy `applicationId` i nazwa, API 36 (AGP/Gradle/Kotlin 2.x), edge-to-edge, 16 KB, podpis release poza repo, build AAB.
2. **Nowy schemat bazy od zera** według wymagań wyżej, z testami jednostkowymi, oraz nowa warstwa danych pod istniejące ekrany.
3. **Kreator backendu:** pierwszy start (lokalnie / własny serwer), interfejs `SyncBackend`, test połączenia, zmiana w ustawieniach. Sama synchronizacja w kolejnych krokach.
4. Dalej: synchronizacja z własnym serwerem, zdjęcia jako pliki, wypożyczenia, lista zakupów, przygotowanie wpisu w Google Play.

## Zasady pracy
- **Zawsze odpowiadaj po polsku.**
- **Punkt powrotu:** tag `punkt-przed-nowa-aplikacja` (na forku) to stan sprzed zmiany kierunku: import bazy, audyt sync, plan migracji 7/8. Powrót: `git switch -c <nazwa> punkt-przed-nowa-aplikacja`. Gałęzie `sync-i-notatki` i `eksport-bazy` zostają jako archiwum, bez nowych zmian.
- **Repo jest publiczne.** Nie commitować numerów seryjnych urządzeń, ścieżek z nazwą użytkownika, informacji o kontach, danych użytkownika, kluczy podpisu (`*.jks`, hasła) ani adresów/tokenów serwera. To trafia do `CLAUDE.local.md` albo plików poza repo.
- **Na telefonie z prawdziwymi danymi nigdy nie odinstalowywać aplikacji kolegi.** Nowa aplikacja (inny `applicationId`) instaluje się obok niej; instalacja na tym telefonie tylko za zgodą właściciela.
- **Przed każdą większą zmianą pokaż plan i poczekaj na zgodę.**
- **Nie zmieniaj formatu bazy bez migracji**, gdy nowa aplikacja ma już prawdziwe dane: nowy plik `.sqm`, `verifySqlDelightMigration` i wcześniejsza kopia danych.
- **Po każdym etapie: commit i push na fork.**
- **Instalacje programów:** najpierw krótko napisz, co i po co instalujesz. Właściciel potwierdza każdą instalację w oknie uprawnień (reguła `ask` dla `winget install` w `.claude/settings.local.json`, plik lokalny, poza gitem).
- Remote'y: `origin` to fork https://github.com/KrzysztofTech/Boxanizer.git (tu pushujemy), a `upstream` to oryginał kolegi https://github.com/CookieDinner/Boxanizer.git (tylko fetch, **nigdy push**).

## Stan prac
Prowadzony w `CLAUDE.local.md` (lokalnie, poza gitem). Aktualizować na koniec każdej sesji.
