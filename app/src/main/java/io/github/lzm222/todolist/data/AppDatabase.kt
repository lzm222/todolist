package io.github.lzm222.todolist.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HomeworkItem::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun homeworkDao(): HomeworkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2：把 (dueDate, subject, content) 的全表唯一索引，
         * 换成「只约束 deleted = 0 的行」的部分唯一索引。
         *
         * 旧索引的问题：软删除的条目仍然占着唯一键，
         * 导致"删光后再导入同样的作业"全部被判为重复、插入 0 条。
         *
         * 迁移时顺手清掉已软删除的历史行（对用户已不可见，清掉无副作用）。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 先删掉硬删除行，避免它们占用部分唯一索引
                db.execSQL("DELETE FROM homework_items WHERE deleted = 1")
                // 旧的唯一索引（Room 默认命名），若存在则删除
                db.execSQL("DROP INDEX IF EXISTS `index_homework_items_dueDate_subject_content`")
                // 部分唯一索引：只对未删除的行生效
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_homework_items_dueDate_subject_content_active` " +
                        "ON `homework_items` (`dueDate`, `subject`, `content`) WHERE `deleted` = 0"
                )
                // Room 声明的普通索引（非唯一）
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_homework_items_dueDate_subject_content` " +
                        "ON `homework_items` (`dueDate`, `subject`, `content`)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "homework.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // 版本不匹配且没有迁移脚本时的兜底：删库重建，避免升级后闪退。
                    // 数据可用导入的 JSON 重新灌入，因此这是可接受的兜底。
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}