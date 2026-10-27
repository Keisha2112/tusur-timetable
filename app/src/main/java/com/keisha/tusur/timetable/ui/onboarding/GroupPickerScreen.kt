@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.keisha.tusur.timetable.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.keisha.tusur.timetable.AppContainer
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Group

@Composable
fun GroupPickerScreen(
    container: AppContainer,
    facultyCode: String,
    facultyName: String,
    onGroupChosen: (Group) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: GroupPickerViewModel = viewModel(
        key = "group_picker_$facultyCode",
        factory = viewModelFactory {
            initializer {
                GroupPickerViewModel(
                    catalogRepository = container.catalogRepository,
                    settingsRepository = container.settingsRepository,
                    facultyCode = facultyCode,
                    facultyName = facultyName,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnGroupChosen by rememberUpdatedState(onGroupChosen)

    BackHandler(onBack = onBack)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { group ->
            currentOnGroupChosen(group)
        }
    }

    Scaffold(
        topBar = {
            OnboardingTopBar(
                title = stringResource(R.string.onboarding_group_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            OnboardingHeader(
                step = stringResource(R.string.onboarding_step, 2, 2),
                subtitle = stringResource(R.string.onboarding_group_subtitle, facultyName),
            )
            OnboardingSearchField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                hint = stringResource(R.string.onboarding_group_search_hint),
            )
            val saveError = state.saveError
            if (saveError != null) {
                SaveErrorBanner(error = saveError)
            }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val error = state.error
                when {
                    state.isLoading -> OnboardingLoading()
                    error != null -> OnboardingError(error = error, onRetry = viewModel::retry)
                    state.sections.isEmpty() -> OnboardingEmpty(
                        text = stringResource(R.string.onboarding_no_groups),
                    )
                    else -> GroupList(
                        sections = state.sections,
                        currentGroupSlug = state.currentGroupSlug,
                        savingSlug = state.savingSlug,
                        onGroupClick = viewModel::chooseGroup,
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveErrorBanner(error: AppError) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(error.userMessageRes()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun GroupList(
    sections: List<GroupSection>,
    currentGroupSlug: String?,
    savingSlug: String?,
    onGroupClick: (Group) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { section ->
            item(key = "course_${section.course ?: "none"}") {
                CourseHeader(course = section.course)
            }
            items(items = section.groups, key = { it.slug }) { group ->
                GroupCard(
                    group = group,
                    current = group.slug == currentGroupSlug,
                    saving = group.slug == savingSlug,
                    enabled = savingSlug == null,
                    onClick = { onGroupClick(group) },
                )
            }
        }
    }
}

@Composable
private fun CourseHeader(course: Int?) {
    Text(
        text = if (course == null) {
            stringResource(R.string.onboarding_course_unknown)
        } else {
            stringResource(R.string.onboarding_course, course)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun GroupCard(
    group: Group,
    current: Boolean,
    saving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (current) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val contentColor = if (current) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // Пока идёт сохранение, карточки недоступны. Отдельные disabled-цвета
    // оставляют подсветку выбранной группы и не приглушают весь список.
    val colors = CardDefaults.cardColors(
        containerColor = containerColor,
        contentColor = contentColor,
        disabledContainerColor = containerColor,
        disabledContentColor = contentColor,
    )
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = colors,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            when {
                saving -> {
                    Spacer(Modifier.width(12.dp))
                    val savingDescription = stringResource(R.string.onboarding_saving)
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = savingDescription },
                        strokeWidth = 2.dp,
                        color = if (current) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                current -> {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = stringResource(R.string.onboarding_selected),
                    )
                }
            }
        }
    }
}
