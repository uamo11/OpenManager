/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RemoteTerminalTest {
    private fun resolveMainFile(relPath: String): File {
        val f1 = File("src/main/$relPath")
        return if (f1.exists()) f1 else File("app/src/main/$relPath")
    }

    @Test
    fun endpointHidesDefaultPortAndBracketsIpv6() {
        assertEquals("root@example.org", RemoteProfile(host = "example.org", username = "root").endpoint)
        assertEquals(
            "pi@10.0.0.2:2222",
            RemoteProfile(host = "10.0.0.2", port = 2222, username = "pi").endpoint
        )
        assertEquals(
            "[fe80::1]",
            RemoteProfile(protocol = RemoteProtocol.TELNET, host = "fe80::1", port = 23).endpoint
        )
        assertEquals("Router", RemoteProfile(name = "Router", host = "192.168.1.1").displayName)
    }

    @Test
    fun defaultPortFollowsProtocol() {
        assertEquals(22, RemoteProfile(protocol = RemoteProtocol.SSH).port)
        assertEquals(23, RemoteProfile(protocol = RemoteProtocol.TELNET).port)
        assertEquals(RemoteProtocol.SSH, RemoteProtocol.parse("bogus"))
        assertEquals(RemoteProtocol.TELNET, RemoteProtocol.parse("telnet"))
    }

    @Test
    fun validationRequiresWhatEachProtocolNeeds() {
        assertEquals(RemoteProfile.ValidationError.HOST, RemoteProfile(host = "").validationError())
        assertEquals(RemoteProfile.ValidationError.HOST, RemoteProfile(host = "bad host").validationError())
        assertEquals(
            RemoteProfile.ValidationError.PORT,
            RemoteProfile(host = "h", port = 70000, username = "u").validationError()
        )
        assertEquals(RemoteProfile.ValidationError.USERNAME, RemoteProfile(host = "h").validationError())
        // Telnet logins can be typed by hand, so no user is required.
        assertNull(RemoteProfile(protocol = RemoteProtocol.TELNET, host = "h").validationError())
        assertEquals(
            RemoteProfile.ValidationError.PRIVATE_KEY,
            RemoteProfile(host = "h", username = "u", authType = RemoteAuthType.PRIVATE_KEY).validationError()
        )
        assertNull(RemoteProfile(host = "srv.example.org", username = "u").validationError())
        assertNull(RemoteProfile(host = "2001:db8::10", username = "u").validationError())
    }

    @Test
    fun startupCommandsAreTypedLineByLineWithCarriageReturn() {
        val profile = RemoteProfile(startupCommands = "tmux new -A -s main\n\n  # comment\n cd ~/OpenManager ")
        assertArrayEquals(
            "tmux new -A -s main\rcd ~/OpenManager\r".toByteArray(),
            profile.startupInput()
        )
        assertNull(RemoteProfile(startupCommands = " \n# only comments").startupInput())
    }

    @Test
    fun shortcutsParseAndFormatRoundTrip() {
        val text = "tmux = tmux new -A -s main\nhtop\ncd OpenManager = cd ~/OpenManager\n= broken\n"
        val shortcuts = RemoteProfile.parseShortcuts(text)
        assertEquals(4, shortcuts.size)
        assertEquals(RemoteShortcut("tmux", "tmux new -A -s main"), shortcuts[0])
        assertEquals(RemoteShortcut("htop", "htop"), shortcuts[1])
        assertEquals("cd ~/OpenManager", shortcuts[2].command)
        // A line without " = " is a command on its own.
        assertEquals("= broken", shortcuts[3].command)
        assertEquals(shortcuts, RemoteProfile.parseShortcuts(RemoteProfile.formatShortcuts(shortcuts)))
        val long = RemoteProfile.parseShortcuts("docker compose logs -f --tail 100 api").single()
        assertTrue(long.label.length <= 18)
        assertEquals("docker compose logs -f --tail 100 api", long.command)
    }

    @Test
    fun localNetworkDetectionForTelnetWarning() {
        for (host in listOf("192.168.1.1", "10.0.2.2", "172.20.0.5", "127.0.0.1", "localhost",
            "router.lan", "[fe80::1]", "100.64.1.2")) {
            assertTrue(host, RemoteProfile.isLocalNetworkHost(host))
        }
        for (host in listOf("8.8.8.8", "172.32.0.1", "example.org", "2001:db8::1", "fdomain.com")) {
            assertFalse(host, RemoteProfile.isLocalNetworkHost(host))
        }
    }

    @Test
    fun telnetLoginPromptsAreRecognized() {
        assertEquals(LoginPrompts.Prompt.USER, LoginPrompts.detect("Ubuntu 24.04\r\nrunner login: "))
        assertEquals(LoginPrompts.Prompt.USER, LoginPrompts.detect("Username:"))
        assertEquals(LoginPrompts.Prompt.PASSWORD, LoginPrompts.detect("\r\nPassword: "))
        assertEquals(LoginPrompts.Prompt.PASSWORD, LoginPrompts.detect("Contraseña:"))
        assertEquals(
            LoginPrompts.Prompt.SHELL,
            LoginPrompts.detect("\u001b]0;omtest@host: ~\u0007\u001b[01;32momtest@host\u001b[00m:~$ ")
        )
        assertEquals(LoginPrompts.Prompt.SHELL, LoginPrompts.detect("root@OpenWrt:~# "))
        assertNull(LoginPrompts.detect("Last login: Sun Nov 22 on pts/0\r\n"))
    }

    @Test
    fun knownHostsNeverAcceptChangedKeysSilently() {
        assertEquals(KnownHostsStore.Verdict.Unknown, KnownHostsStore.evaluate(null, "SHA256:a"))
        assertEquals(KnownHostsStore.Verdict.Trusted, KnownHostsStore.evaluate("SHA256:a", "SHA256:a"))
        assertEquals(
            KnownHostsStore.Verdict.Changed("SHA256:a"),
            KnownHostsStore.evaluate("SHA256:a", "SHA256:b")
        )
        assertEquals("example.org:22 ssh-ed25519", KnownHostsStore.entryKey(" Example.ORG ", 22, "ssh-ed25519"))
    }

    @Test
    fun sshTransportIsStrictAndLowLatency() {
        val ssh = resolveMainFile("java/me/zhanghai/android/files/terminal/remote/SshTransport.kt").readText()
        assertFalse("Remote terminal must verify host keys", ssh.contains("PromiscuousVerifier"))
        assertTrue(ssh.contains("tcpNoDelay = true"))
        assertTrue(ssh.contains("\"xterm-256color\""))
        assertTrue(ssh.contains("KeepAliveProvider.KEEP_ALIVE"))
        assertTrue(ssh.contains("AuthKeyboardInteractive"))
        val telnet = resolveMainFile("java/me/zhanghai/android/files/terminal/remote/TelnetTransport.kt").readText()
        assertTrue(telnet.contains("tcpNoDelay = true"))
        assertTrue(telnet.contains("WindowSizeOptionHandler"))

        val session = resolveMainFile("java/com/termux/terminal/TerminalSession.java").readText()
        val writer = session.substringAfter("TermSessionRemoteWriter").substringBefore("}.start();")
        assertTrue("Each keystroke must be flushed", writer.contains("out.flush();"))
        assertTrue(
            "Remote resize/close must stay off the main thread",
            session.contains("mRemoteControl.execute(") && session.contains("closeRemoteAsync()")
        )
    }

    @Test
    fun secretsAreSealedWithAndroidKeystore() {
        val store = resolveMainFile("java/me/zhanghai/android/files/terminal/remote/RemoteProfileStore.kt").readText()
        assertTrue(store.contains("\"AndroidKeyStore\""))
        assertTrue(store.contains("AES/GCM/NoPadding"))
        val profile = resolveMainFile("java/me/zhanghai/android/files/terminal/remote/RemoteProfile.kt").readText()
        val publicJson = profile.substringAfter("fun toPublicJson()").substringBefore("fun validationError")
        for (secret in listOf("password", "privateKey", "keyPassphrase")) {
            assertFalse("$secret must not be stored in clear text", publicJson.contains("put(\"$secret\""))
        }
    }

    @Test
    fun terminalButtonLongPressOffersRemoteProfiles() {
        val activity = resolveMainFile("java/me/zhanghai/android/files/filelist/FileListActivity.kt").readText()
        val longPress = activity.substringAfter("R.id.drawerItemTerminal)?.setOnLongClickListener")
            .substringBefore("true\n")
        assertTrue(longPress.contains("TerminalLaunchChooser.show"))
        val nav = resolveMainFile("java/me/zhanghai/android/files/navigation/NavigationItems.kt").readText()
        assertTrue(nav.substringAfter("class TerminalMenuItem").contains("ConnectionsActivity"))
        val manifest = resolveMainFile("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("terminal.remote.ConnectionsActivity"))
        assertTrue(manifest.contains("terminal.remote.RemoteProfileEditActivity"))
        val layout = resolveMainFile("res/layout/remote_profiles_activity.xml").readText()
        assertTrue(layout.contains("@drawable/add_icon_white_24dp"))
    }

    @Test
    fun remoteStringsExistInEnglishAndSpanish() {
        val names = Regex("name=\"(om_remote_[a-z_]+)\"")
        val en = names.findAll(resolveMainFile("res/values/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        val es = names.findAll(resolveMainFile("res/values-es/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        assertTrue(en.size > 50)
        assertEquals(en, es)
    }

    @Test
    fun drawerOffersConnectionsHubBelowTerminal() {
        val drawer = resolveMainFile("res/layout/file_list_activity.xml").readText()
        assertFalse("FTP entry is replaced by Connections", drawer.contains("@+id/drawerItemFtp"))
        val terminal = drawer.indexOf("@+id/drawerItemTerminal")
        val connections = drawer.indexOf("@+id/drawerItemConnections")
        assertTrue(terminal in 0 until connections)
        val hub = resolveMainFile("java/me/zhanghai/android/files/terminal/remote/ConnectionsActivity.kt").readText()
        for (type in listOf("SSH(", "TELNET(", "FTP(", "SFTP(", "SMB(", "WEBDAV(", "FTP_SERVER(")) {
            assertTrue("Missing connection type $type", hub.contains(type))
        }
        assertTrue("The device FTP server stays reachable", hub.contains("FtpServerActivity::class.java"))
    }

    @Test
    fun startupCommandsAndShortcutsAreCollapsibleBars() {
        val layout = resolveMainFile("res/layout/remote_profile_edit_activity.xml").readText()
        for (section in listOf("startup", "shortcuts")) {
            assertTrue(layout.contains("@+id/${section}Header"))
            val body = layout.substringAfter("@+id/${section}Body").substringBefore(">")
            assertTrue("$section editor starts collapsed", body.contains("android:visibility=\"gone\""))
        }
        assertTrue(
            layout.indexOf("@+id/startupBody") < layout.indexOf("@+id/startupEdit")
        )
    }

    @Test
    fun typingIsInstantByDefault() {
        val settings = resolveMainFile("java/me/zhanghai/android/files/terminal/TerminalSettings.kt").readText()
        assertTrue(settings.contains("getBoolean(PREF_INSTANT_TYPING, true)"))
        assertTrue(settings.contains("getBoolean(PREF_PREDICTIVE_ECHO, true)"))
        val activity = resolveMainFile("java/me/zhanghai/android/files/terminal/TerminalActivity.kt").readText()
        assertTrue(activity.contains("TerminalSettings.isInstantTyping(this@TerminalActivity)"))
    }

    @Test
    fun videoPlayerUsesHardwareDecodingWithFallbackOnASurface() {
        val player = resolveMainFile("java/me/zhanghai/android/files/viewer/video/VideoPlayerActivity.kt").readText()
        assertTrue(player.contains("ExoPlayer.Builder"))
        assertTrue(player.contains("setEnableDecoderFallback(true)"))
        assertTrue(player.contains("setVideoSurfaceView"))
        assertFalse(player.contains("VideoView"))
        val layout = resolveMainFile("res/layout/activity_video_player.xml").readText()
        assertTrue(layout.contains("<SurfaceView"))
        assertEquals("1:02:03", me.zhanghai.android.files.viewer.video.formatTime(3_723_000))
        assertEquals("01:05", me.zhanghai.android.files.viewer.video.formatTime(65_000))
        assertEquals("1.5×", me.zhanghai.android.files.viewer.video.formatSpeed(1.5f))
        assertEquals("2×", me.zhanghai.android.files.viewer.video.formatSpeed(2f))
    }

    @Test
    fun touchingAnyPartOfAPanelMakesItActive() {
        val activity = resolveMainFile("java/me/zhanghai/android/files/filelist/FileListActivity.kt").readText()
        val dispatch = activity.substringAfter("override fun dispatchTouchEvent(ev: android.view.MotionEvent)")
            .substringBefore("private fun isTouchInside")
        assertTrue(dispatch.contains("ACTION_DOWN"))
        assertTrue(dispatch.contains("isTouchInside(containerA, x, y) -> onPanelFocused(fragmentA)"))
        assertTrue(dispatch.contains("isTouchInside(containerB, x, y) -> onPanelFocused(fragmentB)"))
        assertTrue("Touch must still reach the views", dispatch.contains("return super.dispatchTouchEvent(ev)"))
        // Both back buttons act on the active panel.
        assertTrue(activity.contains("btnMainBack.setOnClickListener {\n            activeFragment.navigateUp()"))
    }

    @Test
    fun reopenedRemoteSessionShowsTheShellLocation() {
        val activity = resolveMainFile("java/me/zhanghai/android/files/terminal/TerminalActivity.kt").readText()
        val breadcrumb = activity.substringAfter("private fun remoteBreadcrumb(").substringBefore("\n\n")
        assertTrue(breadcrumb.contains(".title?.takeIf { it.isNotBlank() } ?: profile.endpoint"))
        val cwd = activity.substringAfter("private fun updateCwdDisplay(").substringBefore("if (!rawPath")
        assertTrue(cwd.contains("remoteBreadcrumb(it)"))
    }

    @Test
    fun zoomStepperIsGoneAndKeyDeckHasThreeLayouts() {
        val layout = resolveMainFile("res/layout/activity_terminal.xml").readText()
        val pill = layout.substringAfter("@+id/layoutZoomPill").substringBefore(">")
        assertTrue("Pinch zoom replaces the - / + stepper", pill.contains("android:visibility=\"gone\""))
        assertTrue(layout.contains("@+id/btnShowExtraKeys"))
        val deck = resolveMainFile("java/me/zhanghai/android/files/terminal/TerminalExtraKeysView.kt").readText()
        assertTrue(deck.contains("enum class DeckMode { FULL, COMPACT, HIDDEN }"))
        assertTrue("⋮ is on every visible layout", deck.substringAfter("private fun addChipRow").contains("createMenuButton()"))
        assertTrue(
            "deckMode must be declared before init {} builds the keys",
            deck.indexOf("var deckMode") < deck.indexOf("    init {")
        )
    }

    @Test
    fun severalSessionsRunAtOnce() {
        val manager = resolveMainFile("java/me/zhanghai/android/files/terminal/ScriptExecutionManager.kt").readText()
        assertTrue(manager.contains("private val entries = CopyOnWriteArrayList<Entry>()"))
        val start = manager.substringAfter("fun startExecution(").substringBefore("fun focus(")
        assertFalse("Starting a session must not end the others", start.contains("finishIfRunning"))
        val activity = resolveMainFile("java/me/zhanghai/android/files/terminal/TerminalActivity.kt").readText()
        assertTrue(activity.contains("MENU_SESSION_BASE"))
        val runner = resolveMainFile("java/me/zhanghai/android/files/filelist/ScriptRunnerHelper.kt").readText()
        assertTrue(runner.contains("R.string.script_exec_conflict_run_parallel"))
        val service = resolveMainFile("java/me/zhanghai/android/files/terminal/ScriptExecutionService.kt").readText()
        assertTrue("One script ending must not drop the others' notification",
            service.substringAfter("private fun handleFinished").contains("runningCount > 0"))
    }

    @Test
    fun scrollingHistoryIsNotYankedByOutput() {
        val view = resolveMainFile("java/com/termux/view/TerminalView.java").readText()
        assertTrue(view.contains("boolean readingHistory = mTopRow < 0"))
        assertTrue("fling must not diff against mTopRow", view.contains("int diff = newY - mLastY;"))
        assertTrue(view.contains("public void scrollToBottomForInput()"))
    }

    @Test
    fun composeBarSendsWholeLines() {
        val activity = resolveMainFile("java/me/zhanghai/android/files/terminal/TerminalActivity.kt").readText()
        val compose = activity.substringAfter("private fun setupComposeBar()")
        assertTrue(compose.contains("session.emulator?.paste(text)"))
        assertTrue(compose.contains("session.write(\"\\r\")"))
        val layout = resolveMainFile("res/layout/activity_terminal.xml").readText()
        assertTrue(layout.contains("@+id/composeBar"))
    }

    @Test
    fun vpsFilesStartWhereTheShellIs() {
        assertEquals("~/probe", RemoteFilesLauncher.remoteDirFromTitle("omtest@ci: ~/probe"))
        assertEquals("/srv/app", RemoteFilesLauncher.remoteDirFromTitle("root@vps: /srv/app"))
        assertEquals("~", RemoteFilesLauncher.remoteDirFromTitle("~"))
        // A bare endpoint or a program's title (vim, htop) falls back to the home folder.
        assertNull(RemoteFilesLauncher.remoteDirFromTitle("omtest@10.0.2.2:2222"))
        assertNull(RemoteFilesLauncher.remoteDirFromTitle("htop"))
        assertNull(RemoteFilesLauncher.remoteDirFromTitle(null))
    }

    @Test
    fun folderChipOpensTheVpsFiles() {
        val activity = resolveMainFile("java/me/zhanghai/android/files/terminal/TerminalActivity.kt").readText()
        assertTrue(activity.contains("openRemoteFiles(profile)"))
        assertTrue(activity.contains("setOnLongClickListener"))
        // Only over a live session: otherwise reconnect instead of an internal SSH error.
        assertTrue(activity.contains("isRunning != true"))
        assertTrue(activity.contains("RemoteFilesLauncher.describeError"))
    }
}
