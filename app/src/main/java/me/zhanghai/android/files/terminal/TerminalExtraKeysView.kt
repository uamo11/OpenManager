/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import me.zhanghai.android.files.terminal.TerminalUiHelper.ModifierState
import kotlin.math.roundToInt

class TerminalExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    data class DeckPalette(
        val isLightChrome: Boolean,
        val deckBg: Int,
        val deckBorder: Int,
        val keyFace: Int,
        val keyShadow: Int,
        val keyBorder: Int,
        val navKeyFace: Int,
        val navKeyBorder: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accentColor: Int,
        val rippleColor: Int
    ) {
        val onAccentColor: Int
            get() {
                val r = ((accentColor shr 16) and 0xFF) / 255.0
                val g = ((accentColor shr 8) and 0xFF) / 255.0
                val b = (accentColor and 0xFF) / 255.0
                val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
                return if (luminance > 0.55) 0xFF121212.toInt() else Color.WHITE
            }
    }

    var onKeyString: ((String) -> Unit)? = null
    var onToggleKeyboardClick: (() -> Unit)? = null
    var onModifierLockedToast: ((String) -> Unit)? = null

    var ctrlState: ModifierState = ModifierState.OFF
        private set
    var altState: ModifierState = ModifierState.OFF
        private set

    val isCtrlActive: Boolean
        get() = ctrlState != ModifierState.OFF
    val isAltActive: Boolean
        get() = altState != ModifierState.OFF

    private var ctrlButton: TextView? = null
    private var altButton: TextView? = null
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var activeRepeatRunnable: Runnable? = null

    private var deckPalette: DeckPalette = createDeckPalette(
        isLightChrome = false,
        isAmoled = false,
        accentColor = 0xFF58A6FF.toInt()
    )

    /** Layout of the key deck, chosen from its ⋮ button. */
    enum class DeckMode { FULL, COMPACT, HIDDEN }

    var deckMode: DeckMode = DeckMode.FULL
        set(value) {
            if (field == value) return
            field = value
            buildKeys()
        }

    /** Called when the user picks another layout from the ⋮ menu. */
    var onDeckModeSelected: ((DeckMode) -> Unit)? = null

    init {
        orientation = VERTICAL
        applyDeckBackground()
        setPadding(dp(6), dp(6), dp(6), dp(6))
        buildKeys()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    private fun createDeckPalette(
        isLightChrome: Boolean,
        isAmoled: Boolean,
        accentColor: Int
    ): DeckPalette = when {
        isLightChrome -> DeckPalette(
            isLightChrome = true,
            deckBg = 0xFFEEF2F6.toInt(),
            deckBorder = 0xFFD5DCE6.toInt(),
            keyFace = 0xFFFFFFFF.toInt(),
            keyShadow = 0xFFCBD5E1.toInt(),
            keyBorder = 0xFFD8E0EA.toInt(),
            navKeyFace = 0xFFF8FAFC.toInt(),
            navKeyBorder = 0xFFCBD5E1.toInt(),
            textPrimary = 0xFF0F172A.toInt(),
            textSecondary = 0xFF475569.toInt(),
            accentColor = accentColor,
            rippleColor = 0x220F172A
        )
        isAmoled -> DeckPalette(
            isLightChrome = false,
            deckBg = 0xFF090C10.toInt(),
            deckBorder = 0xFF1C232E.toInt(),
            keyFace = 0xFF151B24.toInt(),
            keyShadow = 0xFF040608.toInt(),
            keyBorder = 0xFF263040.toInt(),
            navKeyFace = 0xFF1D2532.toInt(),
            navKeyBorder = 0xFF323F54.toInt(),
            textPrimary = 0xFFF0F6FC.toInt(),
            textSecondary = 0xFF94A3B8.toInt(),
            accentColor = accentColor,
            rippleColor = 0x38FFFFFF
        )
        else -> DeckPalette(
            isLightChrome = false,
            deckBg = 0xFF10141C.toInt(),
            deckBorder = 0xFF222A36.toInt(),
            keyFace = 0xFF1B222E.toInt(),
            keyShadow = 0xFF0A0D12.toInt(),
            keyBorder = 0xFF2D3748.toInt(),
            navKeyFace = 0xFF242E3E.toInt(),
            navKeyBorder = 0xFF3B495E.toInt(),
            textPrimary = 0xFFF0F6FC.toInt(),
            textSecondary = 0xFF94A3B8.toInt(),
            accentColor = accentColor,
            rippleColor = 0x38FFFFFF
        )
    }

    fun applyTheme(isLightChrome: Boolean, isAmoled: Boolean, accentColor: Int) {
        val next = createDeckPalette(isLightChrome, isAmoled, accentColor)
        if (next == deckPalette && childCount > 0) return
        deckPalette = next
        applyDeckBackground()
        buildKeys()
    }

    private fun applyDeckBackground() {
        val topBorderLayer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(deckPalette.deckBorder)
        }
        val fillLayer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(deckPalette.deckBg)
        }
        val layers = LayerDrawable(arrayOf(topBorderLayer, fillLayer))
        layers.setLayerInset(1, 0, dp(1), 0, 0)
        background = layers
    }

    private fun buildKeys() {
        stopRepeating()
        removeAllViews()
        ctrlButton = null
        altButton = null
        visibility = if (deckMode == DeckMode.HIDDEN) GONE else VISIBLE
        when (deckMode) {
            DeckMode.FULL -> buildFullDeck()
            DeckMode.COMPACT -> buildCompactDeck()
            DeckMode.HIDDEN -> Unit
        }
        updateModifierStyles()
    }

    /** A scrollable row of chips plus the fixed ⋮ button at its end. */
    private fun addChipRow(keys: List<Pair<String, String>>, heightDp: Int, bottomMarginDp: Int) {
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
                bottomMargin = dp(bottomMarginDp)
            }
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }
        for ((label, action) in keys) {
            val minW = if (label.length >= 2) dp(50) else dp(38)
            val btn = createChipButton(label, minW)
            bindKey(btn, label, action)
            row.addView(btn)
        }
        scroll.addView(row)
        line.addView(scroll)
        line.addView(createMenuButton())
        addView(line)
    }

    private fun createMenuButton(): TextView {
        val btn = createChipButton("⋮", dp(36))
        btn.contentDescription = context.getString(me.zhanghai.android.files.R.string.om_term_keys_layout)
        btn.setOnClickListener { anchor ->
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showDeckMenu(anchor)
        }
        return btn
    }

    fun showDeckMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(context, anchor)
        val labels = listOf(
            DeckMode.FULL to me.zhanghai.android.files.R.string.om_term_keys_full,
            DeckMode.COMPACT to me.zhanghai.android.files.R.string.om_term_keys_compact,
            DeckMode.HIDDEN to me.zhanghai.android.files.R.string.om_term_keys_hidden
        )
        for ((index, entry) in labels.withIndex()) {
            popup.menu.add(0, index, index, entry.second).apply {
                isCheckable = true
                isChecked = entry.first == deckMode
            }
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            val mode = labels[item.itemId].first
            deckMode = mode
            onDeckModeSelected?.invoke(mode)
            true
        }
        popup.show()
    }

    private fun buildFullDeck() {
        // Row 0: Quick shell syntax chips & interrupt signals (Scrollable) + ⋮
        addChipRow(
            listOf(
                "/" to "/", "-" to "-", "." to ".", "|" to "|", "$" to "$", "~" to "~", "*" to "*",
                "&" to "&", ">" to ">", ">>" to ">>", "\"" to "\"", "'" to "'", "=" to "=",
                "(" to "(", ")" to ")", "^C" to "\u0003", "^D" to "\u0004", "^Z" to "\u001a",
                "Clear" to "\u000c"
            ),
            heightDp = 34,
            bottomMarginDp = 4
        )
        // Row 1: Esc | Tab | PgUp | Home | ▲ | End | ⌨
        addKeycapRow(
            listOf(
                "Esc" to "\u001b", "Tab" to "\t", "PgUp" to "\u001b[5~", "Home" to "\u001b[1~",
                "▲" to "\u001b[A", "End" to "\u001b[4~", "⌨" to "KEYBOARD"
            ),
            bottomMarginDp = 4
        )
        // Row 2: Ctrl | Alt | PgDn | ◀ | ▼ | ▶ | ⌫
        addKeycapRow(
            listOf(
                "Ctrl" to "CTRL", "Alt" to "ALT", "PgDn" to "\u001b[6~", "◀" to "\u001b[D",
                "▼" to "\u001b[B", "▶" to "\u001b[C", "⌫" to "\u007f"
            ),
            bottomMarginDp = 0
        )
    }

    /** One row with the keys used most while typing; the rest stays one tap away in ⋮. */
    private fun buildCompactDeck() {
        addChipRow(
            listOf(
                "Esc" to "\u001b", "Tab" to "\t", "Ctrl" to "CTRL", "Alt" to "ALT",
                "◀" to "\u001b[D", "▼" to "\u001b[B", "▲" to "\u001b[A", "▶" to "\u001b[C",
                "/" to "/", "-" to "-", "|" to "|", "~" to "~", "^C" to "\u0003",
                "⌫" to "\u007f", "⌨" to "KEYBOARD"
            ),
            heightDp = 38,
            bottomMarginDp = 0
        )
    }

    private fun addKeycapRow(keys: List<Pair<String, String>>, bottomMarginDp: Int) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(39)).apply {
                bottomMargin = dp(bottomMarginDp)
            }
        }
        for ((label, action) in keys) {
            val btn = createStandardKeycap(label)
            bindKey(btn, label, action)
            row.addView(btn)
        }
        addView(row)
    }

    /** Wires a key: modifiers toggle/lock, arrows and ⌫ repeat while held, the rest send once. */
    private fun bindKey(btn: TextView, label: String, action: String) {
        when (action) {
            "CTRL" -> {
                ctrlButton = btn
                btn.setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    ctrlState = TerminalUiHelper.onTap(ctrlState)
                    updateModifierStyles()
                }
                btn.setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    ctrlState = TerminalUiHelper.onLongPress(ctrlState)
                    updateModifierStyles()
                    if (ctrlState == ModifierState.LOCKED) onModifierLockedToast?.invoke("Ctrl")
                    true
                }
            }
            "ALT" -> {
                altButton = btn
                btn.setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    altState = TerminalUiHelper.onTap(altState)
                    updateModifierStyles()
                }
                btn.setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    altState = TerminalUiHelper.onLongPress(altState)
                    updateModifierStyles()
                    if (altState == ModifierState.LOCKED) onModifierLockedToast?.invoke("Alt")
                    true
                }
            }
            "KEYBOARD" -> btn.setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onToggleKeyboardClick?.invoke()
            }
            "\u001b[A", "\u001b[D", "\u001b[B", "\u001b[C", "\u007f" -> attachRepeatableAction(btn, action)
            else -> btn.setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                dispatchKeyAction(action)
            }
        }
    }

    private fun dispatchKeyAction(action: String) {
        val (ctrlConsumed, nextCtrl) = TerminalUiHelper.consume(ctrlState)
        val (altConsumed, nextAlt) = TerminalUiHelper.consume(altState)
        val transformed = TerminalUiHelper.applyModifiersToKey(action, ctrlConsumed, altConsumed)
        if (nextCtrl != ctrlState || nextAlt != altState) {
            ctrlState = nextCtrl
            altState = nextAlt
            updateModifierStyles()
        }
        onKeyString?.invoke(transformed)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachRepeatableAction(btn: TextView, action: String) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    dispatchKeyAction(action)
                    stopRepeating()
                    val runnable = object : Runnable {
                        override fun run() {
                            if (v.isPressed && v.isAttachedToWindow) {
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                dispatchKeyAction(action)
                                repeatHandler.postDelayed(this, 50L)
                            }
                        }
                    }
                    activeRepeatRunnable = runnable
                    repeatHandler.postDelayed(runnable, 320L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.x < -touchSlop || event.x > v.width + touchSlop ||
                        event.y < -touchSlop || event.y > v.height + touchSlop
                    ) {
                        v.isPressed = false
                        stopRepeating()
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopRepeating()
                    true
                }
                else -> false
            }
        }
    }

    private fun stopRepeating() {
        activeRepeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        activeRepeatRunnable = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeating()
    }

    private fun createStandardKeycap(label: String): TextView {
        val tv = TextView(context)
        val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            val m = dp(2)
            setMargins(m, 0, m, 0)
        }
        tv.layoutParams = lp
        tv.gravity = Gravity.CENTER
        tv.text = label
        tv.typeface = Typeface.MONOSPACE

        when (label) {
            "▲", "◀", "▼", "▶" -> {
                tv.textSize = 15f
                tv.setTextColor(deckPalette.textPrimary)
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = deckPalette.navKeyFace,
                    borderColor = deckPalette.navKeyBorder
                )
            }
            "⌫" -> {
                tv.textSize = 14.5f
                tv.setTextColor(
                    if (deckPalette.isLightChrome) 0xFFC2410C.toInt() else 0xFFFFA657.toInt()
                )
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = deckPalette.navKeyFace,
                    borderColor = deckPalette.navKeyBorder
                )
            }
            "Ctrl", "Alt" -> {
                tv.textSize = 12f
                tv.setTextColor(deckPalette.accentColor)
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = deckPalette.keyFace,
                    borderColor = deckPalette.keyBorder
                )
            }
            "⌨" -> {
                tv.textSize = 15f
                tv.setTextColor(deckPalette.accentColor)
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = deckPalette.navKeyFace,
                    borderColor = deckPalette.navKeyBorder
                )
            }
            else -> {
                tv.textSize = 12f
                tv.setTextColor(deckPalette.textPrimary)
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = deckPalette.keyFace,
                    borderColor = deckPalette.keyBorder
                )
            }
        }
        return tv
    }

    private fun createChipButton(label: String, minWidthPx: Int): TextView {
        val tv = TextView(context)
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
            val m = dp(2)
            setMargins(m, 0, m, 0)
        }
        tv.layoutParams = lp
        tv.minWidth = minWidthPx
        tv.setPadding(dp(9), 0, dp(9), 0)
        tv.gravity = Gravity.CENTER
        tv.text = label
        tv.textSize = 12f
        tv.typeface = Typeface.MONOSPACE
        val light = deckPalette.isLightChrome

        when (label) {
            "^C" -> {
                tv.setTextColor(if (light) 0xFFB91C1C.toInt() else 0xFFFF7B72.toInt())
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = if (light) 0xFFFEE2E2.toInt() else 0xFF33181B.toInt(),
                    borderColor = if (light) 0xFFFCA5A5.toInt() else 0xFF6E2228.toInt(),
                    radiusDp = 9f
                )
            }
            "^D" -> {
                tv.setTextColor(if (light) 0xFF6D28D9.toInt() else 0xFFD2A8FF.toInt())
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = if (light) 0xFFF3E8FF.toInt() else 0xFF241830.toInt(),
                    borderColor = if (light) 0xFFD8B4FE.toInt() else 0xFF582B7D.toInt(),
                    radiusDp = 9f
                )
            }
            "^Z" -> {
                tv.setTextColor(if (light) 0xFFC2410C.toInt() else 0xFFFFA657.toInt())
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = if (light) 0xFFFFEDD5.toInt() else 0xFF2D1E10.toInt(),
                    borderColor = if (light) 0xFFFDBA74.toInt() else 0xFF663B19.toInt(),
                    radiusDp = 9f
                )
            }
            "Clear" -> {
                tv.setTextColor(if (light) 0xFF0369A1.toInt() else 0xFF79C0FF.toInt())
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = if (light) 0xFFE0F2FE.toInt() else 0xFF112434.toInt(),
                    borderColor = if (light) 0xFF7DD3FC.toInt() else 0xFF1D4F75.toInt(),
                    radiusDp = 9f
                )
            }
            "\"", "'" -> {
                tv.setTextColor(if (light) 0xFF0284C7.toInt() else 0xFFA5D6FF.toInt())
                applyTactileKeycapStyle(tv, deckPalette.keyFace, deckPalette.keyBorder, radiusDp = 9f)
            }
            "$", "~" -> {
                tv.setTextColor(if (light) 0xFF047857.toInt() else 0xFF7EE787.toInt())
                applyTactileKeycapStyle(tv, deckPalette.keyFace, deckPalette.keyBorder, radiusDp = 9f)
            }
            "|", ">", ">>", "&" -> {
                tv.setTextColor(if (light) 0xFFC2410C.toInt() else 0xFFFFA657.toInt())
                applyTactileKeycapStyle(tv, deckPalette.keyFace, deckPalette.keyBorder, radiusDp = 9f)
            }
            "/", "-", "*", "=" -> {
                tv.setTextColor(if (light) 0xFF1D4ED8.toInt() else 0xFF79C0FF.toInt())
                applyTactileKeycapStyle(tv, deckPalette.keyFace, deckPalette.keyBorder, radiusDp = 9f)
            }
            else -> {
                tv.setTextColor(deckPalette.textPrimary)
                applyTactileKeycapStyle(tv, deckPalette.keyFace, deckPalette.keyBorder, radiusDp = 9f)
            }
        }
        return tv
    }

    private fun applyTactileKeycapStyle(
        tv: TextView,
        faceColor: Int,
        borderColor: Int,
        radiusDp: Float = 7f,
        strokeWidthDp: Float = 1f,
        shadowColor: Int = deckPalette.keyShadow
    ) {
        val radiusPx = dpF(radiusDp)
        val shadowLayer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(shadowColor)
        }
        val faceLayer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(faceColor)
            setStroke(dpF(strokeWidthDp).roundToInt().coerceAtLeast(1), borderColor)
        }
        val keycapLayers = LayerDrawable(arrayOf(shadowLayer, faceLayer))
        keycapLayers.setLayerInset(1, 0, 0, 0, dpF(1.5f).roundToInt().coerceAtLeast(1))

        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.WHITE)
        }
        tv.background = RippleDrawable(
            ColorStateList.valueOf(deckPalette.rippleColor),
            keycapLayers,
            mask
        )
    }

    private fun blendOverSurface(base: Int, overlay: Int, alphaRatio: Float): Int {
        val inv = 1f - alphaRatio
        val r = (Color.red(base) * inv + Color.red(overlay) * alphaRatio).roundToInt().coerceIn(0, 255)
        val g = (Color.green(base) * inv + Color.green(overlay) * alphaRatio).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(base) * inv + Color.blue(overlay) * alphaRatio).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun updateModifierKeycap(tv: TextView, baseName: String, state: ModifierState) {
        val accent = deckPalette.accentColor
        when (state) {
            ModifierState.LOCKED -> {
                tv.text = "${baseName.uppercase()} ●"
                tv.setTextColor(deckPalette.onAccentColor)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = accent,
                    borderColor = deckPalette.textPrimary,
                    radiusDp = 7f,
                    strokeWidthDp = 1.5f
                )
            }
            ModifierState.ONE_SHOT -> {
                tv.text = "$baseName •"
                tv.setTextColor(if (deckPalette.isLightChrome) accent else deckPalette.textPrimary)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = blendOverSurface(deckPalette.keyFace, accent, 0.24f),
                    borderColor = accent,
                    radiusDp = 7f,
                    strokeWidthDp = 1.5f
                )
            }
            ModifierState.OFF -> {
                tv.text = baseName
                tv.setTextColor(accent)
                applyTactileKeycapStyle(
                    tv,
                    faceColor = deckPalette.keyFace,
                    borderColor = deckPalette.keyBorder,
                    radiusDp = 7f,
                    strokeWidthDp = 1f
                )
            }
        }
    }

    private fun updateModifierStyles() {
        ctrlButton?.let { updateModifierKeycap(it, "Ctrl", ctrlState) }
        altButton?.let { updateModifierKeycap(it, "Alt", altState) }
    }

    fun consumeCtrl(): Boolean {
        val (wasActive, next) = TerminalUiHelper.consume(ctrlState)
        if (next != ctrlState) {
            ctrlState = next
            updateModifierStyles()
        }
        return wasActive
    }

    fun consumeAlt(): Boolean {
        val (wasActive, next) = TerminalUiHelper.consume(altState)
        if (next != altState) {
            altState = next
            updateModifierStyles()
        }
        return wasActive
    }
}
