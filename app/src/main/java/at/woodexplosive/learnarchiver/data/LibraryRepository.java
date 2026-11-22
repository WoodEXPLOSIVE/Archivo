package at.woodexplosive.learnarchiver.data;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.lifecycle.LiveData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;

import at.woodexplosive.learnarchiver.data.dao.CategoryDao;
import at.woodexplosive.learnarchiver.data.dao.ContentHit;
import at.woodexplosive.learnarchiver.data.dao.PdfFileDao;
import at.woodexplosive.learnarchiver.data.dao.PdfTextDao;
import at.woodexplosive.learnarchiver.data.enitity.Category;
import at.woodexplosive.learnarchiver.data.enitity.PdfFile;

public class LibraryRepository {
    private final AppDataBase db;
    private final CategoryDao categoryDao;
    private final PdfFileDao pdfFileDao;
    private final PdfTextDao pdfTextDao;
    private final PdfIndexer indexer;
    private final ExecutorService executor;
    private final File pdfDir;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Eigener Thread für das Umschreiben von PDF-Dateien – kann dauern, soll die DB nicht blockieren */
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private static final String KEY_LAST_OPENED_PDF = "last_opened_pdf_id";

    /** Ergebnis einer Hintergrund-Abfrage, wird auf dem Main-Thread geliefert */
    public interface ResultCallback<T> {
        void onResult(T value);
    }

    @FunctionalInterface
    public interface ImportCallback {
        void onImportFinish(int imported, int failed);
    }

    public LibraryRepository(Context context) {
        db = AppDataBase.getInstance(context);
        this.appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences("library", Context.MODE_PRIVATE);
        categoryDao = db.categoryDao();
        pdfFileDao = db.pdfFileDao();
        pdfTextDao = db.pdfTextDao();
        executor = AppDataBase.dbExecutor;
        pdfDir = new File(context.getFilesDir(), "pdfs");
        if (!pdfDir.exists()) pdfDir.mkdirs();
        indexer = new PdfIndexer(db, pdfDir);
        PDFBoxResourceLoader.init(appContext);
    }

    // ---------- Seiten drehen ----------

    /** Ergebnis von rotatePages */
    public enum RotateResult { OK, PROTECTED, FAILED }

    /**
     * Seiten der PDF-Datei um {@code degrees} (Vielfaches von 90) im Uhrzeigersinn drehen.
     * Ändert nur den /Rotate-Eintrag der Seiten (verlustfrei) und speichert die Datei neu:
     * erst in eine temporäre Datei, dann umbenennen – so ist das Original nie halb geschrieben.
     * Danach wird der Inhalt neu indiziert (gedrehte Scans liefern bei der OCR anderen Text).
     *
     * @param page 0-basierte Seite oder -1 für alle Seiten
     */
    public void rotatePages(PdfFile pdf, int page, int degrees, ResultCallback<RotateResult> callback) {
        fileExecutor.execute(() -> {
            File src = getFile(pdf);
            File tmp = new File(pdfDir, src.getName() + ".tmp");
            RotateResult result;
            try (PDDocument doc = PDDocument.load(src)) {
                if (doc.isEncrypted()) {
                    result = RotateResult.PROTECTED;
                } else {
                    int n = doc.getNumberOfPages();
                    for (int i = 0; i < n; i++) {
                        if (page >= 0 && i != page) continue;
                        PDPage p = doc.getPage(i);
                        p.setRotation((((p.getRotation() + degrees) % 360) + 360) % 360);
                    }
                    doc.save(tmp);
                    result = RotateResult.OK;
                }
            } catch (IOException | RuntimeException e) {
                Log.w("LibraryRepository", "Drehen fehlgeschlagen: " + src, e);
                result = RotateResult.FAILED;
            }
            if (result == RotateResult.OK && !tmp.renameTo(src)) {
                Log.w("LibraryRepository", "Umbenennen fehlgeschlagen: " + tmp);
                result = RotateResult.FAILED;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();   // nur noch vorhanden, wenn etwas schiefging

            if (result == RotateResult.OK) {
                executor.execute(() -> {
                    db.runInTransaction(() -> {
                        pdfTextDao.deleteForPdf(pdf.id);
                        pdfFileDao.setIndexState(pdf.id, PdfFile.INDEX_PENDING);
                    });
                    indexer.indexPending();
                });
            }
            RotateResult r = result;
            mainHandler.post(() -> callback.onResult(r));
        });
    }

    public LiveData<List<Category>> getCategories() {
        return categoryDao.getAll();
    }

    public LiveData<List<PdfFile>> getPdfFiles() {
        return pdfFileDao.getAll();
    }

    public File getFile(PdfFile pdf) {
        return getFile(pdf.filePath);
    }

    public File getFile(String fileName) {
        return new File(pdfDir, fileName);
    }

    /**
     * Mehrere Kategorien und PDFs auf einmal verschieben – in EINER Transaktion:
     * entweder klappt alles oder nichts, und die LiveData meldet nur einmal eine Änderung.
     * @param target neue Eltern-Kategorie; null = oberste Ebene (nur für Kategorien erlaubt)
     */
    public void moveMany(Collection<Long> categoryIds, Collection<Long> pdfIds, Long target) {
        executor.execute(() -> db.runInTransaction(() -> {
            for (long id : categoryIds) {
                categoryDao.move(id, target);
            }
            if (target != null) {
                for (long id : pdfIds) {
                    pdfFileDao.move(id, target);
                }
            }
        }));
    }

    /** Mehrere Kategorien (samt Unterbaum) und PDFs auf einmal löschen, inkl. Dateien */
    public void deleteMany(Collection<Long> categoryIds, Collection<PdfFile> pdfs) {
        executor.execute(() -> {
            List<String> fileNames = new ArrayList<>();
            db.runInTransaction(() -> {
                for (long id : categoryIds) {
                    // Dateinamen VOR dem Löschen holen – danach hat CASCADE die Einträge entfernt
                    fileNames.addAll(pdfFileDao.getFilePathsInSubtree(id));
                    categoryDao.delete(id);
                }
                for (PdfFile p : pdfs) {
                    fileNames.add(p.filePath);
                    pdfFileDao.delete(p.id);
                }
                pdfTextDao.deleteOrphans();   // Volltext-Index der gelöschten PDFs entfernen
            });
            // Dateien erst löschen, wenn die DB-Transaktion durch ist
            for (String name : fileNames) {
                new File(pdfDir, name).delete();
            }
        });
    }

    // ---------- Teilen ----------

    /**
     * Kopiert die PDFs unter ihrem Anzeigenamen nach cache/share/ und liefert FileProvider-Uris.
     * (Der echte Dateiname ist eine UUID – der Empfänger soll "Mathe Skript.pdf" sehen.)
     */
    public void prepareShare(List<PdfFile> pdfs, ResultCallback<ArrayList<Uri>> callback) {
        executor.execute(() -> {
            File dir = new File(appContext.getCacheDir(), "share");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();   // vorherige Freigaben aufräumen
            dir.mkdirs();

            ArrayList<Uri> uris = new ArrayList<>();
            java.util.Set<String> used = new java.util.HashSet<>();
            for (PdfFile p : pdfs) {
                String base = safeFileName(p.displayName);
                String name = base + ".pdf";
                for (int n = 2; !used.add(name.toLowerCase(Locale.ROOT)); n++) {
                    name = base + " (" + n + ").pdf";   // gleiche Namen unterscheidbar machen
                }
                File out = new File(dir, name);
                if (copyFile(getFile(p), out)) {
                    uris.add(androidx.core.content.FileProvider.getUriForFile(
                            appContext, appContext.getPackageName() + ".fileprovider", out));
                }
            }
            mainHandler.post(() -> callback.onResult(uris));
        });
    }

    /** Zeichen entfernen, die in Dateinamen verboten sind */
    private static String safeFileName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "Dokument" : s;
    }

    // ---------- Volltextsuche ----------

    /** Noch nicht indizierte PDFs abarbeiten (beim App-Start + nach Import) */
    public void indexPending() {
        indexer.indexPending();
    }

    public LiveData<Integer> countPendingIndex() {
        return pdfFileDao.countPendingIndex();
    }

    public void searchContent(String ftsQuery, ResultCallback<List<ContentHit>> callback) {
        executor.execute(() -> {
            List<ContentHit> hits;
            try {
                hits = pdfTextDao.search(ftsQuery);
            } catch (Exception e) {
                // ungültige FTS-Syntax o.ä. → einfach keine Treffer
                Log.w("LibraryRepository", "Suche fehlgeschlagen: " + ftsQuery, e);
                hits = new ArrayList<>();
            }
            List<ContentHit> result = hits;
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    // ---------- Lesestand ----------

    public void getPdf(long id, ResultCallback<PdfFile> callback) {
        executor.execute(() -> {
            PdfFile pdf = pdfFileDao.getById(id);
            mainHandler.post(() -> callback.onResult(pdf));
        });
    }

    public void saveReadingPosition(long pdfId, int page, float pageOffset, float zoom, float scrollX) {
        executor.execute(() -> pdfFileDao.setReadingPosition(pdfId, page, pageOffset, zoom, scrollX));
    }

    /** Trefferseiten eines PDFs für die Suche (leer bei Fehler) */
    public void findHitPages(String ftsQuery, long pdfId, ResultCallback<List<Integer>> callback) {
        executor.execute(() -> {
            List<Integer> pages;
            try {
                pages = pdfTextDao.findPages(ftsQuery, pdfId);
            } catch (Exception e) {
                Log.w("LibraryRepository", "Treffersuche fehlgeschlagen: " + ftsQuery, e);
                pages = new ArrayList<>();
            }
            List<Integer> result = pages;
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    public void setLastOpenedPdf(long pdfId) {
        prefs.edit().putLong(KEY_LAST_OPENED_PDF, pdfId).apply();
    }

    /** Nur löschen, wenn noch DIESES PDF eingetragen ist (sonst überschreiben wir ein neueres) */
    public void clearLastOpenedPdf(long pdfId) {
        if (prefs.getLong(KEY_LAST_OPENED_PDF, -1) == pdfId) {
            prefs.edit().remove(KEY_LAST_OPENED_PDF).apply();
        }
    }

    /** Liefert das zuletzt offene PDF oder null (keins gemerkt bzw. inzwischen gelöscht) */
    public void getLastOpenedPdf(ResultCallback<PdfFile> callback) {
        long id = prefs.getLong(KEY_LAST_OPENED_PDF, -1);
        if (id < 0) {
            callback.onResult(null);
            return;
        }
        getPdf(id, callback);
    }

    public void moveCategory(long categoryId, Long newParentId) {
        executor.execute(() -> categoryDao.move(categoryId, newParentId));
    }

    public void movePdf(long pdfId, long categoryId) {
        executor.execute(() -> pdfFileDao.move(pdfId, categoryId));
    }

    public void addCategory(String name, Long parentId) {
        executor.execute(() -> categoryDao.insert(new Category(name, parentId)));
    }

    /** Kategorie anlegen und die neue ID zurückmelden (z.B. um gleich hinein zu importieren) */
    public void addCategory(String name, Long parentId, ResultCallback<Long> callback) {
        executor.execute(() -> {
            long id = categoryDao.insert(new Category(name, parentId));
            mainHandler.post(() -> callback.onResult(id));
        });
    }

    /**
     * Fertiges Scan-PDF (liegt im Cache) in die Bibliothek übernehmen:
     * in pdfs/ verschieben, eintragen und für die Suche indizieren.
     */
    public void saveScannedPdf(long categoryId, String displayName, File pdf, ResultCallback<Boolean> callback) {
        executor.execute(() -> {
            String fileName = UUID.randomUUID().toString() + ".pdf";
            File target = new File(pdfDir, fileName);
            // Aus cache/ kopieren statt verschieben: verschobene Dateien behalten ihre Cache-Gruppe
            // und würden in den Einstellungen als "Cache" gezählt
            boolean inCache = pdf.getAbsolutePath().startsWith(appContext.getCacheDir().getAbsolutePath());
            boolean ok = (!inCache && pdf.renameTo(target)) || copyFile(pdf, target);
            pdf.delete();
            if (ok) {
                pdfFileDao.insert(new PdfFile(categoryId, displayName, fileName));
                indexer.indexPending();   // gescannte Seiten sind Bilder → OCR macht sie durchsuchbar
            }
            mainHandler.post(() -> callback.onResult(ok));
        });
    }

    private static boolean copyFile(File from, File to) {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return true;
        } catch (IOException e) {
            Log.e("LibraryRepository", "Kopieren fehlgeschlagen", e);
            to.delete();
            return false;
        }
    }

    public void renameCategory(long id, String newName) {
        executor.execute(() -> categoryDao.rename(id, newName));
    }

    public void deleteCategory(long id) {
        executor.execute(() -> {
            List<String> fileNames = new ArrayList<>();
            db.runInTransaction(() -> {
                fileNames.addAll(pdfFileDao.getFilePathsInSubtree(id));
                categoryDao.delete(id);
                pdfTextDao.deleteOrphans();
            });
            for (String name : fileNames) {
                new File(pdfDir, name).delete();
            }
        });
    }

    public void renamePdf(long id, String newName) {
        executor.execute(() -> pdfFileDao.rename(id, newName));
    }

    public void deletePdf(PdfFile pdf) {
        executor.execute(() -> {
            db.runInTransaction(() -> {
                pdfFileDao.delete(pdf.id);
                pdfTextDao.deleteOrphans();
            });
            getFile(pdf).delete();
        });
    }

    public void importPdfs(long categoryId, List<Uri> uris, ImportCallback callback) {
        executor.execute(() -> {
            int imported = 0;
            int failed = 0;
            for (Uri uri : uris) {
                if (importSingle(categoryId, uri)) imported++;
                else failed++;
            }
            final int ok = imported, bad = failed;
            mainHandler.post(() -> callback.onImportFinish(ok, bad));
            indexer.indexPending();   // neue PDFs im Hintergrund durchsuchbar machen
        });
    }

    /**
     * Darf diese Uri importiert werden? Nur content:// von FREMDEN Apps.
     * Sonst könnte eine andere App per "Teilen" z.B. file:///data/data/<diese App>/databases/… schicken,
     * und wir würden unsere eigene private Datei in die Bibliothek kopieren (und sie wäre teilbar).
     */
    private boolean isImportAllowed(Uri uri) {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return false;
        String authority = uri.getAuthority();
        return authority != null && !authority.startsWith(appContext.getPackageName());
    }

    /** PDF-Kennung "%PDF" muss in den ersten 1024 Bytes stehen (so verlangt es auch die Spezifikation) */
    private static boolean looksLikePdf(byte[] head, int length) {
        for (int i = 0; i + 3 < length; i++) {
            if (head[i] == '%' && head[i + 1] == 'P' && head[i + 2] == 'D' && head[i + 3] == 'F') return true;
        }
        return false;
    }

    private boolean importSingle(long categoryId, Uri uri) {
        if (!isImportAllowed(uri)) {
            Log.w("LibraryRepository", "Import abgelehnt (keine fremde content://-Uri): " + uri);
            return false;
        }
        String fileName = UUID.randomUUID().toString() + ".pdf";
        File target = new File(pdfDir, fileName);
        try (InputStream in = appContext.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("No InputStream for: " + uri);
            byte[] buf = new byte[8192];
            byte[] head = new byte[1024];
            int headLen = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                if (headLen < head.length) {   // Anfang merken für die PDF-Prüfung
                    int n = Math.min(read, head.length - headLen);
                    System.arraycopy(buf, 0, head, headLen, n);
                    headLen += n;
                }
                out.write(buf, 0, read);
            }
            if (!looksLikePdf(head, headLen)) throw new IOException("Kein PDF: " + uri);
        } catch (IOException | SecurityException e) {
            // SecurityException: keine Leseberechtigung für die Uri (z.B. abgelaufen beim Teilen)
            // → als "fehlgeschlagen" zählen statt die ganze App abstürzen zu lassen
            Log.e("LibraryRepository", "Import failed: " + uri, e);
            target.delete();
            return false;
        }

        pdfFileDao.insert(new PdfFile(categoryId, queryDisplayName(uri), fileName));
        return true;
    }

    private String queryDisplayName(Uri uri) {
        String name = null;
        try (Cursor c = appContext.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (RuntimeException e) {
            Log.w("LibraryRepository", "Dateiname nicht lesbar: " + uri, e);   // Name ist optional
        }
        if (name == null || name.trim().isEmpty()) {
            name = "Unbekannt";
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }
}
