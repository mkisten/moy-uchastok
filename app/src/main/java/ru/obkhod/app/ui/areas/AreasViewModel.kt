package ru.obkhod.app.ui.areas

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.obkhod.app.data.db.AreaEntity
import ru.obkhod.app.data.repo.CreateAreaResult
import ru.obkhod.app.data.repo.EntranceDraft
import ru.obkhod.app.data.repo.Statistics
import ru.obkhod.app.ui.AppContainer

/** Список участков со статистикой по каждому (п. 3, 15 ТЗ). */
data class AreaCard(
    val area: AreaEntity,
    val entranceCount: Int,
    val totalFloors: Int,
    val stats: Statistics,
)

class AreasViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppContainer.areaRepository
    private val entRepo = AppContainer.entranceRepository

    /** false до первой загрузки данных — для skeleton-состояния (п. 23 ТЗ). */
    val loaded = kotlinx.coroutines.flow.MutableStateFlow(false)

    val cards: StateFlow<List<AreaCard>> = combine(
        repo.observeAreas(),
        entRepo.observeAllEntrances(),
        entRepo.observeAllFloors(),
        entRepo.observeAllActiveApartments(),
        entRepo.visitedApartmentIds,
    ) { areas, entrances, floors, apartments, visitedIds ->
        areas.map { area ->
            val entranceIds = entrances.filter { it.areaId == area.id }
            val aptIds = apartments.filter { a -> entranceIds.any { e -> e.id == a.entranceId } }
            AreaCard(
                area = area,
                entranceCount = entranceIds.size,
                totalFloors = floors.count { f -> entranceIds.any { e -> e.id == f.entranceId } },
                stats = Statistics.of(
                    aptIds.map { ru.obkhod.app.data.db.Status.valueOf(it.status) },
                    visitedIds,
                    aptIds.map { it.id },
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            cards.collect { if (!loaded.value) loaded.value = true }
        }
    }

    fun deleteArea(id: Long) = viewModelScope.launch { repo.deleteArea(id) }

    fun renameArea(id: Long, name: String) = viewModelScope.launch { repo.renameArea(id, name) }

    fun createArea(name: String, drafts: List<EntranceDraft>, type: ru.obkhod.app.data.db.AreaType, onResult: (CreateAreaResult) -> Unit) =
        viewModelScope.launch { onResult(repo.createArea(name, drafts, type)) }

    /** Для частного сектора: id служебного подъезда, чтобы открыть схему домов напрямую. */
    fun privateEntranceId(areaId: Long, onResult: (Long?) -> Unit) = viewModelScope.launch {
        onResult(entRepo.firstEntranceId(areaId))
    }
}
