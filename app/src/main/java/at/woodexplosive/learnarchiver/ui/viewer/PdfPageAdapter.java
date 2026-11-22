package at.woodexplosive.learnarchiver.ui.viewer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;

import at.woodexplosive.learnarchiver.databinding.ItemPdfPageBinding;

@SuppressLint("NotifyDataSetChanged")
public class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.PageHolder> {
    private final PdfDocumentRenderer renderer;
    private List<Float> aspectRatios = Collections.emptyList();
    private int pageWidth;
    private final int maxRenderWidth;

    public PdfPageAdapter(PdfDocumentRenderer renderer, int screenWidth) {
        this.renderer = renderer;
        this.maxRenderWidth = screenWidth * 2;
    }

    public void setPages(List<Float> aspectRatios, int pageWidth) {
        this.aspectRatios = aspectRatios;
        this.pageWidth = pageWidth;
        notifyDataSetChanged();
    }

    /** Angezeigte Seitenbreite in Pixeln (Bildschirm × Zoom, ohne Padding) */
    public int getPageWidth() {
        return pageWidth;
    }

    /** Höhe / Breite der Seite */
    public float getAspectRatio(int position) {
        return aspectRatios.get(position);
    }

    /** Breite, in der die Seiten gerendert werden (begrenzt wegen Speicher) */
    public int getRenderWidth() {
        return Math.min(pageWidth, maxRenderWidth);
    }

    public void setPageWidth(int pageWidth) {
        this.pageWidth = pageWidth;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PageHolder(ItemPdfPageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
        ViewGroup.LayoutParams lp = holder.b.page.getLayoutParams();
        lp.width = pageWidth;
        lp.height = Math.round(pageWidth * aspectRatios.get(position));
        holder.b.page.setLayoutParams(lp);

        holder.boundPage = position;
        int renderWidth = getRenderWidth();
        Bitmap cached = renderer.getChached(position);
        if (cached != null) {
            holder.b.page.setImageBitmap(cached);
            if (cached.getWidth() == renderWidth) return;
        } else {
            holder.b.page.setImageDrawable(null);
        }

        renderer.render(position, renderWidth, (index, bitmap) -> {
            if (holder.boundPage == index) {
                holder.b.page.setImageBitmap(bitmap);
            }
        });
    }

    @Override
    public int getItemCount() {
        return aspectRatios.size();
    }

    public static class PageHolder extends RecyclerView.ViewHolder {
        final ItemPdfPageBinding b;
        int boundPage = -1;

        public PageHolder(@NonNull ItemPdfPageBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
