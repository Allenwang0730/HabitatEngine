package com.ailun.habitat.handlers

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.handlers.FilePathGuard.Access
import com.ailun.habitat.paramBool
import com.ailun.habitat.paramInt
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ACTION_SQLITE_QUERY]：对任意 .db 文件执行 SQL（零权限，纯 JVM）。
 * params：
 * - `database`（必填）：数据库文件路径，支持 ${var}
 * - `query`（必填）：SQL 语句（SELECT/INSERT/UPDATE/DELETE/CREATE/PRAGMA 等，一次一条）
 * - `args`（可选）：JSON 数组字符串，绑定到语句中的 ? 占位符
 * - `output_var`（可选）：结果变量名，默认 "sql_result"
 * - `limit`（可选）：SELECT 最大返回行数，默认 500（1-5000）
 * - `create`（可选，默认 false）：数据库文件不存在时是否创建
 * 输出：sql_result（SELECT 为 JSON 数组；否则 "OK"）、sql_rows(Int，行数/受影响数)、
 *       sql_success、sql_error
 *
 * 实现要点：SQLiteDatabase.openDatabase 打开文件；SELECT 走 rawQuery 转 JSON；
 * 非 SELECT 走 execSQL + SELECT changes() 取受影响数；finally 中 close()。
 * 注意：只能访问本应用可读的文件（其他应用 /data/data 下的库会 SecurityException，如实失败）。
 */
class NodeSqliteQueryHandler : INodeHandler {

    private val gson = Gson()

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? =
        withContext(Dispatchers.IO) { handleIo(node, context) }

    private fun handleIo(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("sql_success", false)
        val rawDb = node.params?.get("database")?.toString()?.trim().orEmpty()
        if (rawDb.isEmpty()) {
            context.putVariable("sql_success", false)
            context.putVariable("sql_error", "缺少 'database' 参数")
            return node.next
        }
        val database = context.interpolate(rawDb)
        val rawQuery = node.params?.get("query")?.toString()?.trim().orEmpty()
        if (rawQuery.isEmpty()) {
            context.putVariable("sql_success", false)
            context.putVariable("sql_error", "缺少 'query' 参数")
            return node.next
        }
        val query = context.interpolate(rawQuery)
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "sql_result"
        val limit = paramInt(node, context, "limit", 500).coerceIn(1, 5000)
        val create = paramBool(node, context, "create", false)
        val args = parseArgs(node.params?.get("args")?.toString())

        var db: SQLiteDatabase? = null
        try {
            FilePathGuard.check(context.appContext, database, Access.WRITE)?.let { throw SecurityException(it) }
            var flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            if (create) flags = flags or SQLiteDatabase.CREATE_IF_NECESSARY
            db = SQLiteDatabase.openDatabase(database, null, flags)
            val isSelect = query.trimStart().substringBefore(" ").equals("SELECT", ignoreCase = true)

            if (isSelect) {
                val rows = mutableListOf<Map<String, Any?>>()
                db.rawQuery(query, args).use { cursor ->
                    while (rows.size < limit && cursor.moveToNext()) {
                        rows.add(cursorToMap(cursor))
                    }
                }
                val json = gson.toJson(rows)
                context.putVariable(outputVar, json)
                context.putVariable("sql_rows", rows.size)
                context.putVariable("sql_success", true)
                context.log("SqliteQuery SELECT → ${rows.size} rows")
            } else {
                val argArray = args ?: emptyArray()
                db.execSQL(query, argArray)
                val affected = db.rawQuery("SELECT changes() AS c", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                context.putVariable(outputVar, "OK")
                context.putVariable("sql_rows", affected)
                context.putVariable("sql_success", true)
                context.log("SqliteQuery ${query.trim().substringBefore(" ").uppercase()} → $affected affected")
            }
        } catch (e: Exception) {
            context.putVariable("sql_success", false)
            context.putVariable("sql_error", e.message ?: "Unknown error")
            context.log("SqliteQuery FAILED: ${e.message}")
        } finally {
            runCatching { db?.close() }
        }
        return node.next
    }

    private fun cursorToMap(cursor: Cursor): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            map[name] = when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                Cursor.FIELD_TYPE_BLOB -> "BLOB(${cursor.getBlob(i).size}B)"
                Cursor.FIELD_TYPE_NULL -> null
                else -> cursor.getString(i)
            }
        }
        return map
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseArgs(raw: String?): Array<String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val list = gson.fromJson(raw, List::class.java) as? List<*> ?: return null
            list.map { it?.toString() ?: "" }.toTypedArray()
        } catch (e: Exception) {
            null
        }
    }
}
