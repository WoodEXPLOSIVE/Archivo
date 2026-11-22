package at.woodexplosive.archivo.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

import at.woodexplosive.archivo.data.entity.PdfText;

/**
 * Zugriffe auf den Volltextindex {@code pdf_text} (FTS4, eine Zeile pro Seite).
 * Ohne Foreign Keys – verwaiste Zeilen entfernt {@link #deleteOrphans()}.
 */
@Dao
public interface PdfTextDao {

    @Insert
    void insert(List<PdfText> pages);

    /**
     * @param ftsQuery FTS4-Syntax, z.B. "ableit*" "funktion*" (= beide Wörter, als Wortanfang)
     * snippet(...): Ausschnitt um den Treffer, Treffer in [ ] eingeklammert, max. 10 Wörter.
     * Auslassung bewusst ASCII "..." – ein mehrbytiges "…" kommt aus FTS4 kaputt zurück.
     */
    @Query("SELECT pdf_id, page, snippet(pdf_text, '[', ']', '...', -1, 10) AS snippet " +
            "FROM pdf_text WHERE pdf_text MATCH :ftsQuery ORDER BY pdf_id, page")
    List<ContentHit> search(String ftsQuery);

    /** Alle Seiten (0-basiert, aufsteigend) eines PDFs, die zur Suche passen – für "nächster Treffer" */
    @Query("SELECT page FROM pdf_text WHERE pdf_text MATCH :ftsQuery AND pdf_id = :pdfId ORDER BY page")
    List<Integer> findPages(String ftsQuery, long pdfId);

    /** Index eines PDFs löschen (z.B. nach dem Drehen, damit die OCR neu läuft) */
    @Query("DELETE FROM pdf_text WHERE pdf_id = :pdfId")
    void deleteForPdf(long pdfId);

    /** Index-Zeilen von PDFs entfernen, die es nicht mehr gibt */
    @Query("DELETE FROM pdf_text WHERE pdf_id NOT IN (SELECT id FROM pdf_files)")
    void deleteOrphans();
}
