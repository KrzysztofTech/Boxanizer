package com.cookiedinner.boxanizer.core.database

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.cookiedinner.boxanizer.core.models.ExportSummary
import com.cookiedinner.boxanizer.database.Box
import com.cookiedinner.boxanizer.database.BoxWithItem
import com.cookiedinner.boxanizer.database.BoxanizerDb
import com.cookiedinner.boxanizer.database.Item
import com.cookiedinner.boxanizer.database.ItemInBox
import com.cookiedinner.boxanizer.database.ItemTag
import com.cookiedinner.boxanizer.items.models.ItemAction
import com.cookiedinner.boxanizer.items.models.ItemForQueryInBox
import java.io.File

class Database(databaseDriverFactory: DatabaseDriverFactory) {
    private val driver: SqlDriver = databaseDriverFactory.createDriver()
    private val database = BoxanizerDb(driver = driver)

    private val boxQueries = database.boxQueries
    private val itemQueries = database.itemQueries
    private val itemTagQueries = database.itemTagQueries

    /**
     * Writes a consistent, standalone copy of the whole database into [target]
     * (including changes still sitting in the WAL file), verifies it and returns row counts.
     */
    @Throws(Exception::class)
    fun exportTo(target: File): ExportSummary {
        target.delete()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // SQLite >= 3.27 (Android 11+) can produce a consistent copy while the database is open
            driver.execute(null, "VACUUM INTO ?", 1) {
                bindString(0, target.absolutePath)
            }
        } else {
            // Older SQLite: move everything from the WAL into the main file, then copy the file
            driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { cursor ->
                while (cursor.next().value) { /* consume the result row */ }
                QueryResult.Unit
            }, 0)
            val mainPath = driver.executeQuery(null, "PRAGMA database_list", { cursor ->
                var path: String? = null
                while (cursor.next().value) {
                    if (cursor.getString(1) == "main") path = cursor.getString(2)
                }
                QueryResult.Value(path)
            }, 0).value ?: throw Exception("Could not determine the database file path")
            File(mainPath).copyTo(target, overwrite = true)
        }
        return verifyExport(target)
    }

    private fun verifyExport(file: File): ExportSummary {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok")
                    throw Exception("Exported database failed the integrity check")
            }
            fun count(table: String): Long = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
            return ExportSummary(boxes = count("Box"), items = count("Item"))
        }
    }

    private fun <T : Any> buildListFromQuery(
        searchQuery: String,
        databaseFunction: (String) -> Query<T>,
        comparisonField: (T) -> Any
    ): List<T> {
        val fragmentedQuery = searchQuery.split(" ").filter { it.isNotBlank() }
        val finalList = mutableListOf<T>()
        if (fragmentedQuery.isEmpty()) {
            finalList.addAll(databaseFunction(searchQuery).executeAsList())
        } else {
            fragmentedQuery.forEachIndexed { index, value ->
                if (index == 0)
                    finalList.addAll(databaseFunction(value).executeAsList())
                else
                    finalList.retainAll { item -> databaseFunction(value).executeAsList().any { comparisonField(it) == comparisonField(item) } }
            }
        }
        return finalList
    }

    fun boxesSelectByQuery(query: String): List<Box> {
        return buildListFromQuery(
            searchQuery = query,
            databaseFunction = boxQueries::searchByQuery,
            comparisonField = { it.id },
        )
    }

    fun boxSelectById(id: Long): Box? {
        return boxQueries.selectById(id).executeAsOneOrNull()
    }

    fun boxSelectByCode(code: String): Box? {
        return boxQueries.selectByCode(code).executeAsOneOrNull()
    }

    @Throws(Exception::class)
    fun insertBox(box: Box): Box? {
        var insertedBox: Box? = null
        database.transaction {
            val codeBox = boxQueries.selectByCode(box.code).executeAsOneOrNull()
            if (codeBox != null && box.id != codeBox.id)
                throw Exception()
            boxQueries.insert(
                id = if (box.id == -1L) null else box.id,
                code = box.code,
                name = box.name,
                description = box.description?.ifBlank { null },
                image = box.image
            )
            insertedBox = boxQueries.selectLastInsertedBox().executeAsOneOrNull()
        }
        return insertedBox
    }

    @Throws(Exception::class)
    fun deleteBox(boxId: Long) {
        database.transaction {
            boxQueries.delete(boxId)
            boxQueries.deleteItemLinks(boxId)
        }
    }

    fun itemsSelectRemovedFromBoxes(query: String): List<Item> {
        return buildListFromQuery(
            searchQuery = query,
            databaseFunction = itemQueries::selectRemovedFromBoxes,
            comparisonField = { it.id }
        ).distinctBy { it.id }
    }

    fun itemsSelectInBoxes(query: String): List<Item> {
        return buildListFromQuery(
            searchQuery = query,
            databaseFunction = itemQueries::selectInBoxes,
            comparisonField = { it.id }
        ).distinctBy { it.id }
    }

    fun itemsSelectNotInBoxes(query: String): List<Item> {
        return buildListFromQuery(
            searchQuery = query,
            databaseFunction = itemQueries::selectNotInBoxes,
            comparisonField = { it.id }
        ).distinctBy { it.id }
    }

    fun itemsSelectForQueryInBox(
        query: String,
        boxId: Long
    ): List<ItemForQueryInBox> {
        return buildListFromQuery(
            searchQuery = query,
            databaseFunction = {
                itemQueries.selectForQueryInBox(boxId, it)
            },
            comparisonField = { it.id }
        ).distinctBy { it.id }.map {
            ItemForQueryInBox(
                id = it.id,
                name = it.name,
                description = it.description,
                image = it.image,
                consumable = it.consumable,
                alreadyInBox = it.alreadyInBox == 1L
            )
        }
    }

    fun itemSelectById(id: Long): Item? {
        return itemQueries.selectById(id).executeAsOneOrNull()
    }

    fun itemsSelectByBoxId(boxId: Long): List<ItemInBox> {
        return itemQueries.itemInBox(boxId).executeAsList()
    }

    fun boxesSelectByItemId(itemId: Long): List<BoxWithItem> {
        return boxQueries.boxWithItem(itemId).executeAsList()
    }

    @Throws(Exception::class)
    fun insertItem(item: Item): Item? {
        var insertedItem: Item? = null
        database.transaction {
            itemQueries.insert(
                id = if (item.id == -1L) null else item.id,
                name = item.name,
                description = item.description?.ifBlank { null },
                image = item.image,
                consumable = item.consumable
            )
            insertedItem = itemQueries.selectLastInsertedItem().executeAsOneOrNull()
        }
        return insertedItem
    }

    @Throws(Exception::class)
    fun deleteItem(itemId: Long) {
        database.transaction {
            itemQueries.delete(itemId)
            itemQueries.deleteBoxLinks(itemId)
            itemQueries.deleteTagLinks(itemId)
        }
    }

    @Throws(Exception::class)
    fun editItemInBox(
        item: ItemInBox,
        boxId: Long,
        action: ItemAction,
        customAmount: Long = 1L
    ) {
        when (action) {
            ItemAction.BORROW, ItemAction.RETURN -> {
                if ((action == ItemAction.BORROW && item.amountRemovedFromBox == 0L) || (action == ItemAction.RETURN && item.amountRemovedFromBox == customAmount)) {
                    /**
                     * This is only necessary to update the rowId to the largest one in the table for sorting purposes
                     */
                    itemQueries.reinsertItemInBox(
                        boxId = boxId,
                        itemId = item.id,
                        amountInBox = item.amountInBox,
                        amountRemovedFromBox = if (action == ItemAction.BORROW) customAmount else 0,
                        amountToBuy = item.amountToBuy
                    )
                } else {
                    itemQueries.editAmountRemovedInBox(
                        newAmount = item.amountRemovedFromBox + if (action == ItemAction.BORROW) customAmount else -customAmount,
                        itemId = item.id,
                        boxId = boxId
                    )
                }
            }

            ItemAction.CONSUME, ItemAction.BUY -> {
                if ((action == ItemAction.CONSUME && item.amountToBuy == 0L) || (action == ItemAction.BUY && item.amountToBuy == customAmount)) {
                    /**
                     * This is (once again) only necessary to update the rowId to the largest one in the table for sorting purposes
                     */
                    itemQueries.reinsertItemInBox(
                        boxId = boxId,
                        itemId = item.id,
                        amountInBox = item.amountInBox,
                        amountRemovedFromBox = item.amountRemovedFromBox,
                        amountToBuy = if (action == ItemAction.CONSUME) customAmount else 0
                    )
                } else {
                    itemQueries.editAmountToBuyInBox(
                        newAmount = item.amountToBuy + if (action == ItemAction.CONSUME) customAmount else -customAmount,
                        itemId = item.id,
                        boxId = boxId
                    )
                }
            }

            ItemAction.ADD, ItemAction.REMOVE -> {
                if (action == ItemAction.REMOVE) {
                    if (item.amountInBox > customAmount) {
                        itemQueries.editAmountInBox(
                            newAmount = item.amountInBox - customAmount,
                            itemId = item.id,
                            boxId = boxId
                        )
                    }
                } else {
                    itemQueries.editAmountInBox(
                        newAmount = item.amountInBox + customAmount,
                        itemId = item.id,
                        boxId = boxId
                    )
                }
            }

            ItemAction.DELETE -> {
                itemQueries.deleteFromBox(boxId, item.id)
            }
        }
    }

    @Throws(Exception::class)
    fun selectItemTags(itemId: Long): List<ItemTag> {
        return itemTagQueries.selectByItemId(itemId).executeAsList()
    }

    @Throws(Exception::class)
    fun insertTag(
        itemId: Long,
        name: String
    ) {
        itemTagQueries.insert(itemId, name)
    }

    @Throws(Exception::class)
    fun deleteTag(
        itemId: Long,
        name: String
    ) {
        itemTagQueries.delete(itemId, name)
    }

    @Throws(Exception::class)
    fun addItemToBox(
        boxId: Long,
        itemId: Long
    ) {
        itemQueries.insertToBox(boxId, itemId)
    }
}