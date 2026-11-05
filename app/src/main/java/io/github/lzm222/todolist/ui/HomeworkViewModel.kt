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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 按科目分组后的一科。 */
data class SubjectGroup(val subject: String, val items: List<HomeworkItem>)

/** 逾期条目 + 逾期天数。 */
data class OverdueEntry(val item: HomeworkItem, val days: Int)

/**
 * 一条一次性提示（Snackbar）。
 *
 * 为什么不是裸 `String`：能否撤销原本靠「文案以『已删除』开头」来猜
 * （`msg.startsWith("已删除")`，见 AGENTS.md §6.4 的历史写法）。那是一条隐式协议——
 * 改一次文案措辞，撤销按钮就会静默消失，而且编译器一点忙也帮不上。
 * 现在把「可不可撤销」变成消息自带的字段，与文案彻底解耦。
 */
data class UiMessage(val text: String, val undoable: Boolean = false)

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

    /** 一次性提示消息（Snackbar）。是否可撤销由 [UiMessage.undoable] 决定。 */
    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    val availableDates: StateFlow<List<String>> = repo.observeAllDates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val dayItems = _selectedDate.flatMapLatest { date -> repo.observeByDate(date) }

    /**
     * 「今天」不是一个常量。
     *
     * 这是本类里最容易漏的一处：`combine` 的变换函数里确实每次都调了 `LocalDate.now()`，
     * 看起来是动态的，但 **`combine` 只在上游有新值时才会重算**。数据不变时它一次都不会
     * 重跑，于是「今天」永远停在上一次数据变化的那个日期。作业清单这种长期挂后台、
     * 隔天再打开的应用正好踩中：跨过午夜后 `isToday`、逾期天数、日期条上的「今天」全错位。
     *
     * 修法是把「今天」提升成一条真实的数据流，每天午夜翻页，并作为 `combine` 的输入。
     */
    private val todayFlow: StateFlow<String> = flow {
        while (true) {
            val today = LocalDate.now()
            emit(today.format(iso))
            // 睡到下一个午夜。多睡 1 秒，确保醒来时已经越过日期边界
            val waitMs = ChronoUnit.MILLIS
                .between(LocalDateTime.now(), today.plusDays(1).atStartOfDay())
                .coerceAtLeast(1_000L)
            delay(waitMs + 1_000L)
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, todayString())

    /** 逾期只看"今天"的视角下才有意义，但数据始终计算好，UI 按 isToday 决定是否展示。 */
    private val overdueItems = todayFlow.flatMapLatest { today -> repo.observeOverdue(today) }

    val uiState: StateFlow<HomeworkUiState> =
        combine(
            _selectedDate,
            dayItems,
            overdueItems,
            availableDates,
            todayFlow
        ) { date, items, overdue, dates, todayStr ->
            HomeworkUiState(
                today = todayStr,
                selectedDate = date,
                // 日期条 = 数据库里真实存在的日期 + 今天（今天始终可选）
                availableDates = (dates + todayStr).distinct().sortedDescending(),
                groups = groupBySubject(items),
                overdue = overdue.map { item ->
                    // 天数用流里的 todayStr 算，不要再用 LocalDate.now()：
                    // 否则同一帧里会出现「按旧日期筛出来的列表 + 按新日期算的天数」
                    val days = runCatching {
                        ChronoUnit.DAYS.between(
                            LocalDate.parse(item.dueDate, iso),
                            LocalDate.parse(todayStr, iso)
                        ).toInt()
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

    /**
     * 按科目分组。
     *
     * DAO 的查询已经把结果排成 `subject ASC, sortOrder ASC, id ASC`，而 `groupBy`
     * 返回的是 `LinkedHashMap`，**保留首次出现顺序** —— 所以这里什么都不用再排：
     * 分组顺序天然等于 `subject ASC`，组内顺序天然等于 `sortOrder ASC`。
     *
     * 原本这里叠了两次 `sortedBy`：第一次按 `sortOrder` 排（与 DAO 的 `subject ASC` 语义冲突），
     * 第二次按「该科目首次出现的下标」排，把第一次的结果整个覆盖掉；而那个 `indexOfFirst`
     * 是写在比较器里的，复杂度 O(n²)。两次排序加起来是纯粹的死代码，已删除。
     */
    private fun groupBySubject(items: List<HomeworkItem>): List<SubjectGroup> =
        items.groupBy { it.subject }
            .map { (subject, list) -> SubjectGroup(subject, list) }

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
            _message.value = UiMessage("已删除「${item.content}」", undoable = true)
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
            _message.value = UiMessage("已删除 ${items.size} 项", undoable = true)
        }
    }

    /**
     * 撤销删除：单条删除就是长度为 1 的批量，共用这条通道。
     *
     * 不为单条另开一个 `undoDelete(item)` —— 两条撤销路径意味着两份「撤销后该提示什么」
     * 的文案逻辑，而它们迟早会漂移（AGENTS.md §6.4）。
     */
    fun undoDeleteBatch(items: List<HomeworkItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            repo.restoreBatch(items.map { it.id })
            _message.value = UiMessage("已恢复 ${items.size} 项")
        }
    }

    /** 导入 JSON 文本。成功时切换到 JSON 里指定的日期。 */
    fun importJson(text: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        when (val result = HomeworkJsonParser.parse(text)) {
            is HomeworkJsonParser.Result.Error -> onError(result.message)
            is HomeworkJsonParser.Result.Success -> {
                viewModelScope.launch {
                    val (inserted, skipped) = repo.import(result.items)
                    _message.value = UiMessage(
                        when {
                            inserted == 0 && skipped > 0 -> "全部 $skipped 条都已在清单中，没有新增"
                            skipped > 0 -> "成功导入 $inserted 条，跳过 $skipped 条重复作业"
                            else -> "成功导入 $inserted 条作业"
                        }
                    )
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
            _message.value = UiMessage(
                when {
                    inserted > 0 && skipped > 0 -> "已加载 $inserted 条示例数据，跳过 $skipped 条重复"
                    inserted > 0 -> "已加载 $inserted 条示例数据"
                    // 理论上不应该走到这里（清单里已有这 7 条未删除的示例数据）
                    else -> "示例数据已在清单中"
                }
            )
            _selectedDate.value = todayString()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}