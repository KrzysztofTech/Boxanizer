package com.cookiedinner.boxanizer.core.database

import android.content.Context
import android.database.Cursor
import android.database.CursorWindow
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cookiedinner.boxanizer.core.models.DataCounts
import com.cookiedinner.boxanizer.core.models.ImportException
import com.cookiedinner.boxanizer.core.models.ImportException.Reason
import com.cookiedinner.boxanizer.database.BoxanizerDb
import java.io.File

/**
 * Validation of database files chosen for import (exports from this or older app versions).
 */
object DatabaseImporter {
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val REQUIRED_TABLES = listOf("Box", "Item", "BoxItem", "ItemTag")

    const val COUNTS_SQL = """
        SELECT
            (SELECT COUNT(*) FROM Box),
            (SELECT COUNT(*) FROM Item),
            (SELECT COUNT(*) FROM BoxItem),
            (SELECT COUNT(*) FROM ItemTag),
            (SELECT COUNT(*) FROM Box WHERE image IS NOT NULL) + (SELECT COUNT(*) FROM Item WHERE image IS NOT NULL)
    """

    /**
     * Checks that [file] is an intact Boxanizer database with a schema version this app understands,
     * migrates it in place to the current schema if it is older, and returns its row counts.
     * The file should be a private working copy, because it may be modified by the migration.
     */
    @Throws(ImportException::class)
    fun inspect(context: Context, file: File): DataCounts {
        if (!hasSqliteHeader(file))
            throw ImportException(Reason.NOT_A_DATABASE)

        val currentVersion = BoxanizerDb.Schema.version
        val fileVersion = try {
            openReadOnly(file).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getString(0) != "ok")
                        throw ImportException(Reason.CORRUPTED)
                }
                val tables = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table'", null
                ).use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                if (!tables.containsAll(REQUIRED_TABLES))
                    throw ImportException(Reason.NOT_A_DATABASE)
                db.version.toLong()
            }
        } catch (ex: ImportException) {
            throw ex
        } catch (ex: Exception) {
            throw ImportException(Reason.CORRUPTED, ex.message)
        }

        when {
            fileVersion < 1 -> throw ImportException(Reason.NOT_A_DATABASE)
            fileVersion > currentVersion -> throw ImportException(Reason.NEWER_VERSION)
            fileVersion < currentVersion -> migrateToCurrentSchema(context, file)
        }

        return openReadOnly(file).use { countsOf(it) }
    }

    fun openReadOnly(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    fun countsOf(db: SQLiteDatabase): DataCounts =
        db.rawQuery(COUNTS_SQL, null).use { cursor ->
            cursor.moveToFirst()
            DataCounts(
                boxes = cursor.getLong(0),
                items = cursor.getLong(1),
                boxItems = cursor.getLong(2),
                tags = cursor.getLong(3),
                photos = cursor.getLong(4)
            )
        }

    /**
     * Opens a cursor that can hold rows with large photos (the default window is 2 MB).
     */
    fun queryWithLargeRows(db: SQLiteDatabase, sql: String): Cursor {
        val cursor = db.rawQuery(sql, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && cursor is SQLiteCursor) {
            cursor.setWindow(CursorWindow("import", 32L * 1024 * 1024))
        }
        return cursor
    }

    private fun hasSqliteHeader(file: File): Boolean {
        if (file.length() < SQLITE_HEADER.size) return false
        val header = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { input ->
            if (input.read(header) != header.size) return false
        }
        return header.contentEquals(SQLITE_HEADER)
    }

    /**
     * Opening the file through SQLDelight runs the missing migrations (SQLiteOpenHelper.onUpgrade).
     */
    private fun migrateToCurrentSchema(context: Context, file: File) {
        try {
            val driver = AndroidSqliteDriver(BoxanizerDb.Schema, context, file.absolutePath)
            try {
                driver.executeQuery(null, "SELECT 1", { QueryResult.Unit }, 0)
            } finally {
                driver.close()
            }
        } catch (ex: Exception) {
            throw ImportException(Reason.CORRUPTED, "Migration of the imported file failed: ${ex.message}")
        }
    }
}
