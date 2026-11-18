package ru.obkhod.app.ui.entrance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.obkhod.app.data.db.Status
import ru.obkhod.app.data.repo.ApartmentUi
import ru.obkhod.app.data.repo.FloorWithApartments
import ru.obkhod.app.data.repo.Statistics
import ru.obkhod.app.ui.AppContainer
import ru.obkhod.app.ui.apartment.ApartmentInfo

class EntranceViewModel(app: Application, private val entranceId: Long) : AndroidViewModel(app) {
    private val repo = AppContainer.entranceRepository

    private val areaName = kotlinx.coroutines.flow.MutableStateFlow("")
    val isPrivate = kotlinx.coroutines.flow.MutableStateFlow(false)

    init {
        viewModelScope.launch {
            AppContainer.areaRepository.areaOfEntrance(entranceId)?.let { area ->
                areaName.value = area.name
                isPrivate.value = area.type == ru.obkhod.app.data.db.AreaType.PRIVATE.name
            }
        }
    }

    /** Частный сектор: добавление дома в свободном порядке. */
    fun addHouse(numberText: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        onResult(repo.addHouse(entranceId, numberText))
    }

    val header: StateFlow<Pair<String, Int>> = combine(
        areaName,
        repo.observeEntrance(entranceId),
    ) { name, entrance -> name to (entrance?.entranceNumber ?: 0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "" to 0)

    val scheme: StateFlow<List<FloorWithApartments>> =
        repo.observeFloorScheme(entranceId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val statistics: StateFlow<Statistics> =
        repo.observeStatistics(entranceId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Statistics())

    fun infoFor(apartmentId: Long, scheme: List<FloorWithApartments>): ApartmentInfo? {
        val entranceNumber = header.value.second
        val apt = scheme.asSequence().flatMap { it.apartments.asSequence() }.firstOrNull { it.id == apartmentId }
            ?: return null
        return ApartmentInfo(
            id = apt.id,
            number = apt.number,
            label = apt.label,
            entranceNumber = entranceNumber,
            floorNumber = apt.floorNumber,
            status = apt.status,
            lastVisitAt = apt.lastVisitAt,
            phone = apt.phone,
            comment = apt.comment,
            hasVisits = apt.hasVisits,
        )
    }

    fun setStatus(id: Long, status: Status, correction: Boolean) =
        viewModelScope.launch { repo.setStatus(id, status, correction) }

    fun resetStatus(id: Long) = viewModelScope.launch { repo.resetStatus(id) }

    fun saveNote(id: Long, phone: String?, comment: String?) =
        viewModelScope.launch { repo.saveNote(id, phone, comment) }

    fun move(id: Long, floorId: Long, onResult: (String?) -> Unit) = viewModelScope.launch {
        when (repo.moveApartment(id, floorId)) {
            ru.obkhod.app.data.repo.MoveResult.OK -> onResult(null)
            ru.obkhod.app.data.repo.MoveResult.DUPLICATE -> onResult("На этом этаже уже есть квартира с таким номером")
            else -> onResult("Перемещение невозможно")
        }
    }

    fun delete(id: Long) = viewModelScope.launch { repo.deleteApartment(id) }

    /** Отмена удаления (Undo в snackbar): квартира возвращается на прежний этаж. */
    fun restore(id: Long, onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        onResult(repo.restoreApartment(id))
    }

    /** Редактирование квартиры: этаж/номер (+возможный пересчёт всех номеров подъезда). */
    fun editApartment(id: Long, floorId: Long?, numberText: String, recalc: Boolean, onResult: (String?) -> Unit) =
        viewModelScope.launch {
            onResult(repo.editApartment(id, floorId, numberText, recalc))
        }

    fun addFloor(apartments: Int) = viewModelScope.launch { repo.addFloor(entranceId, apartments) }

    fun removeFloor(floorId: Long) = viewModelScope.launch { repo.removeFloor(floorId, deleteApartments = true) }

    fun historyFlow(apartmentId: Long) = repo.observeHistory(apartmentId)
}
