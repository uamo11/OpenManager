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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.terminal.TerminalUiHelper.ModifierState
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import kotlin.math.roundToInt

class TerminalExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * Colors of the explorer's bottom bar (gray, black on AMOLED, light on the light theme), so
     * the key bar looks like the rest of the app and like the editor's symbol bar.
     */
    data class DeckPalette(
        val isLightChrome: Boolean,
        val deckBg: Int,
        val divider: Int,
        val keyText: Int,
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

    private var deckPalette: DeckPalette = DeckPalette(
        isLightChrome = false,
        deckBg = 0xFF2B2B2B.toInt(),
        divider = 0xFF3A3A3A.toInt(),
        keyText = 0xFFDCDCDC.toInt(),
        accentColor = 0xFF58A6FF.toInt(),
        rippleColor = 0x38FFFFFF
    )

    /** Layout of the key deck, chosen from its ⋮ button. */
    enum class DeckMode { FULL, COMPACT, HIDDEN }

    var deckMode: DeckMode = DeckMode.FULL
        set(value) {
            if (field == value) return
            field = value
            buildKeys()
        }

    /** Local text bar (type with the keyboard's suggestions, send the whole line at once). */
    var composeBarEnabled: Boolean = false
    var onComposeBarToggled: ((Boolean) -> Unit)? = null

    /** Called when the user picks another layout from the ⋮ menu. */
    var onDeckModeSelected: ((DeckMode) -> Unit)? = null

    init {
        orientation = VERTICAL
        applyDeckBackground()
        setPadding(dp(2), dp(2), dp(2), dp(2))
        buildKeys()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    /**
     * [palette] is the app's palette; [keepBlack] is the terminal's "always black" setting.
     */
    fun applyTheme(palette: OpenManagerPalette, keepBlack: Boolean) {
        // The explorer's bar: gray on the dark theme, black on AMOLED, light on the light theme.
        // A light bar under a terminal kept black would glare, so that case takes the dark gray.
        val light = !keepBlack && !palette.isDark
        val darkUnderLightTheme = keepBlack && !palette.isDark
        val next = DeckPalette(
            isLightChrome = light,
            deckBg = if (darkUnderLightTheme) DARK_BAR else palette.bottomBarBg,
            divider = if (darkUnderLightTheme) DARK_DIVIDER else palette.dividerLineColor,
            keyText = if (darkUnderLightTheme) DARK_KEY_TEXT else palette.iconTint,
            accentColor = palette.accentColor,
            rippleColor = if (light) 0x220F172A else 0x38FFFFFF
        )
        if (next == deckPalette && childCount > 0) return
        deckPalette = next
        applyDeckBackground()
        buildKeys()
    }

    /** Background of the whole bar (also used for the navigation bar under it). */
    val barColor: Int
        get() = deckPalette.deckBg

    /** The bar's colors, for the strips drawn above it (shortcuts, writing bar). */
    val palette: DeckPalette
        get() = deckPalette

    private fun applyDeckBackground() {
        setBackgroundColor(deckPalette.deckBg)
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
            if (action == DIVIDER) {
                row.addView(createDivider())
                continue
            }
            val btn = createKey(label, action, weighted = false)
            bindKey(btn, label, action)
            row.addView(btn)
        }
        scroll.addView(row)
        line.addView(scroll)
        line.addView(createDivider())
        line.addView(createMenuButton())
        addView(line)
    }

    private fun createMenuButton(): TextView {
        val btn = createTextKey("⋮", weighted = false).apply {
            minWidth = dp(40)
            textSize = 20f
        }
        btn.contentDescription = context.getString(R.string.om_term_keys_layout)
        btn.setOnClickListener { anchor ->
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showDeckMenu(anchor)
        }
        return btn
    }

    fun showDeckMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(context, anchor)
        val labels = listOf(
            DeckMode.FULL to R.string.om_term_keys_full,
            DeckMode.COMPACT to R.string.om_term_keys_compact,
            DeckMode.HIDDEN to R.string.om_term_keys_hidden
        )
        for ((index, entry) in labels.withIndex()) {
            popup.menu.add(0, index, index, entry.second).apply {
                isCheckable = true
                isChecked = entry.first == deckMode
            }
        }
        popup.menu.setGroupCheckable(0, true, true)
        // Separate, independent toggle: the local writing bar above the keys.
        popup.menu.add(1, MENU_COMPOSE, 10, R.string.om_term_compose_bar).apply {
            isCheckable = true
            isChecked = composeBarEnabled
        }
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == MENU_COMPOSE) {
                composeBarEnabled = !composeBarEnabled
                onComposeBarToggled?.invoke(composeBarEnabled)
                return@setOnMenuItemClickListener true
            }
            val mode = labels[item.itemId].first
            deckMode = mode
            onDeckModeSelected?.invoke(mode)
            true
        }
        popup.show()
    }

    private fun buildFullDeck() {
        // Row 0: shell syntax and signals (scrollable) + ⋮
        addChipRow(
            listOf(
                "/" to "/", "-" to "-", "." to ".", "|" to "|", "$" to "$", "~" to "~", "*" to "*",
                "&" to "&", ">" to ">", ">>" to ">>", "\"" to "\"", "'" to "'", "=" to "=",
                "(" to "(", ")" to ")", "" to DIVIDER, "^C" to "\u0003", "^D" to "\u0004",
                "^Z" to "\u001a", "Clear" to "\u000c"
            ),
            heightDp = 40,
            bottomMarginDp = 0
        )
        addRowDivider()
        // Row 1: Esc | Tab | PgUp | Home | ▲ | End | ⌨
        addKeycapRow(
            listOf(
                "Esc" to "\u001b", "Tab" to "\t", "PgUp" to "\u001b[5~", "Home" to "\u001b[1~",
                "▲" to "\u001b[A", "End" to "\u001b[4~", "⌨" to "KEYBOARD"
            ),
            bottomMarginDp = 0
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
                "Esc" to "\u001b", "Tab" to "\t", "Ctrl" to "CTRL", "Alt" to "ALT", "" to DIVIDER,
                "◀" to "\u001b[D", "▼" to "\u001b[B", "▲" to "\u001b[A", "▶" to "\u001b[C",
                "" to DIVIDER, "/" to "/", "-" to "-", "|" to "|", "~" to "~", "^C" to "\u0003",
                "" to DIVIDER, "⌫" to "\u007f", "⌨" to "KEYBOARD"
            ),
            heightDp = 44,
            bottomMarginDp = 0
        )
    }

    private fun addKeycapRow(keys: List<Pair<String, String>>, bottomMarginDp: Int) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(42)).apply {
                bottomMargin = dp(bottomMarginDp)
            }
        }
        for ((label, action) in keys) {
            val btn = createKey(label, action, weighted = true)
            bindKey(btn, label, action)
            row.addView(btn)
        }
        addView(row)
    }

    private fun addRowDivider() {
        addView(View(context).apply {
            setBackgroundColor(deckPalette.divider)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
        })
    }

    /** Wires a key: modifiers toggle/lock, arrows and ⌫ repeat while held, the rest send once. */
    private fun bindKey(btn: View, label: String, action: String) {
        when (action) {
            "CTRL" -> {
                ctrlButton = btn as TextView
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
                altButton = btn as TextView
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
    private fun attachRepeatableAction(btn: View, action: String) {
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

    /** Arrows, Tab, ⌫ and ⌨ are drawn icons; everything else is monospace text. */
    private fun createKey(label: String, action: String, weighted: Boolean): View {
        val icon = when (action) {
            "\u001b[A" -> R.drawable.ic_key_up_24dp to R.string.om_editor_key_up
            "\u001b[B" -> R.drawable.ic_key_down_24dp to R.string.om_editor_key_down
            "\u001b[D" -> R.drawable.ic_key_left_24dp to R.string.om_editor_key_left
            "\u001b[C" -> R.drawable.ic_key_right_24dp to R.string.om_editor_key_right
            "\t" -> R.drawable.ic_key_tab_24dp to R.string.om_editor_key_tab
            "\u007f" -> R.drawable.ic_key_backspace_24dp to R.string.om_term_key_backspace
            "KEYBOARD" -> R.drawable.ic_key_keyboard_24dp to R.string.om_term_key_keyboard
            else -> null
        } ?: return createTextKey(label, weighted)
        return ImageView(context).apply {
            setImageResource(icon.first)
            imageTintList = ColorStateList.valueOf(deckPalette.keyText)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = context.getString(icon.second)
            background = flatKeyBackground()
            isClickable = true
            isFocusable = true
            TooltipCompat.setTooltipText(this, contentDescription)
            layoutParams = keyLayoutParams(weighted, dp(44))
        }
    }

    private fun createTextKey(label: String, weighted: Boolean): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        textSize = if (label.length > 2) 13f else 16f
        setTextColor(deckPalette.keyText)
        minWidth = dp(if (label.length > 2) 48 else 40)
        setPadding(dp(8), 0, dp(8), 0)
        background = flatKeyBackground()
        isClickable = true
        isFocusable = true
        layoutParams = keyLayoutParams(weighted, LayoutParams.WRAP_CONTENT)
    }

    private fun keyLayoutParams(weighted: Boolean, width: Int): LayoutParams =
        if (weighted) LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        else LayoutParams(width, LayoutParams.MATCH_PARENT)

    /** A thin separator between groups of keys, like the editor's. */
    private fun createDivider(): View = View(context).apply {
        setBackgroundColor(deckPalette.divider)
        layoutParams = LayoutParams(dp(1).coerceAtLeast(1), dp(20)).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(dp(4), 0, dp(4), 0)
        }
    }

    /** Flat key: no face or border, only the ripple when pressed (rounded, inside the key). */
    private fun flatKeyBackground(fill: Int = Color.TRANSPARENT): RippleDrawable {
        val radius = dpF(8f)
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(deckPalette.rippleColor), content, mask)
    }

    private fun updateModifierKeycap(tv: TextView, baseName: String, state: ModifierState) {
        val accent = deckPalette.accentColor
        when (state) {
            ModifierState.LOCKED -> {
                tv.text = "${baseName.uppercase()} ●"
                tv.setTextColor(deckPalette.onAccentColor)
                tv.background = flatKeyBackground(accent)
            }
            ModifierState.ONE_SHOT -> {
                tv.text = "$baseName •"
                tv.setTextColor(accent)
                tv.background = flatKeyBackground((accent and 0x00FFFFFF) or 0x33000000)
            }
            ModifierState.OFF -> {
                tv.text = baseName
                tv.setTextColor(deckPalette.keyText)
                tv.background = flatKeyBackground()
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

    private companion object {
        const val MENU_COMPOSE = 100
        const val DIVIDER = "DIVIDER"
        // The explorer's bottom bar on the dark theme.
        const val DARK_BAR = 0xFF202020.toInt()
        const val DARK_DIVIDER = 0xFF3A3A3A.toInt()
        const val DARK_KEY_TEXT = 0xFFDCDCDC.toInt()
    }
}
