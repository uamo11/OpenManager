/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import me.zhanghai.android.files.filelist.RemoteThumbnailPolicy
import me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpsFilesTest {

    @Test
    fun passwordLoginAlsoTriesKeyboardInteractive() {
        // Stock Ubuntu cloud images take the password only through keyboard-interactive.
        val methods = PasswordAuthentication("secret").toAuthMethods()
        assertEquals(2, methods.size)
        assertTrue(methods[0] is AuthPassword)
        assertTrue(methods[1] is AuthKeyboardInteractive)
    }

    @Test
    fun remoteThumbnailsRespectTheSizeLimit() {
        val mb = 1024L * 1024
        assertTrue(RemoteThumbnailPolicy.isWithinSizeLimit(9 * mb, 10))
        assertFalse(RemoteThumbnailPolicy.isWithinSizeLimit(11 * mb, 10))
        assertTrue(RemoteThumbnailPolicy.isWithinSizeLimit(500 * mb, 0)) // "Unlimited"
    }

    private fun source(path: String): String {
        val f = File("src/main/java/me/zhanghai/android/files/$path").takeIf { it.exists() }
            ?: File("app/src/main/java/me/zhanghai/android/files/$path")
        return f.readText()
    }

    @Test
    fun writeChecksNeverLookAtThePhoneForServerFolders() {
        // canWriteTo used java.io.File("/root") on the phone for a VPS folder: always "read-only".
        val code = source("filelist/FileListFragment.kt")
        val body = code.substringAfter("fun canWriteToPath(path: Path): Boolean {").substringBefore("\n}\n")
        assertFalse(body.contains("java.io.File(path.toString())"))
        assertTrue(body.contains("if (path !is LinuxPath)"))
    }

    @Test
    fun vpsFilesReuseTheTerminalConnection() {
        assertTrue(source("terminal/TerminalActivity.kt").contains("sharedClient()"))
        assertTrue(source("terminal/remote/RemoteFilesLauncher.kt").contains("Client.shareConnection("))
        assertTrue(source("provider/sftp/client/Client.kt").contains("shared.newSFTPClient()"))
    }
}
