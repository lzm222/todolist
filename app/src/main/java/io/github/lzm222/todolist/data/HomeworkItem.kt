package io.github.lzm222.todolist.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条作业。
 *
 * @param dueDate 作业所属日期，ISO 格式 `yyyy-MM-dd`，当天清单按它筛选。
 * @param isDone  是否已完成。完成后条目仍留在所属日期的清单里（划线显示）。
 * @param deleted 软删除标记，用于"滑动删除 + 撤销"。所有查询都会过滤掉已删除项。
 */
@Entity(
    tableName = "homework_items",
    indices = [
        Index(value = ["dueDate", "subject", "content"]),
        Index(value = ["dueDate"]),
        Index(value = ["isDone"])
    ]
)
data class HomeworkItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val subject: String,
    val content: String,
    val dueDate: String,
    val isDone: Boolean = false,
    val doneAt: Long? = null,
    val deleted: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
