/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.content.Context
import com.termux.terminal.RemoteTransport

/** A [RemoteTransport] opened from a saved [RemoteProfile]. */
interface ProfileTransport : RemoteTransport {
    val profile: RemoteProfile

    /** Label/value rows describing the live connection (security, algorithms, endpoint). */
    fun connectionDetails(context: Context): List<Pair<String, String>>

    /** True when the link is encrypted and the server identity was verified. */
    val isSecure: Boolean

    companion object {
        fun create(context: Context, profile: RemoteProfile): ProfileTransport =
            when (profile.protocol) {
                RemoteProtocol.SSH -> SshTransport(context, profile)
                RemoteProtocol.TELNET -> TelnetTransport(context, profile)
            }
    }
}
