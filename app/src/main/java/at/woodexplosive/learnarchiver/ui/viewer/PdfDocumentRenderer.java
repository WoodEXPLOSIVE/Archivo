package at.woodexplosive.learnarchiver.ui.viewer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.graphics.pdf.content.PdfPageTextContent;
import android.graphics.pdf.models.PageMatchBounds;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.RequiresApi;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import at.woodexplosive.learnarchiver.data.PdfIndexer;

public class PdfDocumentRenderer {
    private static final String TAG = "PdfDocumentRenderer";
    /** Weniger Text auf der Seite → eingescannt → Treffer per OCR suchen (wie im PdfIndexer) */
    private static final int MIN_TEXT_CHARS = 20;

    public interface OpenCallback {
        void onOpened(List<Float> aspectRatios);
        void onError(Exception e);
    }

    @FunctionalInterface
    public interface RenderCallback {
        void onPageRendered(int index, Bitmap bitmap);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<Integer, Bitmap> chache;

    private ParcelFileDescriptor fileDescriptor;
    private PdfRenderer renderer;

    /** Suchbegriffe, die auf den Seiten gelb markiert werden (leer = keine Markierung) */
    private List<String> highlightTerms = Collections.emptyList();
    private TextRecognizer recognizer;   // nur bei Bedarf (Scan-Seiten mit Markierung) erzeugt

    public PdfDocumentRenderer() {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        chache = new LruCache<Integer, Bitmap>(maxKb / 8) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    /**
     * Suchbegriff(e) zum Hervorheben setzen – VOR dem ersten render() aufrufen.
     * Mehrere Wörter werden einzeln markiert. Vor Android 15 (keine Textsuche im PdfRenderer) per OCR.
     */
    public void setHighlight(String query) {
        List<String> terms = new ArrayList<>();
        if (query != null) {
            for (String word : query.trim().split("\\s+")) {
                String w = word.replace("\"", "").replace("*", "");
                if (!w.isEmpty()) terms.add(w);
            }
        }
        highlightTerms = terms;
    }

    public void open(File file, OpenCallback callback) {
        executor.execute(() -> {
            try {
                fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(fileDescriptor);

                List<Float> ratios = new ArrayList<>();
                for (int i = 0; i < renderer.getPageCount(); i++) {
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        ratios.add((float) page.getHeight() / page.getWidth());
                    }
                }
                mainHandler.post(() -> callback.onOpened(ratios));
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "Failed to Open: " + file, e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public Bitmap getChached(int index) {
        return chache.get(index);
    }

    public void render(int index, int width, RenderCallback callback) {
        executor.execute(() -> {
            if (renderer == null) return;

            Bitmap bitmap = getChached(index);
            if (bitmap == null || bitmap.getWidth() != width) {
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    int height = Math.round(width * (float) page.getHeight() / page.getWidth());
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                    if (!highlightTerms.isEmpty()) {
                        highlight(page, bitmap);
                    }
                }
                chache.put(index, bitmap);
            }

            final Bitmap result = bitmap;
            mainHandler.post(() -> callback.onPageRendered(index, result));
        });
    }

    // =====================================================================
    // Hervorhebung
    // =====================================================================

    /** Gelber "Textmarker": MULTIPLY lässt Schwarz schwarz und färbt nur den weißen Hintergrund */
    private static Paint highlightPaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFE066);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
        return paint;
    }

    private void highlight(PdfRenderer.Page page, Bitmap bitmap) {
        try {
            // Textebene nur ab Android 15 auslesbar – sonst (und bei Scans) per OCR suchen
            List<RectF> rects;
            if (PdfIndexer.hasTextLayerApi() && hasTextLayer(page)) {
                rects = findInTextLayer(page, bitmap);
            } else {
                rects = findWithOcr(bitmap);
            }
            if (rects.isEmpty()) return;

            Canvas canvas = new Canvas(bitmap);
            Paint paint = highlightPaint();
            float pad = bitmap.getWidth() / 400f;   // etwas Rand um die Wörter
            for (RectF r : rects) {
                canvas.drawRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad, paint);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Markierung ist "nice to have" – Seite trotzdem anzeigen
            Log.w(TAG, "Hervorheben fehlgeschlagen", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private static boolean hasTextLayer(PdfRenderer.Page page) {
        int chars = 0;
        for (PdfPageTextContent content : page.getTextContents()) {
            chars += content.getText().trim().length();
            if (chars >= MIN_TEXT_CHARS) return true;
        }
        return false;
    }

    /** Text-PDF: eingebaute Suche, Ergebnis in Seiten-Koordinaten (Punkte) → auf Bitmap-Pixel skalieren */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private List<RectF> findInTextLayer(PdfRenderer.Page page, Bitmap bitmap) {
        float scale = bitmap.getWidth() / (float) page.getWidth();
        List<RectF> result = new ArrayList<>();
        for (String term : highlightTerms) {
            for (PageMatchBounds match : page.searchText(term)) {
                for (RectF r : match.getBounds()) {
                    result.add(new RectF(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale));
                }
            }
        }
        return result;
    }

    /** Scan: OCR auf der gerenderten Seite, Wörter vergleichen (ohne Groß/klein und Akzente) */
    private List<RectF> findWithOcr(Bitmap bitmap) throws Exception {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)));

        List<String> needles = new ArrayList<>();
        for (String term : highlightTerms) needles.add(normalize(term));

        List<RectF> result = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    String word = normalize(element.getText());
                    Rect box = element.getBoundingBox();
                    if (box == null) continue;
                    for (String needle : needles) {
                        if (word.contains(needle)) {
                            result.add(new RectF(box));
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    /** "Übung" → "ubung": klein, ohne Akzente – passend zum unicode61-Tokenizer der Suche */
    private static String normalize(String s) {
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    public void close() {
        executor.execute(() -> {
            if (renderer != null) renderer.close();
            try {
                if (fileDescriptor != null) fileDescriptor.close();
            } catch (IOException ignored) {}
            if (recognizer != null) recognizer.close();
            chache.evictAll();
        });
        executor.shutdown();
    }
}
