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
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.filter
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
    // 是否允许拖拽。多指同拖时，父层会把除「第一根手指」外的所有行设为 false，
    // 从物理层面杜绝同时拉开两个抽屉。
    dragEnabled: Boolean = true,
    // 拖动起止上报（true=起手，false=松手或取消）
    onDragStateChange: (Boolean) -> Unit = {},
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    val currentOnDelete = rememberUpdatedState(onDelete)
    val currentOnRevealChange = rememberUpdatedState(onRevealChange)
    val currentOnLongPress = rememberUpdatedState(onLongPress)
    val currentOnDragStateChange = rememberUpdatedState(onDragStateChange)

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

    // isRevealed 会被 LaunchedEffect(interactionSource) 捕获。
    // 该 effect 的 key 只有 interactionSource，闭包里必须读最新值，
    // 否则永远是首次组合时的 isRevealed，起手占位判断就会错。
    val currentIsRevealed = rememberUpdatedState(isRevealed)

    // 是否正被手指拖动。拖动中一律不让互斥逻辑插手：
    // 此时 animateTo 会和用户手势抢同一个值，表现为抖动/突然跳位。
    // 松手后（isDragging 变 false）effect 会重跑一次，把拖动期间欠下的
    // 「收起」补做掉 —— 所以拖动中直接 return 是安全的。
    var isDragging by remember { mutableStateOf(false) }

    // 拖动交互源：用于在「手指刚开始拖」的瞬间抢占「拖动名额」（多指互斥），
    // 以及判断拖动何时结束。注意它**不**用来抢占展开态 —— 方向未定时还不能占，
    // 见下方按 offset 判方向的那个 effect。
    val interactionSource = remember { MutableInteractionSource() }

    // 展开态互斥：**只负责收起，永不主动展开**。
    //
    // 这里曾经是双向收敛（含 animateTo(Open)），埋了一个「右滑却把抽屉滑开」的坑：
    // 右滑同样会越过触摸阈值、派发 DragInteraction.Start，起手即占位会先把本行
    // 报成「正在展开」，松手时 effect 看到 isRevealed == true 就 animateTo(Open)，
    // 于是手指明明往右走，抽屉却自己滑出来（v1.7.2 反馈的「右滑被判断成左滑」）。
    //
    // 为什么单向就够：
    //   1) 本行被父层标记为展开，只可能来自「本行自己在拖动中往左动过」的上报
    //      （见下方按 offset 判方向的那个 effect），落点因此必须由手势决定，
    //      互斥 effect 不该插手；
    //   2) 手势确实把行关掉了（右滑关闭）时，由 snapshotFlow(settledValue) 反向
    //      通知 onRevealChange(false)，父层自己会清掉 revealedId，不需要 effect 代劳；
    //   3) 「父层要收起」必须执行，所以收起分支保留。
    LaunchedEffect(isRevealed, selectionMode, isDragging) {
        // 拖动期间一律让开：手势正在掌控 offset，animateTo 会与它抢值（v1.7.1）
        if (isDragging) return@LaunchedEffect
        // 父层仍希望本行展开 → 落点听手势的，不干预
        if (isRevealed && !selectionMode) return@LaunchedEffect
        // 父层要收起（含「别人抢占了展开态」「进了多选」）→ 必须收
        if (state.currentValue != RowAnchor.Settled || state.targetValue != RowAnchor.Settled) {
            state.animateTo(RowAnchor.Settled)
        }
    }

    // 拖动状态上报给父层：占用「拖动中」名额，其余行随即被禁用拖拽。
    //
    // 这里用 DragInteraction.Start：手指一按下、拖动真正开始的那一刻就会发出，
    // 早于任何阈值判断。多指同时拖时，每一行在起手瞬间都试图抢 draggingId，
    // 父层是单值，后来者覆盖先前者，先前者立刻被物理禁用 —— 两根手指按住不放
    // 也不可能同时拉开两个抽屉。
    //
    // 注意：这里**只**占「拖动名额」，不占「展开名额」。占展开名额必须等到
    // 方向确定了再报（见下方按 offset 判方向的 effect），否则一次右滑就会
    // 抢着把别人的抽屉收起来（v1.7.2）。
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isDragging = true
                    currentOnDragStateChange.value(true)
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    isDragging = false
                    currentOnDragStateChange.value(false)
                }
                else -> Unit
            }
        }
    }

    // 「起手即占位」——但**按方向**占位：只有真的往左拖动过，才宣称「现在是我在展开」。
    //
    // 为什么不能在 DragInteraction.Start 里直接占位：左右两个方向都会越过触摸
    // 阈值、都会派发 Start。一次右滑如果也去占位，父层的 revealedId 会被抢走，
    // 别人展开着的抽屉就无端收起，观感正是「右滑被判成了左滑」。
    //
    // offset 由库钳制在 [-88dp, 0f]（AnchoredDraggableState.newOffsetForDelta 内部
    // coerceIn(minPosition, maxPosition)），所以「offset < 0」与「手指在往左拖」
    // 严格等价，右滑永远不可能触发。NaN 参与比较恒为 false，首帧也安全。
    // 用 1px 而不是 0f 作阈值：避免手指几乎没动就被判成左滑。
    //
    // 订阅 offset 不会引起重组：snapshotFlow 只在协程里读状态，不建立组合期依赖。
    LaunchedEffect(state) {
        snapshotFlow { state.offset < -1f }
            .filter { it }
            .collect {
                // 每次「从非左滑变成左滑」时确认一次父层已知晓，重复上报由父层幂等吸收
                if (!currentIsRevealed.value) currentOnRevealChange.value(true)
            }
    }

    // 拖动结束后，按真实落点纠正一次：
    // 没过阈值就回弹、或者右滑关掉的情况，都要把「到底开着还是关着」如实回报给父层。
    //
    // 这里必须读 currentIsRevealed 而不是直接读 isRevealed：本 effect 的 key 只有
    // state，闭包里的 isRevealed 会永远停在首次组合时的值（通常 false），
    // 结果是「关掉了却不回报 false」，父层的 revealedId 会一直残留。
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.collect { settled ->
            val open = settled == RowAnchor.Open
            if (open != currentIsRevealed.value) {
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
                    enabled = !selectionMode && dragEnabled,
                    interactionSource = interactionSource,
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
