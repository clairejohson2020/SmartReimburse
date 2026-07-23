package com.smartreimburse.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration2To3Test {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migratesMoneyToCentsAndAddsSyncColumnsWithoutDataLoss() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createVersion2Schema(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO projects (id, name, createdAt, updatedAt) VALUES (1, '测试项目', 1, 1)"
        )
        helper.writableDatabase.execSQL(
            """
            INSERT INTO expenses
            (id, projectId, name, model, quantity, price, totalAmount, date, hasInvoice, invoiceNumber, onlineLink, notes, isReimbursed)
            VALUES (1, 1, '打印纸', 'A4', 2, 6.17, 12.34, 1, 0, NULL, NULL, NULL, 0)
            """.trimIndent()
        )
        helper.writableDatabase.execSQL("INSERT INTO advance_fund (projectId, totalFund) VALUES (1, 100.01)")
        helper.close()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .build()
        val migrated = database.openHelper.writableDatabase
        migrated.query("SELECT priceCents, amountCents, syncState FROM expenses WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(617L, it.getLong(0))
            assertEquals(1234L, it.getLong(1))
            assertEquals(SyncState.PENDING, it.getString(2))
        }
        migrated.query("SELECT totalFundCents FROM advance_fund WHERE projectId = 1").use {
            it.moveToFirst()
            assertEquals(10001L, it.getLong(0))
        }
        database.close()
    }

    private fun createVersion2Schema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE expenses (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              projectId INTEGER NOT NULL DEFAULT 1,
              name TEXT NOT NULL, model TEXT NOT NULL, quantity INTEGER NOT NULL,
              price REAL NOT NULL, totalAmount REAL NOT NULL, date INTEGER NOT NULL,
              hasInvoice INTEGER NOT NULL, invoiceNumber TEXT, onlineLink TEXT, notes TEXT,
              isReimbursed INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX index_expenses_projectId ON expenses(projectId)")
        db.execSQL(
            """
            CREATE TABLE attachments (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              expenseId INTEGER NOT NULL, type TEXT NOT NULL, filePath TEXT NOT NULL,
              FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX index_attachments_expenseId ON attachments(expenseId)")
        db.execSQL("CREATE TABLE advance_fund (projectId INTEGER PRIMARY KEY NOT NULL, totalFund REAL NOT NULL)")
    }

    companion object {
        private const val DB_NAME = "migration-2-3.db"
    }
}
