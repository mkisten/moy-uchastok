package ru.obkhod.app.sync

import android.content.Context
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import ru.obkhod.app.backup.BackupManager
import ru.obkhod.app.data.db.AppDatabase
import ru.obkhod.app.data.db.TeamSyncCheckpoint
import ru.obkhod.app.data.repo.withTransactionCompat
import java.io.IOException

internal class TeamSync(
    private val context: Context,
    private val db: AppDatabase,
    private val transport: TeamTransport = TeamHttp,
) {
    private val backup = BackupManager(context, db)
    private val store = TeamConnectionStore(context)
    private val dao = db.teamSyncDao()

    data class Result(val sent: Int, val received: Int, val pending: Int)

    fun connection(): TeamConnection? = store.load()

    suspend fun pendingCount(): Int = db.withTransactionCompat {
        val link = store.load() ?: return@withTransactionCompat 0
        val saved = dao.get()?.takeIf { it.connectionId == link.id } ?: return@withTransactionCompat 0
        val base = TeamDocument.read(JSONObject(saved.baseline))
        val current = TeamDocument.capture(backup.buildSnapshot())
        if (saved.pending == null) current.changesFrom(base).length()
        else JSONArray(saved.pending).length() + current.changesFrom(TeamDocument.read(JSONObject(checkNotNull(saved.captured)))).length()
    }

    suspend fun connect(endpoint: String, invite: String, seed: Boolean): Result = BackupManager.cloudSyncAndRestore.withLock {
        check(store.load() == null) { "Сначала завершите или отмените подключение" }
        val yandexPassword = context.getSharedPreferences("yandex_sync", Context.MODE_PRIVATE).contains("login")
        val yandexOAuth = context.getSharedPreferences("yandex_oauth", Context.MODE_PRIVATE).contains("access_token")
        check(!yandexPassword && !yandexOAuth) { "Перед подключением команды отключите Яндекс Диск в настройках" }
        val link = TeamConnectionStore.create(endpoint, invite, seed)
        checkLocalData(seed)
        store.save(link)
        syncLocked(link)
    }

    suspend fun sync(): Result = BackupManager.cloudSyncAndRestore.withLock {
        syncLocked(store.load() ?: throw IOException("Сначала подключитесь к команде"))
    }

    suspend fun disconnect() = BackupManager.cloudSyncAndRestore.withLock {
        store.clear()
        dao.clear()
    }

    private suspend fun checkLocalData(seed: Boolean) {
        if (seed) return
        val snapshot = backup.buildSnapshot()
        if (TeamDocument.tables.any { snapshot.getJSONArray(it).length() > 0 }) {
            throw IOException("Для присоединения нужна пустая база. Сначала сохраните локальную резервную копию.")
        }
    }

    private suspend fun syncLocked(link: TeamConnection): Result {
        if (link.invite != null) {
            checkLocalData(link.seed)
            transport.request(link, JSONObject().put("action", "join").put("invite", link.invite).put("seed", link.seed))
            db.withTransactionCompat {
                if (dao.get()?.connectionId != link.id) dao.save(TeamSyncCheckpoint(connectionId = link.id))
            }
            store.save(link.copy(invite = null))
        }
        val saved = db.withTransactionCompat {
            val checkpoint = dao.get()?.takeIf { it.connectionId == link.id }
                ?: throw IOException("Контрольная точка команды недоступна. Отключитесь и подключитесь заново.")
            if (checkpoint.pending != null) return@withTransactionCompat checkpoint
            normalizeUuids()
            val captured = TeamDocument.capture(backup.buildSnapshot())
            val changes = captured.changesFrom(TeamDocument.read(JSONObject(checkpoint.baseline)))
            checkpoint.copy(pending = changes.toString(), captured = captured.encode().toString()).also { dao.save(it) }
        }

        val outgoing = JSONArray(checkNotNull(saved.pending)).objects()
        for (batch in outgoing.chunked(50)) {
            transport.request(link, JSONObject().put("action", "push").put("operations", JSONArray(batch)))
        }
        if (link.seed) transport.request(link, JSONObject().put("action", "finishSeed"))
        val incoming = JSONArray()
        var cursor = saved.cursor
        var until: Long? = null
        do {
            val request = JSONObject().put("action", "pull").put("after", cursor)
            until?.let { request.put("until", it) }
            val response = transport.request(link, request)
            val next = response.getLong("cursor")
            val upper = response.getLong("until")
            val operations = response.getJSONArray("operations")
            if (next < cursor || upper < next || (until != null && upper != until) ||
                next - cursor != operations.length().toLong() || (next == cursor && next < upper)
            ) throw IOException("Журнал на сервере изменён или ответ неполный")
            operations.objects().forEach { incoming.put(it) }
            cursor = next
            until = upper
        } while (cursor < checkNotNull(until))

        return db.withTransactionCompat {
            normalizeUuids()
            val local = backup.buildSnapshot()
            val current = TeamDocument.capture(local)
            val captured = TeamDocument.read(JSONObject(checkNotNull(saved.captured)))
            val remote = TeamDocument.read(JSONObject(saved.baseline)).apply(incoming)
            val combined = remote.apply(current.changesFrom(captured))
            if (combined.changesFrom(current).length() > 0) {
                val sequences = db.openHelper.writableDatabase.query("SELECT name, seq FROM sqlite_sequence").use { rows ->
                    buildMap {
                        while (rows.moveToNext()) {
                            val table = when (val name = rows.getString(0)) { "visit_history" -> "history"; else -> name }
                            put(table, rows.getLong(1))
                        }
                    }
                }
                backup.replaceData(backup.prepareJson(combined.toBackup(local, sequences)))
            }
            dao.save(saved.copy(cursor = cursor, baseline = remote.encode().toString(), pending = null, captured = null))
            Result(outgoing.size, incoming.length(), combined.changesFrom(remote).length())
        }
    }

    /** Старые версии иногда восстанавливали записи без UUID. */
    private fun normalizeUuids() {
        for (table in listOf("areas", "entrances", "floors", "apartments", "visit_history")) {
            db.openHelper.writableDatabase.execSQL("UPDATE $table SET uuid = lower(hex(randomblob(16))) WHERE trim(uuid) = ''")
        }
    }
}
