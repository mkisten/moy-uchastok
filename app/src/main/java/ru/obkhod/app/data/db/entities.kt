package ru.obkhod.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "areas")
data class AreaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Стабильный идентификатор для синхронизации между устройствами. */
    val uuid: String = "",
    val name: String,
    /** Тип участка: MULTI — многоквартирный дом, PRIVATE — частный сектор. */
    val type: String = AreaType.MULTI.name,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class AreaType { MULTI, PRIVATE }

@Entity(
    tableName = "entrances",
    foreignKeys = [ForeignKey(
        entity = AreaEntity::class, parentColumns = ["id"], childColumns = ["areaId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("areaId")]
)
data class EntranceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = "",
    val areaId: Long,
    val entranceNumber: Int,
    val createdAt: Long,
    val updatedAt: Long = 0,
)

@Entity(
    tableName = "floors",
    foreignKeys = [ForeignKey(
        entity = EntranceEntity::class, parentColumns = ["id"], childColumns = ["entranceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("entranceId")]
)
data class FloorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = "",
    val entranceId: Long,
    val floorNumber: Int,
    val apartmentCount: Int,
    val sortOrder: Int,
    val updatedAt: Long = 0,
)

@Entity(
    tableName = "apartments",
    foreignKeys = [ForeignKey(
        entity = EntranceEntity::class, parentColumns = ["id"], childColumns = ["entranceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("entranceId"), Index("floorId"),
        Index("status"), Index("apartmentNumber"), Index("deletedAt"),
        Index(value = ["entranceId", "apartmentNumber", "deletedAt"]),
    ]
)
data class ApartmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = "",
    val entranceId: Long,
    val floorId: Long,
    val apartmentNumber: Int,
    /** Отображаемый номер, если он не числовой (например, «1А»); null — показывается apartmentNumber. */
    val label: String? = null,
    val status: String = Status.NONE.name,
    val phone: String? = null,
    val comment: String? = null,
    val lastVisitAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "visit_history",
    foreignKeys = [ForeignKey(
        entity = ApartmentEntity::class, parentColumns = ["id"], childColumns = ["apartmentId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("apartmentId"), Index("visitDate")]
)
data class VisitHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = "",
    val apartmentId: Long,
    val visitDate: Long,
    val previousStatus: String?,
    val newStatus: String?,
    val comment: String? = null,
    val eventType: String,
)

/** Статусы квартиры (п. 7 ТЗ). */
enum class Status { NONE, REFUSED, NO_ONE_HOME, REPEAT, NON_RESIDENTIAL }

/** Типы событий истории посещений (п. 14 ТЗ). */
enum class EventType { VISIT, RESET, CORRECTION }
