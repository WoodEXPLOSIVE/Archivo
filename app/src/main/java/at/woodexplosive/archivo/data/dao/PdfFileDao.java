package at.woodexplosive.archivo.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

import at.woodexplosive.archivo.data.entity.PdfFile;

/**
 * Datenbankzugriffe auf die PDF-Einträge: anlegen, umbenennen, verschieben, Lesestand und Index-Status.
 */
@Dao
public interface PdfFileDao {
    @Insert
    long insert(PdfFile pdfFile);

    @Query("UPDATE pdf_files SET display_name = :newName WHERE id = :id")
    void rename(long id, String newName);

    @Query("DELETE FROM pdf_files WHERE id = :id")
    void delete(long id);

    @Query("UPDATE pdf_files SET category_id = :categoryId WHERE id = :id")
    void move(long id, long categoryId);

    @Query("UPDATE pdf_files SET last_page = :page, last_page_offset = :pageOffset, last_zoom = :zoom, " +
            "last_scroll_x = :scrollX WHERE id = :id")
    void setReadingPosition(long id, int page, float pageOffset, float zoom, float scrollX);

    // ---------- Volltext-Indizierung ----------

    @Query("SELECT * FROM pdf_files WHERE index_state = 0 ORDER BY added_at")
    List<PdfFile> getPendingIndex();

    @Query("UPDATE pdf_files SET index_state = :state WHERE id = :id")
    void setIndexState(long id, int state);

    @Query("SELECT COUNT(*) FROM pdf_files WHERE index_state = 0")
    LiveData<Integer> countPendingIndex();

    /** null, wenn es das PDF nicht (mehr) gibt */
    @Query("SELECT * FROM pdf_files WHERE id = :id")
    PdfFile getById(long id);

    @Query("SELECT * FROM pdf_files ORDER BY display_name COLLATE NOCASE")
    LiveData<List<PdfFile>> getAll();

    @Query("WITH RECURSIVE sub(id) AS (SELECT :categoryId UNION ALL SELECT c.id FROM categories c JOIN sub ON c.parent_id = sub.id) SELECT file_path FROM pdf_files WHERE category_id IN sub")
    List<String> getFilePathsInSubtree(long categoryId);
}
