/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import me.zhanghai.android.files.provider.sftp.client.Authentication
import me.zhanghai.android.files.provider.sftp.client.Authenticator
import me.zhanghai.android.files.provider.sftp.client.Authority
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

object SftpServerAuthenticator : Authenticator {
    private val transientServers = mutableSetOf<SftpServer>()
    /** Names of servers opened over a terminal connection, which need no stored credentials. */
    private val transientNames = java.util.concurrent.ConcurrentHashMap<Authority, String>()

    fun setTransientName(authority: Authority, name: String) {
        transientNames[authority] = name
    }

    override fun getAuthentication(authority: Authority): Authentication? {
        val server = synchronized(transientServers) {
            transientServers.find { it.authority == authority }
        } ?: Settings.STORAGES.valueCompat.find {
            it is SftpServer && it.authority == authority
        } as SftpServer?
        return server?.authentication
    }

    fun addTransientServer(server: SftpServer) {
        synchronized(transientServers) {
            // Replaces an earlier entry for the same server, which may hold an old password.
            transientServers.removeAll { it.authority == server.authority }
            transientServers += server
        }
    }

    /** Name the user gave this server (the VPS profile or the saved storage), if any. */
    fun displayName(authority: Authority): String? {
        transientNames[authority]?.takeIf { it.isNotBlank() }?.let { return it }
        val server = synchronized(transientServers) {
            transientServers.find { it.authority == authority }
        } ?: Settings.STORAGES.valueCompat.find {
            it is SftpServer && it.authority == authority
        } as SftpServer?
        return server?.customName?.takeIf { it.isNotBlank() }
    }

    fun removeTransientServer(server: SftpServer) {
        synchronized(transientServers) { transientServers -= server }
    }
}
