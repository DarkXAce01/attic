package io.github.darkxace01.attic.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.darkxace01.attic.vision.Enhance
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.LocalDate

@Entity(tableName = "album")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    /** A date for the whole album, used by prints that have none of their own. */
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val dateLabel: String? = null,
) {
    val date: PhotoDate? get() = photoDate(dateFrom, dateTo, dateLabel)
}

/**
 * One physical print. The capture folder holds the untouched original photo; [fileName] is the
 * flattened crop made from it with [quad], which can always be redone from the original.
 */
@Entity(tableName = "print", indices = [Index("albumId"), Index("dateFrom")])
data class PrintEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumId: Long,
    /** Folder of the capture under files/scans. */
    val scanDir: String,
    val fileName: String,
    /** Album page (one capture = one page) and position on it, in reading order. */
    val page: Int,
    val position: Int,
    val width: Int,
    val height: Int,
    val sharpness: Double,
    /** Corners in the upright original photo, "x,y,x,y,x,y,x,y". */
    val quad: String,
    /** [Enhance] level: 0 original, 1 natural, 2 strong. The crop itself is never changed by it. */
    @ColumnInfo(defaultValue = "1") val enhance: Int = Enhance.NATURAL,
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val dateLabel: String? = null,
    val place: String? = null,
    val caption: String? = null,
    val createdAt: Long,
    /** Bumped on every change that affects the exported file. */
    val version: Int = 1,
    val galleryUri: String? = null,
    val exportedVersion: Int = 0,
) {
    val date: PhotoDate? get() = photoDate(dateFrom, dateTo, dateLabel)
    val cropQuad: Quad get() = parseQuad(quad)
    val inGallery: Boolean get() = galleryUri != null && exportedVersion == version
    fun file(root: File) = File(File(root, scanDir), fileName)
}

@Entity(tableName = "person", indices = [Index(value = ["name"], unique = true)])
data class PersonEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "print_person", primaryKeys = ["printId", "personId"], indices = [Index("personId")])
data class PrintPersonEntity(val printId: Long, val personId: Long)

data class AlbumSummary(
    val id: Long,
    val title: String,
    val dateLabel: String?,
    val count: Int,
    val pages: Int,
    val coverDir: String?,
    val coverFile: String?,
    val coverEnhance: Int?,
)

data class LibraryCounts(val total: Int, val inGallery: Int)

@Dao
interface AtticDao {
    @Insert fun insertAlbum(album: AlbumEntity): Long
    @Update fun updateAlbum(album: AlbumEntity)
    @Query("DELETE FROM album WHERE id = :id") fun deleteAlbum(id: Long)
    @Query("SELECT * FROM album WHERE id = :id") fun album(id: Long): AlbumEntity?
    @Query("SELECT * FROM album WHERE id = :id") fun albumFlow(id: Long): Flow<AlbumEntity?>
    @Query("SELECT * FROM album ORDER BY createdAt DESC") fun albums(): List<AlbumEntity>

    @Query(
        """
        SELECT a.id, a.title, a.dateLabel, COUNT(p.id) AS count, COUNT(DISTINCT p.page) AS pages,
            (SELECT scanDir FROM print WHERE albumId = a.id ORDER BY page, position LIMIT 1) AS coverDir,
            (SELECT fileName FROM print WHERE albumId = a.id ORDER BY page, position LIMIT 1) AS coverFile,
            (SELECT enhance FROM print WHERE albumId = a.id ORDER BY page, position LIMIT 1) AS coverEnhance
        FROM album a LEFT JOIN print p ON p.albumId = a.id
        GROUP BY a.id ORDER BY a.createdAt DESC
        """,
    )
    fun albumSummaries(): Flow<List<AlbumSummary>>

    @Insert fun insertPrint(print: PrintEntity): Long
    @Update fun updatePrint(print: PrintEntity)
    @Query("DELETE FROM print WHERE id = :id") fun deletePrint(id: Long)
    @Query("SELECT * FROM print WHERE id = :id") fun print(id: Long): PrintEntity?
    @Query("SELECT * FROM print WHERE id = :id") fun printFlow(id: Long): Flow<PrintEntity?>
    @Query("SELECT * FROM print WHERE albumId = :albumId ORDER BY page, position") fun printsIn(albumId: Long): Flow<List<PrintEntity>>
    @Query("SELECT * FROM print WHERE albumId = :albumId ORDER BY page, position") fun printsInNow(albumId: Long): List<PrintEntity>
    @Query("SELECT * FROM print ORDER BY createdAt") fun allPrints(): List<PrintEntity>
    @Query("SELECT COALESCE(MAX(page), 0) FROM print WHERE albumId = :albumId") fun lastPage(albumId: Long): Int
    @Query("SELECT DISTINCT scanDir FROM print") fun knownScanDirs(): List<String>
    @Query("SELECT COUNT(*) AS total, SUM(CASE WHEN galleryUri IS NOT NULL AND exportedVersion = version THEN 1 ELSE 0 END) AS inGallery FROM print")
    fun counts(): Flow<LibraryCounts>

    @Query("SELECT * FROM person WHERE name = :name COLLATE NOCASE") fun personNamed(name: String): PersonEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertPerson(person: PersonEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun tag(link: PrintPersonEntity)
    @Query("DELETE FROM print_person WHERE printId = :printId") fun untagAll(printId: Long)
    @Query("SELECT p.name FROM person p JOIN print_person pp ON pp.personId = p.id WHERE pp.printId = :printId ORDER BY p.name")
    fun peopleIn(printId: Long): Flow<List<String>>
    @Query("SELECT p.name FROM person p JOIN print_person pp ON pp.personId = p.id WHERE pp.printId = :printId ORDER BY p.name")
    fun peopleInNow(printId: Long): List<String>
    @Query("SELECT p.name FROM person p LEFT JOIN print_person pp ON pp.personId = p.id GROUP BY p.id ORDER BY COUNT(pp.printId) DESC, p.name")
    fun allPeople(): Flow<List<String>>

    @Transaction
    fun setPeople(printId: Long, names: List<String>) {
        untagAll(printId)
        for (name in names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }) {
            val id = personNamed(name)?.id ?: insertPerson(PersonEntity(name = name))
            tag(PrintPersonEntity(printId, id))
        }
    }
}

@Database(
    entities = [AlbumEntity::class, PrintEntity::class, PersonEntity::class, PrintPersonEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AtticDatabase : RoomDatabase() {
    abstract fun dao(): AtticDao

    companion object {
        fun open(context: Context): AtticDatabase =
            Room.databaseBuilder(context, AtticDatabase::class.java, "attic.db").addMigrations(MIGRATION_1_2).build()

        /** colorFix (on/off) became enhance (off/natural/strong). Prints keep the look they had. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(PRINT_V2.replace("`print`", "`print_new`"))
                val kept = "id, albumId, scanDir, fileName, page, position, width, height, sharpness, quad, dateFrom, " +
                    "dateTo, dateLabel, place, caption, createdAt, version, galleryUri, exportedVersion"
                db.execSQL("INSERT INTO print_new ($kept, enhance) SELECT $kept, CASE WHEN colorFix THEN 1 ELSE 0 END FROM print")
                db.execSQL("DROP TABLE print")
                db.execSQL("ALTER TABLE print_new RENAME TO print")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_print_albumId` ON `print` (`albumId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_print_dateFrom` ON `print` (`dateFrom`)")
            }
        }

        /** The print table exactly as Room creates it at version 2 (schemas/…/2.json). */
        private const val PRINT_V2 = "CREATE TABLE IF NOT EXISTS `print` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`albumId` INTEGER NOT NULL, `scanDir` TEXT NOT NULL, `fileName` TEXT NOT NULL, `page` INTEGER NOT NULL, " +
            "`position` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `sharpness` REAL NOT NULL, " +
            "`quad` TEXT NOT NULL, `enhance` INTEGER NOT NULL DEFAULT 1, `dateFrom` INTEGER, `dateTo` INTEGER, " +
            "`dateLabel` TEXT, `place` TEXT, `caption` TEXT, `createdAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
            "`galleryUri` TEXT, `exportedVersion` INTEGER NOT NULL)"
    }
}

internal fun photoDate(from: Long?, to: Long?, label: String?): PhotoDate? =
    if (from == null || to == null || label == null) null
    else PhotoDate(LocalDate.ofEpochDay(from), LocalDate.ofEpochDay(to), label)

fun Quad.encode(): String = points.joinToString(",") { "${it.x},${it.y}" }

fun parseQuad(s: String): Quad {
    val v = s.split(",").map { it.toFloat() }
    return Quad(Pt(v[0], v[1]), Pt(v[2], v[3]), Pt(v[4], v[5]), Pt(v[6], v[7]))
}
