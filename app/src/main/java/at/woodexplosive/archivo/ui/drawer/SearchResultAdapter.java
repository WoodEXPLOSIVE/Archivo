package at.woodexplosive.archivo.ui.drawer;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import at.woodexplosive.archivo.R;
import at.woodexplosive.archivo.databinding.ItemSearchResultBinding;

/** Zeigt die Trefferliste der Suche in der Seitenleiste; der Suchbegriff wird fett hervorgehoben. */
public class SearchResultAdapter extends ListAdapter<SearchResult, SearchResultAdapter.ViewHolder> {

    /** Antippen eines Treffers */
    public interface Listener {
        void onResultClick(SearchResult result);
    }

    private final Listener listener;

    public SearchResultAdapter(Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemSearchResultBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SearchResult result = getItem(position);
        holder.b.icon.setImageResource(result.type == SearchResult.TYPE_CATEGORY
                ? R.drawable.ic_folder : R.drawable.ic_pdf);
        holder.b.title.setText(result.title);
        holder.b.subtitle.setText(highlight(result.subtitle));
        holder.b.subtitle.setVisibility(result.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        holder.itemView.setOnClickListener(v -> listener.onResultClick(result));
    }

    /** "…die [Ableitung] einer…" → Klammern entfernen, Treffer fett darstellen */
    private static CharSequence highlight(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('[', i);
            int close = open < 0 ? -1 : text.indexOf(']', open + 1);
            if (open < 0 || close < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, open);
            int start = sb.length();
            sb.append(text, open + 1, close);
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = close + 1;
        }
        return sb;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ItemSearchResultBinding b;

        ViewHolder(ItemSearchResultBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }

    private static final DiffUtil.ItemCallback<SearchResult> DIFF = new DiffUtil.ItemCallback<SearchResult>() {
        @Override
        public boolean areItemsTheSame(@NonNull SearchResult a, @NonNull SearchResult b) {
            return a.type == b.type && a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull SearchResult a, @NonNull SearchResult b) {
            return a.title.equals(b.title) && a.subtitle.equals(b.subtitle) && a.page == b.page;
        }
    };
}
