package io.github.lzm222.todolist.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lzm222.todolist.data.HomeworkItem
import io.github.lzm222.todolist.ui.SubjectGroup

/**
 * 当日清单：按科目分组展示。
 *
 * 多选与「单行展开」都是 UI 层私有状态，由 [HomeworkScreen] 持有并通过下面的参数下发：
 * - [revealedId]：当前展开删除按钮的行 id（互斥，同一时刻只有一行展开）
 * - [selectionMode] / [selectedIds]：多选态
 */
@Composable
fun TodayListSection(
    groups: List<SubjectGroup>,
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
    Column(modifier = modifier.fillMaxWidth()) {
        groups.forEach { group ->
            // 分组本身也用 key 包裹：某一科被删空后，避免下一组复用它的滑动状态
            key("group-" + group.subject) {
                SubjectHeader(subject = group.subject, count = group.items.size)
            }
            group.items.forEach { item ->
                // 用条目 id 作为稳定 key：滑动删除时 Compose 才能准确识别"这一行",
                // 而不是把状态错配到相邻的同类组件上（那会导致动画卡住/串行）
                key("item-" + item.id) {
                    HomeworkItemRow(
                        item = item,
                        onToggleDone = { onToggleDone(item) },
                        onDelete = { onDelete(item) },
                        isRevealed = revealedId == item.id,
                        onRevealChange = { open -> onRevealChange(item.id, open) },
                        dragEnabled = draggingId == null || draggingId == item.id,
                        onDragStateChange = { dragging -> onDragStateChange(item.id, dragging) },
                        selectionMode = selectionMode,
                        isSelected = item.id in selectedIds,
                        onToggleSelect = { onToggleSelect(item) },
                        onLongPress = { onLongPress(item) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SubjectHeader(subject: String, count: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    ) {
        Text(
            text = "$subject · $count 项",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}