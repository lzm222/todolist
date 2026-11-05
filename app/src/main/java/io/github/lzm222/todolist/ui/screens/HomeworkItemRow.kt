package io.github.lzm222.todolist.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.lzm222.todolist.data.HomeworkItem

/**
 * 单条作业。
 * - 勾选 = 完成（保留在同一列表，划线 + 灰化）
 * - 左/右滑动 = 删除（Snackbar 可撤销）
 *
 * 删除动画的要点：
 * 1. 滑到位后**不要**把它永久留在 dismissed 状态。真正让行消失的应该是数据变化，
 *    而不是 SwipeToDismissBox 的内部状态；否则数据没刷新（或撤销）时，
 *    组件会卡在"已滑开但还占着位置"的半死状态，再也不能滑回来。
 * 2. 所以这里在触发删除后，用一个 id 变化触发的 LaunchedEffect 把状态 reset 回 Settled。
 *    数据流随后把这一行移除；若用户撤销，行会以正常位置重新出现。
 */
@Composable
fun HomeworkItemRow(
    item: HomeworkItem,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 用 rememberUpdatedState 保证回调始终是最新的，避免闭包捕获旧 item
    val currentOnDelete = rememberUpdatedState(onDelete)

    val dismissState = rememberSwipeToDismissBoxState(
        initialValue = SwipeToDismissBoxValue.Settled,
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.Settled) {
                // 用户取消滑动，不需要处理
                false
            } else {
                // 先让动画播完（返回 true），删除动作放到 LaunchedEffect 里做，
                // 保证不会在滑动动画的中间态同步触发数据变更。
                true
            }
        }
    )

    // 状态变为「已滑开」时触发删除，并把状态复位，避免组件卡在 dismissed
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            currentOnDelete.value()
            // 复位：数据层会移除此行；若被撤销则行会以正常位置重新出现
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth(),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Checkbox(
                    checked = item.isDone,
                    onCheckedChange = { onToggleDone() }
                )
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (item.isDone) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (item.isDone) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp)
                )
            }
        }
    }
}