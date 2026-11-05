package io.github.lzm222.todolist.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lzm222.todolist.data.HomeworkItem
import io.github.lzm222.todolist.skill.SkillInstaller
import io.github.lzm222.todolist.ui.HomeworkUiState
import io.github.lzm222.todolist.ui.HomeworkViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 作业清单主屏。
 *
 * 这里只做两件事：**状态归属** 与 **事件转发**。所有视觉块都拆到同包的小文件里：
 * - [HomeworkTopBar] / [SelectionTopBar]：两个标题栏（多选态二选一）
 * - [TodayListSection] / [OverdueSection]：两块列表内容
 * - [HomeworkItemRow]：单行（滑动删除的全部机关都在这里）
 * - [ImportDialog] / [BatchDeleteConfirmDialog]：两个弹窗
 * - [SkillCard] / [EmptyState]：两个卡片块
 * - [RowInteraction]：行级交互状态（哪行展开、哪行被拖、多选集合）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkScreen(viewModel: HomeworkViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // ---- 纯 UI 状态，刻意留在 UI 层、不进 ViewModel ----
    // 行级那四份（展开 / 拖动 / 多选 / 选中集合）收在 RowInteraction 里统一管理；
    // 剩下的三份是「弹窗可见性」和「待撤销列表」，与行无关，就地持有。
    val rowUi = remember { RowInteraction() }
    var showImport by remember { mutableStateOf(false) }
    var showBatchConfirm by remember { mutableStateOf(false) }
    // 待撤销项用列表：单条删除是 1 个元素，批量删除是 N 个，撤销走同一条路径
    var lastDeleted by remember { mutableStateOf<List<HomeworkItem>>(emptyList()) }

    // Skill 安装状态：放这里而不是 SkillCard 内部，因为后者是列表 item，
    // 滚出屏幕会被销毁，滚回来就重跑一次磁盘 IO + SHA-256。
    val context = LocalContext.current
    var skillReload by remember { mutableIntStateOf(0) }
    val skillStatus by produceState<SkillInstaller.Status?>(initialValue = null, skillReload) {
        value = withContext(Dispatchers.IO) { SkillInstaller.install(context) }
    }

    // 一次性消息（导入结果 / 删除提示 / Skill 状态）
    LaunchedEffect(message) {
        val msg = message ?: return@LaunchedEffect
        // 是否可撤销由消息自带的 undoable 决定，不再靠「文案以某几个字开头」这种隐式协议
        val pending = if (msg.undoable) lastDeleted else emptyList()
        if (!msg.undoable) lastDeleted = emptyList()

        val result = snackbarHostState.showSnackbar(
            message = msg.text,
            actionLabel = if (pending.isNotEmpty()) "撤销" else null,
            withDismissAction = true,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed && pending.isNotEmpty()) {
            viewModel.undoDeleteBatch(pending)
        }
        // 无论用户点撤销、关闭还是超时，都要清空，防止旧条目残留到下一次提示
        if (msg.undoable) lastDeleted = emptyList()
        viewModel.consumeMessage()
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImport = { text, onError, onSuccess ->
                viewModel.importJson(text, onSuccess = onSuccess, onError = onError)
            }
        )
    }

    // 批量删除二次确认
    if (showBatchConfirm) {
        val targets = state.allItems.filter { it.id in rowUi.selectedIds }
        BatchDeleteConfirmDialog(
            count = targets.size,
            onDismiss = { showBatchConfirm = false },
            onConfirm = {
                lastDeleted = targets
                viewModel.deleteBatch(targets)
                showBatchConfirm = false
                // 删完必须退出多选，否则会停在一个「零选中」的多选空壳里
                rowUi.exitSelection()
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // 两个分支共用同一个 TopAppBar 位置，避免布局跳动
            if (rowUi.selectionMode) {
                SelectionTopBar(
                    selectedCount = rowUi.selectedIds.size,
                    allSelected = rowUi.allSelected(state.selectableIds),
                    onToggleSelectAll = { rowUi.toggleSelectAll(state.selectableIds) },
                    onDelete = { showBatchConfirm = true },
                    onCancel = { rowUi.exitSelection() }
                )
            } else {
                HomeworkTopBar(
                    dateLabel = prettyDate(state.selectedDate),
                    showBackToToday = !state.isToday,
                    onBackToToday = { viewModel.backToToday() },
                    onImport = { showImport = true }
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showImport = true },
                icon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                text = { Text("导入") }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Content(
                    state = state,
                    rowUi = rowUi,
                    skillStatus = skillStatus,
                    onSkillReinstall = { skillReload++ },
                    onSelectDate = {
                        // 切日期等于换了一屏内容，展开态失去意义
                        rowUi.clearReveal()
                        viewModel.selectDate(it)
                    },
                    onToggleDone = viewModel::toggleDone,
                    onDelete = {
                        lastDeleted = listOf(it)
                        rowUi.clearReveal()
                        viewModel.delete(it)
                    },
                    onLoadSample = viewModel::loadSampleData,
                    onImport = { showImport = true }
                )
            }
        }
    }
}

/**
 * 列表主体。
 *
 * 渲染顺序是契约的一部分（AGENTS.md §6.1）：日期条恒在第一个，空态是列表里的
 * 一段而不是整屏替换，否则「今天没作业」会把通往历史日期的入口一起吞掉。
 */
@Composable
private fun Content(
    state: HomeworkUiState,
    rowUi: RowInteraction,
    skillStatus: SkillInstaller.Status?,
    onSkillReinstall: () -> Unit,
    onSelectDate: (String) -> Unit,
    onToggleDone: (HomeworkItem) -> Unit,
    onDelete: (HomeworkItem) -> Unit,
    onLoadSample: () -> Unit,
    onImport: () -> Unit,
) {
    val listState = rememberLazyListState()

    // 列表一旦滚动，就把展开的删除按钮收回去：
    // 否则用户滚走之后，某一行会留在屏幕外「仍然展开」，再滚回来时状态莫名残存。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) rowUi.clearReveal()
    }

    // 兜底：拖动的行可能在中途被删除/移出列表，导致 DragInteraction.Stop
    // 永远不会回传，draggingId 就悬空了 —— 那会让所有行永久不可拖。
    // 这里在可见集合变化时检查一次，发现 draggingId 已不在列表中就直接释放。
    // 用 remember 缓存：否则每次重组都要 flatMap + map + 两次 toSet。
    val visibleIds = remember(state.groups, state.overdue) {
        buildSet {
            state.groups.forEach { group -> group.items.forEach { add(it.id) } }
            state.overdue.forEach { add(it.item.id) }
        }
    }
    LaunchedEffect(visibleIds, rowUi.draggingId) {
        val dragging = rowUi.draggingId
        if (dragging != null && dragging !in visibleIds) {
            rowUi.onDragStateChange(dragging, false)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        // 底部留白，否则最后一行被 FAB 挡住
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        item(key = "datebar") {
            DateBar(
                dates = state.availableDates,
                today = state.today,
                selectedDate = state.selectedDate,
                onSelectDate = onSelectDate
            )
        }

        if (state.isEmpty) {
            item(key = "empty") {
                EmptyState(
                    isToday = state.isToday,
                    onLoadSample = onLoadSample,
                    onImport = onImport,
                    modifier = Modifier.padding(top = 48.dp)
                )
            }
        }

        // 逾期栏（只在看今天时展示）
        if (state.isToday && state.overdue.isNotEmpty()) {
            item(key = "overdue") {
                OverdueSection(
                    entries = state.overdue,
                    rowUi = rowUi,
                    onToggleDone = onToggleDone,
                    onDelete = onDelete
                )
            }
        }

        // 当日清单
        if (state.groups.isNotEmpty()) {
            item(key = "todayHeader") {
                SectionHeader(text = if (state.isToday) "今日作业" else "当日作业")
            }
            item(key = "todayList") {
                TodayListSection(
                    groups = state.groups,
                    rowUi = rowUi,
                    onToggleDone = onToggleDone,
                    onDelete = onDelete
                )
            }
        }

        item(key = "skill") {
            SkillCard(status = skillStatus, onReinstall = onSkillReinstall)
        }
    }
}

/** 顶部日期切换条。今天永远可选，数据库里存在过的日期都会出现。 */
@Composable
private fun DateBar(
    dates: List<String>,
    today: String,
    selectedDate: String,
    onSelectDate: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(dates, key = { it }) { date ->
            FilterChip(
                selected = date == selectedDate,
                onClick = { onSelectDate(date) },
                label = { Text(dateChipLabel(date, today)) },
                leadingIcon = if (date == today) {
                    {
                        Icon(
                            Icons.Rounded.Today,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null
            )
        }
    }
}

/** 当日清单的小标题。逾期栏自带红色标题，所以这里只服务当日清单。 */
@Composable
private fun SectionHeader(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}