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
 * 这里**只管分组与排版**，行级交互（展开 / 拖动 / 多选）整包交给 [rowUi]：
 * 之前是 8 个参数逐个往下透传到 `HomeworkItemRow`，加一个交互状态就要改 4 个文件，
 * 且漏传一处就是「某个入口悄悄失效」。现在中间层只认一个对象。
 */
@Composable
fun TodayListSection(
    groups: List<SubjectGroup>,
    rowUi: RowInteraction,
    onToggleDone: (HomeworkItem) -> Unit,
    onDelete: (HomeworkItem) -> Unit,
    modifier: Modifier = Modifier,
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
                    InteractiveHomeworkRow(
                        item = item,
                        rowUi = rowUi,
                        onToggleDone = { onToggleDone(item) },
                        onDelete = { onDelete(item) },
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
