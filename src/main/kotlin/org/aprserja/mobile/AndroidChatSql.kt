// SPDX-License-Identifier: AGPL-3.0-only
package org.aprserja.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import org.aprsfoundation.core.ChatSql
import org.aprsfoundation.core.ChatSqlRow
import org.aprsfoundation.core.ChatStore

internal class MobileChatStore private constructor(private val storage: AndroidChatSql) : ChatStore(
    storage, System::currentTimeMillis, expireAfterMillis = null
) {
    constructor(context: Context) : this(AndroidChatSql(context))
    suspend fun shutdown() = closeStorage(storage::close)
}

private class AndroidChatSql(context: Context) : ChatSql {
    private val db = context.openOrCreateDatabase("aprs-chat.sqlite", Context.MODE_PRIVATE, null)

    override fun execute(sql: String, vararg values: Any?): Int {
        if (values.isEmpty()) {
            db.execSQL(sql)
            return 0
        }
        return db.compileStatement(sql).use { statement ->
            statement.bind(values.asList())
            statement.executeUpdateDelete()
        }
    }

    override fun <T> query(sql: String, values: List<Any?>, read: (ChatSqlRow) -> T): List<T> {
        val cursor = db.rawQueryWithFactory({ _, driver, table, query ->
            query.bind(values)
            android.database.sqlite.SQLiteCursor(driver, table, query)
        }, sql, emptyArray(), "")
        cursor.use {
            val row = object : ChatSqlRow {
                override fun text(index: Int) = cursor.getString(index)
                override fun number(index: Int) = cursor.getLong(index)
            }
            return buildList { while (cursor.moveToNext()) add(read(row)) }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun close() = db.close()

    private fun SQLiteProgram.bind(values: List<Any?>) {
        values.forEachIndexed { index, value ->
            val parameter = index + 1
            when (value) {
                null -> bindNull(parameter)
                is Number -> bindLong(parameter, value.toLong())
                is String -> bindString(parameter, value)
                else -> error("Unsupported chat SQL parameter")
            }
        }
    }
}
