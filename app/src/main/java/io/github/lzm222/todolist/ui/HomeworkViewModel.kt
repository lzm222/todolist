package io.github.lzm222.todolist.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lzm222.todolist.data.HomeworkItem
import io.github.lzm222.todolist.data.HomeworkRepository
import io.github.lzm222.todolist.data.SampleData
import io.github.lzm222.todolist.parse.HomeworkJsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 按科目分组后的一科。 */
data class SubjectGroup(val subject: String, val items: List<HomeworkItem>)

/** 逾期条目 + 逾期天数。 */
data class OverdueEntry(val item: HomeworkItem, val days: Int)

data class HomeworkUiState(
    val today: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
    val selectedDate: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
    val availableDates: List<String> = emptyList(),
    val groups: List<SubjectGroup> = emptyList(),
    val overdue: List<OverdueEntry> = emptyList(),
    val isToday: Boolean = true,
    val loading: Boolean = true
) {
    val isEmpty: Boolean
        get() = if (isToday) {
            // 今天视角：当日清单和逾期栏都是可见内容
            groups.isEmpty() && overdue.isEmpty()
        } else {
            // 历史日期视角：逾期栏根本不展示，判断空态时也不能把它算进来，
            // 否则"删光某一天的任务"会被其它日期的逾期项掩盖，掉进空白页
            groups.isEmpty()
        }

    /**
     * 当前屏幕上「可被多选」的所有条目 id，即当日清单 + 今天视角下的逾期栏。
     *
     * 抽成派生属性而不是新增字段，理由有二：
     * 1. 全选必须和用户实际能看到的行一致，逾期栏在历史日期不渲染，就不该进全选集合；
     * 2. 它是纯派生数据，加进数据流 combine 只会多一处需要同步的地方。
     */
    val selectableIds: List<Long>
        get() = buildList {
            groups.forEach { group -> group.items.forEach { add(it.id) } }
            if (isToday) overdue.forEach { add(it.item.id) }
        }

    /**
     * 屏幕上所有条目对象（当日清单 + 今天视角下的逾期栏），供按 id 反查实体用。
     * 批量删除需要拿到 [HomeworkItem] 本身而不是 id，撤销时还要靠这些对象回填文案。
     */
    val allItems: List<HomeworkItem>
        get() = buildList {
            groups.forEach { group -> addAll(group.items) }
            if (isToday) overdue.forEach { add(it.item) }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkViewModel(private val repo: HomeworkRepository) : ViewModel() {

    private val iso = DateTimeFormatter.ISO_LOCAL_DATE

    private val _selectedDate = MutableStateFlow(LocalDate.now().format(iso))
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    /** 一次性提示消息（Snackbar） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val availableDates: StateFlow<List<String>> = repo.observeAllDates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val dayItems = _selectedDate.flatMapLatest { date -> repo.observeByDate(date) }

    /** 逾期只看"今天"的视角下才有意义，但数据始终计算好，UI 按 isToday 决定是否展示。 */
    private val overdueItems = repo.observeOverdue(todayString())

    val uiState: StateFlow<HomeworkUiState> =
        combine(_selectedDate, dayItems, overdueItems, availableDates) { date, items, overdue, dates ->
            val todayStr = todayString()
            HomeworkUiState(
                today = todayStr,
                selectedDate = date,
                // 日期条 = 数据库里真实存在的日期 + 今天（今天始终可选）
                availableDates = (dates + todayStr).distinct().sortedDescending(),
                groups = groupBySubject(items),
                overdue = overdue.map { item ->
                    val days = runCatching {
                        ChronoUnit.DAYS.between(LocalDate.parse(item.dueDate, iso), LocalDate.now())
                            .toInt()
                    }.getOrDefault(0)
                    OverdueEntry(item, days)
                }.filter { it.days > 0 },
                isToday = date == todayStr,
                loading = false
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HomeworkUiState()
        )

    private fun todayString(): String = LocalDate.now().format(iso)

    init {
        /**
         * 悬空日期回落。
         *
         * 用户停在某个历史日期上把该天任务全部删光后，这个日期就不再存在于数据库，
         * 日期条里也不会再有它。若不处理，界面会滞留在一个"标题是 11 月 3 日、
         * 日期条里却没有 11 月 3 日"的矛盾状态。
         *
         * 这里单独订阅日期集合（而不是在 combine 的变换函数里改状态），
         * 一旦发现选中日期已经消失，就回到今天。
         */
        viewModelScope.launch {
            repo.observeAllDates().collect { dates ->
                val selected = _selectedDate.value
                val today = todayString()
                if (selected != today && selected !in dates) {
                    _selectedDate.value = today
                }
            }
        }
    }

    private fun groupBySubject(items: List<HomeworkItem>): List<SubjectGroup> =
        items.groupBy { it.subject }
            .map { (subject, list) -> SubjectGroup(subject, list) }
            .sortedBy { it.items.firstOrNull()?.sortOrder ?: 0 }
            .sortedBy { group -> items.indexOfFirst { it.subject == group.subject } }

    fun selectDate(date: String) {
        _selectedDate.value = date
    }

    fun backToToday() {
        _selectedDate.value = todayString()
    }

    fun toggleDone(item: HomeworkItem) {
        viewModelScope.launch {
            repo.setDone(item.id, !item.isDone)
        }
    }

    fun delete(item: HomeworkItem) {
        viewModelScope.launch {
            repo.delete(item.id)
            _message.value = "已删除「${item.content}」"
        }
    }

    /**
     * 批量删除。
     * 文案里只给条数，不拼接内容（多选可能横跨多个科目）。
     */
    fun deleteBatch(items: List<HomeworkItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            repo.deleteBatch(items.map { it.id })
            _message.value = "已删除 ${items.size} 项"
        }
    }

    fun undoDelete(item: HomeworkItem) {
        viewModelScope.launch {
            repo.restore(item.id)
            _message.value = "已恢复"
        }
    }

    /** 撤销批量删除：一次性恢复全部 id，文案按条数生成。 */
    fun undoDeleteBatch(items: List<HomeworkItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            repo.restoreBatch(items.map { it.id })
            _message.value = "已恢复 ${items.size} 项"
        }
    }

    /** 导入 JSON 文本。成功时切换到 JSON 里指定的日期。 */
    fun importJson(text: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        when (val result = HomeworkJsonParser.parse(text)) {
            is HomeworkJsonParser.Result.Error -> onError(result.message)
            is HomeworkJsonParser.Result.Success -> {
                viewModelScope.launch {
                    val (inserted, skipped) = repo.import(result.items)
                    if (inserted == 0 && skipped > 0) {
                        _message.value = "全部 $skipped 条都已在清单中，没有新增"
                    } else if (skipped > 0) {
                        _message.value = "成功导入 $inserted 条，跳过 $skipped 条重复作业"
                    } else {
                        _message.value = "成功导入 $inserted 条作业"
                    }
                    // 跳转到被导入的日期（取第一个）
                    result.dates.firstOrNull()?.let { _selectedDate.value = it }
                    onSuccess()
                }
            }
        }
    }

    /** 
     * 空态「加载示例数据」。
     * 
     * 注意：即使之前导入过又全部删掉，这里也应该能重新灌入。
     * 这依赖数据库的部分唯一索引（只约束 deleted = 0 的行），
     * 软删除的行不会挡住插入。
     */
    fun loadSampleData() {
        viewModelScope.launch {
            val (inserted, skipped) = repo.import(SampleData.build())
            _message.value = when {
                inserted > 0 && skipped > 0 -> "已加载 $inserted 条示例数据，跳过 $skipped 条重复"
                inserted > 0 -> "已加载 $inserted 条示例数据"
                // 理论上不应该走到这里（清单里已有这 7 条未删除的示例数据）
                else -> "示例数据已在清单中"
            }
            _selectedDate.value = todayString()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}