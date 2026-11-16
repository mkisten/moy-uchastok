package ru.obkhod.app.ui

import android.content.Context
import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.data.repo.AreaRepository
import ru.obkhod.app.data.repo.EntranceRepository

/** Простой контейнер зависимостей (без DI-фреймворков). */
object AppContainer {
    private var db: AppDatabase? = null
    private lateinit var appContext: Context
    lateinit var areaRepository: AreaRepository
        private set
    lateinit var entranceRepository: EntranceRepository
        private set

    fun init(context: Context) {
        if (db == null) {
            appContext = context.applicationContext
            db = AppDatabase.get(context.applicationContext)
            areaRepository = AreaRepository(db!!)
            entranceRepository = EntranceRepository(db!!)
        }
    }

    fun context(): Context = appContext
}


