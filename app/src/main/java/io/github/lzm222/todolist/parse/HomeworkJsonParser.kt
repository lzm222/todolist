package io.github.lzm222.todolist.parse

import io.github.lzm222.todolist.data.HomeworkItem
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * 把「固定结构的 JSON」解析成待入库的作业列表。
 *
 * 目标格式：
 * ```json
 * {
 *   "date": "2025-06-01",
 *   "subjects": [
 *     { "name": "数学", "items": ["练习册 P12-14", "订正卷子"] },
 *     { "name": "语文", "items": ["背诵第三段"] }
 *   ]
 * }
 * ```
 *
 * 也接受用一个数组包裹上述对象的写法：`[ { ... }, { ... } ]`（多天一起导入）。
 */
object HomeworkJsonParser {

    private val DATE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    sealed interface Result {
        data class Success(val items: List<HomeworkItem>, val dates: List<String>) : Result
        data class Error(val message: String, val line: Int? = null) : Result
    }

    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Error("内容为空，请先粘贴 JSON")

        val root: Any = try {
            JSONTokener(text).nextValue()
        } catch (e: Exception) {
            return Result.Error("JSON 语法错误：${e.message ?: "无法解析"}", locateErrorLine(text))
        }

        // 统一成「一组待导入块」
        val blocks = when (root) {
            is JSONObject -> listOf(root)
            is JSONArray -> buildList {
                for (i in 0 until root.length()) {
                    val obj = root.optJSONObject(i)
                        ?: return Result.Error("第 ${i + 1} 个元素不是对象 { }")
                    add(obj)
                }
            }
            else -> return Result.Error("顶层必须是对象 { } 或对象数组 [ ]")
        }

        if (blocks.isEmpty()) return Result.Error("没有可导入的内容")

        val items = mutableListOf<HomeworkItem>()
        val dates = linkedSetOf<String>()
        val orderCounter = mutableMapOf<String, Int>()

        for ((blockIndex, obj) in blocks.withIndex()) {
            val prefix = if (blocks.size > 1) "第 ${blockIndex + 1} 个作业块：" else ""

            val dateRaw = obj.optString("date", "").trim()
            if (dateRaw.isEmpty()) return Result.Error("${prefix}缺少 date 字段（格式 yyyy-MM-dd）")
            val date = try {
                LocalDate.parse(dateRaw, DATE_FMT)
            } catch (e: DateTimeParseException) {
                return Result.Error("${prefix}date 格式不对：\"$dateRaw\"，应为 yyyy-MM-dd 且是真实日期")
            }
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            dates += dateStr

            if (!obj.has("subjects")) return Result.Error("${prefix}缺少 subjects 字段")
            val subjects = obj.optJSONArray("subjects")
                ?: return Result.Error("${prefix}subjects 必须是数组 [ ]")

            if (subjects.length() == 0) return Result.Error("${prefix}subjects 为空，没有作业可导入")

            for (i in 0 until subjects.length()) {
                val subjectObj = subjects.optJSONObject(i)
                    ?: return Result.Error("${prefix}subjects[$i] 不是对象 { }")

                val subjectName = subjectObj.optString("name", "").trim()
                if (subjectName.isEmpty()) return Result.Error("${prefix}subjects[$i].name 不能为空")

                val itemsArr = subjectObj.optJSONArray("items")
                    ?: return Result.Error("${prefix}subjects[$i].items 必须是数组 [ ]")

                for (j in 0 until itemsArr.length()) {
                    val content = itemsArr.opt(j)
                    val contentStr = when (content) {
                        is String -> content.trim()
                        null, JSONObject.NULL -> ""
                        else -> return Result.Error("${prefix}subjects[$i].items[$j] 必须是字符串")
                    }
                    if (contentStr.isEmpty()) continue

                    val key = "$dateStr|$subjectName"
                    val order = orderCounter.getOrDefault(key, 0)
                    orderCounter[key] = order + 1

                    items += HomeworkItem(
                        subject = subjectName,
                        content = contentStr,
                        dueDate = dateStr,
                        sortOrder = order
                    )
                }
            }
        }

        if (items.isEmpty()) return Result.Error("所有 items 都是空的，没有可导入的作业")
        return Result.Success(items, dates.toList())
    }

    /** 把压缩在一行的 JSON 美化，方便肉眼检查。失败返回 null。 */
    fun prettify(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            when (val v = JSONTokener(text).nextValue()) {
                is JSONObject -> v.toString(2)
                is JSONArray -> v.toString(2)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 粗略定位 JSON 语法错误所在行：用异常信息里的 index 反查。 */
    private fun locateErrorLine(text: String): Int? {
        // org.json 的异常消息不带 offset，这里退化为：找第一个不成对的括号位置
        var depth = 0
        var inString = false
        var escaped = false
        var line = 1
        var openLine = 1
        for (ch in text) {
            if (ch == '\n') line++
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' || ch == '[' -> {
                    depth++
                    openLine = line
                }
                ch == '}' || ch == ']' -> depth--
            }
        }
        return if (depth != 0) openLine else null
    }
}