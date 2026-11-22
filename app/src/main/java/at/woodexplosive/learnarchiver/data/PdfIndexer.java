package at.woodexplosive.learnarchiver.data;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.graphics.pdf.content.PdfPageTextContent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.RequiresApi;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import at.woodexplosive.learnarchiver.data.dao.PdfFileDao;
import at.woodexplosive.learnarchiver.data.dao.PdfTextDao;
import at.woodexplosive.learnarchiver.data.enitity.PdfFile;
import at.woodexplosive.learnarchiver.data.enitity.PdfText;

/**
 * Liest den Text aller noch nicht indizierten PDFs aus und speichert ihn seitenweise in pdf_text.
 * <p>
 * - Text-PDFs: PdfRenderer.Page.getTextContents() (gibt's erst ab Android 15 / API 35)
 * - Eingescannte Seiten (kaum/kein Text): Seite als Bild rendern → ML Kit Texterkennung (OCR)
 * - Vor Android 15 gibt es keine Textebene → JEDE Seite wird per OCR gelesen (langsamer, aber geht überall)
 * <p>
 * Läuft auf einem EIGENEN Thread, damit lange OCR-Läufe die normalen DB-Aktionen
 * (dbExecutor) nicht blockieren. Fortschritt im Logcat unter "PdfIndexer".
 */
public class PdfIndexer {

    private static final String TAG = "PdfIndexer";
    /** Weniger Zeichen als das auf einer Seite → vermutlich ein Scan → OCR versuchen */
    private static final int MIN_TEXT_CHARS = 20;
    /** Breite für OCR-Bilder: genug für gut lesbare Schrift, ohne zu viel Speicher */
    private static final int OCR_WIDTH = 1600;
    private static final int OCR_MAX_HEIGHT = 4000;

    private final AppDataBase db;
    private final PdfFileDao pdfFileDao;
    private final PdfTextDao pdfTextDao;
    private final File pdfDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    PdfIndexer(AppDataBase db, File pdfDir) {
        this.db = db;
        this.pdfFileDao = db.pdfFileDao();
        this.pdfTextDao = db.pdfTextDao();
        this.pdfDir = pdfDir;
    }

    /**
     * Kann PdfRenderer die eingebettete Textebene liefern? Erst ab Android 15.
     * Sonst wird alles per OCR gelesen (Indexer) bzw. markiert (Viewer).
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static boolean hasTextLayerApi() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM;
    }

    /**
     * Alle PDFs mit index_state = PENDING abarbeiten. Mehrfaches Aufrufen ist harmlos:
     * Die Aufträge laufen nacheinander, und ein späterer findet nur noch, was übrig ist.
     */
    public void indexPending() {
        executor.execute(() -> {
            List<PdfFile> pending = pdfFileDao.getPendingIndex();
            if (pending.isEmpty()) return;

            Log.i(TAG, pending.size() + " PDF(s) zu indizieren");
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            try {
                for (PdfFile pdf : pending) {
                    indexPdf(pdf, recognizer);
                }
            } finally {
                recognizer.close();
            }
        });
    }

    private void indexPdf(PdfFile pdf, TextRecognizer recognizer) {
        long start = SystemClock.elapsedRealtime();
        List<PdfText> pages = new ArrayList<>();
        int ocrPages = 0;
        int pageCount;

        File file = new File(pdfDir, pdf.filePath);
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            pageCount = renderer.getPageCount();
            for (int i = 0; i < pageCount; i++) {
                String text;
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    // Ohne Textebene (vor Android 15) bleibt das leer → OCR übernimmt unten
                    text = hasTextLayerApi() ? extractText(page) : "";
                    if (text.trim().length() < MIN_TEXT_CHARS) {
                        String ocr = recognize(page, recognizer);
                        if (ocr.trim().length() > text.trim().length()) {
                            text = ocr;
                            ocrPages++;
                        }
                    }
                }
                if (!text.trim().isEmpty()) {
                    pages.add(new PdfText(pdf.id, i, text));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;   // App wird beendet → bleibt PENDING und wird beim nächsten Start fortgesetzt
        } catch (Exception e) {
            // IOException (Datei fehlt/kaputt), SecurityException (Passwort), ML-Kit-Fehler, ...
            Log.w(TAG, "Indizieren fehlgeschlagen: " + pdf.displayName, e);
            pdfFileDao.setIndexState(pdf.id, PdfFile.INDEX_FAILED);
            return;
        }

        db.runInTransaction(() -> {
            if (pdfFileDao.getById(pdf.id) == null) return;   // inzwischen gelöscht → nichts speichern
            if (!pages.isEmpty()) pdfTextDao.insert(pages);
            pdfFileDao.setIndexState(pdf.id, PdfFile.INDEX_DONE);
        });

        Log.i(TAG, "Indiziert: " + pdf.displayName + " – " + pageCount + " Seiten, davon "
                + ocrPages + " per OCR, " + (SystemClock.elapsedRealtime() - start) + " ms");
    }

    /** Eingebettete Textebene der Seite (leer bei Scans) */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private String extractText(PdfRenderer.Page page) {
        StringBuilder sb = new StringBuilder();
        for (PdfPageTextContent content : page.getTextContents()) {
            sb.append(content.getText()).append('\n');
        }
        return sb.toString();
    }

    /** Seite als Bild rendern und mit ML Kit den Text erkennen (synchron – wir sind im Hintergrund) */
    private String recognize(PdfRenderer.Page page, TextRecognizer recognizer) throws Exception {
        int width = OCR_WIDTH;
        int height = Math.round(width * (float) page.getHeight() / page.getWidth());
        if (height > OCR_MAX_HEIGHT) {   // sehr lange Seiten verkleinern
            width = Math.round(width * (float) OCR_MAX_HEIGHT / height);
            height = OCR_MAX_HEIGHT;
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).getText();
        } finally {
            bitmap.recycle();   // ~13 MB sofort freigeben, nicht auf den GC warten
        }
    }
}
