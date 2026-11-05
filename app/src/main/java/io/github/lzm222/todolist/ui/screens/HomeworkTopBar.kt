package io.github.lzm222.todolist.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val WeekLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 把 `2026-11-05` 显示成 `11月5日 周三`；解析失败时原样返回。
 *
 * 这是纯展示格式化，不含任何业务判断，所以留在 UI 层而不是 ViewModel。
 * 「今天 / 昨天」这类相对说法与所有动态文案仍由 ViewModel 生成。
 */
internal fun prettyDate(iso: String): String = runCatching {
    val d = LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
    "${d.monthValue}月${d.dayOfMonth}日 ${WeekLabels[d.dayOfWeek.value - 1]}"
}.getOrDefault(iso)

/** 日期条里的单片标签：今天固定显示「今天」，其余显示具体日期。 */
internal fun dateChipLabel(iso: String, today: String): String =
    if (iso == today) "今天" else prettyDate(iso)

/** 常规标题栏：应用名 + 当前日期，右侧「回到今天」与导入入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeworkTopBar(
    dateLabel: String,
    showBackToToday: Boolean,
    onBackToToday: () -> Unit,
    onImport: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text("作业清单", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            if (showBackToToday) {
                TextButton(onClick = onBackToToday) { Text("回到今天") }
            }
            IconButton(onClick = onImport) {
                Icon(Icons.Rounded.Add, contentDescription = "导入作业")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

/**
 * 多选态的标题栏。
 *
 * 与 [HomeworkTopBar] 共用同一个 `TopAppBar` 位置（由调用方二选一），避免布局跳动：
 * 进入多选时用户关心的是「选了几项、能做什么」，而不是日期。
 *
 * 选中数为 0 时禁用删除按钮 —— 避免用户按下去毫无反馈。
 * 「全选」按当前可见条目算，逾期栏在历史日期不渲染时自然不会进集合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
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