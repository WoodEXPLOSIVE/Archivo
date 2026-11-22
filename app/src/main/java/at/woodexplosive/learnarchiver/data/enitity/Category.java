package at.woodexplosive.learnarchiver.data.enitity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Eine Kategorie der Bibliothek. {@link #parentId} zeigt auf die Eltern-Kategorie
 * (null = oberste Ebene), so entsteht ein beliebig tiefer Baum.
 */
@Entity(
        tableName = "categories",
        foreignKeys = @ForeignKey(
                entity = Category.class,
                parentColumns = "id",
                childColumns = "parent_id",
                onDelete = ForeignKey.CASCADE),
        indices = @Index("parent_id"))
public class Category {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String name;

    @Nullable
    @ColumnInfo(name = "parent_id")
    public Long parentId;

    @ColumnInfo(name = "sortOrder")
    public int sortOrder;

    public Category(@NonNull String name, @Nullable Long parentId) {
        this.name = name;
        this.parentId = parentId;
    }
}
