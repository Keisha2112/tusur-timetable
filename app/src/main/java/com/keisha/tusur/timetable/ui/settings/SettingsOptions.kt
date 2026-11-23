package com.keisha.tusur.timetable.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.AnimationMode
import com.keisha.tusur.timetable.domain.model.ColorPalette
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.ui.schedule.ScheduleSheet
import com.keisha.tusur.timetable.ui.schedule.ScheduleSheetTitle
import com.keisha.tusur.timetable.ui.theme.paletteScheme

internal val ColorPalette.labelRes: Int
    get() = when (this) {
        ColorPalette.ORIGINAL -> R.string.settings_palette_original
        ColorPalette.MARINE -> R.string.settings_palette_marine
        ColorPalette.TERRACOTTA -> R.string.settings_palette_terracotta
        ColorPalette.FOREST -> R.string.settings_palette_forest
        ColorPalette.PLUM -> R.string.settings_palette_plum
        ColorPalette.AMBER -> R.string.settings_palette_amber
        ColorPalette.GRAPHITE -> R.string.settings_palette_graphite
        ColorPalette.ROSE -> R.string.settings_palette_rose
    }

internal val AnimationMode.labelRes: Int
    get() = when (this) {
        AnimationMode.NORMAL -> R.string.settings_animations_normal
        AnimationMode.OFF -> R.string.settings_animations_off
        AnimationMode.FAST -> R.string.settings_animations_fast
    }

internal val ScheduleSource.labelRes: Int
    get() = when (this) {
        ScheduleSource.GROUP -> R.string.schedule_source_group
        ScheduleSource.GROUP_FROM_ROOMS -> R.string.schedule_source_group_from_rooms
    }

@Composable
internal fun <T : Enum<T>> SettingsOptions(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    preview: @Composable (T) -> Unit = {},
) {
    ScheduleSheet(onDismiss = onDismiss) {
        ScheduleSheetTitle(title, onDismiss, Modifier.padding(horizontal = 16.dp))
        LazyColumn(
            modifier = Modifier.heightIn(max = 460.dp).padding(horizontal = 12.dp, vertical = 8.dp)
                .selectableGroup(),
        ) {
            items(options, key = { it.name }) { option ->
                val isSelected = option == selected
                Row(
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(label(option)), modifier = Modifier.weight(1f))
                    preview(option)
                    RadioButton(selected = isSelected, onClick = null)
                }
            }
        }
    }
}

@Composable
internal fun PaletteSwatches(palette: ColorPalette) {
    val light = paletteScheme(palette, dark = false)
    val dark = paletteScheme(palette, dark = true)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (color in listOf(light.primary, light.primaryContainer, dark.primary)) {
            Surface(
                modifier = Modifier.size(18.dp),
                shape = CircleShape,
                color = color,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {}
        }
    }
}
