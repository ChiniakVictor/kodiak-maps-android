package com.victor.kodiakmaps.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Local pin (only on this phone). */
@Entity(tableName = "pins")
data class PinEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val color: Int,
    val note: String = "",
    val lat: Double,
    val lon: Double,
    val alt: Int? = null,
    val created: Long,
    val updated: Long,
    val expires: Long = 0,    // ms epoch, 0 = never (navigation targets made from Kodiak features expire in 24 h)
)

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val started: Long,
    val ended: Long? = null,
)

@Entity(tableName = "track_points", indices = [Index("trackId", "time")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val acc: Float? = null,
)

@Dao
interface PinDao {
    @Query("SELECT * FROM pins WHERE expires = 0 OR expires > :now ORDER BY created DESC") fun visible(now: Long): Flow<List<PinEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(p: PinEntity)
    @Query("DELETE FROM pins WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY started DESC") fun tracks(): Flow<List<TrackEntity>>
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun track(id: Long): TrackEntity?
    @Insert suspend fun newTrack(t: TrackEntity): Long
    @Update suspend fun update(t: TrackEntity)
    @Query("DELETE FROM tracks WHERE id = :id") suspend fun deleteTrack(id: Long)
    @Query("DELETE FROM track_points WHERE trackId = :id") suspend fun deletePoints(id: Long)
    @Insert suspend fun add(p: TrackPointEntity)
    @Query("SELECT * FROM track_points WHERE trackId = :id ORDER BY time") suspend fun points(id: Long): List<TrackPointEntity>
    @Query("SELECT * FROM track_points WHERE trackId IN (:ids) ORDER BY trackId, time") fun pointsFor(ids: List<Long>): Flow<List<TrackPointEntity>>
    @Query("SELECT * FROM track_points WHERE trackId = :id ORDER BY time DESC LIMIT 1") suspend fun lastPoint(id: Long): TrackPointEntity?
}

@Database(entities = [PinEntity::class, TrackEntity::class, TrackPointEntity::class], version = 1, exportSchema = false)
abstract class MapDb : RoomDatabase() {
    abstract fun pins(): PinDao
    abstract fun tracks(): TrackDao
    companion object {
        @Volatile private var inst: MapDb? = null
        fun get(ctx: Context): MapDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, MapDb::class.java, "kodiakmaps.db").build().also { inst = it }
        }
    }
}
