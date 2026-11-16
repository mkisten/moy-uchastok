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
    version = 3,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "obkhod.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
