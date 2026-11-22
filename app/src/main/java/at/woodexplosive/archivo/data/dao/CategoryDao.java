package at.woodexplosive.archivo.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

import at.woodexplosive.archivo.data.entity.Category;
/** Datenbankzugriffe auf die Kategorien. Unterkategorien und PDFs löscht die DB per CASCADE mit. */
@Dao
public interface CategoryDao {
    @Insert
    long insert(Category category);

    @Query("UPDATE categories SET name = :newName WHERE id = :id")
    void rename(long id, String newName);

    @Query("UPDATE categories SET parent_id = :newParentId WHERE id = :id")
    void move(long id, Long newParentId);

    @Query("DELETE FROM categories WHERE id = :id")
    void delete(long id);

    @Query("SELECT * FROM categories ORDER BY sortOrder, name COLLATE NOCASE")
    LiveData<List<Category>> getAll();
}
