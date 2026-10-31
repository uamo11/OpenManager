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
        setBackgroundColor(Color.parseColor("#141414"))
        val pad = dp(2)
        setPadding(pad, pad, pad, pad)
        buildKeys()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildKeys() {
        removeAllViews()

        // Row 0: Quick shell symbols & interrupt combos (scrollable)
        val scrollSymbols = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(34)).apply {
                bottomMargin = dp(2)
            }
        }
        val row0 = LinearLayout(context).apply {
            orientation = HORIZONTAL
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
            val btn = createChipButton(label, if (isCombo) dp(56) else dp(38))
            btn.setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onKeyString?.invoke(action)
            }
            row0.addView(btn)
        }
        scrollSymbols.addView(row0)
        addView(scrollSymbols)

        // Row 1: Esc, Tab, PgUp, Home, ▲, End, ⋮
        val row1 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(38)).apply {
                bottomMargin = dp(2)
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
            val btn = createButton(label)
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

        // Row 2: Ctrl, Alt, PgDn, ◀, ▼, ▶, ⌫
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
            val btn = createButton(label)
            when (action) {
                "CTRL" -> {
                    ctrlButton = btn
                    btn.setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        isCtrlActive = !isCtrlActive
                        updateModifierStyles()
                    }
                }
                "ALT" -> {
                    altButton = btn
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
                                dispatchKeyAction(action)
                                repeatHandler.postDelayed(this, 55L)
                            }
                        }
                    }
                    activeRepeatRunnable = runnable
                    repeatHandler.postDelayed(runnable, 350L)
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

    private fun createButton(label: String): TextView {
        val tv = TextView(context)
        val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            val m = dp(2)
            setMargins(m, 0, m, 0)
        }
        tv.layoutParams = lp
        tv.gravity = Gravity.CENTER
        tv.text = label
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13.5f
        tv.typeface = Typeface.MONOSPACE
        applyButtonStyle(tv, false)
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
        tv.setPadding(dp(8), 0, dp(8), 0)
        tv.gravity = Gravity.CENTER
        tv.text = label
        tv.setTextColor(Color.parseColor("#E0E0E0"))
        tv.textSize = 12.5f
        tv.typeface = Typeface.MONOSPACE
        applyButtonStyle(tv, false, isSubtle = true)
        return tv
    }

    private fun applyButtonStyle(tv: TextView, isActive: Boolean, isSubtle: Boolean = false) {
        val bgNormal = when {
            isActive -> Color.parseColor("#1976D2")
            isSubtle -> Color.parseColor("#212121")
            else -> Color.parseColor("#2A2A2A")
        }
        val radius = dp(4).toFloat()

        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(bgNormal)
            setStroke(
                dp(1),
                when {
                    isActive -> Color.parseColor("#42A5F5")
                    isSubtle -> Color.parseColor("#333333")
                    else -> Color.parseColor("#3C3C3C")
                }
            )
        }

        val ripple = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#50FFFFFF")),
            shape,
            null
        )
        tv.background = ripple
    }

    private fun updateModifierStyles() {
        ctrlButton?.let { applyButtonStyle(it, isCtrlActive) }
        altButton?.let { applyButtonStyle(it, isAltActive) }
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
