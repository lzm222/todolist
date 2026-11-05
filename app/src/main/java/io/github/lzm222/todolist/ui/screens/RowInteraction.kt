package io.github.lzm222.todolist.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.lzm222.todolist.data.HomeworkItem

/**
 * 行级交互状态的持有者：谁在展开、谁在拖动、多选态与选中集合。
 *
 * 为什么要有这个类：
 * 这四份状态原本是 `HomeworkScreen` 里的四个 `remember { mutableStateOf(...) }`，
 * 然后连同四个回调一起，经 `Content → TodayListSection / OverdueSection →
 * OverdueRow → HomeworkItemRow` 逐层透传 —— 每个中间层都要原样转发 8 个参数、
 * 共 3 遍。加一个交互状态就要动 4 个文件 7 处，且漏传一个就是「某个入口悄悄失效」。
 * 现在状态与改动它的方法收在一个类里，中间层只需拿一个 [RowInteraction]。
 *
 * 它**仍然是 UI 层私有状态**，不进 ViewModel、不进 `UiState`、不参与 `combine`：
 * 这条红线没有变（见 AGENTS.md §2、§6.4）。变的只是它的**携带方式**——
 * 从「4 份散装 state + 4 个手写回调」变成一个自带方法的小状态机。
 *
 * 所有 `var` 都对外只读（`private set`），只能通过这些方法改，
 * 于是「收起分支必须比对 id」这类易错逻辑只有一处实现。
 */
@Stable
class RowInteraction {

    /** 当前展开删除按钮的行。互斥：同一时刻最多一行。 */
    var revealedId by mutableStateOf<Long?>(null)
        private set

    /**
     * 当前正被手指拖动的行。存在期间其余行的拖拽被物理禁用
     * （`dragEnabled = false`），这是唯一能杜绝「两指同时拉开两个抽屉」的手段。
     */
    var draggingId by mutableStateOf<Long?>(null)
        private set

    var selectionMode by mutableStateOf(false)
        private set

    var selectedIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    /**
     * 某行上报「我展开了 / 我收起了」。
     *
     * 收起分支**必须**比对 id：旧行的延迟回调（`settledValue` 晚一拍才回传）
     * 不能把新行的展开态清掉。
     */
    fun onRevealChange(id: Long, open: Boolean) {
        revealedId = if (open) id else if (revealedId == id) null else revealedId
    }

    /** 某行上报拖动起止。同样只在 id 匹配时释放，避免旧行的回调误清新行。 */
    fun onDragStateChange(id: Long, dragging: Boolean) {
        draggingId = if (dragging) id else if (draggingId == id) null else draggingId
    }

    /** 收起所有抽屉。切日期、点击删除、开始滚动等入口共用。 */
    fun clearReveal() {
        revealedId = null
    }

    fun toggleSelect(item: HomeworkItem) {
        selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
    }

    /** 长按进多选，并顺手收起手滑打开的抽屉 —— 两种交互状态不该叠在一起。 */
    fun longPress(item: HomeworkItem) {
        selectionMode = true
        selectedIds = selectedIds + item.id
        revealedId = null
    }

    /** 退出多选。批量删除完、点标题栏关闭都走这里。 */
    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    /**
     * 是否已全选。
     *
     * 判据用「当前可见行」（[selectableIds]），不是全库：逾期栏在历史日期视角下
     * 根本不渲染，那时候它不该被选中，否则会出现「选中了看不见的东西」。
     */
    fun allSelected(selectableIds: List<Long>): Boolean =
        selectableIds.isNotEmpty() && selectedIds.containsAll(selectableIds)

    fun toggleSelectAll(selectableIds: List<Long>) {
        selectedIds = if (selectedIds.containsAll(selectableIds)) {
            emptySet()
        } else {
            selectableIds.toSet()
        }
    }
}
