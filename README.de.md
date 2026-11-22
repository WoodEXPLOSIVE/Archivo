# Archivo

**Deutsch** · [English](README.md)

Android-App zum Archivieren, Durchsuchen und Lesen von PDFs: Lernunterlagen, Briefe, Formulare und alles,
was sonst als Papier herumliegt. Dokumente landen in selbst angelegten Kategorien und Unterkategorien
(z.B. *Schule → Mathe → Analysis*) und lassen sich über ihren **Inhalt** wiederfinden, auch eingescannte Seiten.

Alles bleibt auf dem Gerät: kein Konto, keine Cloud, Texterkennung offline.

## Funktionen

### Ordnen
- Kategorien und beliebig tiefe Unterkategorien als aufklappbarer Baum in der Seitenleiste
- PDFs importieren (auch mehrere auf einmal), umbenennen, verschieben und löschen
- Mehrfachauswahl für PDFs und Kategorien: verschieben, löschen, teilen
- **Teilen → Archivo** aus anderen Apps (z.B. E-Mail, Downloads, Dateimanager)
- PDFs aus der App heraus teilen, auch ganze Kategorien

### Scannen
- **Mit dem Drucker scannen** über eSCL/AirScan im WLAN: Scanner werden automatisch gefunden,
  manuelle IP möglich. Unterstützt Scannerglas, Einzug und **Duplex**.
- **Leere Rückseiten** werden beim Duplex-Scan automatisch entfernt.
- **Mit der Kamera scannen** über den ML Kit Document Scanner (Kantenerkennung, Entzerren, Filter)
- Vorschau vor dem Speichern: Seiten ansehen, drehen, entfernen und per Ziehen umsortieren
- Scans werden verlustfrei als JPEG ins PDF übernommen (kein erneutes Komprimieren)

### Suchen
- Suche nach Namen von Kategorien und PDFs
- **Volltextsuche im PDF-Inhalt** (SQLite FTS4) mit Ausschnitt und allen Trefferseiten
- Eingescannte Seiten werden per **OCR** (ML Kit, offline) lesbar gemacht
- Treffer werden im Viewer gelb markiert; mit ◀ ▶ von Trefferseite zu Trefferseite, direkt zum Wort

### Lesen
- Flüssiger PDF-Viewer mit Pinch-Zoom (1×–3×), Doppeltippen und freiem Schieben in alle Richtungen
- Genauer Lesestand pro PDF (Seite, Position, Zoom); das zuletzt offene PDF öffnet sich beim Start wieder
- Einzelne Seiten oder das ganze Dokument dauerhaft drehen

## Voraussetzungen

- Android 6.0 (API 23) oder neuer
- Kamera-Scan: Google Play-Dienste
- Drucker-Scan: ein eSCL-fähiger Scanner/Multifunktionsdrucker im selben WLAN

Ab Android 15 liest die App die Textebene von PDFs mit dem eingebauten `PdfRenderer`.
Auf älteren Versionen übernimmt das PdfBox. Seiten ohne Text (Scans) laufen immer über die OCR.

## Bauen

Mit Android Studio öffnen und **Run** drücken, oder auf der Kommandozeile:

```bash
./gradlew :app:assembleDebug        # APK bauen
./gradlew :app:installDebug         # auf ein verbundenes Gerät installieren
```

## Technik

| Bereich | Umsetzung |
|---|---|
| Sprache | Java 11, ViewBinding |
| UI | Material 3, Navigation Component, RecyclerView |
| Architektur | ViewModel + LiveData → Repository → Room / Dateisystem |
| Datenbank | Room (Kategorien, PDFs, FTS4-Index für den Volltext) |
| PDF anzeigen | `android.graphics.pdf.PdfRenderer` |
| PDF bearbeiten / Text vor Android 15 | [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) |
| OCR | ML Kit Text Recognition (gebündeltes Modell, offline) |
| Kamera-Scan | ML Kit Document Scanner |
| Drucker-Scan | eigener eSCL-Client (`NsdManager` + HTTP/XML), eigener JPEG→PDF-Writer |

### Projektstruktur

```
app/src/main/java/at/woodexplosive/archivo/
├── MainActivity.java       Seitenleiste, Suche, Import, Teilen
├── data/                   Room-Datenbank, DAOs, Repository, PdfIndexer (Volltext + OCR)
├── scan/                   eSCL-Client, Scanner-Suche, JPEG→PDF, Leerseiten-Erkennung
└── ui/
    ├── drawer/             Kategorie-Baum, Dialoge
    ├── viewer/             PDF-Viewer, Renderer, Zoom
    ├── scan/               Scan-Bildschirm mit Vorschau
    └── home/               Startseite
```

Die App gibt es auf **Deutsch** und **Englisch**. Sie folgt der Gerätesprache; ab Android 13 lässt sich die
Sprache auch pro App einstellen (Einstellungen → Apps → Archivo → Sprache).

## Datenschutz

- PDFs liegen im privaten App-Speicher, die Datenbank ebenfalls.
- Texterkennung und Suche laufen vollständig auf dem Gerät.
- Netzwerk wird nur für das Scannen im lokalen WLAN genutzt.
- Beim Import über „Teilen“ werden nur Dateien fremder Apps angenommen und auf echtes PDF geprüft.

## Disclaimer
Claude code wurde genutzt für Documentation und das Planen von komplexeren Features.

## Lizenz

© 2026 WoodEXPLOSIVE. Lizenziert unter [CC BY-NC-ND 4.0](LICENSE.txt):

- **Teilen** – App und Code dürfen kopiert und weitergegeben werden.
- **Namensnennung** – WoodEXPLOSIVE muss genannt und auf diese Lizenz verwiesen werden.
- **Nicht kommerziell** – keine kommerzielle Nutzung.
- **Keine Bearbeitungen** – veränderte Versionen dürfen nicht weitergegeben werden.

Fremde Bibliotheken behalten ihre eigenen Lizenzen (z.B. PdfBox-Android: Apache-Lizenz 2.0).
