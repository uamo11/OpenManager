/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import androidx.annotation.ColorInt
import me.zhanghai.android.files.theme.night.NightMode
import java.text.Normalizer
import java.util.Locale

/**
 * Pure Kotlin helpers for Settings search normalization, semantic icon badge contrast math,
 * quick theme preset resolution, and viewport-bounded adapter refresh ranges.
 */
object SettingsUiLogic {

    enum class ThemeQuickPreset {
        LIGHT,
        DARK,
        AMOLED,
        SYSTEM
    }

    data class IconBadgeColors(
        @ColorInt val bgColor: Int,
        @ColorInt val fgColor: Int,
        @ColorInt val strokeColor: Int
    )

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val KEYWORD_MAP: Map<String, String> = mapOf(
        "key_appearance_performance_hub" to "tema apariencia claro oscuro amoled oled acento color miniaturas iconos vectoriales rendimiento turbo fps ram",
        "key_thumbnail_load_mode" to "miniaturas iconos imagenes fotos videos apk pdf ram velocidad rapido vectoriales fps rendimiento thumbnails icons speed",
        "key_visual_performance_mode" to "rendimiento fluidez fps lag animaciones turbo bateria bajo consumo performance smooth power",
        "key_black_night_mode" to "amoled oled negro puro oscuro bateria pure black power",
        "key_night_mode" to "tema claro oscuro dia noche automatico apariencia theme light dark system",
        "key_theme_color" to "color acento paleta tema personalizado accent palette swatch",
        "key_material_design_3" to "material design 3 monet md3 estilo ui",
        "key_dual_panel_contrast" to "contraste paneles dual a b separador divisor gpu contrast pane",
        "key_launcher_icon" to "icono lanzador app launcher icon clasico rustico",
        "key_file_list_animation" to "animacion animaciones lista transicion smooth fps",
        "key_file_list_size" to "tamano lista compacto mediano grande fila densidad densidad size compact",
        "key_file_list_dividers" to "lineas divisoras separadores lista dividers separator lines",
        "key_max_lines_file_name" to "lineas nombre archivo truncar wrap lines filename",
        "key_file_name_ellipsize" to "marquesina elipsis nombre largo marquee ellipsize filename",
        "key_file_list_time_preference" to "hora segundos anio fecha lista time date",
        "key_date_time_format" to "formato fecha hora dd mm yyyy date time",
        "key_disable_permission_in_file_list" to "permisos chmod rwx lista archivos permissions",
        "key_file_list_show_hidden_files" to "archivos ocultos punto hidden dotfiles",
        "key_request_root_permission" to "root superusuario su magisk kernelsu apatch privilegios",
        "key_request_shell_permission" to "shizuku adb shell sin root permisos",
        "key_customize_su_command" to "comando su personalizado root custom binary",
        "key_install_shizuku" to "shizuku instalar rikka",
        "key_root_strategy" to "estrategia root acceso privilegios",
        "key_dead_man_dfroot_protection" to "dfroot hombre muerto deadman proteccion bootloop jailbreak",
        "key_dead_man_root_protection" to "hombre muerto dead man root bloqueo proteccion sistema",
        "key_open_apk_default_action" to "apk instalar abrir accion paquete apks xapk apkm package",
        "key_use_shizuku_install" to "apk shizuku instalar silencioso",
        "key_use_root_install" to "apk root instalar pm",
        "key_auto_check_updates" to "actualizaciones auto update comprobar nueva version github",
        "key_check_for_update" to "buscar actualizacion update version descargar",
        "key_locale" to "idioma lenguaje espanol ingles locale language",
        "key_view_error_logs" to "logs errores registro crash diagnostico"
    )

    fun normalizeQuery(text: CharSequence?): String {
        if (text.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(text.toString(), Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "")
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    fun keywordsForKey(prefKey: String?): String =
        if (prefKey == null) "" else KEYWORD_MAP[prefKey].orEmpty()

    fun matchesSearch(
        normalizedQuery: String,
        title: CharSequence?,
        summary: CharSequence?,
        entries: Array<CharSequence>?,
        keywords: String? = null
    ): Boolean {
        if (normalizedQuery.isBlank()) return true
        val haystack = buildString {
            if (!title.isNullOrBlank()) append(normalizeQuery(title)).append(' ')
            if (!summary.isNullOrBlank()) append(normalizeQuery(summary)).append(' ')
            if (!entries.isNullOrEmpty()) {
                for (entry in entries) {
                    append(normalizeQuery(entry)).append(' ')
                }
            }
            if (!keywords.isNullOrBlank()) append(normalizeQuery(keywords))
        }
        if (haystack.isBlank()) return false
        val tokens = normalizedQuery.split(' ')
        return tokens.all { token -> token.isEmpty() || haystack.contains(token) }
    }

    @ColorInt
    fun withAlpha(@ColorInt color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    fun computeIconBadgeColors(
        @ColorInt semanticColor: Int,
        isDark: Boolean,
        isAmoled: Boolean
    ): IconBadgeColors {
        val baseRgb = semanticColor and 0x00FFFFFF
        val r = (baseRgb shr 16) and 0xFF
        val g = (baseRgb shr 8) and 0xFF
        val b = baseRgb and 0xFF
        val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0

        val fgColor: Int = if (!isDark) {
            if (lum > 0.52) {
                val scale = (0.44 / lum).coerceIn(0.42, 0.85)
                val nr = (r * scale).toInt().coerceIn(0, 255)
                val ng = (g * scale).toInt().coerceIn(0, 255)
                val nb = (b * scale).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            } else {
                0xFF000000.toInt() or baseRgb
            }
        } else {
            if (lum < 0.36) {
                val boost = (((0.42 - lum) / 0.42) * 0.55).coerceIn(0.18, 0.55)
                val nr = (r + (255 - r) * boost).toInt().coerceIn(0, 255)
                val ng = (g + (255 - g) * boost).toInt().coerceIn(0, 255)
                val nb = (b + (255 - b) * boost).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            } else {
                0xFF000000.toInt() or baseRgb
            }
        }

        val bgAlpha = when {
            !isDark -> 0x1F // ~12%
            isAmoled -> 0x29 // ~16%
            else -> 0x2E // ~18%
        }
        val strokeAlpha = when {
            !isDark -> 0x38 // ~22%
            isAmoled -> 0x4D // ~30%
            else -> 0x42 // ~26%
        }
        val bgSource = if (isDark) fgColor else baseRgb
        return IconBadgeColors(
            bgColor = withAlpha(bgSource, bgAlpha),
            fgColor = fgColor,
            strokeColor = withAlpha(fgColor, strokeAlpha)
        )
    }

    fun resolveThemeQuickPreset(nightMode: NightMode, blackNightMode: Boolean): ThemeQuickPreset =
        when {
            nightMode == NightMode.OFF -> ThemeQuickPreset.LIGHT
            nightMode == NightMode.ON && blackNightMode -> ThemeQuickPreset.AMOLED
            nightMode == NightMode.ON -> ThemeQuickPreset.DARK
            else -> ThemeQuickPreset.SYSTEM
        }

    fun computeVisibleRefreshRange(
        firstVisible: Int,
        lastVisible: Int,
        totalCount: Int,
        buffer: Int = 4
    ): IntRange? {
        if (totalCount <= 0 || firstVisible < 0 || lastVisible < firstVisible) return null
        val start = (firstVisible - buffer).coerceAtLeast(0)
        val end = (lastVisible + buffer).coerceAtMost(totalCount - 1)
        return if (start <= end) start..end else null
    }
}
