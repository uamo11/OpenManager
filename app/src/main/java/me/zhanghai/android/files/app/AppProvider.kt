/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

lateinit var application: Application private set

class AppProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        application = context as Application
        // Debug builds log what each start-up step costs on the main thread (tag OMStartup).
        val timed = me.zhanghai.android.files.BuildConfig.DEBUG
        var total = 0L
        appInitializers.forEach { initializer ->
            val start = if (timed) android.os.SystemClock.elapsedRealtimeNanos() else 0L
            initializer()
            if (timed) {
                val ms = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                total += ms
                android.util.Log.i("OMStartup", "${(initializer as kotlin.reflect.KCallable<*>).name}: $ms ms")
            }
        }
        if (timed) android.util.Log.i("OMStartup", "total: $total ms")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor? {
        throw UnsupportedOperationException()
    }

    override fun getType(uri: Uri): String? {
        throw UnsupportedOperationException()
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        throw UnsupportedOperationException()
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int {
        throw UnsupportedOperationException()
    }
}
