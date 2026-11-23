/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

// @see https://android-developers.googleblog.com/2018/03/cryptography-changes-in-android-p.html
// @see net.schmizz.sshj.common.SecurityUtils
// @see net.schmizz.sshj.DefaultConfig.DefaultConfig
// SSHJ requires BouncyCastle to be registered before enabling most functionality by default, so we
// better keep BouncyCastle registered.
//
// Registering BouncyCastle loads ~1,500 OIDs, all algorithm SPIs (including post-quantum ones) and
// their classes, so callers that only *may* need it (SFTP, BKS keystores) use ensureInitialized()
// and the app registers it eagerly only when Ultra RAM mode is off.
object SecurityProviderHelper {
    @Volatile
    private var initialized = false

    fun init() {
        synchronized(this) {
            val start = System.nanoTime()
            val bouncyCastleProvider = BouncyCastleProvider()
            Security.removeProvider(bouncyCastleProvider.name)
            Security.addProvider(bouncyCastleProvider)
            initialized = true
            if (me.zhanghai.android.files.BuildConfig.DEBUG) {
                runCatching {
                    android.util.Log.i(
                        "OMStartup",
                        "BouncyCastle registered in ${(System.nanoTime() - start) / 1_000_000} ms on " +
                            Thread.currentThread().name
                    )
                }
            }
        }
    }

    fun ensureInitialized() {
        if (initialized) {
            return
        }
        synchronized(this) {
            if (!initialized) {
                init()
            }
        }
    }
}
