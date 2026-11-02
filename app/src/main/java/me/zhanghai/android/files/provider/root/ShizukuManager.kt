/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST_CODE = 5101

    private val binderReceivedListeners = mutableListOf<() -> Unit>()
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        try {
            Shizuku.addBinderReceivedListenerSticky {
                Log.d(TAG, "Shizuku binder received, hasPermission=${hasPermission()}")
                notifyListeners()
            }
            Shizuku.addBinderDeadListener {
                Log.d(TAG, "Shizuku binder died")
                notifyListeners()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Shizuku listener registration failed", t)
        }
        try {
            ShizukuProvider.requestBinderForNonProviderProcess(context)
        } catch (t: Throwable) {
            Log.e(TAG, "requestBinderForNonProviderProcess failed", t)
        }
    }

    fun addBinderListener(listener: () -> Unit) {
        synchronized(binderReceivedListeners) {
            binderReceivedListeners.add(listener)
        }
    }

    fun removeBinderListener(listener: () -> Unit) {
        synchronized(binderReceivedListeners) {
            binderReceivedListeners.remove(listener)
        }
    }

    private fun notifyListeners() {
        val listeners = synchronized(binderReceivedListeners) { binderReceivedListeners.toList() }
        for (l in listeners) {
            try {
                l()
            } catch (ignored: Throwable) {}
        }
    }

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        if (!isAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    fun ready(): Boolean = isAvailable() && hasPermission()

    fun getUid(): Int = try {
        if (ready()) Shizuku.getUid() else -1
    } catch (_: Throwable) {
        -1
    }

    fun getVersion(): Int = try {
        if (isAvailable()) Shizuku.getVersion() else -1
    } catch (_: Throwable) {
        -1
    }

    fun isRootMode(): Boolean = getUid() == 0

    fun requestPermission(
        activity: Activity,
        callback: ((Boolean) -> Unit)? = null
    ) {
        if (!isAvailable()) {
            callback?.invoke(false)
            return
        }
        if (hasPermission()) {
            callback?.invoke(true)
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    try {
                        Shizuku.removeRequestPermissionResultListener(this)
                    } catch (ignored: Throwable) {}
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    activity.runOnUiThread {
                        callback?.invoke(granted)
                    }
                }
            }
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            try {
                Shizuku.removeRequestPermissionResultListener(listener)
            } catch (ignored: Throwable) {}
            callback?.invoke(false)
        }
    }

    fun openShizukuApp(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent != null) {
                context.startActivity(intent)
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
                context.startActivity(webIntent)
            }
        } catch (ignored: Throwable) {}
    }
}
