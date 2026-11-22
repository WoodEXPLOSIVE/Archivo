package at.woodexplosive.archivo.data.dao;

import androidx.room.ColumnInfo;

/** Ein Treffer der Inhaltssuche: welches PDF, welche Seite, Textausschnitt mit [Markierung] */
public class ContentHit {
    @ColumnInfo(name = "pdf_id")
    public long pdfId;

    @ColumnInfo(name = "page")
    public int page;

    @ColumnInfo(name = "snippet")
    public String snippet;
}
