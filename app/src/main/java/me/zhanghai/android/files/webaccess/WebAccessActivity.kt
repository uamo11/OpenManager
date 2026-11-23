/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.webaccess

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.applyEdgeToEdgeInsets

/** Start/stop browser access and show the address and code to type on the computer. */
class WebAccessActivity : AppActivity() {

    private lateinit var tvState: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvCode: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnCopy: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = OpenManagerPalette.from(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val toolbar = Toolbar(this).apply {
            setBackgroundColor(palette.toolbarBg)
            setTitleTextColor(palette.textPrimary)
            title = getString(R.string.om_web_access_title)
            setNavigationIcon(R.drawable.arrow_back_icon_white_24dp)
            navigationIcon?.setTint(palette.iconTint)
            setNavigationOnClickListener { finish() }
            minimumHeight = dp(56)
        }
        tvState = TextView(this).apply {
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER
        }
        tvUrl = TextView(this).apply {
            setTextColor(palette.textPrimary)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(4))
        }
        tvCode = TextView(this).apply {
            setTextColor(palette.accentColor)
            textSize = 30f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.25f
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
        }
        btnToggle = Button(this).apply {
            setOnClickListener {
                if (WebAccessService.status.value?.running == true) {
                    WebAccessService.stop(this@WebAccessActivity)
                } else {
                    WebAccessService.start(this@WebAccessActivity)
                }
            }
        }
        btnCopy = Button(this).apply {
            setText(R.string.om_web_access_copy_link)
            setOnClickListener {
                val link = WebAccessService.status.value?.directLink ?: return@setOnClickListener
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OpenManager", link))
                Toast.makeText(this@WebAccessActivity, R.string.om_web_access_link_copied, Toast.LENGTH_SHORT).show()
            }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(palette.cardBg)
                setStroke(dp(1), palette.dividerLineColor)
            }
            addView(tvState)
            addView(tvUrl)
            addView(TextView(this@WebAccessActivity).apply {
                setText(R.string.om_web_access_code_label)
                setTextColor(palette.textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
            addView(tvCode)
            addView(btnToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            })
            addView(btnCopy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun note(res: Int) = TextView(this).apply {
            setText(res)
            setTextColor(palette.textSecondary)
            setPadding(dp(4), dp(14), dp(4), 0)
            setLineSpacing(0f, 1.15f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            addView(card)
            addView(note(R.string.om_web_access_how))
            addView(note(R.string.om_web_access_safety))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.screenBg)
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        toolbar.applyEdgeToEdgeInsets(applyTop = true, applyHorizontal = true)
        scroll.applyEdgeToEdgeInsets(applyBottom = true, applyHorizontal = true)

        WebAccessService.status.observe(this) { render(it) }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_START, false)) {
            WebAccessService.start(this)
        }
    }

    private fun render(status: WebAccessService.Status) {
        tvState.text = getString(
            when {
                status.locked -> R.string.om_web_access_locked
                status.running -> R.string.om_web_access_running
                status.error != null -> R.string.om_web_access_failed
                else -> R.string.om_web_access_stopped
            }
        ) + (status.error?.let { "\n$it" } ?: "")
        tvUrl.text = when {
            status.url != null -> status.url
            status.running -> getString(R.string.om_web_access_no_network)
            else -> "—"
        }
        tvCode.text = status.code ?: "— — — —"
        btnToggle.setText(if (status.running) R.string.om_web_access_stop else R.string.om_web_access_start)
        btnCopy.isEnabled = status.directLink != null
    }

    companion object {
        const val EXTRA_START = "me.zhanghai.android.files.webaccess.extra.START"
    }
}
