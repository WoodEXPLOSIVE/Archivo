package at.woodexplosive.learnarchiver.ui.drawer;

import androidx.annotation.Nullable;

import at.woodexplosive.learnarchiver.data.enitity.Category;
import at.woodexplosive.learnarchiver.data.enitity.PdfFile;

/**
 * Eine Zeile im Kategorie-Baum der Seitenleiste (Kategorie oder PDF). Unveränderlich, damit DiffUtil
 * alten und neuen Zustand vergleichen kann – Änderungen erzeugen immer neue Objekte.
 */
public class TreeItem {
    public static final int TYPE_CATEGORY = 0;
    public static final int TYPE_PDF = 1;

    public final int type;
    public final long id;
    public final String name;
    public final int depth;
    public final boolean expanded;
    public final boolean hasChildren;
    public final boolean selectionMode;   // Auswahlmodus aktiv → Checkbox anzeigen
    public final boolean selected;        // diese Zeile ist markiert
    @Nullable public final PdfFile pdf;

    public TreeItem(int type, long id, String name, int depth, boolean expanded, boolean hasChildren,
                    boolean selectionMode, boolean selected, @Nullable PdfFile pdf) {
        this.type = type;
        this.id = id;
        this.name = name;
        this.depth = depth;
        this.expanded = expanded;
        this.hasChildren = hasChildren;
        this.selectionMode = selectionMode;
        this.selected = selected;
        this.pdf = pdf;
    }

    /** Kategorie-Zeile ohne Auswahlmodus (z.B. für Auswahldialoge) */
    public static TreeItem category(Category c, int depth, boolean expanded, boolean hasChildren) {
        return category(c, depth, expanded, hasChildren, false, false);
    }

    public static TreeItem category(Category c, int depth, boolean expanded, boolean hasChildren,
                                    boolean selectionMode, boolean selected) {
        return new TreeItem(TYPE_CATEGORY, c.id, c.name, depth, expanded, hasChildren,
                selectionMode, selected, null);
    }

    /** PDF-Zeile in der angegebenen Einrückungstiefe */
    public static TreeItem pdf(PdfFile p, int depth, boolean selectionMode, boolean selected) {
        return new TreeItem(TYPE_PDF, p.id, p.displayName, depth, false, false,
                selectionMode, selected, p);
    }
}
