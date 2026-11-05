package io.github.lzm222.todolist.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lzm222.todolist.data.HomeworkItem
import io.github.lzm222.todolist.skill.SkillInstaller
import io.github.lzm222.todolist.ui.HomeworkUiState
import io.github.lzm222.todolist.ui.HomeworkViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 把 2025-06-01 显示成 6月1日 周日。 */
private fun prettyDate(iso: String): String = runCatching {
    val d = LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
    val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[d.dayOfWeek.value - 1]
    "${d.monthValue}月${d.dayOfMonth}日 $week"
}.getOrDefault(iso)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkScreen(viewModel: HomeworkViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showImport by remember { mutableStateOf(false) }

    // ---- 以下三个都是「纯 UI 状态」，刻意放在 Screen 而不是 ViewModel ----
    // 多选态、当前展开删除按钮的行、待撤销的删除记录，都不涉及持久化，
    // 也不参与业务规则，放进 ViewModel 只会让状态聚合变复杂。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var revealedId by remember { mutableStateOf<Long?>(null) }
    // 批量删除前的二次确认：多选误触的代价是整批丢失，值得多一步
    var showBatchConfirm by remember { mutableStateOf(false) }
    // 待撤销项改成列表：单条删除是 1 个元素，批量删除是 N 个，撤销走同一条路径
    var lastDeleted by remember { mutableStateOf<List<HomeworkItem>>(emptyList()) }

    // 一次性消息（导入结果 / 删除提示 / Skill 状态）
    LaunchedEffect(message) {
        val msg = message ?: return@LaunchedEffect
        // 只有「已删除」这类消息才提供撤销；其余消息清空待撤销项，避免误撤销上一条
        val undoable = msg.startsWith("已删除")
        val pending = if (undoable) lastDeleted else emptyList()
        if (!undoable) lastDeleted = emptyList()

        val result = snackbarHostState.showSnackbar(
            message = msg,
            actionLabel = if (pending.isNotEmpty()) "撤销" else null,
            withDismissAction = true,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed && pending.isNotEmpty()) {
            viewModel.undoDeleteBatch(pending)
        }
        // 无论用户点撤销、关闭还是超时，都要清空，防止旧条目残留到下一次提示
        if (undoable) lastDeleted = emptyList()
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
        val targets = state.allItems.filter { it.id in selectedIds }
        BatchDeleteConfirmDialog(
            count = targets.size,
            onDismiss = { showBatchConfirm = false },
            onConfirm = {
                lastDeleted = targets
                viewModel.deleteBatch(targets)
                showBatchConfirm = false
                // 删完必须退出多选，否则会停在一个「零选中」的多选空壳里
                selectionMode = false
                selectedIds = emptySet()
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // 多选态把标题栏整体换成操作栏：这时候用户关心的是「选了几项、能做什么」，
            // 而不是日期。两个分支共用同一个 TopAppBar 位置，避免布局跳动。
            if (selectionMode) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    allSelected = state.selectableIds.isNotEmpty() &&
                        selectedIds.containsAll(state.selectableIds),
                    onToggleSelectAll = {
                        selectedIds = if (selectedIds.containsAll(state.selectableIds)) {
                            emptySet()
                        } else {
                            state.selectableIds.toSet()
                        }
                    },
                    onDelete = { showBatchConfirm = true },
                    onCancel = {
                        selectionMode = false
                        selectedIds = emptySet()
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("作业清单", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = prettyDate(state.selectedDate),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        if (!state.isToday) {
                            TextButton(onClick = { viewModel.backToToday() }) { Text("回到今天") }
                        }
                        IconButton(onClick = { showImport = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = "导入作业")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
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
                    onSelectDate = {
                        // 切日期等于换了一屏内容，展开态与选中态都失去意义，直接清空
                        revealedId = null
                        viewModel.selectDate(it)
                    },
                    onToggleDone = { viewModel.toggleDone(it) },
                    onDelete = {
                        lastDeleted = listOf(it)
                        revealedId = null
                        viewModel.delete(it)
                    },
                    onLoadSample = { viewModel.loadSampleData() },
                    onImport = { showImport = true },
                    revealedId = revealedId,
                    onRevealChange = { id, open ->
                        revealedId = if (open) id else if (revealedId == id) null else revealedId
                    },
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onToggleSelect = { item ->
                        selectedIds = if (item.id in selectedIds) selectedIds - item.id
                        else selectedIds + item.id
                    },
                    onLongPress = { item ->
                        // 长按进入多选，并把手滑后的展开态收起来，避免两种交互状态叠在一起
                        selectionMode = true
                        selectedIds = selectedIds + item.id
                        revealedId = null
                    }
                )
            }
        }
    }
}
@Composable
private fun Content(
    state: HomeworkUiState,
    onSelectDate: (String) -> Unit,
    onToggleDone: (HomeworkItem) -> Unit,
    onDelete: (HomeworkItem) -> Unit,
    onLoadSample: () -> Unit,
    onImport: () -> Unit,
    revealedId: Long?,
    onRevealChange: (Long, Boolean) -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelect: (HomeworkItem) -> Unit,
    onLongPress: (HomeworkItem) -> Unit
) {
    val listState = rememberLazyListState()

    // 列表一旦滚动，就把展开的删除按钮收回去：
    // 否则用户滚走之后，某一行会留在屏幕外「仍然展开」，再滚回来时状态莫名残存。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && revealedId != null) {
            onRevealChange(revealedId, false)
        }
    }


    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        // 日期切换条
        item(key = "datebar") {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.availableDates, key = { it }) { date ->
                    FilterChip(
                        selected = date == state.selectedDate,
                        onClick = { onSelectDate(date) },
                        label = { Text(if (date == state.today) "今天" else prettyDate(date)) },
                        leadingIcon = if (date == state.today) {
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

        // 空态作为列表内的一段内容展示，而不是整屏替换：
        // 日期条必须始终可见，否则"今天没有作业"会把通往历史日期的入口一起吞掉，
        // 用户会误以为整个清单是空的（实际昨天/前天的任务还在数据库里）。
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
                    onToggleDone = onToggleDone,
                    onDelete = onDelete,
                    revealedId = revealedId,
                    onRevealChange = onRevealChange,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onToggleSelect = onToggleSelect,
                    onLongPress = onLongPress
                )
            }
        }

        // 当日清单
        if (state.groups.isNotEmpty()) {
            item(key = "todayHeader") {
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
                        text = if (state.isToday) "今日作业" else "当日作业",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            item(key = "todayList") {
                TodayListSection(
                    groups = state.groups,
                    onToggleDone = onToggleDone,
                    onDelete = onDelete,
                    revealedId = revealedId,
                    onRevealChange = onRevealChange,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onToggleSelect = onToggleSelect,
                    onLongPress = onLongPress
                )
            }
        }

        // Skill 状态卡片
        item(key = "skill") {
            SkillCard()
        }
    }
}

/**
 * 多选态的标题栏。
 *
 * 选中数为 0 时禁用删除按钮 —— 避免用户按下去毫无反馈。
 * 「全选」按当前可见条目算，逾期栏在历史日期不渲染时自然不会进集合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = if (selectedCount == 0) "选择作业" else "已选 $selectedCount 项",
                style = MaterialTheme.typography.titleMedium
            )
        },
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = "退出多选")
            }
        },
        actions = {
            IconButton(onClick = onToggleSelectAll) {
                Icon(
                    Icons.Rounded.SelectAll,
                    contentDescription = if (allSelected) "取消全选" else "全选"
                )
            }
            IconButton(onClick = onDelete, enabled = selectedCount > 0) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除所选",
                    tint = if (selectedCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    )
}

/** 批量删除的二次确认。文案只给条数，不枚举内容（可能横跨多个科目）。 */
@Composable
private fun BatchDeleteConfirmDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 $count 项作业？") },
        text = { Text("删除后可以在提示条里点「撤销」找回。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SkillCard() {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val status = remember(version) { SkillInstaller.install(context) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("导入 Skill", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (status.installed) {
                    "已安装到 ${status.targetPath}"
                } else {
                    status.message ?: "尚未安装"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status.installed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {
                        if (status.needsPermission) {
                            SkillInstaller.openPermissionSettings(context)
                        } else {
                            version++
                        }
                    },
                    label = { Text(if (status.needsPermission) "去授权" else "重新安装") }
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    isToday: Boolean,
    onLoadSample: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.FileDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isToday) "今天还没有作业" else "这一天没有作业记录",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "点右下角「导入」粘贴作业 JSON，或先看看示例数据长什么样。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onLoadSample, label = { Text("加载示例数据") })
            AssistChip(onClick = onImport, label = { Text("导入 JSON") })
        }
    }
}