package ru.obkhod.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class DeletedApartmentDetails(
    val id: Long,
    val label: String,
    val status: String,
    val deletedAt: Long,
    val areaName: String,
    val areaType: String,
    val entranceNumber: Int,
    val floorNumber: Int?,
    val numberTaken: Boolean,
)

// Сравниваем отображаемые номера на исходном этаже, как при редактировании квартиры.
private const val DELETED_DETAILS = """
    SELECT d.id, COALESCE(d.label, CAST(d.apartmentNumber AS TEXT)) AS label,
           d.status, d.deletedAt, a.name AS areaName, a.type AS areaType,
           e.entranceNumber, f.floorNumber,
           EXISTS (
               SELECT 1 FROM apartments AS active
               WHERE active.floorId = d.floorId AND active.entranceId = d.entranceId
                 AND active.deletedAt IS NULL
                 AND COALESCE(active.label, CAST(active.apartmentNumber AS TEXT))
                     = COALESCE(d.label, CAST(d.apartmentNumber AS TEXT))
           ) AS numberTaken
    FROM apartments AS d
    JOIN entrances AS e ON e.id = d.entranceId
    JOIN areas AS a ON a.id = e.areaId
    LEFT JOIN floors AS f ON f.id = d.floorId AND f.entranceId = d.entranceId
    WHERE d.deletedAt IS NOT NULL
"""

@Dao
interface AreaDao {
    @Query("SELECT * FROM areas ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AreaEntity>>

    @Query("SELECT * FROM areas WHERE id = :id")
    fun observeById(id: Long): Flow<AreaEntity?>

    @Query("SELECT * FROM areas WHERE id = :id")
    suspend fun getById(id: Long): AreaEntity?

    @Query("SELECT * FROM areas")
    suspend fun getAll(): List<AreaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(area: AreaEntity): Long

    @Update
    suspend fun update(area: AreaEntity)

    @Query("DELETE FROM areas WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM areas")
    suspend fun deleteEverything()

    @Query("DELETE FROM areas")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(areas: List<AreaEntity>)
}

@Dao
interface EntranceDao {
    @Query("SELECT * FROM entrances WHERE areaId = :areaId ORDER BY entranceNumber")
    fun observeByArea(areaId: Long): Flow<List<EntranceEntity>>

    @Query("SELECT * FROM entrances WHERE id = :id")
    fun observeByEntrance(id: Long): Flow<EntranceEntity?>

    @Query("SELECT * FROM entrances WHERE areaId = :areaId ORDER BY entranceNumber")
    suspend fun getByArea(areaId: Long): List<EntranceEntity>

    @Query("SELECT * FROM entrances ORDER BY entranceNumber")
    fun observeAll(): Flow<List<EntranceEntity>>

    @Query("SELECT * FROM entrances")
    suspend fun getAll(): List<EntranceEntity>

    @Query("SELECT * FROM entrances WHERE id = :id")
    suspend fun getById(id: Long): EntranceEntity?

    @Query("SELECT COUNT(*) FROM entrances WHERE areaId = :areaId AND entranceNumber = :number")
    suspend fun countByNumber(areaId: Long, number: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entrance: EntranceEntity): Long

    @Update
    suspend fun update(entrance: EntranceEntity)

    @Query("DELETE FROM entrances WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM entrances")
    suspend fun deleteEverything()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entrances: List<EntranceEntity>)
}

@Dao
interface FloorDao {
    @Query("SELECT * FROM floors WHERE entranceId = :entranceId ORDER BY floorNumber")
    fun observeByEntrance(entranceId: Long): Flow<List<FloorEntity>>

    @Query("SELECT * FROM floors WHERE entranceId = :entranceId ORDER BY floorNumber")
    suspend fun getByEntrance(entranceId: Long): List<FloorEntity>

    @Query("SELECT * FROM floors ORDER BY floorNumber")
    fun observeAll(): Flow<List<FloorEntity>>

    @Query("SELECT * FROM floors")
    suspend fun getAll(): List<FloorEntity>

    @Query("SELECT * FROM floors WHERE id = :id")
    suspend fun getById(id: Long): FloorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(floor: FloorEntity): Long

    @Update
    suspend fun update(floor: FloorEntity)

    @Query("DELETE FROM floors WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM floors")
    suspend fun deleteEverything()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(floors: List<FloorEntity>)
}

@Dao
interface ApartmentDao {
    @Query("SELECT * FROM apartments WHERE floorId = :floorId AND deletedAt IS NULL ORDER BY apartmentNumber")
    fun observeByFloor(floorId: Long): Flow<List<ApartmentEntity>>

    @Query("SELECT * FROM apartments WHERE entranceId = :entranceId AND deletedAt IS NULL ORDER BY apartmentNumber")
    fun observeByEntrance(entranceId: Long): Flow<List<ApartmentEntity>>

    @Query("SELECT * FROM apartments WHERE entranceId = :entranceId AND deletedAt IS NULL ORDER BY apartmentNumber")
    suspend fun observeByEntranceOnce(entranceId: Long): List<ApartmentEntity>

    @Query("SELECT * FROM apartments WHERE id = :id")
    fun observeById(id: Long): Flow<ApartmentEntity?>

    @Query("SELECT * FROM apartments WHERE id = :id")
    suspend fun getById(id: Long): ApartmentEntity?

    @Query("SELECT * FROM apartments WHERE deletedAt IS NULL")
    suspend fun getAllActive(): List<ApartmentEntity>

    @Query("SELECT * FROM apartments WHERE deletedAt IS NULL")
    fun observeAllActive(): Flow<List<ApartmentEntity>>

    @Query("SELECT * FROM apartments")
    suspend fun getAll(): List<ApartmentEntity>

    @Query(DELETED_DETAILS + " ORDER BY d.deletedAt DESC, d.id DESC")
    fun observeDeletedDetails(): Flow<List<DeletedApartmentDetails>>

    @Query(DELETED_DETAILS + " AND d.id = :id")
    suspend fun getDeletedDetails(id: Long): DeletedApartmentDetails?

    @Query("SELECT * FROM apartments WHERE deletedAt IS NOT NULL")
    suspend fun getDeleted(): List<ApartmentEntity>

    @Query(
        "SELECT apartments.* FROM apartments INNER JOIN entrances ON apartments.entranceId = entrances.id " +
        "WHERE entrances.areaId = :areaId AND apartments.deletedAt IS NULL ORDER BY apartments.apartmentNumber"
    )
    fun observeByArea(areaId: Long): Flow<List<ApartmentEntity>>

    @Query("SELECT COUNT(*) FROM apartments WHERE entranceId = :entranceId AND apartmentNumber = :number AND deletedAt IS NULL")
    suspend fun countActiveByNumber(entranceId: Long, number: Int): Int

    @Query("SELECT COUNT(*) FROM apartments WHERE floorId = :floorId AND deletedAt IS NULL")
    suspend fun countActiveOnFloor(floorId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(apartment: ApartmentEntity): Long

    @Update
    suspend fun update(apartment: ApartmentEntity)

    @Query("UPDATE apartments SET phone = :phone, comment = :comment, updatedAt = :updatedAt WHERE id = :id AND deletedAt IS NULL")
    suspend fun updateNote(id: Long, phone: String?, comment: String?, updatedAt: Long): Int

    @Query("UPDATE apartments SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long, updatedAt: Long)

    @Query("UPDATE apartments SET deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restore(id: Long, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apartments: List<ApartmentEntity>)

    @Query("DELETE FROM apartments")
    suspend fun deleteEverything()
}

@Dao
interface VisitHistoryDao {
    @Query("SELECT * FROM visit_history WHERE apartmentId = :apartmentId ORDER BY visitDate DESC, id DESC")
    fun observeByApartment(apartmentId: Long): Flow<List<VisitHistoryEntity>>

    @Query("SELECT * FROM visit_history WHERE apartmentId = :apartmentId ORDER BY id DESC LIMIT 1")
    suspend fun latestRecordedVisit(apartmentId: Long): VisitHistoryEntity?

    @Query("DELETE FROM visit_history WHERE id = :id AND uuid = :uuid")
    suspend fun deleteVisit(id: Long, uuid: String)

    @Query("SELECT * FROM visit_history")
    suspend fun getAll(): List<VisitHistoryEntity>

    @Query("SELECT DISTINCT apartmentId FROM visit_history")
    suspend fun apartmentIdsWithVisits(): List<Long>

    @Query("SELECT DISTINCT apartmentId FROM visit_history")
    fun apartmentIdsWithVisitsFlow(): Flow<List<Long>>

    @Insert
    suspend fun insert(record: VisitHistoryEntity): Long

    @Insert
    suspend fun insertAll(records: List<VisitHistoryEntity>)

    @Transaction
    @Query("SELECT EXISTS(SELECT 1 FROM visit_history WHERE apartmentId = :apartmentId)")
    suspend fun hasVisits(apartmentId: Long): Boolean

    @Query("DELETE FROM visit_history")
    suspend fun deleteEverything()

}
