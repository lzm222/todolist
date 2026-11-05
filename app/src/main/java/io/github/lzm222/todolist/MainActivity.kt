package io.github.lzm222.todolist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.lzm222.todolist.data.AppDatabase
import io.github.lzm222.todolist.data.HomeworkRepository
import io.github.lzm222.todolist.skill.SkillInstaller
import io.github.lzm222.todolist.ui.HomeworkViewModel
import io.github.lzm222.todolist.ui.screens.HomeworkScreen
import io.github.lzm222.todolist.ui.theme.TodoListTheme

class MainActivity : ComponentActivity() {
    /** 存储权限/所有文件访问状态变更的版本号，用来驱动 Compose 重组。 */
    private val permissionVersion = mutableIntStateOf(0)

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionVersion.intValue++
        }

    private val viewModel: HomeworkViewModel by viewModels {
        val db = AppDatabase.get(applicationContext)
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeworkViewModel(HomeworkRepository(db.homeworkDao())) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensureStoragePermission()
        // 首次进入时尝试把内置 Skill 安装到 /sdcard/Download/Operit/skills/
        SkillInstaller.install(applicationContext)

        setContent {
            TodoListTheme {
                // 读取 permissionVersion，权限变化后重组并刷新 Skill 卡片状态
                val version = permissionVersion.intValue
                key(version) {
                    HomeworkScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统「所有文件访问」设置页返回时刷新状态，并重试安装 Skill
        permissionVersion.intValue++
        if (SkillInstaller.hasStoragePermission(this)) {
            SkillInstaller.install(applicationContext)
        }
    }

    /** Android 10 及以下用普通存储权限；11+ 只能引导用户去系统设置里开（由 SkillCard 的「去授权」触发）。 */
    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(perm)
        }
    }
}