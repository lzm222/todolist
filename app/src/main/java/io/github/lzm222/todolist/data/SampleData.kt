package io.github.lzm222.todolist.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 空态下「加载示例数据」用的假数据：今天 / 昨天 / 前天各若干条，
 * 让用户一眼看到分组、划线、逾期栏三种状态。
 */
object SampleData {

    private val FMT = DateTimeFormatter.ISO_LOCAL_DATE

    fun build(today: LocalDate = LocalDate.now()): List<HomeworkItem> {
        val todayStr = today.format(FMT)
        val yest = today.minusDays(1).format(FMT)
        val beforeYest = today.minusDays(2).format(FMT)

        return listOf(
            HomeworkItem(subject = "语文", content = "背诵《岳阳楼记》第三段", dueDate = todayStr, sortOrder = 0),
            HomeworkItem(subject = "语文", content = "作文一篇：我的周末", dueDate = todayStr, sortOrder = 1),
            HomeworkItem(subject = "数学", content = "练习册 P42-44", dueDate = todayStr, sortOrder = 0),
            HomeworkItem(subject = "英语", content = "Unit 5 单词默写", dueDate = todayStr, sortOrder = 0, isDone = true, doneAt = System.currentTimeMillis()),

            HomeworkItem(subject = "数学", content = "错题本整理 10 题", dueDate = yest, sortOrder = 0),
            HomeworkItem(subject = "物理", content = "实验报告：测密度", dueDate = yest, sortOrder = 0),

            HomeworkItem(subject = "历史", content = "阅读课本 P88-92 并做笔记", dueDate = beforeYest, sortOrder = 0)
        )
    }
}