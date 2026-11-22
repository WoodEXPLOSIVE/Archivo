package at.woodexplosive.archivo.data;

import android.content.Context;

import androidx.annotation.NonNull;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import at.woodexplosive.archivo.data.dao.CategoryDao;
import at.woodexplosive.archivo.data.dao.PdfFileDao;
import at.woodexplosive.archivo.data.dao.PdfTextDao;
import at.woodexplosive.archivo.data.entity.Category;
import at.woodexplosive.archivo.data.entity.PdfFile;
import at.woodexplosive.archivo.data.entity.PdfText;

/**
 * Room-Datenbank der Bibliothek (Datei {@code archivo.db}).
 * <p>
 * Tabellen: Kategorien, PDFs und der FTS4-Volltextindex. Schema-Änderungen brauchen eine höhere
 * {@code version} und eine Migration (siehe {@link #MIGRATION_3_4}), sonst wird die DB neu angelegt.
 */
@Database(entities = {Category.class, PdfFile.class, PdfText.class}, version = 4, exportSchema = false)
public abstract class AppDataBase extends RoomDatabase {
    /** Zugriff auf die Kategorien */
    public abstract CategoryDao categoryDao();
    /** Zugriff auf die PDF-Einträge */
    public abstract PdfFileDao pdfFileDao();
    /** Zugriff auf den Volltextindex (Text je Seite) */
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

    /**
     * Ein Thread für alle DB-Schreibzugriffe: Room verbietet DB-Zugriffe auf dem Main-Thread, und
     * nacheinander ausgeführt kommen sich Aktionen nicht in die Quere.
     */
    public static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    /**
     * Die eine Datenbank-Instanz der App (wird beim ersten Aufruf angelegt, thread-sicher).
     * @param context beliebiger Context – intern wird der Application-Context verwendet
     */
    public static AppDataBase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDataBase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDataBase.class,
                            "archivo.db"
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
