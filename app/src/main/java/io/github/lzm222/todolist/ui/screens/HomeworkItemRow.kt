package io.github.lzm222.todolist.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lzm222.todolist.data.HomeworkItem
import kotlin.math.roundToInt

/** 左滑露出的删除按钮宽度，固定 88dp：恰好容纳图标 + 「删除」二字 */
private val DeleteButtonWidth = 88.dp

/**
 * 行的滑动停靠位置。
 *
 * 只有两个状态，`Open` 的锚点位移**恰好等于按钮宽度**，
 * 所以左滑最多只露出按钮，不会像 `SwipeToDismissBox` 那样吸附到整屏宽的 dismiss 位。
 */
private enum class RowAnchor { Settled, Open }

/**
 * 单条作业。
 * - 勾选 = 完成（保留在同一列表，划线 + 灰化），**不是删除**
 * - 左滑 = **仅露出删除按钮**，行最多位移 [DeleteButtonWidth]；点击按钮才真正删除
 * - 右滑 = 完全不响应（锚点全为负值，向右拖会被 `End` 边界钳住）
 * - 长按 = 进入多选模式
 *
 * 为什么不用 `SwipeToDismissBox`：
 * 它的语义是「二态吸附（Settled / Dismissed）」，一旦越过阈值就吸附到
 * **整条滑出屏幕**的位置，无法停在「只露按钮」的中间态。改用 `anchoredDraggable`
 * 后位移量由我们给出，`Open` 锚点即 `-按钮宽度`，天然只露按钮。
 *
 * `positionalThreshold` 取「按钮宽度的一半」：拖动超过按钮一半才吸附展开，
 * 否则回弹，与常见的侧滑菜单手感一致。
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

    val density = LocalDensity.current
    val buttonWidthPx = with(density) { DeleteButtonWidth.toPx() }

    // 锚点必须**在组合期就建立**，不能只放在 LaunchedEffect 里：
    // 首帧布局早于 LaunchedEffect 执行，那之前 state.offset 是 NaN，
    // 而 Modifier.offset 的 lambda 会在布局阶段直接读它 → roundToInt() 崩溃。
    // 用 remember(buttonWidthPx) 保证同一帧内构造好锚点，密度变化时重建。
    val state: AnchoredDraggableState<RowAnchor> = remember(buttonWidthPx) {
        AnchoredDraggableState(
            initialValue = RowAnchor.Settled,
            anchors = DraggableAnchors {
                RowAnchor.Settled at 0f
                // Open 位移 = -按钮宽度，这就是「只露按钮」的保证
                RowAnchor.Open at -buttonWidthPx
            },
        )
    }
    // 阈值与动画规格按官方建议挂在 flingBehavior 上，而不是 state 构造器（后者已弃用）
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = state,
        positionalThreshold = { distance: Float -> distance * 0.5f },
        animationSpec = AnchoredDraggableDefaults.SnapAnimationSpec,
    )

    // 展开态互斥：父层收起了我 / 别人展开了 / 进入多选 → 复位
    LaunchedEffect(isRevealed, selectionMode) {
        val shouldOpen = isRevealed && !selectionMode
        val target = if (shouldOpen) RowAnchor.Open else RowAnchor.Settled
        if (state.currentValue != target || state.targetValue != target) {
            state.animateTo(target)
        }
    }

    // 把自己滑开的动作上报给父层（父层据此收起其他行）
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.collect { settled ->
            val open = settled == RowAnchor.Open
            if (open != isRevealed) {
                currentOnRevealChange.value(open)
            }
        }
    }

    // 关键：**不要**在这里读 state.offset。
    //
    // state.offset 是可观察值，在组合作用域里读它，会让整个 HomeworkItemRow
    // 在拖动期间每一帧都重组一次（ItemContent、Surface、图标、文字全部跟着重走），
    // 表现为滑动「一顿一顿」。正确做法是把读取下沉到真正需要它的最小作用域：
    // 用 graphicsLayer / offset 的 lambda 版 modifier —— 它们只在绘制/布局阶段求值，
    // 不触发重组，也不触发父层重新测量。
    //
    // 因此这里只把 state 与 buttonWidthPx 传下去，由 DeleteBackground 内部读。

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        // 背景按钮：随拖动进度淡入，只占按钮宽度那一条。
        // 传 state 而不是 progress：让 offset 的读取发生在 graphicsLayer 的
        // 绘制阶段 lambda 里，避免拖动期间触发重组。
        DeleteBackground(
            state = state,
            buttonWidthPx = buttonWidthPx,
            enabled = state.settledValue == RowAnchor.Open,
            onDelete = { currentOnDelete.value() },
        )

        // 前景内容：位移由 state.offset 决定，向右拖被 0f 锚点钳住
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset {
                    // lambda 内独立防守：布局阶段可能先于锚点就绪执行
                    val raw = state.offset
                    val dx: Int = if (raw.isNaN()) 0 else raw.roundToInt().coerceAtMost(0)
                    IntOffset(dx, 0)
                }
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    enabled = !selectionMode,
                    flingBehavior = flingBehavior,
                )
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
}

/**
 * 右对齐的删除按钮：整块可点、有涟漪；展开程度驱动淡入。
 *
 * 这里**刻意不收 `progress: Float`**，而是收 `state`：
 * `progress` 若在父层组合期算出来传下来，父层就会依赖 `state.offset`，
 * 拖动期间每一帧都重组整行，滑动就会「一顿一顿」。
 * 改成在 `graphicsLayer { }` 的绘制阶段 lambda 里读 offset，
 * 于是每帧只重绘这一层，不重组、不重新测量。
 */
@Composable
private fun DeleteBackground(
    state: AnchoredDraggableState<RowAnchor>,
    buttonWidthPx: Float,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    // 展开程度：offset 向左为负（区间 [-buttonWidthPx, 0]），取负归一化到 [0, 1]。
    // 漏掉负号会让值恒为负数，被 coerceIn 压成 0，按钮就永远不显形（v1.6.2 的坑）。
    val progressOf: () -> Float = {
        val raw = state.offset
        if (raw.isNaN() || buttonWidthPx <= 0f) 0f
        else (-raw / buttonWidthPx).coerceIn(0f, 1f)
    }

    Box(
        modifier = Modifier
            .width(DeleteButtonWidth)
            .fillMaxHeight()
            .graphicsLayer { alpha = progressOf() }
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
