package at.woodexplosive.learnarchiver.data;

import android.content.Context;

import androidx.annotation.NonNull;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import at.woodexplosive.learnarchiver.data.dao.CategoryDao;
import at.woodexplosive.learnarchiver.data.dao.PdfFileDao;
import at.woodexplosive.learnarchiver.data.dao.PdfTextDao;
import at.woodexplosive.learnarchiver.data.enitity.Category;
import at.woodexplosive.learnarchiver.data.enitity.PdfFile;
import at.woodexplosive.learnarchiver.data.enitity.PdfText;

@Database(entities = {Category.class, PdfFile.class, PdfText.class}, version = 4, exportSchema = false)
public abstract class AppDataBase extends RoomDatabase {
    public abstract CategoryDao categoryDao();
    public abstract PdfFileDao pdfFileDao();
    public abstract PdfTextDao pdfTextDao();

    private static volatile AppDataBase INSTANCE;

    /**
     * 3 → 4: genauer Lesestand. Echte Migration statt Neuanlegen, damit die Bibliothek erhalten bleibt.
     * Typ, NOT NULL und DEFAULT müssen exakt zur Entity passen, sonst meldet Room beim Start einen Fehler.
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE pdf_files ADD COLUMN last_page_offset REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE pdf_files ADD COLUMN last_zoom REAL NOT NULL DEFAULT 1");
            db.execSQL("ALTER TABLE pdf_files ADD COLUMN last_scroll_x REAL NOT NULL DEFAULT 0");
        }
    };

    public static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    public static AppDataBase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDataBase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDataBase.class,
                            "learnarchiver.db"
                    )
                    // App ist nicht veröffentlicht → bei Schema-Änderung DB einfach neu anlegen
                    // (Version trotzdem erhöhen!). Achtung: löscht dabei alle Kategorien/PDF-Einträge.
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration(true)
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
