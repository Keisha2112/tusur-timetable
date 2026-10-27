package com.keisha.tusur.timetable.ui.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.data.repository.CatalogRepository
import com.keisha.tusur.timetable.data.repository.SettingsRepository
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.Faculty
import com.keisha.tusur.timetable.domain.model.Group
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/**
 * Группы одного курса. Курс может быть неизвестен — такие группы
 * показываются последним разделом.
 */
data class GroupSection(
    val course: Int?,
    val groups: List<Group>,
)

data class FacultyPickerState(
    val query: String = "",
    val faculties: List<Faculty> = emptyList(),
    val totalFaculties: Int = 0,
    val currentFacultyCode: String? = null,
    val isLoading: Boolean = true,
    val error: AppError? = null,
)

data class GroupPickerState(
    val query: String = "",
    val sections: List<GroupSection> = emptyList(),
    val totalGroups: Int = 0,
    val currentGroupSlug: String? = null,
    val isLoading: Boolean = true,
    val error: AppError? = null,
    val savingSlug: String? = null,
    val saveError: AppError? = null,
)

/**
 * Экран выбора факультета. Список фильтруется на месте, сеть трогаем
 * только при первой загрузке и по кнопке «Повторить».
 */
class FacultyPickerViewModel(
    private val catalogRepository: CatalogRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FacultyPickerState())
    val state: StateFlow<FacultyPickerState> = _state.asStateFlow()

    private var allFaculties: List<Faculty> = emptyList()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.selection
                .catch { emit(null) }
                .collect { selection ->
                    _state.update { it.copy(currentFacultyCode = selection?.facultyCode) }
                }
        }
        load(forceRefresh = false)
    }

    fun onQueryChange(query: String) {
        _state.update {
            it.copy(
                query = query,
                faculties = filterFaculties(allFaculties, query),
            )
        }
    }

    /**
     * Подсветить факультет сразу после нажатия. Ничего не сохраняем:
     * выбор станет постоянным только после нажатия на группу.
     */
    fun markFacultyChosen(code: String) {
        _state.update { it.copy(currentFacultyCode = code) }
    }

    fun retry() {
        load(forceRefresh = true)
    }

    private fun load(forceRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val cached = catalogRepository.faculties(forceRefresh)
                allFaculties = cached.value
                _state.update {
                    it.copy(
                        faculties = filterFaculties(cached.value, it.query),
                        totalFaculties = cached.value.size,
                        isLoading = false,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError) {
                _state.update { it.copy(isLoading = false, error = e) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = AppError.Unknown(e)) }
            }
        }
    }
}

/**
 * Экран выбора группы. Выбор сохраняется только в момент нажатия на группу,
 * поэтому прежняя группа остаётся рабочей до подтверждения новой.
 */
class GroupPickerViewModel(
    private val catalogRepository: CatalogRepository,
    private val settingsRepository: SettingsRepository,
    private val facultyCode: String,
    private val facultyName: String,
) : ViewModel() {

    private val _state = MutableStateFlow(GroupPickerState())
    val state: StateFlow<GroupPickerState> = _state.asStateFlow()

    private val _events = Channel<Group>(Channel.BUFFERED)
    val events: Flow<Group> = _events.receiveAsFlow()

    private var allGroups: List<Group> = emptyList()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.selection
                .catch { emit(null) }
                .collect { selection ->
                    _state.update { it.copy(currentGroupSlug = selection?.groupSlug) }
                }
        }
        load(forceRefresh = false)
    }

    fun onQueryChange(query: String) {
        _state.update {
            it.copy(
                query = query,
                sections = buildSections(allGroups, query),
            )
        }
    }

    fun chooseGroup(group: Group) {
        if (_state.value.savingSlug != null) return
        viewModelScope.launch {
            _state.update { it.copy(savingSlug = group.slug, saveError = null) }
            try {
                settingsRepository.saveSelection(
                    UserSelection(
                        facultyCode = facultyCode,
                        facultyName = facultyName,
                        groupSlug = group.slug,
                        groupName = group.name,
                    )
                )
                _events.send(group)
                // Экран уходит на расписание, но его ViewModel остаётся в back stack.
                // Снимаем блокировку, иначе после возврата назад список будет недоступен.
                _state.update { it.copy(savingSlug = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError) {
                _state.update { it.copy(savingSlug = null, saveError = e) }
            } catch (e: Exception) {
                _state.update { it.copy(savingSlug = null, saveError = AppError.Unknown(e)) }
            }
        }
    }

    fun retry() {
        load(forceRefresh = true)
    }

    private fun load(forceRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val cached = catalogRepository.groups(facultyCode, forceRefresh)
                allGroups = cached.value
                _state.update {
                    it.copy(
                        sections = buildSections(cached.value, it.query),
                        totalGroups = cached.value.size,
                        isLoading = false,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError) {
                _state.update { it.copy(isLoading = false, error = e) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = AppError.Unknown(e)) }
            }
        }
    }
}

@StringRes
internal fun AppError.userMessageRes(): Int = when (this) {
    is AppError.Network -> R.string.error_network
    is AppError.Server -> R.string.error_server
    is AppError.Parse -> R.string.error_parse
    else -> R.string.error_unknown
}

private fun filterFaculties(faculties: List<Faculty>, query: String): List<Faculty> {
    val needle = query.normalized()
    val filtered = if (needle.isEmpty()) {
        faculties
    } else {
        faculties.filter { faculty ->
            faculty.name.normalized().contains(needle) ||
                faculty.code.normalized().contains(needle)
        }
    }
    val byName = compareBy(nameComparator()) { faculty: Faculty -> faculty.name }
    return filtered.sortedWith(byName.thenBy { it.code })
}

private fun buildSections(groups: List<Group>, query: String): List<GroupSection> {
    val needle = query.normalized()
    val filtered = if (needle.isEmpty()) {
        groups
    } else {
        groups.filter { group ->
            group.name.normalized().contains(needle) ||
                group.slug.normalized().contains(needle)
        }
    }
    val byName = compareBy(nameComparator()) { group: Group -> group.name }
    return filtered
        .groupBy { it.course }
        .map { (course, courseGroups) ->
            GroupSection(course = course, groups = courseGroups.sortedWith(byName))
        }
        .sortedWith(courseComparator())
}

private fun courseComparator(): Comparator<GroupSection> = Comparator { first, second ->
    val firstCourse = first.course
    val secondCourse = second.course
    when {
        firstCourse == null && secondCourse == null -> 0
        firstCourse == null -> 1
        secondCourse == null -> -1
        else -> firstCourse.compareTo(secondCourse)
    }
}

private fun nameComparator(): Comparator<String> {
    val collator = Collator.getInstance(Locale.forLanguageTag("ru-RU")).apply {
        strength = Collator.PRIMARY
    }
    return Comparator { first, second -> compareNatural(first, second, collator) }
}

/**
 * Естественное сравнение: «714-2» идёт раньше «714-10», кириллица
 * сравнивается через Collator. Результат не зависит от локали устройства.
 */
private fun compareNatural(first: String, second: String, collator: Collator): Int {
    var i = 0
    var j = 0
    while (i < first.length && j < second.length) {
        if (first[i].isDigit() && second[j].isDigit()) {
            val firstStart = i
            while (i < first.length && first[i].isDigit()) i++
            val secondStart = j
            while (j < second.length && second[j].isDigit()) j++

            val firstNumber = first.substring(firstStart, i).trimStart('0')
            val secondNumber = second.substring(secondStart, j).trimStart('0')
            val byLength = firstNumber.length.compareTo(secondNumber.length)
            if (byLength != 0) return byLength
            val byValue = firstNumber.compareTo(secondNumber)
            if (byValue != 0) return byValue
        } else {
            val firstStart = i
            while (i < first.length && !first[i].isDigit()) i++
            val secondStart = j
            while (j < second.length && !second[j].isDigit()) j++

            val byText = collator.compare(
                first.substring(firstStart, i),
                second.substring(secondStart, j),
            )
            if (byText != 0) return byText
        }
    }
    return (first.length - i).compareTo(second.length - j)
}

private val extraSpaces = Regex("\\s+")

private fun String.normalized(): String =
    trim().replace(extraSpaces, " ").lowercase(Locale.ROOT)
