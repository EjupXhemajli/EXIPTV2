package de.exiptv.hd.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProviderEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        EpgEntry::class,
        FavoriteEntity::class,
        HistoryEntity::class,
        HiddenCategoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DbConverters::class)
abstract class ExIptvDatabase : RoomDatabase() {

    abstract fun contentDao(): ContentDao
    abstract fun providerDao(): ProviderDao
    abstract fun epgDao(): EpgDao
    abstract fun userDataDao(): UserDataDao

    companion object {
        const val NAME = "exiptv.db"

        fun build(context: Context): ExIptvDatabase =
            Room.databaseBuilder(context.applicationContext, ExIptvDatabase::class.java, NAME)
                // Write-Ahead-Logging: Leser blockieren den Schreiber nicht und
                // umgekehrt. Für eine App, die im Hintergrund importiert, während
                // vorne gescrollt wird, ist das die entscheidende Einstellung.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Eine ältere App-Version über eine neuere zu installieren, darf
                // nicht in einem Absturz beim Öffnen enden.
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // Kompromiss zwischen Sicherheit und Schreibtempo: Im
                        // WAL-Modus geht bei einem Absturz höchstens die letzte
                        // Transaktion verloren, dafür entfällt ein fsync pro Stapel.
                        db.query("PRAGMA synchronous = NORMAL").close()
                        // Der Katalog-Import erzeugt kurzfristig viele temporäre
                        // B-Baum-Seiten; im Arbeitsspeicher ist das deutlich schneller.
                        db.query("PRAGMA temp_store = MEMORY").close()
                    }
                })
                .build()
    }
}
