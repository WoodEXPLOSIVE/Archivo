package at.woodexplosive.archivo.ui.drawer;

import androidx.annotation.Nullable;

import at.woodexplosive.archivo.data.entity.PdfFile;

/** Ein Eintrag in der Trefferliste der Suche */
public class SearchResult {
    public static final int TYPE_CATEGORY = 0;
    public static final int TYPE_PDF = 1;

    public final int type;
    public final long id;
    public final String title;
    /** Kategoriepfad ("Mathe › Analysis") oder Textausschnitt ("S. 4: …[Ableitung]…") */
    public final String subtitle;
    @Nullable public final PdfFile pdf;
    /** Trefferseite bei Inhaltssuche (0-basiert), sonst -1 */
    public final int page;

    private SearchResult(int type, long id, String title, String subtitle, @Nullable PdfFile pdf, int page) {
        this.type = type;
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.pdf = pdf;
        this.page = page;
    }

    /**
     * Treffer auf eine Kategorie.
     * @param path Pfad der Eltern-Kategorien, z.B. "Mathe › Analysis"
     */
    public static SearchResult category(long id, String name, String path) {
        return new SearchResult(TYPE_CATEGORY, id, name, path, null, -1);
    }

    /**
     * Treffer auf ein PDF.
     * @param subtitle Kategoriepfad (Namenssuche) oder Textausschnitt (Inhaltssuche)
     * @param page     erste Trefferseite (0-basiert), -1 bei Namenssuche
     */
    public static SearchResult pdf(PdfFile pdf, String subtitle, int page) {
        return new SearchResult(TYPE_PDF, pdf.id, pdf.displayName, subtitle, pdf, page);
    }
}
