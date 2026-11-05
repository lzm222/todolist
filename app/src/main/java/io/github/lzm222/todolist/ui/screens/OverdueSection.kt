package io.github.lzm222.todolist.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lzm222.todolist.data.HomeworkItem
import io.github.lzm222.todolist.ui.OverdueEntry

/** 逾期栏：当天未完成、日期早于今天的作业单独成栏，标注逾期天数。 */
@Composable
fun OverdueSection(
    entries: List<OverdueEntry>,
    onToggleDone: (HomeworkItem) -> Unit,
    onDelete: (HomeworkItem) -> Unit,
    modifier: Modifier = Modifier,
    revealedId: Long? = null,
    draggingId: Long? = null,
    onRevealChange: (Long, Boolean) -> Unit = { _, _ -> },
    onDragStateChange: (Long, Boolean) -> Unit = { _, _ -> },
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelect: (HomeworkItem) -> Unit = {},
    onLongPress: (HomeworkItem) -> Unit = {},
) {
    if (entries.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "逾期作业",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "${entries.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        entries.forEach { entry ->
            // 稳定 key：逾期行也参与滑动删除，必须用 item.id 绑定组件身份
            key("overdue-" + entry.item.id) {
                OverdueRow(
                    entry = entry,
                    onToggleDone = { onToggleDone(entry.item) },
                    onDelete = { onDelete(entry.item) },
                    isRevealed = revealedId == entry.item.id,
                    onRevealChange = { open -> onRevealChange(entry.item.id, open) },
                    dragEnabled = draggingId == null || draggingId == entry.item.id,
                    onDragStateChange = { dragging -> onDragStateChange(entry.item.id, dragging) },
                    selectionMode = selectionMode,
                    isSelected = entry.item.id in selectedIds,
                    onToggleSelect = { onToggleSelect(entry.item) },
                    onLongPress = { onLongPress(entry.item) },
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun OverdueRow(
    entry: OverdueEntry,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit,
    isRevealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    dragEnabled: Boolean,
    onDragStateChange: (Boolean) -> Unit,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = entry.item.subject,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
            )
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "逾期 ${entry.days} 天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        HomeworkItemRow(
            item = entry.item,
            onToggleDone = onToggleDone,
            onDelete = onDelete,
            isRevealed = isRevealed,
            onRevealChange = onRevealChange,
            dragEnabled = dragEnabled,
            onDragStateChange = onDragStateChange,
            selectionMode = selectionMode,
            isSelected = isSelected,
            onToggleSelect = onToggleSelect,
            onLongPress = onLongPress,
        )
    }
}