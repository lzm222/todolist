package io.github.lzm222.todolist.skill

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.security.MessageDigest

/**
 * 把打包在 assets 里的 Skill 释放到 Operit 的 skills 目录，
 * 让「作业记录 → 导入 JSON」这套规则能在任意设备上复用。
 *
 * 目标：/sdcard/Download/Operit/skills/homework-import/SKILL.md
 */
object SkillInstaller {

    const val SKILL_NAME = "homework-import"
    private const val ASSET_PATH = "skills/$SKILL_NAME/SKILL.md"
    private const val SUB_DIR = "Download/Operit/skills/$SKILL_NAME"

    data class Status(
        val installed: Boolean,
        val needsPermission: Boolean,
        val targetPath: String,
        val message: String? = null
    )

    private fun targetFile(): File =
        File(Environment.getExternalStorageDirectory(), "$SUB_DIR/SKILL.md")

    fun targetPath(): String = targetFile().absolutePath

    /** Android 11+ 需要「所有文件访问权限」才能写 Download 目录。 */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /** 跳到系统的权限页，让用户手动授权（MANAGE_EXTERNAL_STORAGE 不会弹普通对话框）。 */
    fun openPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        } else {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 读取 assets 中的 Skill 正文，用于「手动导出」。 */
    fun readAsset(context: Context): String = context.assets.open(ASSET_PATH)
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

    /**
     * 尝试安装/更新 Skill。内容不变则跳过。
     * @return 安装结果状态（含失败原因，供 UI 提示）
     */
    fun install(context: Context): Status {
        val target = targetFile()
        val assetText = runCatching { readAsset(context) }.getOrElse {
            return Status(false, false, target.absolutePath, "读取内置 Skill 失败：${it.message}")
        }

        // 已是最新
        if (target.exists()) {
            val existing = runCatching { target.readText() }.getOrNull()
            if (existing != null && sha256(existing) == sha256(assetText)) {
                return Status(true, false, target.absolutePath, null)
            }
        }

        if (!hasStoragePermission(context)) {
            return Status(
                installed = false,
                needsPermission = true,
                targetPath = target.absolutePath,
                message = "需要存储权限才能自动更新 Skill 文件"
            )
        }

        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(assetText, Charsets.UTF_8)
            Status(true, false, target.absolutePath, null)
        }.getOrElse {
            Status(false, false, target.absolutePath, "写入 Skill 失败：${it.message}")
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}