package dev.mealprep.app.data.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** One saved server reply per key ("week:2026-10-11", "card:21", …) — the offline copy. */
@Entity(tableName = "cached_doc")
data class CachedDoc(@PrimaryKey val key: String, val json: String, val fetchedAt: Long)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cached_doc WHERE `key` = :key") suspend fun get(key: String): CachedDoc?
    @Upsert suspend fun put(doc: CachedDoc)
    @Query("DELETE FROM cached_doc WHERE `key` = :key") suspend fun delete(key: String)
    @Query("DELETE FROM cached_doc WHERE fetchedAt < :before AND `key` NOT LIKE 'once:%'") suspend fun prune(before: Long)
}

@Database(entities = [CachedDoc::class], version = 1, exportSchema = false)
abstract class CacheDb : RoomDatabase() {
    abstract fun cache(): CacheDao
}
