package at.woodexplosive.learnarchiver.ui.drawer;

import androidx.annotation.Nullable;

import at.woodexplosive.learnarchiver.data.enitity.PdfFile;

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

    public static SearchResult category(long id, String name, String path) {
        return new SearchResult(TYPE_CATEGORY, id, name, path, null, -1);
    }

    public static SearchResult pdf(PdfFile pdf, String subtitle, int page) {
        return new SearchResult(TYPE_PDF, pdf.id, pdf.displayName, subtitle, pdf, page);
    }
}
