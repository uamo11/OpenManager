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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class TerminalExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onKeyString: ((String) -> Unit)? = null
    var onOverflowMenuClick: ((View) -> Unit)? = null

    var isCtrlActive: Boolean = false
        private set
    var isAltActive: Boolean = false
        private set

    private var ctrlButton: TextView? = null
    private var altButton: TextView? = null
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var activeRepeatRunnable: Runnable? = null

    init {
        orientation = VERTICAL
        // Obsidian terminal bar with hairline top divider
        val topDivider = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#0F1217"))
            setStroke(dp(1), Color.parseColor("#21262D"))
        }
        background = topDivider
        setPadding(dp(4), dp(4), dp(4), dp(6))
        buildKeys()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildKeys() {
        removeAllViews()

        // -------------------------------------------------------------
        // Row 0: Quick shell syntax chips & interrupt signals (Scrollable)
        // -------------------------------------------------------------
        val scrollSymbols = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(34)).apply {
                bottomMargin = dp(3)
            }
        }
        val row0 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }
        val row0Keys = arrayOf(
            Pair("/", "/"),
            Pair("-", "-"),
            Pair("|", "|"),
            Pair("$", "$"),
            Pair("~", "~"),
            Pair("*", "*"),
            Pair("&", "&"),
            Pair(">", ">"),
            Pair("\"", "\""),
            Pair("'", "'"),
            Pair("Ctrl+C", "\u0003"),
            Pair("Ctrl+D", "\u0004"),
            Pair("Ctrl+Z", "\u001a"),
            Pair("Clear", "\u000c")
        )
        for ((label, action) in row0Keys) {
            val isCombo = label.length > 2
            val minW = if (isCombo) dp(60) else dp(38)
            val btn = createChipButton(label, action, minW)
            btn.setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onKeyString?.invoke(action)
            }
            row0.addView(btn)
        }
        scrollSymbols.addView(row0)
        addView(scrollSymbols)

        // -------------------------------------------------------------
        // Row 1: Esc, Tab, PgUp, Home, ▲, End, ⋮
        // -------------------------------------------------------------
        val row1 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(38)).apply {
                bottomMargin = dp(3)
            }
        }

        val row1Keys = arrayOf(
            Pair("Esc", "\u001b"),
            Pair("Tab", "\t"),
            Pair("PgUp", "\u001b[5~"),
            Pair("Home", "\u001b[1~"),
            Pair("▲", "\u001b[A"),
            Pair("End", "\u001b[4~"),
            Pair("⋮", "MENU")
        )

        for ((label, action) in row1Keys) {
            val btn = createStandardKeycap(label)
            if (label == "▲") {
                attachRepeatableAction(btn, action)
            } else {
                btn.setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (action == "MENU") {
                        onOverflowMenuClick?.invoke(btn)
                    } else {
                        dispatchKeyAction(action)
                    }
                }
            }
            row1.addView(btn)
        }
        addView(row1)

        // -------------------------------------------------------------
        // Row 2: Ctrl, Alt, PgDn, ◀, ▼, ▶, ⌫
        // -------------------------------------------------------------
        val row2 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(38))
        }

        val row2Keys = arrayOf(
            Pair("Ctrl", "CTRL"),
            Pair("Alt", "ALT"),
            Pair("PgDn", "\u001b[6~"),
            Pair("◀", "\u001b[D"),
            Pair("▼", "\u001b[B"),
            Pair("▶", "\u001b[C"),
            Pair("⌫", "\u007f")
        )

        for ((label, action) in row2Keys) {
            val btn = createStandardKeycap(label)
            when (action) {
                "CTRL" -> {
                    ctrlButton = btn
                    updateModifierKeycap(btn, isCtrlActive)
                    btn.setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        isCtrlActive = !isCtrlActive
                        updateModifierStyles()
                    }
                }
                "ALT" -> {
                    altButton = btn
                    updateModifierKeycap(btn, isAltActive)
                    btn.setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        isAltActive = !isAltActive
                        updateModifierStyles()
                    }
                }
                "\u001b[D", "\u001b[B", "\u001b[C", "\u007f" -> {
                    attachRepeatableAction(btn, action)
                }
                else -> {
                    btn.setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        dispatchKeyAction(action)
                    }
                }
            }
            row2.addView(btn)
        }
        addView(row2)
    }

    private fun dispatchKeyAction(action: String) {
        if (isCtrlActive && action.length == 1) {
            val ch = action[0].uppercaseChar()
            if (ch in 'A'..'Z') {
                consumeCtrl()
                onKeyString?.invoke((ch.code - 64).toChar().toString())
                return
            }
        }
        if (isAltActive && action.length == 1) {
            consumeAlt()
            onKeyString?.invoke("\u001b$action")
            return
        }
        onKeyString?.invoke(action)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachRepeatableAction(btn: TextView, action: String) {
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

    /**
     * Builds a tactile, developer-grade keycap for rows 1 & 2
     */
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
                tv.setTextColor(Color.parseColor("#F0F6FC"))
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyKeycapStyle(tv, bgHex = "#282E38", strokeHex = "#3E4756")
            }
            "⌫" -> {
                tv.textSize = 14f
                tv.setTextColor(Color.parseColor("#FFA657"))
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyKeycapStyle(tv, bgHex = "#21262D", strokeHex = "#30363D")
            }
            "Ctrl", "Alt" -> {
                tv.textSize = 12.5f
                tv.setTextColor(Color.parseColor("#58A6FF"))
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyKeycapStyle(tv, bgHex = "#161B22", strokeHex = "#30363D")
            }
            "⋮" -> {
                tv.textSize = 15f
                tv.setTextColor(Color.parseColor("#58A6FF"))
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyKeycapStyle(tv, bgHex = "#21262D", strokeHex = "#30363D")
            }
            else -> {
                // Esc, Tab, Home, End, PgUp, PgDn
                tv.textSize = 12.5f
                tv.setTextColor(Color.parseColor("#E6EDF3"))
                applyKeycapStyle(tv, bgHex = "#21262D", strokeHex = "#30363D")
            }
        }
        return tv
    }

    /**
     * Builds a sleek capsule chip for Row 0 with syntax-highlighted accents
     */
    private fun createChipButton(label: String, action: String, minWidthPx: Int): TextView {
        val tv = TextView(context)
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
            val m = dp(2)
            setMargins(m, 0, m, 0)
        }
        tv.layoutParams = lp
        tv.minWidth = minWidthPx
        tv.setPadding(dp(8), 0, dp(8), 0)
        tv.gravity = Gravity.CENTER
        tv.text = label
        tv.textSize = 12f
        tv.typeface = Typeface.MONOSPACE

        when (label) {
            "Ctrl+C" -> {
                tv.setTextColor(Color.parseColor("#FF7B72"))
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyKeycapStyle(tv, bgHex = "#3D1B1E", strokeHex = "#6E2228", radiusDp = 10)
            }
            "Ctrl+D" -> {
                tv.setTextColor(Color.parseColor("#D2A8FF"))
                applyKeycapStyle(tv, bgHex = "#2A1B38", strokeHex = "#582B7D", radiusDp = 10)
            }
            "Ctrl+Z" -> {
                tv.setTextColor(Color.parseColor("#FFA657"))
                applyKeycapStyle(tv, bgHex = "#332212", strokeHex = "#663B19", radiusDp = 10)
            }
            "Clear" -> {
                tv.setTextColor(Color.parseColor("#79C0FF"))
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                applyKeycapStyle(tv, bgHex = "#122738", strokeHex = "#1D4F75", radiusDp = 10)
            }
            "\"", "'" -> {
                tv.setTextColor(Color.parseColor("#A5D6FF"))
                applyKeycapStyle(tv, bgHex = "#161B22", strokeHex = "#30363D", radiusDp = 10)
            }
            "$", "~" -> {
                tv.setTextColor(Color.parseColor("#7EE787"))
                applyKeycapStyle(tv, bgHex = "#161B22", strokeHex = "#30363D", radiusDp = 10)
            }
            "|", ">", "&" -> {
                tv.setTextColor(Color.parseColor("#FFA657"))
                applyKeycapStyle(tv, bgHex = "#161B22", strokeHex = "#30363D", radiusDp = 10)
            }
            "/", "-", "*" -> {
                tv.setTextColor(Color.parseColor("#79C0FF"))
                applyKeycapStyle(tv, bgHex = "#161B22", strokeHex = "#30363D", radiusDp = 10)
            }
            else -> {
                tv.setTextColor(Color.parseColor("#E6EDF3"))
                applyKeycapStyle(tv, bgHex = "#161B22", strokeHex = "#30363D", radiusDp = 10)
            }
        }
        return tv
    }

    private fun applyKeycapStyle(
        tv: TextView,
        bgHex: String,
        strokeHex: String,
        radiusDp: Int = 6,
        strokeWidthDp: Int = 1
    ) {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(bgHex))
            setStroke(dp(strokeWidthDp), Color.parseColor(strokeHex))
        }

        val ripple = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#45FFFFFF")),
            shape,
            null
        )
        tv.background = ripple
    }

    private fun updateModifierKeycap(tv: TextView, isActive: Boolean) {
        if (isActive) {
            tv.setTextColor(Color.WHITE)
            applyKeycapStyle(
                tv,
                bgHex = "#1F6FEB",
                strokeHex = "#58A6FF",
                radiusDp = 6,
                strokeWidthDp = 2
            )
        } else {
            tv.setTextColor(Color.parseColor("#58A6FF"))
            applyKeycapStyle(
                tv,
                bgHex = "#161B22",
                strokeHex = "#30363D",
                radiusDp = 6,
                strokeWidthDp = 1
            )
        }
    }

    private fun updateModifierStyles() {
        ctrlButton?.let { updateModifierKeycap(it, isCtrlActive) }
        altButton?.let { updateModifierKeycap(it, isAltActive) }
    }

    fun consumeCtrl(): Boolean {
        val was = isCtrlActive
        if (was) {
            isCtrlActive = false
            updateModifierStyles()
        }
        return was
    }

    fun consumeAlt(): Boolean {
        val was = isAltActive
        if (was) {
            isAltActive = false
            updateModifierStyles()
        }
        return was
    }
}
