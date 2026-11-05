package io.github.lzm222.todolist.data

import kotlinx.coroutines.flow.Flow

/**
 * 数据仓库。所有读写都走这里，方便以后加缓存或换存储。
 */
class HomeworkRepository(private val dao: HomeworkDao) {

    fun observeByDate(date: String): Flow<List<HomeworkItem>> = dao.observeByDate(date)

    fun observeOverdue(today: String): Flow<List<HomeworkItem>> = dao.observeOverdue(today)

    fun observeAllDates(): Flow<List<String>> = dao.observeAllDates()

    /**
     * 导入一批作业。
     * @return Pair(成功插入条数, 因重复被跳过条数)
     */
    suspend fun import(items: List<HomeworkItem>): Pair<Int, Int> {
        if (items.isEmpty()) return 0 to 0
        val rowIds = dao.insertAll(items)
        val inserted = rowIds.count { it != -1L }
        return inserted to (items.size - inserted)
    }

    suspend fun setDone(id: Long, done: Boolean) {
        dao.setDone(id, done, if (done) System.currentTimeMillis() else null)
    }

    suspend fun delete(id: Long) = dao.setDeleted(id, true)

    suspend fun restore(id: Long) = dao.setDeleted(id, false)

    /** 清理被软删除的行，释放唯一索引占位。 */
    suspend fun purgeDeleted() = dao.purgeDeleted()
}