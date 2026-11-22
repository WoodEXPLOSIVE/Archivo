package at.woodexplosive.learnarchiver.ui.scan;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import at.woodexplosive.learnarchiver.R;
import at.woodexplosive.learnarchiver.databinding.ItemScanPageBinding;
import at.woodexplosive.learnarchiver.scan.JpegPdfWriter;

/**
 * Vorschau der gescannten Seiten als waagrechte Leiste. Die Bilder werden verkleinert (und gedreht)
 * im Hintergrund geladen. Die Liste wird beim Ziehen lokal umgestellt, das ViewModel erfährt die
 * neue Reihenfolge erst beim Loslassen (siehe ScanFragment).
 */
public class ScanPageAdapter extends RecyclerView.Adapter<ScanPageAdapter.Holder> {

    public interface Listener {
        void onPageClick(int position, JpegPdfWriter.Page page);
        void onPageRotate(int position);
        void onPageDelete(int position);
    }

    private final List<JpegPdfWriter.Page> pages = new ArrayList<>();
    private final Listener listener;
    private final int thumbHeightPx;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Schlüssel = Pfad + Drehung, damit eine gedrehte Seite ein neues Vorschaubild bekommt */
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private boolean enabled = true;

    public ScanPageAdapter(int thumbHeightPx, Listener listener) {
        this.thumbHeightPx = thumbHeightPx;
        this.listener = listener;
    }

    public void setPages(List<JpegPdfWriter.Page> newPages) {
        if (pages.equals(newPages)) return;
        pages.clear();
        pages.addAll(newPages);
        notifyDataSetChanged();
    }

    /** Während eines Scans/Speicherns: Knöpfe ausblenden */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        notifyDataSetChanged();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Nur die Anzeige umstellen (beim Ziehen) */
    public void moveLocal(int from, int to) {
        Collections.swap(pages, from, to);
        notifyItemMoved(from, to);
    }

    /** Positionen auf den Karten neu nummerieren (nach dem Ziehen) */
    public void refreshNumbers() {
        notifyItemRangeChanged(0, pages.size());
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemScanPageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        JpegPdfWriter.Page page = pages.get(position);
        String path = page.jpegFile().getAbsolutePath();
        String key = path + "#" + page.rotation();
        boolean samePage = path.equals(h.boundPath);
        h.boundPath = path;
        h.boundKey = key;
        h.b.pageNumber.setText(String.valueOf(position + 1));
        h.b.btnDeletePage.setVisibility(enabled ? View.VISIBLE : View.GONE);
        h.b.btnRotatePage.setVisibility(enabled ? View.VISIBLE : View.GONE);
        h.b.btnDeletePage.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onPageDelete(pos);
        });
        h.b.btnRotatePage.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onPageRotate(pos);
        });
        h.itemView.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onPageClick(pos, pages.get(pos));
        });

        Bitmap cached = cache.get(key);
        if (cached != null) {
            h.b.pageImage.setImageBitmap(cached);
            return;
        }
        // Gleiche Seite (nur gedreht): altes Bild stehen lassen, bis das neue fertig ist (kein Flackern)
        if (!samePage) h.b.pageImage.setImageResource(R.drawable.ic_scan);
        decoder.execute(() -> {
            Bitmap bmp = decodePage(page, thumbHeightPx * 2 / 3, thumbHeightPx);
            mainHandler.post(() -> {
                if (bmp == null) return;
                cache.put(key, bmp);
                // Holder wurde inzwischen evtl. für eine andere Seite recycelt
                if (key.equals(h.boundKey)) h.b.pageImage.setImageBitmap(bmp);
            });
        });
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    public void shutdown() {
        decoder.shutdownNow();
        cache.evictAll();
    }

    /**
     * Seite verkleinert laden und so drehen, wie sie im PDF erscheint.
     * minW x minH = Mindestgröße der fertigen (gedrehten) Anzeige.
     */
    @Nullable
    public static Bitmap decodePage(JpegPdfWriter.Page page, int minW, int minH) {
        boolean sideways = page.rotation() == 90 || page.rotation() == 270;
        // Bei 90°/270° wird die Bildbreite zur Anzeigehöhe → Mindestmaße tauschen
        Bitmap bmp = sideways
                ? decodeSampled(page.jpegFile(), minH, minW)
                : decodeSampled(page.jpegFile(), minW, minH);
        if (bmp == null || page.rotation() == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(page.rotation());
        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (rotated != bmp) bmp.recycle();
        return rotated;
    }

    /** JPEG verkleinert laden (Zweierpotenzen), aber nicht kleiner als minW x minH */
    @Nullable
    public static Bitmap decodeSampled(File file, int minW, int minH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        // Halbieren, solange das Bild danach noch in beiden Richtungen groß genug ist
        while (o.outWidth / (sample * 2) >= minW && o.outHeight / (sample * 2) >= minH) {
            sample *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), o);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemScanPageBinding b;
        String boundPath;
        String boundKey;

        Holder(ItemScanPageBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
