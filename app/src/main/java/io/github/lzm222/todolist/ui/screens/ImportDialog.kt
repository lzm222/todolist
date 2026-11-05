package io.github.lzm222.todolist.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lzm222.todolist.parse.HomeworkJsonParser

/** 导入弹窗：粘贴 / 读取剪贴板 / 格式化 / 报错提示，导入失败不清空输入。 */
@Composable
fun ImportDialog(
    onDismiss: () -> Unit,
    onImport: (String, onError: (String) -> Unit, onSuccess: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        val pasted = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        } else ""
        if (pasted.isBlank()) {
            error = "剪贴板里没有文本"
        } else {
            text = pasted
            error = null
        }
    }

    fun format() {
        val pretty = HomeworkJsonParser.prettify(text)
        if (pretty == null) {
            error = "无法格式化：当前内容不是合法的 JSON"
        } else {
            text = pretty
            error = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入今日作业") },
        text = {
            Column {
                Text(
                    text = "粘贴固定格式的 JSON，或用 Skill 把任意记录转换后再粘贴。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 280.dp),
                    placeholder = {
                        Text("{\n  \"date\": \"2025-06-01\",\n  \"subjects\": [\n    { \"name\": \"数学\", \"items\": [\"练习册 P12-14\"] }\n  ]\n}")
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    isError = error != null,
                    singleLine = false
                )

                if (error != null) {
                    Spacer(Modifier.padding(top = 6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 图标 + 文字必须合并成一个可点击区域：
                    // 之前图标是 IconButton、文字是裸 Text，点文字没有任何反应，很反直觉。
                    AssitTool(
                        text = "粘贴",
                        onClick = { pasteFromClipboard() }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    AssitTool(
                        text = "格式化",
                        onClick = { format() }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.FormatAlignLeft,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onImport(
                        text,
                        { msg -> error = msg },
                        { onDismiss() }
                    )
                }
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 图标 + 文字合一的轻量工具按钮。
 *
 * 用 [Modifier.clickable] 包住整个图标 + 文字区域，而不是「IconButton 配裸 Text」：
 * 这样点文字和点图标效果一致，触摸目标也更大更符合直觉。
 * 同时用 [Modifier.semantics] 给整块挂上 button 角色与 onClick 动作，
 * 让 TalkBack / UI 自动化能把整块识别成一个可点击按钮。
 */
@Composable
private fun AssitTool(
    text: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val semantics = Modifier.semantics(mergeDescendants = true) {
        role = Role.Button
        onClick(label = text) { onClick(); true }
    }

    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .then(semantics)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon()
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
