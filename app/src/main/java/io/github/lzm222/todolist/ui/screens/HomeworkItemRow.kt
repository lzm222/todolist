package io.github.lzm222.todolist.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.lzm222.todolist.data.HomeworkItem

/** 左滑露出的删除按钮宽度，固定 88dp：恰好容纳图标 + 「删除」二字 */
private val DeleteButtonWidth = 88.dp

/**
 * 单条作业。
 * - 勾选 = 完成（保留在同一列表，划线 + 灰化），**不是删除**
 * - 左滑 = **仅展开**删除按钮（行不消失）；点击按钮才真正删除
 * - 右滑 = 完全不响应（`enableDismissFromStartToEnd = false`）
 * - 长按 = 进入多选模式
 *
 * 手势严格化的关键在 `positionalThreshold`：库默认是固定 56dp，
 * 对「上下滚动时的斜向抖动」太宽容，故改为约屏宽 30%。
 *
 * 与旧实现（滑开即删除、删完 reset）的差异：
 * 1. 行**允许长期停在 EndToStart**，因为那是「展开」而非「已删除」。
 * 2. `onDismiss` 刻意留空——一旦在里面调 onDelete 就退回「滑开即删」的误触行为。
 * 3. 展开态互斥由父层的 [revealKey] / [isRevealed] / [onRevealChange] 统一管理，
 *    本组件只负责在「自己不该是展开行」时 reset 回 Settled。
 */
@Composable
fun HomeworkItemRow(
    item: HomeworkItem,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isRevealed: Boolean = false,
    onRevealChange: (Boolean) -> Unit = {},
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    val currentOnDelete = rememberUpdatedState(onDelete)
    val currentOnRevealChange = rememberUpdatedState(onRevealChange)
    val currentOnLongPress = rememberUpdatedState(onLongPress)

    // 阈值：屏宽 30%，明显比默认 56dp 严格，上下滚动时的误触基本消失
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val density = LocalDensity.current
    val thresholdPx = with(density) { (screenWidthDp.dp * 0.3f).toPx() }

    val dismissState = rememberSwipeToDismissBoxState(
        initialValue = SwipeToDismissBoxValue.Settled,
        positionalThreshold = { thresholdPx },
    )

    // 展开态互斥：父层收起了我 / 别人展开了 / 进入多选 → 复位
    LaunchedEffect(isRevealed, selectionMode) {
        val shouldBeOpen = isRevealed && !selectionMode
        if (!shouldBeOpen && dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.reset()
        }
    }

    // 把自己滑开的动作上报给父层（父层据此收起其他行）
    LaunchedEffect(dismissState.currentValue) {
        val open = dismissState.currentValue == SwipeToDismissBoxValue.EndToStart
        if (open != isRevealed) {
            currentOnRevealChange.value(open)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth(),
        enableDismissFromStartToEnd = false,   // 需求②：右滑不响应
        enableDismissFromEndToStart = true,    // 仅左滑
        gesturesEnabled = !selectionMode,      // 多选时禁用滑动，避免手势冲突
        // 需求①：留空，滑开不删除。切勿在此调用 onDelete
        onDismiss = { },
        backgroundContent = {
            DeleteBackground(
                enabled = dismissState.currentValue == SwipeToDismissBoxValue.EndToStart,
                onDelete = { currentOnDelete.value() },
            )
        },
    ) {
        ItemContent(
            item = item,
            selectionMode = selectionMode,
            isSelected = isSelected,
            onToggleDone = onToggleDone,
            onToggleSelect = onToggleSelect,
            onLongPress = { currentOnLongPress.value() },
        )
    }
}

/** 右对齐的删除按钮：整块可点、有涟漪，语义合并为一个 Button */
@Composable
private fun DeleteBackground(
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(
            modifier = Modifier
                .width(DeleteButtonWidth)
                .fillMaxHeight()
                .padding(vertical = 2.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.error)
                .clickable(enabled = enabled, onClick = onDelete)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "删除",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onError,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** 行内容：多选时左侧显示选中框且点击=选中，否则点击无关、长按进多选 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemContent(
    item: HomeworkItem,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleDone: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
) {
    val indication = LocalIndication.current
    Surface(
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    indication = indication,
                    interactionSource = null,
                    onClick = {
                        if (selectionMode) onToggleSelect()
                    },
                    onLongClick = onLongPress,
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            if (selectionMode) {
                // 多选：勾选 = 选中（与「完成」语义分离）
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                )
            } else {
                Checkbox(
                    checked = item.isDone,
                    onCheckedChange = { onToggleDone() },
                )
            }
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
                    .padding(vertical = 12.dp),
            )
        }
    }
}