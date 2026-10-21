package com.altivane.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    var endTime: Long? = null,
    var sportType: String = "RIDE",
    var distanceM: Double = 0.0,
    var movingS: Int = 0,
    var avgKmh: Double = 0.0,
    var maxKmh: Double = 0.0,
    var elevGainM: Double = 0.0,
    var maxAltM: Double = 0.0,
    var kcal: Double = 0.0,
    var pointCount: Int = 0,
    var waypointCount: Int = 0
)

@Entity(tableName = "points", primaryKeys = ["rideId", "t"])
data class PointEntity(
    val rideId: Long,
    val t: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val speed: Float = 0f,
    val hr: Int = -1,
    val cad: Int = -1
)

@Entity(tableName = "waypoints", primaryKeys = ["rideId", "t"])
data class WaypointEntity(
    val rideId: Long,
    val t: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val kind: String,
    val label: String,
    val photo: String? = null
)

@Dao
interface RideDao {
    @Insert
    fun insertRide(r: RideEntity): Long

    @Update
    fun updateRide(r: RideEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPoints(pts: List<PointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertWaypoints(wps: List<WaypointEntity>)

    @Query("SELECT * FROM rides ORDER BY startTime DESC")
    fun rides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM points WHERE rideId = :rideId ORDER BY t")
    suspend fun points(rideId: Long): List<PointEntity>

    @Query("SELECT * FROM waypoints WHERE rideId = :rideId ORDER BY t")
    suspend fun waypoints(rideId: Long): List<WaypointEntity>

    @Query("SELECT * FROM rides WHERE id = :rideId")
    suspend fun ride(rideId: Long): RideEntity?

    /** 各运动类型最近一次有效记录（冷启动恢复"上次运动"快照用） */
    @Query("SELECT * FROM rides WHERE sportType = :sport AND pointCount > 0 ORDER BY startTime DESC LIMIT 1")
    suspend fun lastRideOfSport(sport: String): RideEntity?

    /** 每次记录的轨迹经纬度范围（成就判定：是否到过某山/某地） */
    @Query(
        "SELECT rideId, MIN(lat) AS minLat, MAX(lat) AS maxLat, " +
                "MIN(lon) AS minLon, MAX(lon) AS maxLon FROM points GROUP BY rideId"
    )
    suspend fun rideBounds(): List<RideBound>

    @Query("DELETE FROM points WHERE rideId = :rideId")
    suspend fun deletePoints(rideId: Long)

    @Query("DELETE FROM waypoints WHERE rideId = :rideId")
    suspend fun deleteWaypoints(rideId: Long)

    @Query("DELETE FROM rides WHERE id = :rideId")
    suspend fun deleteRide(rideId: Long)
}

/** 轨迹经纬度包围盒（成就徽章判定用） */
data class RideBound(
    val rideId: Long,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

@Database(entities = [RideEntity::class, PointEntity::class, WaypointEntity::class], version = 3, exportSchema = false)
abstract class RideDb : RoomDatabase() {
    abstract fun dao(): RideDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN sportType TEXT NOT NULL DEFAULT 'RIDE'")
                db.execSQL("ALTER TABLE rides ADD COLUMN kcal REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rides ADD COLUMN waypointCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE points ADD COLUMN speed REAL NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS waypoints (" +
                            "rideId INTEGER NOT NULL, t INTEGER NOT NULL, lat REAL NOT NULL, " +
                            "lon REAL NOT NULL, ele REAL NOT NULL, kind TEXT NOT NULL, " +
                            "label TEXT NOT NULL, PRIMARY KEY(rideId, t))"
                )
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE waypoints ADD COLUMN photo TEXT")
            }
        }
    }
}
