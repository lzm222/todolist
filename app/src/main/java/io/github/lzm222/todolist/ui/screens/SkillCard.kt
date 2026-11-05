package io.github.lzm222.todolist.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.lzm222.todolist.skill.SkillInstaller

/**
 * Skill 安装状态卡片。
 *
 * 状态由 [HomeworkScreen] 提供，而不是在这里自己算，原因有两条：
 *
 * 1. **这是列表里的一个 item**。`LaunchedEffect` / `produceState` 跟着 item 的
 *    生命周期走，滚出屏幕再滚回来会**重跑一遍**；而 `SkillInstaller.install()`
 *    要读 assets、读目标文件，再算两次 SHA-256 —— 那是正经的磁盘 IO。
 *    放在 Screen 层（不随滚动销毁）就只在首帧算一次。
 * 2. **不能在组合期做 IO**。原来的 `remember(version) { SkillInstaller.install(context) }`
 *    是在组合阶段同步执行的，等于主线程读文件；[status] 为 null 期间的
 *    「正在检查…」就是把它挪到后台线程的代价，也是它本来就该有的行为。
 *
 * [onReinstall] 由父层递增一个计数触发重算，卡片本身不持有版本号。
 */
@Composable
internal fun SkillCard(
    status: SkillInstaller.Status?,
    onReinstall: () -> Unit,
) {
    val context = LocalContext.current

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("导入 Skill", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    status == null -> "正在检查…"
                    status.installed -> "已安装到 ${status.targetPath}"
                    else -> status.message ?: "尚未安装"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status?.installed == true) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {
                        if (status?.needsPermission == true) {
                            SkillInstaller.openPermissionSettings(context)
                        } else {
                            // 重新算一次安装状态；权限变更走 MainActivity 的
                            // permissionVersion → key() 重建整屏，不需要卡片自己感知
                            onReinstall()
                        }
                    },
                    label = {
                        Text(if (status?.needsPermission == true) "去授权" else "重新安装")
                    }
                )
            }
        }
    }
}