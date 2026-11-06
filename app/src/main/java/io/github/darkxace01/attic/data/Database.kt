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

/**
 * A photo from the phone's own gallery kept in an album: only where it is, never a copy. An album can be
 * nothing but these, a way of keeping photos together.
 */
@Entity(tableName = "album_phone", indices = [Index("albumId"), Index(value = ["albumId", "uri"], unique = true)])
data class AlbumPhoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumId: Long,
    val uri: String,
    /** The day it was taken, as an epoch day; null if the photo doesn't say. */
    val taken: Long?,
    val width: Int,
    val height: Int,
    val addedAt: Long,
)

@Entity(tableName = "album")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    /** A date for the whole album, used by prints that have none of their own. */
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val dateLabel: String? = null,
    /** How its island looks in the album world (see world.riso.IslandLook); null until someone chooses. */
    val look: String? = null,
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
    /** The [version] last written to the backup folder; 0 if never. */
    @ColumnInfo(defaultValue = "0") val backedUpVersion: Int = 0,
    /** [io.github.darkxace01.attic.vision.PrintHash] of the crop turned 0, 90, 180 and 270 degrees, comma-separated. */
    val hashes: String? = null,
    /** The flattened back of the print (handwriting, a studio stamp), in the capture folder; null if none. */
    val backFile: String? = null,
    /** What the camera's date stamp on the print reads ('87 7 14): "" if it has none, null if not read yet. */
    val stamp: String? = null,
    /** The [quad] the print's faces were found with; null or different: not looked for yet (or recropped since). */
    val facesQuad: String? = null,
    /** While the print's border is trimmed off: its outline with the border, to put it back. */
    val borderQuad: String? = null,
    /** Taken from a film negative: the crop is developed into a positive every time it's made. */
    @ColumnInfo(defaultValue = "0") val negative: Boolean = false,
    /** Made bigger (see Upscale): every time the crop is made, it's made bigger again. */
    @ColumnInfo(defaultValue = "0") val upscaled: Boolean = false,
) {
    val date: PhotoDate? get() = photoDate(dateFrom, dateTo, dateLabel)
    /** The dates the stamp can mean: one, two when it reads both ways, none without a stamp. */
    val stampDates: List<java.time.LocalDate> get() = stamp?.let(io.github.darkxace01.attic.vision.StampDate::read).orEmpty()
    val cropQuad: Quad get() = parseQuad(quad)
    val inGallery: Boolean get() = galleryUri != null && exportedVersion == version
    val backedUp: Boolean get() = backedUpVersion == version
    val hashList: List<Long> get() = hashes?.split(",")?.mapNotNull { it.toLongOrNull() }.orEmpty()
    fun file(root: File) = File(File(root, scanDir), fileName)
}

@Entity(tableName = "person", indices = [Index(value = ["name"], unique = true)])
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** When they were born, as loosely as it's known ("1952", "spring 1952"): photos then show their age. */
    val bornFrom: Long? = null,
    val bornTo: Long? = null,
    val bornLabel: String? = null,
) {
    val born: PhotoDate? get() = photoDate(bornFrom, bornTo, bornLabel)
}

/**
 * A face found on a print: its box (0..1 of the print as shown), its fingerprint ([io.github.darkxace01.attic.vision.FaceGroups])
 * and who it is, once someone says. [ignored]: not a face, or nobody to name.
 */
@Entity(tableName = "face", indices = [Index("printId"), Index("personId")])
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val printId: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val vec: ByteArray,
    val personId: Long? = null,
    @ColumnInfo(defaultValue = "0") val ignored: Boolean = false,
) {
    override fun equals(other: Any?) = other is FaceEntity && other.id == id && other.personId == personId && other.ignored == ignored &&
        other.printId == printId && other.left == left && other.top == top
    override fun hashCode() = id.hashCode()
}

/**
 * How two people are family: [kind] is [PARENT] ([a] is [b]'s parent), [SPOUSE] or [SIBLING] (for siblings
 * whose parents aren't in Attic; those with parents in common are siblings anyway).
 */
@Entity(tableName = "relation", indices = [Index("a"), Index("b")])
data class RelationEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val a: Long, val b: Long, val kind: String) {
    companion object {
        const val PARENT = "parent"
        const val SPOUSE = "spouse"
        const val SIBLING = "sibling"
    }
}

/** A person with how many photos they're in and a face to show for them. */
data class PersonSummary(val id: Long, val name: String, val count: Int, val faceId: Long?, val bornFrom: Long?, val bornTo: Long?, val bornLabel: String?) {
    val born: PhotoDate? get() = photoDate(bornFrom, bornTo, bornLabel)
}

/** A spoken memory about a print: an audio file in the print's capture folder. */
@Entity(tableName = "story", indices = [Index("printId")])
data class StoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val printId: Long,
    /** The m4a file's name in the print's capture folder. */
    val file: String,
    val durationMs: Long,
    val createdAt: Long,
)

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
    val look: String? = null,
    /** Photos from the phone kept in it. */
    val phone: Int = 0,
)

/** One name on one print. */
data class PrintName(val printId: Long, val name: String)

data class LibraryCounts(val total: Int, val inGallery: Int, val backedUp: Int = 0, val onlyInAttic: Int = 0)

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
        SELECT a.id, a.title, a.dateLabel, a.look, COUNT(p.id) AS count,
            (SELECT COUNT(*) FROM album_phone WHERE albumId = a.id) AS phone, COUNT(DISTINCT p.page) AS pages,
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
    @Query("SELECT * FROM print ORDER BY createdAt") fun allPrintsFlow(): Flow<List<PrintEntity>>
    @Query("SELECT * FROM album") fun albumsFlow(): Flow<List<AlbumEntity>>
    @Query("SELECT place FROM print WHERE place IS NOT NULL GROUP BY place ORDER BY MAX(createdAt) DESC LIMIT 12") fun recentPlaces(): Flow<List<String>>
    @Query("SELECT COALESCE(MAX(page), 0) FROM print WHERE albumId = :albumId") fun lastPage(albumId: Long): Int
    @Query("SELECT DISTINCT scanDir FROM print") fun knownScanDirs(): List<String>
    @Query(
        """
        SELECT COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN galleryUri IS NOT NULL AND exportedVersion = version THEN 1 ELSE 0 END), 0) AS inGallery,
            COALESCE(SUM(CASE WHEN backedUpVersion = version THEN 1 ELSE 0 END), 0) AS backedUp,
            COALESCE(SUM(CASE WHEN (galleryUri IS NULL OR exportedVersion != version) AND backedUpVersion != version THEN 1 ELSE 0 END), 0) AS onlyInAttic
        FROM print
        """,
    )
    fun counts(): Flow<LibraryCounts>
    @Query("UPDATE print SET backedUpVersion = :version WHERE id = :id") fun markBackedUp(id: Long, version: Int)
    @Query("UPDATE print SET hashes = :hashes WHERE id = :id") fun setHashes(id: Long, hashes: String)
    @Query("SELECT * FROM print WHERE hashes IS NOT NULL") fun printsWithHashes(): List<PrintEntity>
    @Query("SELECT * FROM print WHERE hashes IS NULL") fun printsWithoutHashes(): List<PrintEntity>
    @Query("SELECT * FROM print WHERE stamp IS NULL") fun printsWithoutStamp(): List<PrintEntity>

    @Insert fun insertStory(story: StoryEntity): Long
    @Query("DELETE FROM story WHERE id = :id") fun deleteStory(id: Long)
    @Query("SELECT * FROM story WHERE printId = :printId ORDER BY createdAt") fun storiesOf(printId: Long): Flow<List<StoryEntity>>
    @Query("SELECT * FROM story WHERE printId = :printId ORDER BY createdAt") fun storiesOfNow(printId: Long): List<StoryEntity>
    @Query("SELECT DISTINCT printId FROM story") fun printsWithStories(): Flow<List<Long>>
    @Query("SELECT * FROM album WHERE title = :title AND createdAt = :createdAt") fun albumMatching(title: String, createdAt: Long): AlbumEntity?
    @Query("SELECT * FROM print WHERE scanDir = :scanDir AND fileName = :fileName") fun printAt(scanDir: String, fileName: String): PrintEntity?

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

    @Insert fun insertFace(face: FaceEntity): Long
    @Update fun updateFace(face: FaceEntity)
    @Query("DELETE FROM face WHERE printId = :printId") fun deleteFacesOf(printId: Long)
    @Query("SELECT * FROM face WHERE printId = :printId ORDER BY left") fun facesOf(printId: Long): Flow<List<FaceEntity>>
    @Query("SELECT * FROM face WHERE printId = :printId ORDER BY left") fun facesOfNow(printId: Long): List<FaceEntity>
    @Query("SELECT * FROM face WHERE id = :id") fun face(id: Long): FaceEntity?
    @Query("SELECT * FROM face WHERE ignored = 0") fun allFacesNow(): List<FaceEntity>
    @Query("SELECT * FROM face WHERE ignored = 0") fun allFaces(): Flow<List<FaceEntity>>
    @Query("SELECT * FROM print WHERE facesQuad IS NULL OR facesQuad != quad") fun printsWithoutFaces(): List<PrintEntity>
    @Query("UPDATE print SET negative = 1 WHERE id IN (:ids)") fun markNegative(ids: List<Long>)
    @Query("UPDATE print SET facesQuad = :quad WHERE id = :id") fun setFacesQuad(id: Long, quad: String)
    @Query("UPDATE face SET personId = NULL WHERE personId = :personId AND printId = :printId") fun unnameFacesOn(printId: Long, personId: Long)

    @Query("SELECT * FROM person WHERE id = :id") fun person(id: Long): PersonEntity?
    @Query("SELECT * FROM person WHERE id = :id") fun personFlow(id: Long): Flow<PersonEntity?>
    @Update fun updatePerson(person: PersonEntity)
    @Query("SELECT * FROM person") fun peopleNow(): List<PersonEntity>
    @Query(
        """
        SELECT p.id, p.name, p.bornFrom, p.bornTo, p.bornLabel,
            (SELECT COUNT(*) FROM print_person pp WHERE pp.personId = p.id) AS count,
            (SELECT f.id FROM face f WHERE f.personId = p.id ORDER BY (f.right - f.left) * (f.bottom - f.top) * f.score DESC LIMIT 1) AS faceId
        FROM person p ORDER BY count DESC, p.name
        """,
    )
    fun personSummaries(): Flow<List<PersonSummary>>
    @Query("SELECT pr.* FROM print pr JOIN print_person pp ON pp.printId = pr.id WHERE pp.personId = :personId ORDER BY pr.dateFrom IS NULL, pr.dateFrom, pr.createdAt")
    fun printsOf(personId: Long): Flow<List<PrintEntity>>
    @Query("SELECT personId FROM print_person WHERE printId = :printId") fun personIdsOn(printId: Long): List<Long>
    @Query("DELETE FROM print_person WHERE printId = :printId AND personId = :personId") fun untag(printId: Long, personId: Long)
    @Query("INSERT OR IGNORE INTO print_person (printId, personId) SELECT printId, :to FROM print_person WHERE personId = :from") fun copyTags(from: Long, to: Long)
    @Query("DELETE FROM print_person WHERE personId = :personId") fun untagPerson(personId: Long)
    @Query("DELETE FROM person WHERE id = :id") fun deletePersonRow(id: Long)
    @Transaction fun retag(from: Long, to: Long) { copyTags(from, to); untagPerson(from) }
    @Transaction fun deletePerson(id: Long) { untagPerson(id); deleteRelationsOf(id); deletePersonRow(id) }

    @Insert fun insertRelation(r: RelationEntity): Long
    @Query("DELETE FROM relation WHERE id = :id") fun deleteRelation(id: Long)
    @Query("DELETE FROM relation WHERE a = :person OR b = :person") fun deleteRelationsOf(person: Long)
    @Query("SELECT * FROM relation") fun relations(): Flow<List<RelationEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertPhone(items: List<AlbumPhoneEntity>)
    @Query("SELECT * FROM album_phone WHERE albumId = :albumId ORDER BY COALESCE(taken, addedAt / 86400000), id") fun phoneIn(albumId: Long): Flow<List<AlbumPhoneEntity>>
    @Query("SELECT * FROM album_phone WHERE albumId = :albumId ORDER BY COALESCE(taken, addedAt / 86400000), id") fun phoneInNow(albumId: Long): List<AlbumPhoneEntity>
    @Query("DELETE FROM album_phone WHERE id = :id") fun deletePhone(id: Long)
    @Query("SELECT * FROM album_phone WHERE id = :id") fun phoneByIdNow(id: Long): AlbumPhoneEntity?
    @Query("SELECT COUNT(*) FROM album_phone WHERE uri = :uri") fun phoneUriCount(uri: String): Int
    @Query("SELECT pp.printId AS printId, p.name AS name FROM print_person pp JOIN person p ON p.id = pp.personId ORDER BY p.name")
    fun printNames(): Flow<List<PrintName>>
    @Query("SELECT * FROM relation") fun relationsNow(): List<RelationEntity>
    @Query("UPDATE relation SET a = :to WHERE a = :from") fun moveRelationsA(from: Long, to: Long)
    @Query("UPDATE relation SET b = :to WHERE b = :from") fun moveRelationsB(from: Long, to: Long)

    @Transaction
    fun setPeople(printId: Long, names: List<String>) {
        untagAll(printId)
        for (name in names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }) {
            val id = personNamed(name)?.id ?: insertPerson(PersonEntity(name = name))
            tag(PrintPersonEntity(printId, id))
        }
        // A name taken off the photo comes off its face too.
        val kept = personIdsOn(printId).toSet()
        facesOfNow(printId).filter { it.personId != null && it.personId !in kept }.forEach { updateFace(it.copy(personId = null)) }
    }
}

@Database(
    entities = [AlbumEntity::class, PrintEntity::class, PersonEntity::class, PrintPersonEntity::class, StoryEntity::class, FaceEntity::class, RelationEntity::class, AlbumPhoneEntity::class],
    version = 14,
    exportSchema = true,
)
abstract class AtticDatabase : RoomDatabase() {
    abstract fun dao(): AtticDao

    companion object {
        fun open(context: Context): AtticDatabase =
            Room.databaseBuilder(context, AtticDatabase::class.java, "attic.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14).build()

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

        /** Backups to a folder: which version of each print the backup has. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `print` ADD COLUMN `backedUpVersion` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Fingerprints for finding prints scanned twice. Filled in for older prints on the next start. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `print` ADD COLUMN `hashes` TEXT")
            }
        }

        /** The backs of prints. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `print` ADD COLUMN `backFile` TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `print` ADD COLUMN `stamp` TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `story` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `printId` INTEGER NOT NULL, " +
                        "`file` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_printId` ON `story` (`printId`)")
            }
        }

        /** Faces, and when people were born. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `print` ADD COLUMN `facesQuad` TEXT")
                db.execSQL("ALTER TABLE `person` ADD COLUMN `bornFrom` INTEGER")
                db.execSQL("ALTER TABLE `person` ADD COLUMN `bornTo` INTEGER")
                db.execSQL("ALTER TABLE `person` ADD COLUMN `bornLabel` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `face` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `printId` INTEGER NOT NULL, " +
                        "`left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, `score` REAL NOT NULL, " +
                        "`vec` BLOB NOT NULL, `personId` INTEGER, `ignored` INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_printId` ON `face` (`printId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_personId` ON `face` (`personId`)")
            }
        }

        /** Trimmed borders. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `print` ADD COLUMN `borderQuad` TEXT")
            }
        }

        /** Film negatives. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `print` ADD COLUMN `negative` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Photos from the phone in albums. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_phone` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `albumId` INTEGER NOT NULL, " +
                        "`uri` TEXT NOT NULL, `taken` INTEGER, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_phone_albumId` ON `album_phone` (`albumId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_album_phone_albumId_uri` ON `album_phone` (`albumId`, `uri`)")
            }
        }

        /** Album islands. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `album` ADD COLUMN `look` TEXT")
            }
        }

        /** Made bigger. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `print` ADD COLUMN `upscaled` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Family. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `relation` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `a` INTEGER NOT NULL, " +
                        "`b` INTEGER NOT NULL, `kind` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_relation_a` ON `relation` (`a`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_relation_b` ON `relation` (`b`)")
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
