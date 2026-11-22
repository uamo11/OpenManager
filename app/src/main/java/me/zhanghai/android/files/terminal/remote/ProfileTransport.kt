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

    companion object {
        fun create(context: Context, profile: RemoteProfile): ProfileTransport =
            when (profile.protocol) {
                RemoteProtocol.SSH -> SshTransport(context, profile)
                RemoteProtocol.TELNET -> TelnetTransport(context, profile)
            }
    }
}
