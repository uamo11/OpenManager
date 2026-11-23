/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeysTest {
    @Test
    fun generatedKeyIsStandardOpenSshAndReadableBySshj() {
        val pair = SshKeys.generateEd25519("openmanager@pixel")
        assertTrue(pair.privateKeyOpenSsh.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        assertTrue(pair.privateKeyOpenSsh.trimEnd().endsWith("-----END OPENSSH PRIVATE KEY-----"))
        assertTrue(pair.privateKeyOpenSsh.lines().all { it.length <= 70 })
        assertTrue(pair.publicKeyLine.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA"))
        assertTrue(pair.publicKeyLine.endsWith(" openmanager@pixel"))
        // The exact parser used to log in derives the same public key.
        assertEquals(pair.publicKeyLine, SshKeys.publicKeyLine(pair.privateKeyOpenSsh, null, "openmanager@pixel"))
    }

    @Test
    fun everyKeyIsDifferent() {
        val a = SshKeys.generateEd25519("x")
        val b = SshKeys.generateEd25519("x")
        assertFalse(a.publicKeyLine == b.publicKeyLine)
    }

    @Test
    fun installCommandIsIdempotentAndSafe() {
        val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBASE64 openmanager@pixel"
        val cmd = SshKeys.installCommand(line)
        assertTrue(cmd.contains("grep -qxF '$line' ~/.ssh/authorized_keys || echo '$line' >> ~/.ssh/authorized_keys"))
        assertTrue(cmd.contains("chmod 600 ~/.ssh/authorized_keys"))
        assertFalse("quotes cannot break out", SshKeys.installCommand("ssh-ed25519 AAA x';rm -rf ~;'").contains("';rm"))
        assertEquals("openmanager@sm-s918b", SshKeys.sanitizeComment("openmanager@sm-s918b"))
        assertEquals("openmanager@pixel_8_pro", SshKeys.sanitizeComment("openmanager@pixel 8 pro"))
    }
}
