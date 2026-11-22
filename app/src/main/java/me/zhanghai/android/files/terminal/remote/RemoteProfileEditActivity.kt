/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity

/** Creates or edits a [RemoteProfile]. */
class RemoteProfileEditActivity : AppActivity() {
    private var original: RemoteProfile? = null

    private lateinit var protocolToggle: MaterialButtonToggleGroup
    private lateinit var authToggle: MaterialButtonToggleGroup
    private lateinit var telnetWarning: View
    private lateinit var authSection: View
    private lateinit var keySection: View
    private lateinit var nameEdit: TextInputEditText
    private lateinit var hostLayout: TextInputLayout
    private lateinit var hostEdit: TextInputEditText
    private lateinit var portLayout: TextInputLayout
    private lateinit var portEdit: TextInputEditText
    private lateinit var usernameLayout: TextInputLayout
    private lateinit var usernameEdit: TextInputEditText
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var passwordEdit: TextInputEditText
    private lateinit var privateKeyLayout: TextInputLayout
    private lateinit var privateKeyEdit: TextInputEditText
    private lateinit var passphraseEdit: TextInputEditText
    private lateinit var startupEdit: TextInputEditText
    private lateinit var shortcutsEdit: TextInputEditText
    private lateinit var keepAliveEdit: TextInputEditText

    private val importKeyLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importKey(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.remote_profile_edit_activity)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        original = intent.getStringExtra(EXTRA_PROFILE_ID)?.let { RemoteProfileStore.get(this, it) }
        setTitle(if (original == null) R.string.om_remote_profile_add else R.string.om_remote_profile_edit)

        protocolToggle = findViewById(R.id.protocolToggle)
        authToggle = findViewById(R.id.authToggle)
        telnetWarning = findViewById(R.id.telnetWarning)
        authSection = findViewById(R.id.authSection)
        keySection = findViewById(R.id.keySection)
        nameEdit = findViewById(R.id.nameEdit)
        hostLayout = findViewById(R.id.hostLayout)
        hostEdit = findViewById(R.id.hostEdit)
        portLayout = findViewById(R.id.portLayout)
        portEdit = findViewById(R.id.portEdit)
        usernameLayout = findViewById(R.id.usernameLayout)
        usernameEdit = findViewById(R.id.usernameEdit)
        passwordLayout = findViewById(R.id.passwordLayout)
        passwordEdit = findViewById(R.id.passwordEdit)
        privateKeyLayout = findViewById(R.id.privateKeyLayout)
        privateKeyEdit = findViewById(R.id.privateKeyEdit)
        passphraseEdit = findViewById(R.id.passphraseEdit)
        startupEdit = findViewById(R.id.startupEdit)
        shortcutsEdit = findViewById(R.id.shortcutsEdit)
        keepAliveEdit = findViewById(R.id.keepAliveEdit)

        if (savedInstanceState == null) {
            bind(original ?: RemoteProfile())
        }
        protocolToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val protocol = protocolFor(checkedId)
            // Keep a custom port, but follow the protocol default when it was the other default.
            val port = portEdit.text?.toString()?.toIntOrNull()
            if (port == null || RemoteProtocol.entries.any { it.defaultPort == port }) {
                portEdit.setText(protocol.defaultPort.toString())
            }
            updateVisibility()
        }
        authToggle.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) updateVisibility() }
        findViewById<MaterialButton>(R.id.importKeyButton).setOnClickListener {
            importKeyLauncher.launch("*/*")
        }
        findViewById<MaterialButton>(R.id.saveConnectButton).setOnClickListener {
            val profile = collectValid() ?: return@setOnClickListener
            RemoteProfileStore.save(this, profile)
            RemoteProfilesActivity.connect(this, profile)
            finish()
        }
        setupShortcutPresets()
        updateVisibility()
    }

    private fun bind(profile: RemoteProfile) {
        protocolToggle.check(
            if (profile.protocol == RemoteProtocol.SSH) R.id.protocolSshButton else R.id.protocolTelnetButton
        )
        authToggle.check(
            if (profile.authType == RemoteAuthType.PASSWORD) R.id.authPasswordButton else R.id.authKeyButton
        )
        nameEdit.setText(profile.name)
        hostEdit.setText(profile.host)
        portEdit.setText(profile.port.toString())
        usernameEdit.setText(profile.username)
        passwordEdit.setText(profile.password.orEmpty())
        privateKeyEdit.setText(profile.privateKey.orEmpty())
        passphraseEdit.setText(profile.keyPassphrase.orEmpty())
        startupEdit.setText(profile.startupCommands)
        shortcutsEdit.setText(RemoteProfile.formatShortcuts(profile.shortcuts))
        keepAliveEdit.setText(profile.keepAliveSeconds.toString())
    }

    private fun protocolFor(buttonId: Int): RemoteProtocol =
        if (buttonId == R.id.protocolTelnetButton) RemoteProtocol.TELNET else RemoteProtocol.SSH

    private fun updateVisibility() {
        val isSsh = protocolFor(protocolToggle.checkedButtonId) == RemoteProtocol.SSH
        val isKey = isSsh && authToggle.checkedButtonId == R.id.authKeyButton
        telnetWarning.visibility = if (isSsh) View.GONE else View.VISIBLE
        authSection.visibility = if (isSsh) View.VISIBLE else View.GONE
        keySection.visibility = if (isKey) View.VISIBLE else View.GONE
        passwordLayout.hint = getString(
            if (isKey) R.string.om_remote_field_password_fallback else R.string.om_remote_field_password
        )
        usernameLayout.helperText = if (isSsh) null else getString(R.string.om_remote_field_username_telnet_helper)
        passwordLayout.helperText = getString(
            if (isSsh) R.string.om_remote_field_password_helper else R.string.om_remote_field_password_telnet_helper
        )
    }

    private fun setupShortcutPresets() {
        val group = findViewById<ChipGroup>(R.id.shortcutPresets)
        for ((label, command) in PRESETS) {
            group.addView(Chip(this).apply {
                text = label
                setOnClickListener { appendShortcut(label, command) }
            })
        }
    }

    private fun appendShortcut(label: String, command: String) {
        val current = shortcutsEdit.text?.toString().orEmpty().trimEnd()
        val line = if (label == command) command else "$label = $command"
        if (current.lines().any { it.trim() == line }) return
        shortcutsEdit.setText(if (current.isEmpty()) line else "$current\n$line")
        shortcutsEdit.setSelection(shortcutsEdit.text?.length ?: 0)
    }

    private fun importKey(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > MAX_KEY_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
        if (text.isNullOrBlank() || !text.contains("PRIVATE KEY")) {
            Toast.makeText(this, R.string.om_remote_error_key_format, Toast.LENGTH_LONG).show()
            return
        }
        privateKeyEdit.setText(text.trim())
        privateKeyLayout.error = null
    }

    /** Builds the profile from the form, showing inline errors; null when something is missing. */
    private fun collectValid(): RemoteProfile? {
        val protocol = protocolFor(protocolToggle.checkedButtonId)
        val base = original ?: RemoteProfile()
        val profile = base.copy(
            name = nameEdit.text?.toString()?.trim().orEmpty(),
            protocol = protocol,
            host = hostEdit.text?.toString()?.trim().orEmpty(),
            port = portEdit.text?.toString()?.trim()?.toIntOrNull() ?: protocol.defaultPort,
            username = usernameEdit.text?.toString()?.trim().orEmpty(),
            authType = if (protocol == RemoteProtocol.SSH && authToggle.checkedButtonId == R.id.authKeyButton) {
                RemoteAuthType.PRIVATE_KEY
            } else {
                RemoteAuthType.PASSWORD
            },
            password = passwordEdit.text?.toString()?.takeIf { it.isNotEmpty() },
            privateKey = privateKeyEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            keyPassphrase = passphraseEdit.text?.toString()?.takeIf { it.isNotEmpty() },
            startupCommands = startupEdit.text?.toString()?.trim().orEmpty(),
            shortcuts = RemoteProfile.parseShortcuts(shortcutsEdit.text?.toString().orEmpty()),
            keepAliveSeconds = keepAliveEdit.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(0, 600)
                ?: RemoteProfile.DEFAULT_KEEP_ALIVE_SECONDS,
            // Re-confirm Telnet exposure if the destination changed.
            telnetRiskAccepted = base.telnetRiskAccepted && base.host == hostEdit.text?.toString()?.trim()
        )
        hostLayout.error = null
        portLayout.error = null
        usernameLayout.error = null
        privateKeyLayout.error = null
        when (profile.validationError()) {
            RemoteProfile.ValidationError.HOST -> hostLayout.error = getString(R.string.om_remote_error_host)
            RemoteProfile.ValidationError.PORT -> portLayout.error = getString(R.string.om_remote_error_port)
            RemoteProfile.ValidationError.USERNAME ->
                usernameLayout.error = getString(R.string.om_remote_error_username)
            RemoteProfile.ValidationError.PRIVATE_KEY ->
                privateKeyLayout.error = getString(R.string.om_remote_error_private_key)
            null -> return profile
        }
        return null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SAVE, Menu.NONE, R.string.save)
            .setIcon(R.drawable.check_icon_control_normal_24dp)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            confirmDiscard()
            true
        }
        MENU_SAVE -> {
            collectValid()?.let {
                RemoteProfileStore.save(this, it)
                Toast.makeText(this, R.string.om_remote_profile_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmDiscard()
    }

    private fun confirmDiscard() {
        val hasInput = !hostEdit.text.isNullOrBlank() || !usernameEdit.text.isNullOrBlank()
        val changed = if (original == null) hasInput else {
            val now = runCatching { snapshot() }.getOrNull()
            now != null && now != snapshotOf(original!!)
        }
        if (!changed) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.om_remote_discard_changes)
            .setPositiveButton(R.string.om_remote_discard) { _, _ -> finish() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun snapshot(): List<String> = listOf(
        protocolFor(protocolToggle.checkedButtonId).name, nameEdit.text.toString().trim(),
        hostEdit.text.toString().trim(), portEdit.text.toString().trim(), usernameEdit.text.toString().trim(),
        passwordEdit.text.toString(), privateKeyEdit.text.toString().trim(), passphraseEdit.text.toString(),
        startupEdit.text.toString().trim(),
        RemoteProfile.formatShortcuts(RemoteProfile.parseShortcuts(shortcutsEdit.text.toString())),
        keepAliveEdit.text.toString().trim()
    )

    private fun snapshotOf(profile: RemoteProfile): List<String> = listOf(
        profile.protocol.name, profile.name, profile.host, profile.port.toString(), profile.username,
        profile.password.orEmpty(), profile.privateKey.orEmpty(), profile.keyPassphrase.orEmpty(),
        profile.startupCommands, RemoteProfile.formatShortcuts(profile.shortcuts),
        profile.keepAliveSeconds.toString()
    )

    companion object {
        private const val EXTRA_PROFILE_ID = "me.zhanghai.android.files.terminal.remote.extra.PROFILE_ID"
        private const val MENU_SAVE = 1
        private const val MAX_KEY_BYTES = 64 * 1024

        private val PRESETS = listOf(
            "tmux" to "tmux new -A -s main",
            "tmux ls" to "tmux ls",
            "htop" to "htop",
            "cd OpenManager" to "cd ~/OpenManager",
            "git status" to "git status",
            "ls -la" to "ls -la --color=auto",
            "sudo -i" to "sudo -i",
            "docker ps" to "docker ps",
            "logs" to "journalctl -f",
            "df -h" to "df -h"
        )

        fun createIntent(context: Context, profileId: String?): Intent =
            Intent(context, RemoteProfileEditActivity::class.java).apply {
                if (profileId != null) putExtra(EXTRA_PROFILE_ID, profileId)
            }
    }
}
