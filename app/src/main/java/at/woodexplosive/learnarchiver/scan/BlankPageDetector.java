package at.woodexplosive.learnarchiver.scan;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Log;

import java.io.File;

/**
 * Erkennt leere Seiten (z.B. unbedruckte Rückseiten beim Duplex-Scan).
 * <p>
 * Vorgehen: Bild verkleinert laden (~400 px breit), Rand weglassen (Scanner-Schatten),
 * Papierhelligkeit = Median der Helligkeit. "Tinte" = Pixel, das deutlich dunkler als das Papier ist
 * UND einen ebenso dunklen Nachbarn hat (einzelne Staubkörner zählen nicht). Durchscheinende Schrift
 * der Vorderseite ist nur leicht grau und bleibt unter der Schwelle.
 * Leer = weniger als {@link #MAX_INK_FRACTION} der Fläche ist Tinte.
 * <p>
 * Blockiert (Datei lesen) → nur im Hintergrund aufrufen.
 */
public final class BlankPageDetector {

    private static final String TAG = "BlankPageDetector";

    /** Zielbreite für die Analyse – reicht für Text, ist schnell */
    private static final int ANALYSIS_WIDTH = 400;
    /** Anteil des Rands, der ignoriert wird (je Seite) */
    private static final float MARGIN = 0.04f;
    /** So viel dunkler als das Papier muss ein Pixel sein, um als Tinte zu zählen (0–255) */
    private static final int INK_CONTRAST = 60;
    /** Höchstens so viel Tinte (Anteil der Fläche) darf eine "leere" Seite haben – ca. 45 Pixel */
    private static final float MAX_INK_FRACTION = 0.0002f;

    private BlankPageDetector() {}

    /** @return true, wenn die Seite leer ist. Im Zweifel (Datei nicht lesbar) false – lieber behalten. */
    public static boolean isBlank(File jpeg) {
        Bitmap bmp = decode(jpeg);
        if (bmp == null) return false;
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int x0 = Math.round(w * MARGIN), x1 = w - x0;
            int y0 = Math.round(h * MARGIN), y1 = h - y0;
            int cw = x1 - x0, ch = y1 - y0;
            if (cw < 10 || ch < 10) return false;

            int[] px = new int[cw * ch];
            bmp.getPixels(px, 0, cw, x0, y0, cw, ch);

            // Helligkeit (0–255) + Histogramm für den Median
            int[] lum = new int[px.length];
            int[] hist = new int[256];
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                lum[i] = l;
                hist[l]++;
            }
            int paper = median(hist, px.length);
            int inkLimit = paper - INK_CONTRAST;

            // Tinte = dunkel + mindestens ein dunkler Nachbar (rechts/links/oben/unten)
            int ink = 0;
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    int i = y * cw + x;
                    if (lum[i] >= inkLimit) continue;
                    boolean neighbor = (x > 0 && lum[i - 1] < inkLimit)
                            || (x < cw - 1 && lum[i + 1] < inkLimit)
                            || (y > 0 && lum[i - cw] < inkLimit)
                            || (y < ch - 1 && lum[i + cw] < inkLimit);
                    if (neighbor) ink++;
                }
            }
            float fraction = ink / (float) px.length;
            boolean blank = fraction < MAX_INK_FRACTION;
            Log.i(TAG, jpeg.getName() + ": Papier=" + paper + " Tinte=" + ink + " ("
                    + String.format(java.util.Locale.ROOT, "%.4f", fraction * 100) + " %) → "
                    + (blank ? "leer" : "bedruckt"));
            return blank;
        } finally {
            bmp.recycle();
        }
    }

    private static int median(int[] hist, int total) {
        int half = total / 2, sum = 0;
        for (int v = 0; v < 256; v++) {
            sum += hist[v];
            if (sum > half) return v;
        }
        return 255;
    }

    /** Verkleinert laden: größte Zweierpotenz, bei der das Bild noch mindestens ANALYSIS_WIDTH breit ist */
    private static Bitmap decode(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= ANALYSIS_WIDTH) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), o);
    }
}
