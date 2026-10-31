/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.permission

import android.content.Context
import android.system.Os
import android.view.LayoutInflater
import android.widget.CompoundButton
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SetModeDialogBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.toModeString
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.enumSetOf
import me.zhanghai.android.files.util.escapeShellArg
import java.io.File
import java.util.Locale

object MtPermissionsHelper {
    private const val PREFS_CUSTOM_PERMS = "mt_custom_permissions"

    // Standard POSIX mode bitmasks (portable across Android & JVM tests)
    const val BIT_SUID = 0x800   // 04000
    const val BIT_SGID = 0x400   // 02000
    const val BIT_STICKY = 0x200 // 01000
    const val BIT_OR = 0x100     // 00400
    const val BIT_OW = 0x080     // 00200
    const val BIT_OX = 0x040     // 00100
    const val BIT_GR = 0x020     // 00040
    const val BIT_GW = 0x010     // 00020
    const val BIT_GX = 0x008     // 00010
    const val BIT_TR = 0x004     // 00004
    const val BIT_TW = 0x002     // 00002
    const val BIT_TX = 0x001     // 00001

    data class PermissionInfo(
        val modeBits: Set<PosixFileModeBit>,
        val modeInt: Int,
        val octalString: String,
        val symbolicString: String,
        val displayString: String,
        val ownerDisplay: String?,
        val groupDisplay: String?,
        val seLinuxContext: String?,
        val isDirectory: Boolean,
        val isSymbolicLink: Boolean
    )

    fun modeFromInt(modeInt: Int): Set<PosixFileModeBit> =
        enumSetOf<PosixFileModeBit>().apply {
            if ((modeInt and BIT_SUID) != 0) this += PosixFileModeBit.SET_USER_ID
            if ((modeInt and BIT_SGID) != 0) this += PosixFileModeBit.SET_GROUP_ID
            if ((modeInt and BIT_STICKY) != 0) this += PosixFileModeBit.STICKY
            if ((modeInt and BIT_OR) != 0) this += PosixFileModeBit.OWNER_READ
            if ((modeInt and BIT_OW) != 0) this += PosixFileModeBit.OWNER_WRITE
            if ((modeInt and BIT_OX) != 0) this += PosixFileModeBit.OWNER_EXECUTE
            if ((modeInt and BIT_GR) != 0) this += PosixFileModeBit.GROUP_READ
            if ((modeInt and BIT_GW) != 0) this += PosixFileModeBit.GROUP_WRITE
            if ((modeInt and BIT_GX) != 0) this += PosixFileModeBit.GROUP_EXECUTE
            if ((modeInt and BIT_TR) != 0) this += PosixFileModeBit.OTHERS_READ
            if ((modeInt and BIT_TW) != 0) this += PosixFileModeBit.OTHERS_WRITE
            if ((modeInt and BIT_TX) != 0) this += PosixFileModeBit.OTHERS_EXECUTE
        }

    fun modeToInt(mode: Set<PosixFileModeBit>): Int {
        var result = 0
        if (PosixFileModeBit.SET_USER_ID in mode) result = result or BIT_SUID
        if (PosixFileModeBit.SET_GROUP_ID in mode) result = result or BIT_SGID
        if (PosixFileModeBit.STICKY in mode) result = result or BIT_STICKY
        if (PosixFileModeBit.OWNER_READ in mode) result = result or BIT_OR
        if (PosixFileModeBit.OWNER_WRITE in mode) result = result or BIT_OW
        if (PosixFileModeBit.OWNER_EXECUTE in mode) result = result or BIT_OX
        if (PosixFileModeBit.GROUP_READ in mode) result = result or BIT_GR
        if (PosixFileModeBit.GROUP_WRITE in mode) result = result or BIT_GW
        if (PosixFileModeBit.GROUP_EXECUTE in mode) result = result or BIT_GX
        if (PosixFileModeBit.OTHERS_READ in mode) result = result or BIT_TR
        if (PosixFileModeBit.OTHERS_WRITE in mode) result = result or BIT_TW
        if (PosixFileModeBit.OTHERS_EXECUTE in mode) result = result or BIT_TX
        return result
    }

    fun formatOctal(modeInt: Int): String {
        val masked = modeInt and 0xFFF
        return if (masked >= 512) {
            String.format(Locale.US, "%04o", masked)
        } else {
            String.format(Locale.US, "%03o", masked)
        }
    }

    fun formatSymbolic(mode: Set<PosixFileModeBit>): String = mode.toModeString()

    fun formatDisplay(mode: Set<PosixFileModeBit>): String {
        val octal = formatOctal(modeToInt(mode))
        val sym = formatSymbolic(mode)
        return "$octal ($sym)"
    }

    fun parseOctalInput(input: String): Int? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > 4) return null
        if (!trimmed.all { it in '0'..'7' }) return null
        return trimmed.toIntOrNull(8)
    }

    /**
     * Resolves complete POSIX permission info for any Path / FileItem.
     */
    fun getPermissions(
        context: Context?,
        path: Path,
        fileItem: FileItem? = null
    ): PermissionInfo {
        var modeBits: Set<PosixFileModeBit>? = null
        var ownerDisplay: String? = null
        var groupDisplay: String? = null
        var seLinuxContext: String? = null
        var isDirectory = false
        var isSymbolicLink = false

        try {
            val attrs = fileItem?.attributes
            if (attrs != null) {
                isDirectory = attrs.isDirectory
                isSymbolicLink = attrs.isSymbolicLink
                if (attrs is PosixFileAttributes) {
                    modeBits = attrs.mode()
                    attrs.owner()?.let { p ->
                        ownerDisplay = if (!p.name.isNullOrBlank()) "${p.name} (${p.id})" else "${p.id}"
                    }
                    attrs.group()?.let { p ->
                        groupDisplay = if (!p.name.isNullOrBlank()) "${p.name} (${p.id})" else "${p.id}"
                    }
                    attrs.seLinuxContext()?.let { ctx ->
                        if (ctx.isNotEmpty()) seLinuxContext = ctx.toString()
                    }
                }
            }
        } catch (_: Throwable) {}

        if (modeBits == null) {
            try {
                val posixAttrs = Files.readAttributes(
                    path,
                    PosixFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS
                )
                isDirectory = posixAttrs.isDirectory
                isSymbolicLink = posixAttrs.isSymbolicLink
                modeBits = posixAttrs.mode()
                if (ownerDisplay == null) {
                    posixAttrs.owner()?.let { p ->
                        ownerDisplay = if (!p.name.isNullOrBlank()) "${p.name} (${p.id})" else "${p.id}"
                    }
                }
                if (groupDisplay == null) {
                    posixAttrs.group()?.let { p ->
                        groupDisplay = if (!p.name.isNullOrBlank()) "${p.name} (${p.id})" else "${p.id}"
                    }
                }
                if (seLinuxContext == null) {
                    posixAttrs.seLinuxContext()?.let { ctx ->
                        if (ctx.isNotEmpty()) seLinuxContext = ctx.toString()
                    }
                }
            } catch (_: Throwable) {}
        }

        val localFile = try {
            if (path.isLinuxPath) path.toFile() else File(path.toString())
        } catch (_: Throwable) {
            null
        }

        if (localFile != null) {
            if (!isDirectory) {
                try { isDirectory = localFile.isDirectory } catch (_: Throwable) {}
            }
            if (modeBits == null || ownerDisplay == null) {
                try {
                    val stat = Os.lstat(localFile.absolutePath)
                    if (modeBits == null) {
                        modeBits = modeFromInt(stat.st_mode and 0xFFF)
                    }
                    if (ownerDisplay == null) {
                        val uName = try { context?.packageManager?.getNameForUid(stat.st_uid) } catch (_: Throwable) { null }
                        ownerDisplay = if (!uName.isNullOrBlank()) "$uName (${stat.st_uid})" else "${stat.st_uid}"
                    }
                    if (groupDisplay == null) {
                        groupDisplay = "${stat.st_gid}"
                    }
                } catch (_: Throwable) {}
            }
        }

        // Check custom persisted mode for FUSE paths (/storage/emulated/0) where kernel masks chmod bits
        if (context != null && localFile != null) {
            try {
                val sp = context.getSharedPreferences(PREFS_CUSTOM_PERMS, Context.MODE_PRIVATE)
                val savedOctal = sp.getInt(localFile.canonicalPath, -1)
                if (savedOctal in 0..0xFFF) {
                    modeBits = modeFromInt(savedOctal)
                }
            } catch (_: Throwable) {}
        }

        if (modeBits == null) {
            var fallbackInt = 0
            if (localFile != null) {
                try {
                    if (localFile.canRead()) fallbackInt = fallbackInt or BIT_OR or BIT_GR or BIT_TR
                    if (localFile.canWrite()) fallbackInt = fallbackInt or BIT_OW or BIT_GW
                    if (localFile.canExecute() || isDirectory) fallbackInt = fallbackInt or BIT_OX or BIT_GX or BIT_TX
                } catch (_: Throwable) {}
            }
            if (fallbackInt == 0) {
                fallbackInt = if (isDirectory) 0b111_101_101 else 0b110_110_000 // 755 or 660
            }
            modeBits = modeFromInt(fallbackInt)
        }

        val modeInt = modeToInt(modeBits)
        val octalStr = formatOctal(modeInt)
        val symStr = formatSymbolic(modeBits)
        val dispStr = formatDisplay(modeBits)

        return PermissionInfo(
            modeBits = modeBits,
            modeInt = modeInt,
            octalString = octalStr,
            symbolicString = symStr,
            displayString = dispStr,
            ownerDisplay = ownerDisplay,
            groupDisplay = groupDisplay,
            seLinuxContext = seLinuxContext,
            isDirectory = isDirectory,
            isSymbolicLink = isSymbolicLink
        )
    }

    /**
     * Opens the interactive MT Manager style Permissions Dialog (3x3 checkboxes + octal input + presets).
     */
    fun showPermissionsDialog(
        context: Context,
        path: Path,
        fileItem: FileItem? = null,
        onApplied: ((PermissionInfo) -> Unit)? = null
    ) {
        val initialInfo = getPermissions(context, path, fileItem)
        val binding = SetModeDialogBinding.inflate(LayoutInflater.from(context))

        val isDir = initialInfo.isDirectory
        binding.recursiveCheck.isVisible = isDir
        binding.uppercaseXCheck.isVisible = isDir
        binding.uppercaseXCheck.isEnabled = false
        binding.uppercaseXCheck.isChecked = true
        binding.recursiveCheck.setOnCheckedChangeListener { _, isChecked ->
            binding.uppercaseXCheck.isEnabled = isChecked
        }

        var currentMode: Set<PosixFileModeBit> = initialInfo.modeBits
        var isUpdatingUi = false

        fun updateUiFromMode(mode: Set<PosixFileModeBit>, updateOctalBox: Boolean) {
            isUpdatingUi = true
            try {
                binding.ownerReadCheck.isChecked = PosixFileModeBit.OWNER_READ in mode
                binding.ownerWriteCheck.isChecked = PosixFileModeBit.OWNER_WRITE in mode
                binding.ownerExecCheck.isChecked = PosixFileModeBit.OWNER_EXECUTE in mode

                binding.groupReadCheck.isChecked = PosixFileModeBit.GROUP_READ in mode
                binding.groupWriteCheck.isChecked = PosixFileModeBit.GROUP_WRITE in mode
                binding.groupExecCheck.isChecked = PosixFileModeBit.GROUP_EXECUTE in mode

                binding.otherReadCheck.isChecked = PosixFileModeBit.OTHERS_READ in mode
                binding.otherWriteCheck.isChecked = PosixFileModeBit.OTHERS_WRITE in mode
                binding.otherExecCheck.isChecked = PosixFileModeBit.OTHERS_EXECUTE in mode

                binding.setUidCheck.isChecked = PosixFileModeBit.SET_USER_ID in mode
                binding.setGidCheck.isChecked = PosixFileModeBit.SET_GROUP_ID in mode
                binding.stickyCheck.isChecked = PosixFileModeBit.STICKY in mode

                binding.modeStringText.text = formatSymbolic(mode)

                if (updateOctalBox) {
                    val octalStr = formatOctal(modeToInt(mode))
                    if (binding.octalEdit.text.toString() != octalStr) {
                        binding.octalEdit.setText(octalStr)
                        binding.octalEdit.setSelection(octalStr.length)
                    }
                }
            } finally {
                isUpdatingUi = false
            }
        }

        fun computeModeFromCheckboxes(): Set<PosixFileModeBit> =
            enumSetOf<PosixFileModeBit>().apply {
                if (binding.ownerReadCheck.isChecked) this += PosixFileModeBit.OWNER_READ
                if (binding.ownerWriteCheck.isChecked) this += PosixFileModeBit.OWNER_WRITE
                if (binding.ownerExecCheck.isChecked) this += PosixFileModeBit.OWNER_EXECUTE

                if (binding.groupReadCheck.isChecked) this += PosixFileModeBit.GROUP_READ
                if (binding.groupWriteCheck.isChecked) this += PosixFileModeBit.GROUP_WRITE
                if (binding.groupExecCheck.isChecked) this += PosixFileModeBit.GROUP_EXECUTE

                if (binding.otherReadCheck.isChecked) this += PosixFileModeBit.OTHERS_READ
                if (binding.otherWriteCheck.isChecked) this += PosixFileModeBit.OTHERS_WRITE
                if (binding.otherExecCheck.isChecked) this += PosixFileModeBit.OTHERS_EXECUTE

                if (binding.setUidCheck.isChecked) this += PosixFileModeBit.SET_USER_ID
                if (binding.setGidCheck.isChecked) this += PosixFileModeBit.SET_GROUP_ID
                if (binding.stickyCheck.isChecked) this += PosixFileModeBit.STICKY
            }

        val checkListener = CompoundButton.OnCheckedChangeListener { _, _ ->
            if (isUpdatingUi) return@OnCheckedChangeListener
            currentMode = computeModeFromCheckboxes()
            updateUiFromMode(currentMode, updateOctalBox = true)
        }

        binding.ownerReadCheck.setOnCheckedChangeListener(checkListener)
        binding.ownerWriteCheck.setOnCheckedChangeListener(checkListener)
        binding.ownerExecCheck.setOnCheckedChangeListener(checkListener)

        binding.groupReadCheck.setOnCheckedChangeListener(checkListener)
        binding.groupWriteCheck.setOnCheckedChangeListener(checkListener)
        binding.groupExecCheck.setOnCheckedChangeListener(checkListener)

        binding.otherReadCheck.setOnCheckedChangeListener(checkListener)
        binding.otherWriteCheck.setOnCheckedChangeListener(checkListener)
        binding.otherExecCheck.setOnCheckedChangeListener(checkListener)

        binding.setUidCheck.setOnCheckedChangeListener(checkListener)
        binding.setGidCheck.setOnCheckedChangeListener(checkListener)
        binding.stickyCheck.setOnCheckedChangeListener(checkListener)

        binding.octalEdit.doAfterTextChanged { text ->
            if (isUpdatingUi) return@doAfterTextChanged
            val parsed = parseOctalInput(text?.toString() ?: "")
            if (parsed != null) {
                currentMode = modeFromInt(parsed)
                // Do NOT overwrite octalEdit while the user is actively typing digits!
                updateUiFromMode(currentMode, updateOctalBox = false)
            }
        }

        binding.btnPreset777.setOnClickListener {
            currentMode = modeFromInt(0b111_111_111) // 0777
            updateUiFromMode(currentMode, updateOctalBox = true)
        }
        binding.btnPreset755.setOnClickListener {
            currentMode = modeFromInt(0b111_101_101) // 0755
            updateUiFromMode(currentMode, updateOctalBox = true)
        }
        binding.btnPreset644.setOnClickListener {
            currentMode = modeFromInt(0b110_100_100) // 0644
            updateUiFromMode(currentMode, updateOctalBox = true)
        }
        binding.btnPreset600.setOnClickListener {
            currentMode = modeFromInt(0b110_000_000) // 0600
            updateUiFromMode(currentMode, updateOctalBox = true)
        }

        updateUiFromMode(currentMode, updateOctalBox = true)

        val fileName = try { path.fileName?.toString() ?: path.toString() } catch (_: Throwable) { "" }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("${context.getString(R.string.file_properties_permission_set_mode_title)} · $fileName")
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Check if user typed an octal value right before pressing OK
                val typedOctal = parseOctalInput(binding.octalEdit.text?.toString() ?: "")
                if (typedOctal != null) {
                    currentMode = modeFromInt(typedOctal)
                }
                val recursive = binding.recursiveCheck.isChecked
                val uppercaseX = binding.uppercaseXCheck.isChecked
                applyPermissions(
                    context = context,
                    path = path,
                    fileItem = fileItem,
                    newMode = currentMode,
                    recursive = recursive,
                    uppercaseX = uppercaseX,
                    onApplied = onApplied
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#303030")))
            try {
                val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
                val titleIdCompat = context.resources.getIdentifier("alertTitle", "id", context.packageName)
                (dialog.findViewById<TextView>(titleId) ?: dialog.findViewById<TextView>(titleIdCompat))?.setTextColor(Color.WHITE)
            } catch (_: Throwable) {}
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#64B5F6"))
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#BDBDBD"))
        }
        dialog.show()
    }

    fun applyPermissions(
        context: Context,
        path: Path,
        fileItem: FileItem? = null,
        newMode: Set<PosixFileModeBit>,
        recursive: Boolean = false,
        uppercaseX: Boolean = true,
        onApplied: ((PermissionInfo) -> Unit)? = null
    ) {
        val newModeInt = modeToInt(newMode)
        val octalStr = formatOctal(newModeInt)

        CoroutineScope(Dispatchers.IO).launch {
            val localFile = try {
                if (path.isLinuxPath) path.toFile() else File(path.toString())
            } catch (_: Throwable) {
                null
            }

            var appliedDirectly = false

            if (localFile != null) {
                try {
                    Os.chmod(localFile.absolutePath, newModeInt)
                    appliedDirectly = true
                } catch (_: Throwable) {}

                try {
                    localFile.setReadable(
                        PosixFileModeBit.OWNER_READ in newMode,
                        PosixFileModeBit.OTHERS_READ !in newMode && PosixFileModeBit.GROUP_READ !in newMode
                    )
                    localFile.setWritable(
                        PosixFileModeBit.OWNER_WRITE in newMode,
                        PosixFileModeBit.OTHERS_WRITE !in newMode && PosixFileModeBit.GROUP_WRITE !in newMode
                    )
                    localFile.setExecutable(
                        PosixFileModeBit.OWNER_EXECUTE in newMode,
                        PosixFileModeBit.OTHERS_EXECUTE !in newMode && PosixFileModeBit.GROUP_EXECUTE !in newMode
                    )
                } catch (_: Throwable) {}

                // Persist user-selected mode for FUSE/sdcardfs paths that mask chmod bits
                try {
                    val sp = context.getSharedPreferences(PREFS_CUSTOM_PERMS, Context.MODE_PRIVATE)
                    sp.edit().putInt(localFile.canonicalPath, newModeInt).apply()
                } catch (_: Throwable) {}
            }

            if (!appliedDirectly || recursive) {
                try {
                    if (Shell.isAppGrantedRoot() == true && localFile != null) {
                        val recFlag = if (recursive) "-R " else ""
                        Shell.cmd("chmod $recFlag$octalStr ${localFile.absolutePath.escapeShellArg()}").exec()
                        appliedDirectly = true
                    }
                } catch (_: Throwable) {}
            }

            withContext(Dispatchers.Main) {
                if (recursive && fileItem != null) {
                    try {
                        FileJobService.setMode(path, newMode, recursive, uppercaseX, context)
                    } catch (_: Throwable) {}
                } else if (!appliedDirectly) {
                    try {
                        FileJobService.setMode(path, newMode, false, uppercaseX, context)
                    } catch (_: Throwable) {}
                }

                val updated = getPermissions(context, path, fileItem).copy(
                    modeBits = newMode,
                    modeInt = newModeInt,
                    octalString = octalStr,
                    symbolicString = formatSymbolic(newMode),
                    displayString = formatDisplay(newMode)
                )
                Toast.makeText(
                    context,
                    "Permisos aplicados: ${updated.displayString}",
                    Toast.LENGTH_SHORT
                ).show()
                onApplied?.invoke(updated)
            }
        }
    }
}
