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
        AdvanceFundEntity::class,
        LocalDeletionEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun advanceFundDao(): AdvanceFundDao
    abstract fun localDeletionDao(): LocalDeletionDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `projects` ADD COLUMN `remoteId` TEXT")
                database.execSQL("ALTER TABLE `projects` ADD COLUMN `syncVersion` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `projects` ADD COLUMN `syncState` TEXT NOT NULL DEFAULT 'PENDING'")
                database.execSQL("ALTER TABLE `projects` ADD COLUMN `clientMutationId` TEXT")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_projects_remoteId` ON `projects` (`remoteId`)")

                database.execSQL("ALTER TABLE `expenses` ADD COLUMN `priceCents` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `expenses` ADD COLUMN `amountCents` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `expenses` ADD COLUMN `remoteId` TEXT")
                database.execSQL("ALTER TABLE `expenses` ADD COLUMN `syncVersion` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `expenses` ADD COLUMN `syncState` TEXT NOT NULL DEFAULT 'PENDING'")
                database.execSQL("ALTER TABLE `expenses` ADD COLUMN `clientMutationId` TEXT")
                database.execSQL("UPDATE `expenses` SET `priceCents` = CAST(ROUND(`price` * 100.0) AS INTEGER)")
                database.execSQL("UPDATE `expenses` SET `amountCents` = CAST(ROUND(`totalAmount` * 100.0) AS INTEGER)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_expenses_remoteId` ON `expenses` (`remoteId`)")

                database.execSQL("ALTER TABLE `attachments` ADD COLUMN `remoteId` TEXT")
                database.execSQL("ALTER TABLE `attachments` ADD COLUMN `cloudFileId` TEXT")
                database.execSQL("ALTER TABLE `attachments` ADD COLUMN `syncVersion` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `attachments` ADD COLUMN `syncState` TEXT NOT NULL DEFAULT 'PENDING'")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_attachments_remoteId` ON `attachments` (`remoteId`)")

                database.execSQL("ALTER TABLE `advance_fund` ADD COLUMN `totalFundCents` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE `advance_fund` SET `totalFundCents` = CAST(ROUND(`totalFund` * 100.0) AS INTEGER)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_deletions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `remoteId` TEXT NOT NULL,
                        `baseVersion` INTEGER NOT NULL,
                        `clientMutationId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
