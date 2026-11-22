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

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

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
    /** Vor Android 15: Textebene + Wortpositionen per PdfBox (bei Bedarf geladen) */
    private File file;
    private PDDocument pdfBoxDoc;
    private boolean pdfBoxFailed;
    /** Ursprüngliche Drehung je Seite – im PdfBox-Dokument wird sie zum Auslesen auf 0 gesetzt */
    private final java.util.Map<Integer, Integer> originalRotation = new java.util.HashMap<>();

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
     * Mehrere Wörter werden einzeln markiert. Vor Android 15 (keine Textsuche im PdfRenderer) über die
     * Wortpositionen aus PdfBox, bei Scans per OCR.
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
                this.file = file;
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
            // Ab Android 15: Textebene über PdfRenderer. Davor: PdfBox (null = Seite ohne Text).
            // Scans (keine Textebene) in beiden Fällen per OCR.
            List<RectF> rects = null;
            if (PdfIndexer.hasTextLayerApi()) {
                if (hasTextLayer(page)) rects = findInTextLayer(page, bitmap);
            } else {
                rects = findWithPdfBox(page, bitmap);
            }
            if (rects == null) rects = findWithOcr(bitmap);
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

    /**
     * Vor Android 15: Treffer über die Zeichenpositionen aus PdfBox finden.
     * Positionen sind dort schon auf die Seitendrehung umgerechnet (oben links = 0,0), in Punkt.
     * @return null, wenn die Seite (fast) keinen Text hat → dann OCR
     */
    private List<RectF> findWithPdfBox(PdfRenderer.Page page, Bitmap bitmap) {
        if (pdfBoxDoc == null && !pdfBoxFailed) {
            try {
                pdfBoxDoc = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly());
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "PdfBox kann die Datei nicht laden – Markierung per OCR", e);
                pdfBoxFailed = true;
            }
        }
        if (pdfBoxDoc == null || page.getIndex() >= pdfBoxDoc.getNumberOfPages()) return null;

        List<String> needles = new ArrayList<>();
        for (String term : highlightTerms) needles.add(normalize(term));
        float scale = bitmap.getWidth() / (float) page.getWidth();
        PdfBoxFinder finder;
        try {
            // Wie im Indexer: ungedreht auslesen (saubere Wörter, Positionen in ungedrehten Koordinaten),
            // die Drehung rechnet bounds() danach selbst ein. Nur im Speicher, nichts wird gespeichert.
            PDPage pdPage = pdfBoxDoc.getPage(page.getIndex());
            Integer rotation = originalRotation.get(page.getIndex());
            if (rotation == null) {   // erstes Mal: echte Drehung merken, dann im Speicher auf 0
                rotation = ((pdPage.getRotation() % 360) + 360) % 360;
                originalRotation.put(page.getIndex(), rotation);
                pdPage.setRotation(0);
            }
            finder = new PdfBoxFinder(needles, scale, rotation,
                    pdPage.getCropBox().getWidth(), pdPage.getCropBox().getHeight());
            finder.setStartPage(page.getIndex() + 1);   // PdfBox zählt ab 1
            finder.setEndPage(page.getIndex() + 1);
            finder.getText(pdfBoxDoc);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "PdfBox-Suche fehlgeschlagen – Markierung per OCR", e);
            return null;
        }
        return finder.letters < MIN_TEXT_CHARS ? null : finder.result;
    }

    /**
     * Sammelt beim Textauslesen die Rechtecke der Suchtreffer. writeString bekommt jeweils ein Stück Text
     * (meist ein Wort) samt Position jedes Zeichens.
     */
    private static class PdfBoxFinder extends PDFTextStripper {
        final List<RectF> result = new ArrayList<>();
        final List<String> needles;
        final float scale;
        int letters;
        /** Ursprüngliche Drehung der Seite und ihre UNgedrehte Größe (CropBox, in Punkt) */
        private final int rotation;
        private final float w0, h0;

        PdfBoxFinder(List<String> needles, float scale, int rotation, float w0, float h0) throws IOException {
            this.needles = needles;
            this.scale = scale;
            this.rotation = rotation;
            this.w0 = w0;
            this.h0 = h0;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            letters += text.trim().length();
            if (text.length() != positions.size()) return;   // z.B. Ligaturen: Zuordnung unsicher → auslassen
            String hay = normalizeSameLength(text);
            for (String needle : needles) {
                if (needle.isEmpty()) continue;
                int from = 0;
                int idx;
                while ((idx = hay.indexOf(needle, from)) >= 0) {
                    result.add(bounds(positions.subList(idx, idx + needle.length())));
                    from = idx + needle.length();
                }
            }
        }

        /**
         * Umgebendes Rechteck der Zeichen, in Bitmap-Pixeln.
         * Erst in UNgedrehten Seitenkoordinaten (oben links = 0,0; dort läuft normaler Text waagrecht,
         * "DirAdj"-Werte, Y = Grundlinie), dann selbst um die Seitendrehung drehen – so wie PdfRenderer
         * die Seite anzeigt.
         */
        private RectF bounds(List<TextPosition> chars) {
            float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
            for (TextPosition tp : chars) {
                float h = Math.max(tp.getHeightDir(), tp.getFontSizeInPt() * 0.7f);
                left = Math.min(left, tp.getXDirAdj());
                right = Math.max(right, tp.getXDirAdj() + tp.getWidthDirAdj());
                top = Math.min(top, tp.getYDirAdj() - h);
                bottom = Math.max(bottom, tp.getYDirAdj() + h * 0.25f);   // Unterlängen (g, p, …)
            }
            RectF r = rotate(left, top, right, bottom);
            return new RectF(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale);
        }

        /** Rechteck von ungedrehten in angezeigte Koordinaten (Drehung im Uhrzeigersinn) */
        private RectF rotate(float l, float t, float r, float b) {
            switch (rotation) {
                case 90:  return new RectF(h0 - b, l, h0 - t, r);
                case 180: return new RectF(w0 - r, h0 - b, w0 - l, h0 - t);
                case 270: return new RectF(t, w0 - r, b, w0 - l);
                default:  return new RectF(l, t, r, b);
            }
        }
    }

    /** Wie normalize(), aber Zeichen für Zeichen – damit die Positionen zu den Indizes passen */
    private static String normalizeSameLength(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            String n = normalize(String.valueOf(s.charAt(i)));
            sb.append(n.length() == 1 ? n.charAt(0) : Character.toLowerCase(s.charAt(i)));
        }
        return sb.toString();
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
            if (pdfBoxDoc != null) {
                try {
                    pdfBoxDoc.close();
                } catch (IOException ignored) {}
            }
            chache.evictAll();
        });
        executor.shutdown();
    }
}
