/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.provider.remote.IRemoteFileService
import me.zhanghai.android.files.provider.remote.RemoteFileServiceInterface
import me.zhanghai.android.files.provider.remote.RemoteFileSystemException
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.valueCompat
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LibSuFileServiceLauncher {
    private val lock = Any()
    @Volatile
    private var isBuilderInitialized = false

    @Volatile
    private var lastLaunchFailureMillis = 0L
    private const val FAILURE_COOLDOWN_MILLIS = 6000L

    init {
        initBuilder()
    }

    @JvmStatic
    fun initBuilder() {
        if (isBuilderInitialized) return
        synchronized(lock) {
            if (isBuilderInitialized) return
            try {
                Shell.enableVerboseLogging = BuildConfig.DEBUG
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setInitializers(LibSuShellInitializer::class.java)
                        .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                        .setTimeout(TimeUnit.MILLISECONDS.toSeconds(RootFileService.TIMEOUT_MILLIS))
                )
            } catch (t: Throwable) {
                // Main shell was already created or builder already set; safely ignore to prevent ExceptionInInitializerError
            } finally {
                isBuilderInitialized = true
            }
        }
    }

    fun resetCooldown() {
        lastLaunchFailureMillis = 0L
    }

    fun isInFailureCooldown(): Boolean {
        val elapsed = System.currentTimeMillis() - lastLaunchFailureMillis
        return lastLaunchFailureMillis > 0L && elapsed in 0 until FAILURE_COOLDOWN_MILLIS
    }

    fun isSuAvailable(): Boolean {
        initBuilder()
        if (!me.zhanghai.android.files.settings.Settings.REQUEST_ROOT_PERMISSION.valueCompat) {
            return false
        }
        try {
            val cachedGranted = Shell.isAppGrantedRoot()
            if (cachedGranted != null) {
                return cachedGranted
            }
        } catch (_: Throwable) {}

        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/system/sd/xbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su"
        )
        for (path in paths) {
            val f = java.io.File(path)
            if (f.exists() && f.canExecute()) return true
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val finished = process.waitFor(700, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                val out = process.inputStream.bufferedReader().readText()
                process.exitValue() == 0 && out.contains("uid=0")
            }
        } catch (e: Exception) {
            false
        }
    }

    @Throws(RemoteFileSystemException::class)
    fun launchService(): IRemoteFileService {
        initBuilder()
        synchronized(lock) {
            if (!me.zhanghai.android.files.settings.Settings.REQUEST_ROOT_PERMISSION.valueCompat) {
                throw RemoteFileSystemException("Root permission request is disabled in settings")
            }
            if (isInFailureCooldown()) {
                throw RemoteFileSystemException("Root service launch is in cooldown after recent denial/failure")
            }
            // libsu won't call back when su isn't available.
            if (!isSuAvailable()) {
                lastLaunchFailureMillis = System.currentTimeMillis()
                throw RemoteFileSystemException("Root isn't available")
            }
            return try {
                runBlocking {
                    try {
                        withTimeout(3000L) {
                            // Proactively create the shell because RootService doesn't allow us to
                            // handle errors during shell creation.
                            suspendCancellableCoroutine<Unit> { continuation ->
                                // Shell.getShell(GetShellCallback) doesn't allow handling errors.
                                Shell.EXECUTOR.submit {
                                    try {
                                        val shell = Shell.getShell()
                                        if (!shell.isRoot) {
                                            lastLaunchFailureMillis = System.currentTimeMillis()
                                            continuation.resumeWithException(
                                                RemoteFileSystemException("Root access denied by superuser manager")
                                            )
                                        } else {
                                            continuation.resume(Unit)
                                        }
                                    } catch (e: NoShellException) {
                                        lastLaunchFailureMillis = System.currentTimeMillis()
                                        continuation.resumeWithException(
                                            RemoteFileSystemException(e)
                                        )
                                    } catch (t: Throwable) {
                                        lastLaunchFailureMillis = System.currentTimeMillis()
                                        continuation.resumeWithException(
                                            RemoteFileSystemException(t)
                                        )
                                    }
                                }
                            }
                            suspendCancellableCoroutine { continuation ->
                                val intent = LibSuFileService::class.createIntent()
                                val connection = object : ServiceConnection {
                                    override fun onServiceConnected(
                                        name: ComponentName,
                                        service: IBinder
                                    ) {
                                        lastLaunchFailureMillis = 0L
                                        val serviceInterface =
                                            IRemoteFileService.Stub.asInterface(service)
                                        continuation.resume(serviceInterface)
                                    }

                                    override fun onServiceDisconnected(name: ComponentName) {
                                        if (continuation.isActive) {
                                            lastLaunchFailureMillis = System.currentTimeMillis()
                                            continuation.resumeWithException(
                                                RemoteFileSystemException(
                                                    "libsu service disconnected"
                                                )
                                            )
                                        }
                                    }

                                    override fun onBindingDied(name: ComponentName) {
                                        if (continuation.isActive) {
                                            lastLaunchFailureMillis = System.currentTimeMillis()
                                            continuation.resumeWithException(
                                                RemoteFileSystemException("libsu binding died")
                                            )
                                        }
                                    }

                                    override fun onNullBinding(name: ComponentName) {
                                        if (continuation.isActive) {
                                            lastLaunchFailureMillis = System.currentTimeMillis()
                                            continuation.resumeWithException(
                                                RemoteFileSystemException("libsu binding is null")
                                            )
                                        }
                                    }
                                }
                                launch(Dispatchers.Main.immediate) {
                                    RootService.bind(intent, connection)
                                    continuation.invokeOnCancellation {
                                        launch(Dispatchers.Main.immediate) {
                                            RootService.unbind(connection)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        lastLaunchFailureMillis = System.currentTimeMillis()
                        throw RemoteFileSystemException(e)
                    }
                }
            } catch (e: InterruptedException) {
                lastLaunchFailureMillis = System.currentTimeMillis()
                throw RemoteFileSystemException(e)
            }
        }
    }
}

private class LibSuShellInitializer : Shell.Initializer() {
    // Prevent normal shells from being created and set as the main shell.
    override fun onInit(context: Context, shell: Shell): Boolean = shell.isRoot
}

class LibSuFileService : RootService() {
    override fun onCreate() {
        super.onCreate()

        RootFileService.main()
    }

    override fun onBind(intent: Intent): IBinder = RemoteFileServiceInterface()
}
