package com.keisha.tusur.timetable.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import java.time.Instant

/**
 * Плашка «показано сохранённое расписание» с временем последнего обновления.
 */
@Composable
fun OfflineBanner(
    message: String,
    updatedAt: Instant?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (updatedAt != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.schedule_updated_at,
                            ScheduleTime.formatUpdatedAt(updatedAt),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onTertiaryContainer,
                    )
                }
            }
        }
    }
}
