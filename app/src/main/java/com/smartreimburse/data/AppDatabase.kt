package com.smartreimburse.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProjectEntity::class,
        ExpenseEntity::class,
        AttachmentEntity::class,
        AdvanceFundEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun advanceFundDao(): AdvanceFundDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_reimburse.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `projects` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT OR IGNORE INTO `projects` (`id`, `name`, `createdAt`, `updatedAt`)
                    VALUES (1, '默认项目', $now, $now)
                    """.trimIndent()
                )
                database.execSQL("ALTER TABLE `expenses` ADD COLUMN `projectId` INTEGER NOT NULL DEFAULT 1")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_projectId` ON `expenses` (`projectId`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `advance_fund_new` (
                        `projectId` INTEGER NOT NULL,
                        `totalFund` REAL NOT NULL,
                        PRIMARY KEY(`projectId`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `advance_fund_new` (`projectId`, `totalFund`)
                    SELECT 1, `totalFund` FROM `advance_fund` WHERE `id` = 1
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE `advance_fund`")
                database.execSQL("ALTER TABLE `advance_fund_new` RENAME TO `advance_fund`")
            }
        }
    }
}
