/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import java.util.Locale

/**
 * Pure Kotlin state & formatting helpers for the Terminal HUD, interactive environment badge,
 * working-directory breadcrumbs, elapsed runtime counter, and Extra Keys modifier logic.
 */
object TerminalUiHelper {

    enum class ModifierState {
        OFF,
        ONE_SHOT,
        LOCKED
    }

    data class SessionBadgeSpec(
        val label: String,
        val dotColor: Int,
        val haloColor: Int,
        val bgColor: Int,
        val strokeColor: Int,
        val textColor: Int
    )

    private val APP_INTERNAL_ROOTS = listOf(
        "/data/user/0/com.y.om",
        "/data/data/com.y.om",
        "/data/data/me.zhanghai.android.files"
    )

    fun onTap(state: ModifierState): ModifierState = when (state) {
        ModifierState.OFF -> ModifierState.ONE_SHOT
        ModifierState.ONE_SHOT, ModifierState.LOCKED -> ModifierState.OFF
    }

    fun onLongPress(state: ModifierState): ModifierState = when (state) {
        ModifierState.LOCKED -> ModifierState.OFF
        ModifierState.OFF, ModifierState.ONE_SHOT -> ModifierState.LOCKED
    }

    fun consume(state: ModifierState): Pair<Boolean, ModifierState> = when (state) {
        ModifierState.OFF -> false to ModifierState.OFF
        ModifierState.ONE_SHOT -> true to ModifierState.OFF
        ModifierState.LOCKED -> true to ModifierState.LOCKED
    }

    fun applyModifiersToKey(action: String, ctrl: Boolean, alt: Boolean): String {
        if (!ctrl && !alt) return action
        var out = action
        if (ctrl && out.length == 1) {
            val ch = out[0]
            out = when {
                ch in 'a'..'z' || ch in 'A'..'Z' -> {
                    ((ch.uppercaseChar().code - 64).toChar()).toString()
                }
                ch == '@' || ch == ' ' || ch == '2' -> "\u0000"
                ch == '[' || ch == '3' -> "\u001b"
                ch == '\\' || ch == '4' -> "\u001c"
                ch == ']' || ch == '5' -> "\u001d"
                ch == '^' || ch == '6' -> "\u001e"
                ch == '_' || ch == '-' || ch == '/' || ch == '7' -> "\u001f"
                ch == '?' || ch == '8' -> "\u007f"
                else -> out
            }
        }
        if (alt && out.isNotEmpty() && !out.startsWith("\u001b")) {
            out = "\u001b$out"
        }
        return out
    }

    fun computeSessionBadge(
        isRunning: Boolean,
        isRoot: Boolean,
        isTermex: Boolean,
        exitStatus: Int?,
        isLightChrome: Boolean,
        remoteLabel: String? = null
    ): SessionBadgeSpec {
        return when {
            isRunning && remoteLabel != null -> SessionBadgeSpec(
                label = remoteLabel,
                dotColor = 0xFFA78BFA.toInt(),
                haloColor = 0x38A78BFA,
                bgColor = if (isLightChrome) 0xFFF5F3FF.toInt() else 0xFF1E1633.toInt(),
                strokeColor = if (isLightChrome) 0xFF8B5CF6.toInt() else 0xFF4C1D95.toInt(),
                textColor = if (isLightChrome) 0xFF6D28D9.toInt() else 0xFFC4B5FD.toInt()
            )
            isRunning && isRoot -> SessionBadgeSpec(
                label = "ROOT #",
                dotColor = 0xFFF59E0B.toInt(),
                haloColor = 0x38F59E0B,
                bgColor = if (isLightChrome) 0xFFFFFBEB.toInt() else 0xFF2B1F0E.toInt(),
                strokeColor = if (isLightChrome) 0xFFF59E0B.toInt() else 0xFF784F14.toInt(),
                textColor = if (isLightChrome) 0xFFB45309.toInt() else 0xFFFCD34D.toInt()
            )
            isRunning && isTermex -> SessionBadgeSpec(
                label = "BASH",
                dotColor = 0xFF38BDF8.toInt(),
                haloColor = 0x3838BDF8,
                bgColor = if (isLightChrome) 0xFFEFF6FF.toInt() else 0xFF0F2438.toInt(),
                strokeColor = if (isLightChrome) 0xFF3B82F6.toInt() else 0xFF1D4ED8.toInt(),
                textColor = if (isLightChrome) 0xFF1D4ED8.toInt() else 0xFF93C5FD.toInt()
            )
            isRunning -> SessionBadgeSpec(
                label = "SH",
                dotColor = 0xFF22C55E.toInt(),
                haloColor = 0x3822C55E,
                bgColor = if (isLightChrome) 0xFFECFDF5.toInt() else 0xFF0F291E.toInt(),
                strokeColor = if (isLightChrome) 0xFF10B981.toInt() else 0xFF1E5638.toInt(),
                textColor = if (isLightChrome) 0xFF047857.toInt() else 0xFF86EFAC.toInt()
            )
            exitStatus == 0 -> SessionBadgeSpec(
                label = "OK • 0",
                dotColor = 0xFF10B981.toInt(),
                haloColor = 0x2410B981,
                bgColor = if (isLightChrome) 0xFFF1F5F9.toInt() else 0xFF161D26.toInt(),
                strokeColor = if (isLightChrome) 0xFFCBD5E1.toInt() else 0xFF2D3846.toInt(),
                textColor = if (isLightChrome) 0xFF334155.toInt() else 0xFFCBD5E1.toInt()
            )
            else -> SessionBadgeSpec(
                label = "EXIT ${exitStatus ?: 1}",
                dotColor = 0xFFEF4444.toInt(),
                haloColor = 0x38EF4444,
                bgColor = if (isLightChrome) 0xFFFEF2F2.toInt() else 0xFF2D1418.toInt(),
                strokeColor = if (isLightChrome) 0xFFEF4444.toInt() else 0xFF7F1D1D.toInt(),
                textColor = if (isLightChrome) 0xFFB91C1C.toInt() else 0xFFFCA5A5.toInt()
            )
        }
    }

    fun formatBreadcrumbPath(rawPath: String?): String {
        if (rawPath.isNullOrBlank()) return "~"
        var clean = rawPath.trim()
        if (clean.length > 1) {
            clean = clean.trimEnd('/')
        }
        if (clean.isEmpty()) return "~"

        val isExactAppRoot = APP_INTERNAL_ROOTS.any { it == clean }
        if (!isExactAppRoot && clean.contains('/')) {
            val leaf = clean.substringAfterLast('/')
            if (leaf.contains('.') && !leaf.startsWith('.')) {
                clean = clean.substringBeforeLast('/').ifEmpty { "/" }
            }
        }

        if (clean == "/storage/emulated/0" || clean == "/sdcard") {
            return "~/sdcard"
        }
        if (clean.startsWith("/storage/emulated/0/")) {
            return "~/" + clean.removePrefix("/storage/emulated/0/")
        }
        if (clean.startsWith("/sdcard/")) {
            return "~/" + clean.removePrefix("/sdcard/")
        }
        for (appRoot in APP_INTERNAL_ROOTS) {
            if (clean == appRoot) return "[app]"
            if (clean.startsWith("$appRoot/")) {
                return "[app]/" + clean.removePrefix("$appRoot/")
            }
        }
        return clean
    }

    fun formatElapsedSeconds(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0L)
        val hours = s / 3600L
        val mins = (s % 3600L) / 60L
        val secs = s % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }
}
