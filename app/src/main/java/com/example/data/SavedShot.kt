package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_shots")
data class SavedShot(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val notes: String,
    val cueX: Float,
    val cueY: Float,
    val targetX: Float,
    val targetY: Float,
    val pocketId: Int, // 0 to 5
    val englishSpinX: Float, // -1 (left) to 1 (right)
    val englishSpinY: Float, // -1 (draw) to 1 (follow)
    val shotSpeed: Float, // 1 to 10
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface SavedShotDao {
    @Query("SELECT * FROM saved_shots ORDER BY timestamp DESC")
    fun getAllShots(): Flow<List<SavedShot>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShot(shot: SavedShot)

    @Delete
    suspend fun deleteShot(shot: SavedShot)

    @Query("DELETE FROM saved_shots WHERE id = :id")
    suspend fun deleteShotById(id: Int)
}

@Database(entities = [SavedShot::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun savedShotDao(): SavedShotDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cuepath_database"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class ShotRepository(private val dao: SavedShotDao) {
    val allShots: Flow<List<SavedShot>> = dao.getAllShots()

    suspend fun insert(shot: SavedShot) = dao.insertShot(shot)

    suspend fun delete(shot: SavedShot) = dao.deleteShot(shot)

    suspend fun deleteById(id: Int) = dao.deleteShotById(id)
}
