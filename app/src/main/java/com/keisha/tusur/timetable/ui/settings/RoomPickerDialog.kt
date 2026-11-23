package com.keisha.tusur.timetable.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.data.repository.RoomScheduleRepository
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Auditorium
import com.keisha.tusur.timetable.domain.model.Building
import com.keisha.tusur.timetable.ui.components.ScheduleLoadingState
import com.keisha.tusur.timetable.ui.onboarding.SelectionRow
import com.keisha.tusur.timetable.ui.schedule.ScheduleSheet
import com.keisha.tusur.timetable.ui.schedule.ScheduleSheetTitle
import kotlinx.coroutines.CancellationException

private data class CatalogState(
    val buildings: List<Building> = emptyList(),
    val rooms: List<Auditorium> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
)

@Composable
internal fun RoomPickerDialog(
    repository: RoomScheduleRepository,
    onSelect: (Auditorium) -> Unit,
    onDismiss: () -> Unit,
) {
    var building by remember { mutableStateOf<Building?>(null) }
    var selectedRoom by remember(building) { mutableStateOf<Auditorium?>(null) }
    var retry by remember(building) { mutableIntStateOf(0) }
    val selectedBuilding = building
    val state by produceState(CatalogState(), selectedBuilding, retry) {
        value = CatalogState()
        value = try {
            if (selectedBuilding == null) {
                CatalogState(buildings = repository.buildings(forceRefresh = retry > 0), loading = false)
            } else {
                CatalogState(rooms = repository.rooms(selectedBuilding, forceRefresh = retry > 0), loading = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CatalogState(loading = false, error = error as? AppError ?: AppError.Unknown(error))
        }
    }

    ScheduleSheet(onDismiss) {
        ScheduleSheetTitle(
            title = stringResource(
                if (selectedBuilding == null) R.string.room_choose_building else R.string.room_choose_auditorium,
            ),
            onDismiss = onDismiss,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (selectedBuilding != null) {
            TextButton(onClick = { building = null }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.room_back_to_buildings))
            }
        }
        when {
            state.loading -> ScheduleLoadingState(Modifier.height(124.dp))
            state.error != null -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val message = when (state.error) {
                    is AppError.Network -> R.string.error_network
                    is AppError.Server -> R.string.error_server
                    is AppError.NotFound -> R.string.room_catalog_not_found
                    is AppError.Parse -> R.string.error_parse
                    else -> R.string.error_unknown
                }
                Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.action_retry)) }
            }
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp).selectableGroup(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (selectedBuilding == null) {
                    items(state.buildings, key = { it.code }) { item ->
                        SelectionRow(title = item.name, selected = false, onClick = { building = item })
                    }
                } else {
                    items(state.rooms, key = { it.slug }) { item ->
                        SelectionRow(title = item.label, selected = selectedRoom == item, onClick = { selectedRoom = item })
                    }
                }
            }
        }
        if (selectedBuilding != null && !state.loading && state.error == null) {
            Button(
                onClick = { selectedRoom?.let(onSelect) },
                enabled = selectedRoom != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.room_apply_auditorium))
            }
        }
    }
}
