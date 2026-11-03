package com.openlattice.chronicle.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Observes or refuses real SQL through Room's supported I/O boundary, without replacing generated DAOs. */
internal fun checkedSQLiteOpenHelperFactory(beforeQuery: (String) -> Unit) = SupportSQLiteOpenHelper.Factory { config ->
    fun invoke(method: Method, receiver: Any, args: Array<out Any?>?): Any? = try {
        method.invoke(receiver, *(args ?: emptyArray()))
    } catch (error: InvocationTargetException) { throw error.targetException }
    fun statement(delegate: SupportSQLiteStatement, sql: String) = Proxy.newProxyInstance(
        SupportSQLiteStatement::class.java.classLoader, arrayOf(SupportSQLiteStatement::class.java),
    ) { _, method, args ->
        if (method.name in setOf("execute", "executeInsert", "executeUpdateDelete", "simpleQueryForLong", "simpleQueryForString")) {
            beforeQuery(sql)
        }
        invoke(method, delegate, args)
    } as SupportSQLiteStatement
    fun database(delegate: SupportSQLiteDatabase) = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        val sql = when (val query = args?.firstOrNull()) {
            is String -> query
            is SupportSQLiteQuery -> query.sql
            else -> null
        }
        if (sql != null && method.name in setOf("query", "execSQL")) beforeQuery(sql)
        val result = invoke(method, delegate, args)
        if (method.name == "compileStatement") statement(result as SupportSQLiteStatement, checkNotNull(sql)) else result
    } as SupportSQLiteDatabase
    val delegate = FrameworkSQLiteOpenHelperFactory().create(config)
    object : SupportSQLiteOpenHelper by delegate {
        override val writableDatabase get() = database(delegate.writableDatabase)
        override val readableDatabase get() = database(delegate.readableDatabase)
    }
}
