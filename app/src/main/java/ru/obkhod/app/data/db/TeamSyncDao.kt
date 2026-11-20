package ru.obkhod.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "team_sync")
data class TeamSyncCheckpoint(
    @PrimaryKey val id: Int = 1,
    val connectionId: String,
    val cursor: Long = 0,
    val baseline: String = "{}",
    val pending: String? = null,
    val captured: String? = null,
)

@Dao
interface TeamSyncDao {
    @Query("SELECT * FROM team_sync WHERE id = 1")
    suspend fun get(): TeamSyncCheckpoint?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: TeamSyncCheckpoint)

    @Query("DELETE FROM team_sync")
    suspend fun clear()
}
