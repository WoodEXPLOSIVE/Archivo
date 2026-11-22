package at.woodexplosive.archivo.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Fts4;
import androidx.room.FtsOptions;
import androidx.room.PrimaryKey;

/**
 * Volltext-Index: eine Zeile pro PDF-Seite.
 * FTS4 = SQLite-Volltextsuche. "unicode61" ignoriert Groß-/Kleinschreibung (auch Umlaute)
 * und entfernt Akzente, d.h. "ubung" findet auch "Übung".
 * pdf_id und page werden nicht durchsucht, nur mitgespeichert (notIndexed).
 * FTS-Tabellen können keine Foreign Keys → verwaiste Zeilen löscht PdfTextDao.deleteOrphans().
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = {"pdf_id", "page"})
@Entity(tableName = "pdf_text")
public class PdfText {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    public int rowId;

    @ColumnInfo(name = "pdf_id")
    public long pdfId;

    @ColumnInfo(name = "page")
    public int page;   // 0-basiert

    @NonNull
    @ColumnInfo(name = "content")
    public String content;

    public PdfText(long pdfId, int page, @NonNull String content) {
        this.pdfId = pdfId;
        this.page = page;
        this.content = content;
    }
}
