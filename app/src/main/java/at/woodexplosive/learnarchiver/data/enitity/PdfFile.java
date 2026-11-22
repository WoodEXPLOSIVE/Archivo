package at.woodexplosive.learnarchiver.data.enitity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Ein PDF der Bibliothek. Die Datei liegt unter {@code files/pdfs/} mit einem UUID-Namen
 * ({@link #filePath}), angezeigt wird {@link #displayName}. Speichert außerdem den Lesestand
 * und ob der Inhalt schon für die Suche ausgelesen wurde.
 */
@Entity(
        tableName = "pdf_files",
        foreignKeys = @ForeignKey(
                entity = Category.class,
                parentColumns = "id",
                childColumns = "category_id",
                onDelete = ForeignKey.CASCADE),
        indices = @Index("category_id"))
public class PdfFile {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "category_id")
    public long categoryId;

    @NonNull
    @ColumnInfo(name = "display_name")
    public String displayName;

    @NonNull
    @ColumnInfo(name = "file_path")
    public String filePath;

    @ColumnInfo(name = "added_at")
    public long addedAt;

    /** Zuletzt angesehene Seite (0-basiert) – seit DB-Version 2 */
    @ColumnInfo(name = "last_page", defaultValue = "0")
    public int lastPage;

    /**
     * Genauer Lesestand – seit DB-Version 4:
     * lastPageOffset = wie weit in die Seite gescrollt war, in Seitenbreiten (unabhängig von Zoom/Bildschirm),
     * lastZoom = Zoomstufe (1 = ganze Breite), lastScrollX = waagrechte Position als Anteil der Inhaltsbreite
     */
    @ColumnInfo(name = "last_page_offset", defaultValue = "0")
    public float lastPageOffset;

    @ColumnInfo(name = "last_zoom", defaultValue = "1")
    public float lastZoom = 1f;

    @ColumnInfo(name = "last_scroll_x", defaultValue = "0")
    public float lastScrollX;

    public static final int INDEX_PENDING = 0;   // Inhalt noch nicht ausgelesen
    public static final int INDEX_DONE = 1;      // Inhalt steht in pdf_text
    public static final int INDEX_FAILED = 2;    // z.B. passwortgeschützt oder Datei fehlt

    /** Stand der Volltext-Indizierung – seit DB-Version 3 */
    @ColumnInfo(name = "index_state", defaultValue = "0")
    public int indexState;

    public PdfFile(long categoryId, @NonNull String displayName, @NonNull String filePath) {
        this.categoryId = categoryId;
        this.displayName = displayName;
        this.filePath = filePath;
        this.addedAt = System.currentTimeMillis();
    }
}
