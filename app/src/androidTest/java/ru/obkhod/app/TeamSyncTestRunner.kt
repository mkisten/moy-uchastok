package ru.obkhod.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.data.db.AreaEntity
import ru.obkhod.app.data.db.Status
import ru.obkhod.app.data.repo.AreaRepository
import ru.obkhod.app.data.repo.CreateAreaResult
import ru.obkhod.app.data.repo.EntranceDraft
import ru.obkhod.app.data.repo.EntranceRepository
import ru.obkhod.app.sync.TeamHttp
import ru.obkhod.app.sync.TeamSync
import ru.obkhod.app.sync.TeamTransport
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.UUID

/** Две отдельные базы Room общаются с локальным стендом, который исполняет настоящий Code.gs. */
class TeamSyncTestRunner : Instrumentation() {
    private var arguments = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking(Dispatchers.IO) {
                migrationScenario()
                scenario()
            }
            result.putString("stream", "\nPASS: миграция Room 6→7, две базы Room, журнал Apps Script, повторы, отмена, корзина и конкурентные правки\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "\nFAIL: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private suspend fun migrationScenario() {
        val name = "team-migration-${UUID.randomUUID()}.db"
        try {
            val initialDb = Room.databaseBuilder(targetContext, AppDatabase::class.java, name).build()
            try {
                initialDb.areaDao().insert(AreaEntity(uuid = "migration-test", name = "Старый участок", createdAt = 1, updatedAt = 1))
            } finally {
                initialDb.close()
            }
            SQLiteDatabase.openDatabase(targetContext.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE team_sync")
                it.version = 6
            }
            val migratedDb = Room.databaseBuilder(targetContext, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_6_7).build()
            try {
                check(migratedDb.areaDao().getAll().single().uuid == "migration-test")
                check(migratedDb.teamSyncDao().get() == null)
            } finally {
                migratedDb.close()
            }
        } finally {
            targetContext.deleteDatabase(name)
        }
    }

    private suspend fun scenario() {
        val endpoint = arguments.getString("endpoint") ?: "http://127.0.0.1:18766"
        val fixtures = JSONObject(URL("$endpoint/fixtures").readText())
        val ownerInvite = fixtures.getString("owner")
        val memberInvite = fixtures.getString("member")
        val deployment = "https://script.google.com/macros/s/local-test/exec"
        val firstDb = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java).build()
        val secondDb = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java).build()
        val temp = File(targetContext.cacheDir, "team-test-${UUID.randomUUID()}")
        var dropPushResponse = false
        var editDuringPull: (suspend () -> Unit)? = null
        val wire = TeamTransport { link, request ->
            val response = TeamHttp.request(link.copy(endpoint = "$endpoint/exec"), request)
            when (request.getString("action")) {
                "push" -> if (dropPushResponse) {
                    dropPushResponse = false
                    throw IOException("Имитация потери ответа после записи на сервере")
                }
                "pull" -> editDuringPull?.let { edit ->
                    editDuringPull = null
                    runBlocking { edit() }
                }
            }
            response
        }
        fun client(db: AppDatabase, name: String): TeamSync {
            val device = object : ContextWrapper(targetContext) {
                override fun getNoBackupFilesDir(): File = File(temp, name).apply { mkdirs() }
            }
            return TeamSync(device, db, wire)
        }
        try {
            val first = client(firstDb, "first")
            val second = client(secondDb, "second")
            val firstRepo = EntranceRepository(firstDb)
            val secondRepo = EntranceRepository(secondDb)
            val source = AreaRepository(firstDb).createArea(
                "Тестовая улица 40",
                listOf(EntranceDraft(1, 1, 25, mutableListOf(2))),
            )
            check(source is CreateAreaResult.Success)
            val firstApartments = firstDb.apartmentDao().getAllActive().sortedBy { it.apartmentNumber }
            val first25 = firstApartments[0]
            val first25a = firstApartments[1]
            check(firstRepo.editApartment(first25a.id, null, "25А", false) == null)
            // Вторая база имела собственные ID. Идентичность идёт по UUID.
            val abandoned = secondDb.areaDao().insert(AreaEntity(uuid = "abandoned-test", name = "tmp", createdAt = 1, updatedAt = 1))
            secondDb.areaDao().delete(abandoned)

            first.connect(deployment, ownerInvite, seed = true)
            second.connect(deployment, memberInvite, seed = false)
            check(secondDb.areaDao().getAll().single().id != source.areaId)
            val second25 = secondDb.apartmentDao().getAllActive().single { it.uuid == first25.uuid }
            val second25a = secondDb.apartmentDao().getAllActive().single { it.uuid == first25a.uuid }
            check(second25a.label == "25А")

            val undo = checkNotNull(firstRepo.setStatus(first25.id, Status.NO_ONE_HOME))
            checkNotNull(secondRepo.setStatus(second25a.id, Status.REPEAT))
            check(first.pendingCount() == 2 && second.pendingCount() == 2)
            dropPushResponse = true
            val failure = runCatching { first.sync() }.exceptionOrNull()
            check(failure is IOException)
            check(first.pendingCount() == 2)
            second.sync()
            first.sync()
            second.sync()
            check(firstDb.visitHistoryDao().getAll().size == 2 && secondDb.visitHistoryDao().getAll().size == 2)

            secondRepo.saveNote(second25.id, null, "Заметка второго устройства")
            second.sync()
            check(firstRepo.undoVisit(undo))
            first.sync()
            second.sync()
            val restoredStatus = checkNotNull(secondDb.apartmentDao().getById(second25.id))
            check(restoredStatus.status == Status.NONE.name && restoredStatus.lastVisitAt == null)
            check(restoredStatus.comment == "Заметка второго устройства")
            check(secondDb.visitHistoryDao().getAll().single().apartmentId == second25a.id)

            val earlier = checkNotNull(firstRepo.setStatus(first25.id, Status.NO_ONE_HOME))
            first.sync()
            second.sync()
            checkNotNull(secondRepo.setStatus(second25.id, Status.REFUSED))
            check(firstRepo.undoVisit(earlier))
            second.sync()
            first.sync()
            second.sync()
            check(firstDb.apartmentDao().getById(first25.id)?.status == Status.REFUSED.name)
            check(secondDb.apartmentDao().getById(second25.id)?.status == Status.REFUSED.name)
            check(secondDb.visitHistoryDao().getAll().none { it.uuid == earlier.record.uuid })

            firstRepo.deleteApartment(first25a.id)
            first.sync()
            second.sync()
            check(secondDb.apartmentDao().getById(second25a.id)?.deletedAt != null)
            check(secondRepo.restoreApartment(second25a.id))
            second.sync()
            first.sync()
            check(firstDb.apartmentDao().getAllActive().map { it.label ?: it.apartmentNumber.toString() }.toSet() == setOf("25", "25А"))

            editDuringPull = { firstRepo.saveNote(first25a.id, null, "Правка во время синхронизации") }
            check(first.sync().pending == 1)
            check(first.pendingCount() == 1)
            first.sync()
            second.sync()
            check(secondDb.apartmentDao().getById(second25a.id)?.comment == "Правка во время синхронизации")
            check(first.pendingCount() == 0 && second.pendingCount() == 0)
        } finally {
            firstDb.close()
            secondDb.close()
            temp.deleteRecursively()
        }
    }
}
