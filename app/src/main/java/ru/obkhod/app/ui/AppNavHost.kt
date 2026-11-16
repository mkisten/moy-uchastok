package ru.obkhod.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import ru.obkhod.app.ui.area.AreaScreen
import ru.obkhod.app.ui.area.AreaViewModel
import ru.obkhod.app.ui.areas.AreasScreen
import ru.obkhod.app.ui.areas.AreasViewModel
import ru.obkhod.app.ui.create.CreateAreaWizardScreen
import ru.obkhod.app.ui.deleted.DeletedApartmentsScreen
import ru.obkhod.app.ui.entrance.EntranceScreen
import ru.obkhod.app.ui.entrance.EntranceViewModel

object Routes {
    const val AREAS = "areas"
    const val CREATE = "create"
    const val AREA = "area/{areaId}"
    const val ENTRANCE = "entrance/{entranceId}"
    const val DELETED = "deleted"
    const val SETTINGS = "settings" // используется как "settings?areaId={areaId}"
    const val CLOUD = "cloud"
    const val YANDEX = "yandex"

    fun area(id: Long) = "area/$id"
    fun entrance(id: Long) = "entrance/$id"
}

@Composable
fun AppNavHost(
    nav: NavHostController,
    app: Application,
    startDestination: String = Routes.AREAS,
    restoreEntranceId: Long = -1L,
) {
    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.AREAS) {
            val vm: AreasViewModel = viewModel(factory = viewModelFactory { AreasViewModel(app) })
            val cards by vm.cards.collectAsState()
            val areasLoaded by vm.loaded.collectAsState()
            // Тихая автосинхронизация с Яндекс Диском при запуске (если подключён)
            LaunchedEffect(Unit) {
                val prefs = app.getSharedPreferences("yandex_sync", Context.MODE_PRIVATE)
                if (prefs.getString("login", null) != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val client = ru.obkhod.app.data.remote.WebDavClient(
                                "https://webdav.yandex.ru",
                                prefs.getString("login", "")!!,
                                prefs.getString("app_password", "")!!,
                            )
                            ru.obkhod.app.sync.YandexSync(app, AppContainer.db(), client).sync()
                        } catch (_: Exception) { /* офлайн — пропускаем */ }
                    }
                }
            }
            // Восстановление последнего подъезда (п. 21 ТЗ): поверх списка участков,
            // с построением полной иерархии, чтобы «Назад» шёл подъезд → участок → список
            var restoreApplied by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(areasLoaded) {
                if (areasLoaded && !restoreApplied && restoreEntranceId > 0) {
                    restoreApplied = true
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val area = AppContainer.areaRepository.areaOfEntrance(restoreEntranceId)
                        val isPrivate = area?.type == ru.obkhod.app.data.db.AreaType.PRIVATE.name
                        area to isPrivate
                    }.let { (area, isPrivate) ->
                        if (area != null && !isPrivate) nav.navigate(Routes.area(area.id))
                        if (area != null) nav.navigate(Routes.entrance(restoreEntranceId))
                    }
                }
            }
            AreasScreen(
                cards = cards,
                loaded = areasLoaded,
                onOpenSettings = { nav.navigate(Routes.SETTINGS + "?areaId=-1") { launchSingleTop = true } },
                onOpenArea = { areaId ->
                    // Частный сектор: сразу открываем схему домов (служебный подъезд)
                    val card = cards.firstOrNull { it.area.id == areaId }
                    if (card?.area?.type == ru.obkhod.app.data.db.AreaType.PRIVATE.name) {
                        vm.privateEntranceId(areaId) { entranceId ->
                            if (entranceId != null) nav.navigate(Routes.entrance(entranceId))
                            else nav.navigate(Routes.area(areaId))
                        }
                    } else {
                        nav.navigate(Routes.area(areaId))
                    }
                },
                onAddArea = { nav.navigate(Routes.CREATE) },
                onDeleteArea = { vm.deleteArea(it) },
                onRenameArea = { id, name -> vm.renameArea(id, name) },
                onExportArea = { nav.navigate("${Routes.SETTINGS}?areaId=$it") },
            )
        }
        composable(Routes.CREATE) {
            val vm: AreasViewModel = viewModel(factory = viewModelFactory { AreasViewModel(app) })
            CreateAreaWizardScreen(
                onCreate = { name, drafts, type, cb -> vm.createArea(name, drafts, type, cb) },
                onDone = { areaId -> nav.navigate(Routes.area(areaId)) { popUpTo(Routes.AREAS) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.AREA, arguments = listOf(navArgument("areaId") { type = androidx.navigation.NavType.LongType })) { entry ->
            val areaId = entry.arguments?.getLong("areaId") ?: return@composable
            val vm: AreaViewModel = viewModel(
                key = "area$areaId",
                factory = viewModelFactory { AreaViewModel(app, areaId) },
            )
            val name by vm.areaName.collectAsState()
            val cards by vm.cards.collectAsState()
            val isPrivateArea by vm.isPrivate.collectAsState()
            // Частный сектор: авто-перенаправление убрано (создавало навигационный тупик —
            // «Назад» возвращало на экран участка, который снова открывал схему домов).
            // Обычно на этот маршрут для PRIVATE не попадаем: AreasScreen открывает схему домов
            // напрямую. Если маршрут всё же открыт — плитка «Подъезд № 1» ведёт к домам.
            AreaScreen(
                isPrivate = isPrivateArea,
                areaName = name,
                cards = cards,
                onOpenEntrance = { nav.navigate(Routes.entrance(it)) },
                onBack = { nav.popBackStack() },
                onAddEntrance = { nav.navigate("addEntrance/$areaId") },
                onEditEntrance = { nav.navigate("editEntrance/$it") },
                onDeleteEntrance = { vm.deleteEntrance(it) },
                onOpenDeleted = { nav.navigate(Routes.DELETED) },
            )
        }
        composable(Routes.ENTRANCE, arguments = listOf(navArgument("entranceId") { type = androidx.navigation.NavType.LongType })) { entry ->
            val entranceId = entry.arguments?.getLong("entranceId") ?: return@composable
            val vm: EntranceViewModel = viewModel(
                key = "entrance$entranceId",
                factory = viewModelFactory { EntranceViewModel(app, entranceId) },
            )
            val header by vm.header.collectAsState()
            val scheme by vm.scheme.collectAsState()
            val stats by vm.statistics.collectAsState()
            val isPrivate by vm.isPrivate.collectAsState()
            var infoForId by remember { mutableStateOf<Long?>(null) }
            var historyForId by remember { mutableStateOf<Long?>(null) }
            var noteForId by remember { mutableStateOf<Long?>(null) }
            val historyFlow = if (historyForId != null) vm.historyFlow(historyForId!!) else flowOfNull()
            val history by historyFlow.collectAsState(initial = emptyList<ru.obkhod.app.data.db.VisitHistoryEntity>())

            EntranceScreen(
                areaName = header.first,
                entranceNumber = header.second,
                scheme = scheme,
                statistics = stats,
                isPrivate = isPrivate,
                onBack = { nav.popBackStack() },
                onSetStatus = { id, status, corr -> vm.setStatus(id, status, corr) },
                onResetStatus = { vm.resetStatus(it) },
                onSaveNote = { id, phone, comment -> vm.saveNote(id, phone, comment) },
                onMove = { id, floorId, onResult -> vm.move(id, floorId, onResult) },
                onDelete = { vm.delete(it) },
                onRestore = { id -> vm.restore(id) },
                onAddHouse = if (isPrivate) {
                    { numberText, onResult -> vm.addHouse(numberText, onResult) }
                } else null,
                onEditApartment = { id, floorId, numberText, recalc, onResult ->
                    vm.editApartment(id, floorId, numberText, recalc, onResult)
                },
                onEditEntrance = { nav.navigate("editEntrance/$entranceId") },
                onAddFloor = { vm.addFloor(4) },
                onRemoveFloor = { floorId, _ -> vm.removeFloor(floorId) },
                onOpenInfo = { infoForId = it },
            )

            // Информационное окно квартиры (п. 9)
            infoForId?.let { id ->
                vm.infoFor(id, scheme)?.let { info ->
                    ru.obkhod.app.ui.apartment.ApartmentInfoDialog(
                        info = info,
                        isPrivate = isPrivate,
                        onDismiss = { infoForId = null },
                        onEditNote = { infoForId = null; noteForId = id },
                        onOpenHistory = { infoForId = null; historyForId = id },
                    )
                }
            }
            // История посещений (п. 14)
            val historyId: Long? = historyForId
            if (historyId != null) {
                var label = ""
                for (fw in scheme) for (a in fw.apartments) if (a.id == historyId) label = a.label
                ru.obkhod.app.ui.apartment.HistoryDialog(
                    apartmentNumber = label,
                    records = history,
                    isPrivate = isPrivate,
                    onDismiss = { historyForId = null },
                )
            }
            // Заметка из инфо-окна (п. 8)
            val noteId: Long? = noteForId
            if (noteId != null) {
                var apt: ru.obkhod.app.data.repo.ApartmentUi? = null
                for (fw in scheme) for (a in fw.apartments) if (a.id == noteId) apt = a
                apt?.let {
                    ru.obkhod.app.ui.entrance.NoteDialogPublic(
                        apartment = it,
                        isPrivate = isPrivate,
                        onDismiss = { noteForId = null },
                        onSave = { phone, comment -> vm.saveNote(noteId, phone, comment); noteForId = null },
                    )
                }
            }
        }
        composable(
            "addEntrance/{areaId}",
            arguments = listOf(navArgument("areaId") { type = androidx.navigation.NavType.LongType }),
        ) { entry ->
            val areaId = entry.arguments?.getLong("areaId") ?: return@composable
            ru.obkhod.app.ui.edit.AddEntranceScreen(
                areaId = areaId,
                onDone = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "editEntrance/{entranceId}",
            arguments = listOf(navArgument("entranceId") { type = androidx.navigation.NavType.LongType }),
        ) { entry ->
            val entranceId = entry.arguments?.getLong("entranceId") ?: return@composable
            ru.obkhod.app.ui.edit.EditEntranceScreen(
                entranceId = entranceId,
                onDone = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.DELETED) {
            DeletedApartmentsScreen(
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "${Routes.SETTINGS}?areaId={areaId}",
            arguments = listOf(navArgument("areaId") { type = androidx.navigation.NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            ru.obkhod.app.ui.settings.SettingsScreen(
                onBack = { nav.popBackStack() },
                exportAreaId = entry.arguments?.getLong("areaId")?.takeIf { it >= 0 },
                onOpenCloud = { nav.navigate(Routes.CLOUD) },
                onOpenYandex = { nav.navigate(Routes.YANDEX) },
            )
        }
        composable(Routes.CLOUD) {
            ru.obkhod.app.ui.cloud.CloudSyncScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.YANDEX) {
            ru.obkhod.app.ui.cloud.YandexSyncScreen(onBack = { nav.popBackStack() })
        }
    }
}

private fun flowOfNull() = kotlinx.coroutines.flow.flowOf(emptyList<ru.obkhod.app.data.db.VisitHistoryEntity>())
