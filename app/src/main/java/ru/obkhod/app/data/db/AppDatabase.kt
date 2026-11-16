package ru.obkhod.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AreaEntity::class, EntranceEntity::class, FloorEntity::class,
        ApartmentEntity::class, VisitHistoryEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun areaDao(): AreaDao
    abstract fun entranceDao(): EntranceDao
    abstract fun floorDao(): FloorDao
    abstract fun apartmentDao(): ApartmentDao
    abstract fun visitHistoryDao(): VisitHistoryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v1 → v2: добавлено поле label (нестандартные номера квартир вида «1А»). */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apartments ADD COLUMN label TEXT DEFAULT NULL")
            }
        }

        /** v2 → v3: добавлен тип участка (частный сектор / многоквартирный дом). */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE areas ADD COLUMN type TEXT NOT NULL DEFAULT 'MULTI'")
            }
        }

        /** v3 → v4: UUID всем строкам (синхронизация через Google Таблицу), updated_at подъездам/этажам. */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE areas ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entrances ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE floors ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE apartments ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE visit_history ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entrances ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE floors ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                // Заполняем UUID у существующих строк
                db.execSQL("UPDATE areas SET uuid = lower(hex(randomblob(16))) WHERE uuid = ''")
                db.execSQL("UPDATE entrances SET uuid = lower(hex(randomblob(16))) WHERE uuid = ''")
                db.execSQL("UPDATE floors SET uuid = lower(hex(randomblob(16))) WHERE uuid = ''")
                db.execSQL("UPDATE apartments SET uuid = lower(hex(randomblob(16))) WHERE uuid = ''")
                db.execSQL("UPDATE visit_history SET uuid = lower(hex(randomblob(16))) WHERE uuid = ''")
                db.execSQL("UPDATE entrances SET updatedAt = createdAt WHERE updatedAt = 0")
            }
        }

        /** v4 → v5: отметка «есть ребёнок» у квартиры. */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apartments ADD COLUMN hasChild INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "obkhod.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }
    }
}
