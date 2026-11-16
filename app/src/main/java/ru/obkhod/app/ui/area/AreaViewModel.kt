package ru.obkhod.app.ui.area

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.obkhod.app.ui.AppContainer

class AreaViewModel(app: Application, private val areaId: Long) : AndroidViewModel(app) {
    private val repo = AppContainer.areaRepository
    private val entRepo = AppContainer.entranceRepository

    val areaName: StateFlow<String> = repo.observeArea(areaId)
        .map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** Частный сектор: подъезды не показываются, только дома. */
    val isPrivate: StateFlow<Boolean> = repo.observeArea(areaId)
        .map { it?.type == ru.obkhod.app.data.db.AreaType.PRIVATE.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val cards: StateFlow<List<EntranceCard>> =
        combine(
            entRepo.observeAllEntrances().map { list -> list.filter { it.areaId == areaId } },
            entRepo.observeAllFloors(),
            entRepo.observeAllActiveApartments(),
            entRepo.visitedApartmentIds,
        ) { entrances, floors, apartments, visited ->
            entrances.map { entrance ->
                val entranceFloors = floors.filter { it.entranceId == entrance.id }
                val entranceApartments = apartments.filter { it.entranceId == entrance.id }
                EntranceCard(entrance, entranceFloors, entranceApartments, entranceApartments.count { it.id in visited })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteEntrance(id: Long) = viewModelScope.launch { entRepo.deleteEntrance(id) }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> kotlinx.coroutines.flow.Flow<T>.flatMapLatestCompat(transform: suspend (T) -> kotlinx.coroutines.flow.Flow<T>): kotlinx.coroutines.flow.Flow<T> =
    flatMapLatest { transform(it) }
