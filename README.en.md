# Archivo

[Deutsch](README.md) · **English**

Android app for archiving, searching and reading PDFs: study notes, letters, forms and everything else
that would otherwise pile up as paper. Documents go into categories and subcategories you create yourself
(e.g. *School → Maths → Calculus*) and can be found again by their **content**, even scanned pages.

Everything stays on the device: no account, no cloud, offline text recognition.

## Features

### Organize
- Categories and subcategories of any depth, shown as an expandable tree in the side drawer
- Import PDFs (several at once), rename, move and delete them
- Multi-select for PDFs and categories: move, delete, share
- **Share → Archivo** from other apps (e.g. email, downloads, file manager)
- Share PDFs from within the app, including whole categories

### Scan
- **Scan with your printer** via eSCL/AirScan over Wi-Fi: scanners are discovered automatically,
  a manual IP is possible too. Supports flatbed, document feeder and **duplex**.
- **Blank back pages** are removed automatically when scanning in duplex.
- **Scan with the camera** using the ML Kit Document Scanner (edge detection, perspective correction, filters)
- Preview before saving: view, rotate, remove and drag to reorder pages
- Scans are embedded into the PDF as lossless JPEG passthrough (no re-compression)

### Search
- Search by category and PDF names
- **Full-text search in PDF content** (SQLite FTS4) with snippet and all matching pages
- Scanned pages are made searchable via **OCR** (ML Kit, offline)
- Matches are highlighted in yellow in the viewer; ◀ ▶ jumps from match to match, right to the word

### Read
- Smooth PDF viewer with pinch zoom (1×–3×), double tap and free panning in every direction
- Exact reading position per PDF (page, position, zoom); the last opened PDF reopens on start
- Permanently rotate single pages or the whole document

## Requirements

- Android 6.0 (API 23) or newer
- Camera scan: Google Play services
- Printer scan: an eSCL-capable scanner/multifunction printer on the same Wi-Fi

On Android 15 and newer the app reads the text layer of PDFs with the built-in `PdfRenderer`.
On older versions PdfBox does that. Pages without text (scans) always go through OCR.

## Building

Open in Android Studio and press **Run**, or from the command line:

```bash
./gradlew :app:assembleDebug        # build the APK
./gradlew :app:installDebug         # install on a connected device
```

## Tech

| Area | Implementation |
|---|---|
| Language | Java 11, ViewBinding |
| UI | Material 3, Navigation Component, RecyclerView |
| Architecture | ViewModel + LiveData → Repository → Room / file system |
| Database | Room (categories, PDFs, FTS4 index for full text) |
| Displaying PDFs | `android.graphics.pdf.PdfRenderer` |
| Editing PDFs / text before Android 15 | [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) |
| OCR | ML Kit Text Recognition (bundled model, offline) |
| Camera scan | ML Kit Document Scanner |
| Printer scan | custom eSCL client (`NsdManager` + HTTP/XML), custom JPEG→PDF writer |

### Project structure

```
app/src/main/java/at/woodexplosive/learnarchiver/
├── MainActivity.java       side drawer, search, import, sharing
├── data/                   Room database, DAOs, repository, PdfIndexer (full text + OCR)
├── scan/                   eSCL client, scanner discovery, JPEG→PDF, blank page detection
└── ui/
    ├── drawer/             category tree, dialogs
    ├── viewer/             PDF viewer, renderer, zoom
    ├── scan/               scan screen with preview
    └── home/               start screen
```

The app's user interface is currently in German.

## Privacy

- PDFs are stored in the app's private storage, as is the database.
- Text recognition and search run entirely on the device.
- The network is only used for scanning on the local Wi-Fi.
- When importing via "Share", only files from other apps are accepted, and they are checked to be real PDFs.
