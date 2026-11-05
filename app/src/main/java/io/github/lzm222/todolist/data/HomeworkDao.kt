package io.github.lzm222.todolist.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HomeworkDao {

    /** 某一天的清单（含已完成项，不含已删除项）。 */
    @Query(
        """
        SELECT * FROM homework_items
        WHERE deleted = 0 AND dueDate = :date
        ORDER BY subject ASC, sortOrder ASC, id ASC
        """
    )
    fun observeByDate(date: String): Flow<List<HomeworkItem>>

    /** 逾期清单：未完成、未删除、日期早于今天。 */
    @Query(
        """
        SELECT * FROM homework_items
        WHERE deleted = 0 AND isDone = 0 AND dueDate < :today
        ORDER BY dueDate ASC, subject ASC, sortOrder ASC, id ASC
        """
    )
    fun observeOverdue(today: String): Flow<List<HomeworkItem>>

    /** 所有存在的作业日期，倒序（最近的在前）。 */
    @Query("SELECT DISTINCT dueDate FROM homework_items WHERE deleted = 0 ORDER BY dueDate DESC")
    fun observeAllDates(): Flow<List<String>>

    /**
     * 批量插入。返回每条记录插入后的 rowId，冲突（重复）时为 -1。
     * 调用方可通过比较 size 判断被跳过的重复条数。
     *
     * 注意：唯一约束是"部分唯一索引"（见 AppDatabase.MIGRATION_1_2），
     * 只约束 deleted = 0 的行；因此软删除的条目不会挡住重新导入。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<HomeworkItem>): List<Long>

    @Query("UPDATE homework_items SET isDone = :done, doneAt = :doneAt WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean, doneAt: Long?)

    @Query("UPDATE homework_items SET deleted = :deleted WHERE id = :id")
    suspend fun setDeleted(id: Long, deleted: Boolean)

    /**
     * 批量软删除 / 批量恢复。
     * 用一条 UPDATE 完成，避免 N 次往返；空列表直接不产生影响行。
     */
    @Query("UPDATE homework_items SET deleted = :deleted WHERE id IN (:ids)")
    suspend fun setDeletedBatch(ids: List<Long>, deleted: Boolean)

    @Query("DELETE FROM homework_items WHERE deleted = 1")
    suspend fun purgeDeleted()
}
