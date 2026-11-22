package at.woodexplosive.learnarchiver.ui.drawer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import at.woodexplosive.learnarchiver.R;
import at.woodexplosive.learnarchiver.databinding.ItemTreeBinding;

public class DrawerTreeAdapter extends ListAdapter<TreeItem, DrawerTreeAdapter.ViewHolder> {
    public interface Listener {
        void onItemClick(TreeItem item);
        void onItemLongClick(TreeItem item, View anchor);
        void onExpandClick(TreeItem item);
    }
    private final Listener listener;
    public DrawerTreeAdapter(Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemTreeBinding b = ItemTreeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(b);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position), listener);
    }

    private static final DiffUtil.ItemCallback<TreeItem> DIFF = new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull TreeItem a, @NonNull TreeItem b) {
            return a.type == b.type && a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull TreeItem a, @NonNull TreeItem b) {
            return a.name.equals(b.name) && a.depth == b.depth
                    && a.expanded == b.expanded && a.hasChildren == b.hasChildren
                    && a.selectionMode == b.selectionMode && a.selected == b.selected;
        }
    };

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemTreeBinding b;
        public ViewHolder(ItemTreeBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(TreeItem item, Listener listener) {
            float density = itemView.getResources().getDisplayMetrics().density;
            int start = (int) ((8 + item.depth * 20) * density);
            itemView.setPaddingRelative(start, 0, itemView.getPaddingEnd(), 0);

            boolean isCategory = item.type == TreeItem.TYPE_CATEGORY;
            b.name.setText(item.name);
            b.icon.setImageResource(isCategory ? R.drawable.ic_folder : R.drawable.ic_pdf);

            b.arrow.setVisibility(isCategory && item.hasChildren ? View.VISIBLE : View.INVISIBLE);
            b.arrow.setRotation(item.expanded ? 90f : 0f);
            b.arrow.setOnClickListener(v -> listener.onExpandClick(item));

            b.check.setVisibility(item.selectionMode ? View.VISIBLE : View.GONE);
            b.check.setChecked(item.selected);

            itemView.setOnClickListener(v -> listener.onItemClick(item));
            itemView.setOnLongClickListener(v -> {
                listener.onItemLongClick(item, v);
                return true;
            });
        }
    }
}
