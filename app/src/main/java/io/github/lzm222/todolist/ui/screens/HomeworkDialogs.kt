package io.github.lzm222.todolist.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 批量删除的二次确认。
 *
 * 多选误触的代价是整批丢失，值得多一步确认。
 * 文案只给条数，不枚举内容（选中的可能横跨多个科目）。
 */
@Composable
internal fun BatchDeleteConfirmDialog(
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