/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Observer
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.root.ShizukuManager
import me.zhanghai.android.files.provider.root.SuperuserDetector

class SuperuserStatusPreference : Preference {

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    private var currentStatus: SuperuserDetector.SuperuserStatus? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val rootPermissionObserver = Observer<Boolean> { _ ->
        refresh()
    }

    private val shellPermissionObserver = Observer<Boolean> { _ ->
        refresh()
    }

    private val shizukuListener: () -> Unit = {
        scope.launch {
            refresh()
        }
    }

    private fun init() {
        layoutResource = R.layout.preference_superuser_flag
        isSelectable = false
    }

    override fun onAttached() {
        super.onAttached()
        try {
            Settings.REQUEST_ROOT_PERMISSION.observeForever(rootPermissionObserver)
            Settings.REQUEST_SHELL_PERMISSION.observeForever(shellPermissionObserver)
            ShizukuManager.addBinderListener(shizukuListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDetached() {
        try {
            Settings.REQUEST_ROOT_PERMISSION.removeObserver(rootPermissionObserver)
            Settings.REQUEST_SHELL_PERMISSION.removeObserver(shellPermissionObserver)
            ShizukuManager.removeBinderListener(shizukuListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDetached()
    }

    fun refresh() {
        scope.launch {
            val status = SuperuserDetector.detect(context, forceRefresh = true)
            currentStatus = status
            notifyChanged()
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val card = holder.itemView.findViewById<View>(R.id.cardSuperuserFlag)
        val cardInner = holder.itemView.findViewById<View>(R.id.cardInnerContainer)
        val flIconContainer = holder.itemView.findViewById<View>(R.id.flRootIconContainer)
        val ivIcon = holder.itemView.findViewById<ImageView>(R.id.ivRootIcon)
        val tvStatus = holder.itemView.findViewById<TextView>(R.id.tvSuperuserStatus)
        val tvJailbreakTag = holder.itemView.findViewById<TextView>(R.id.tvJailbreakTag)
        val ivDfRootMini = holder.itemView.findViewById<ImageView>(R.id.ivDfRootMini)
        val ivShizukuMini = holder.itemView.findViewById<ImageView>(R.id.ivShizukuMini)
        val ivCornerStatus = holder.itemView.findViewById<ImageView>(R.id.ivCornerStatus)
        val tvManagerHeader = holder.itemView.findViewById<TextView>(R.id.tvManagerHeader)

        val status = currentStatus
        if (cardInner != null && flIconContainer != null && ivIcon != null &&
            tvStatus != null && tvJailbreakTag != null && ivDfRootMini != null &&
            ivShizukuMini != null && ivCornerStatus != null && tvManagerHeader != null
        ) {
            if (status != null) {
                bindStatus(
                    status, cardInner, flIconContainer, ivIcon,
                    tvStatus, tvJailbreakTag, ivDfRootMini, ivShizukuMini,
                    tvManagerHeader, ivCornerStatus
                )
            } else {
                bindDefaultGrayState(
                    cardInner, flIconContainer, ivIcon,
                    tvStatus, tvJailbreakTag, ivDfRootMini, ivShizukuMini,
                    tvManagerHeader, ivCornerStatus
                )
            }
        }

        card?.setOnClickListener {
            scope.launch {
                val detected = SuperuserDetector.detect(context, forceRefresh = true)
                currentStatus = detected
                notifyChanged()
                showDetailsDialog(detected)
            }
        }

        scope.launch {
            val detected = SuperuserDetector.detect(context)
            currentStatus = detected
            if (cardInner != null && flIconContainer != null && ivIcon != null &&
                tvStatus != null && tvJailbreakTag != null && ivDfRootMini != null &&
                ivShizukuMini != null && ivCornerStatus != null && tvManagerHeader != null
            ) {
                bindStatus(
                    detected, cardInner, flIconContainer, ivIcon,
                    tvStatus, tvJailbreakTag, ivDfRootMini, ivShizukuMini,
                    tvManagerHeader, ivCornerStatus
                )
            }
        }
    }

    private fun bindDefaultGrayState(
        cardInner: View,
        flIconContainer: View,
        ivIcon: ImageView,
        tvStatus: TextView,
        tvJailbreakTag: TextView,
        ivDfRootMini: ImageView,
        ivShizukuMini: ImageView,
        tvManagerHeader: TextView,
        ivCornerStatus: ImageView
    ) {
        cardInner.setBackgroundResource(R.drawable.bg_superuser_card_gray)
        flIconContainer.setBackgroundResource(R.drawable.bg_circle_root_icon_gray)
        ivIcon.setImageResource(R.drawable.ic_shield_locked)
        ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFB0BEC5.toInt())
        ivIcon.clearColorFilter()
        tvManagerHeader.visibility = View.GONE
        tvStatus.text = "No Root"
        tvStatus.setTextColor(0xFFE2E8F0.toInt())
        tvJailbreakTag.visibility = View.GONE
        ivDfRootMini.visibility = View.GONE
        ivShizukuMini.visibility = View.GONE
        ivCornerStatus.setImageResource(R.drawable.ic_circle_error_red)
        ivCornerStatus.visibility = View.VISIBLE
    }

    private fun bindStatus(
        status: SuperuserDetector.SuperuserStatus,
        cardInner: View,
        flIconContainer: View,
        ivIcon: ImageView,
        tvStatus: TextView,
        tvJailbreakTag: TextView,
        ivDfRootMini: ImageView,
        ivShizukuMini: ImageView,
        tvManagerHeader: TextView,
        ivCornerStatus: ImageView
    ) {
        val hasRoot = status.isRootEnabledInSettings && status.isRootGranted
        val hasShizuku = status.isShizukuReady
        val isActive = hasRoot || hasShizuku

        if (isActive) {
            // GREEN GRADIENT CARD
            cardInner.setBackgroundResource(R.drawable.bg_superuser_card_green)
            flIconContainer.setBackgroundResource(R.drawable.bg_circle_root_icon_green)
            ivIcon.imageTintList = null
            ivIcon.clearColorFilter()
            ivDfRootMini.imageTintList = null
            ivDfRootMini.clearColorFilter()
            ivShizukuMini.imageTintList = null
            ivShizukuMini.clearColorFilter()

            if (hasRoot) {
                ivIcon.setImageResource(status.type.iconRes)

                // Top Header: Manager name centered over top border line
                tvManagerHeader.visibility = View.VISIBLE
                tvManagerHeader.setBackgroundResource(R.drawable.bg_superuser_header_badge_green)
                tvManagerHeader.setTextColor(0xFF34D399.toInt())
                val headerText = when {
                    status.type == SuperuserDetector.SuperuserType.DFROOT && hasShizuku -> "DFRoot + Shizuku"
                    status.type == SuperuserDetector.SuperuserType.DFROOT -> "DFRoot (Jailbreak)"
                    status.hasDfRoot && hasShizuku -> "${status.type.displayName} + DFRoot + Shizuku"
                    status.hasDfRoot -> "${status.type.displayName} + DFRoot"
                    hasShizuku && status.type != SuperuserDetector.SuperuserType.NONE -> "${status.type.displayName} + Shizuku"
                    status.type != SuperuserDetector.SuperuserType.NONE -> status.type.displayName
                    status.hasDfRoot -> "DFRoot (Jailbreak)"
                    else -> context.getString(R.string.om_su_superuser_label)
                }
                tvManagerHeader.text = headerText

                // Status beside icon
                tvStatus.text = "Working"
                tvStatus.setTextColor(0xFFFFFFFF.toInt())

                when {
                    status.type == SuperuserDetector.SuperuserType.DFROOT -> {
                        tvJailbreakTag.text = if (hasShizuku) "[Jailbreak + Shizuku]" else "[Jailbreak Root]"
                        tvJailbreakTag.visibility = View.VISIBLE
                        ivDfRootMini.visibility = View.GONE
                        ivShizukuMini.visibility = if (hasShizuku) View.VISIBLE else View.GONE
                    }
                    status.hasDfRoot -> {
                        tvJailbreakTag.text = if (hasShizuku) "[Jailbreak + Shizuku]" else "[Jailbreak Root]"
                        tvJailbreakTag.visibility = View.VISIBLE
                        ivDfRootMini.visibility = View.VISIBLE
                        ivShizukuMini.visibility = if (hasShizuku) View.VISIBLE else View.GONE
                    }
                    hasShizuku -> {
                        tvJailbreakTag.text = "[+ Shizuku]"
                        tvJailbreakTag.visibility = View.VISIBLE
                        ivDfRootMini.visibility = View.GONE
                        ivShizukuMini.visibility = View.VISIBLE
                    }
                    else -> {
                        tvJailbreakTag.visibility = View.GONE
                        ivDfRootMini.visibility = View.GONE
                        ivShizukuMini.visibility = View.GONE
                    }
                }
            } else {
                // Shizuku Active Only (No Root) - Full official Shizuku logo
                ivIcon.setImageResource(R.drawable.ic_shizuku_logo)
                ivIcon.imageTintList = null
                ivIcon.clearColorFilter()

                tvManagerHeader.visibility = View.VISIBLE
                tvManagerHeader.setBackgroundResource(R.drawable.bg_superuser_header_badge_green)
                tvManagerHeader.setTextColor(0xFF34D399.toInt())
                val modeStr = if (status.shizukuUid == 0) "Root API" else "ADB API"
                tvManagerHeader.text = "Shizuku ($modeStr)"

                tvStatus.text = "Working"
                tvStatus.setTextColor(0xFFFFFFFF.toInt())

                tvJailbreakTag.text = context.getString(R.string.om_su_exec_perm_tag)
                tvJailbreakTag.visibility = View.VISIBLE
                ivDfRootMini.visibility = View.GONE
                ivShizukuMini.visibility = View.GONE
            }

            // Bottom-right corner: Green check circle
            ivCornerStatus.setImageResource(R.drawable.ic_check_circle_green)
            ivCornerStatus.visibility = View.VISIBLE
        } else {
            // NO ROOT: GRAY GRADIENT CARD
            cardInner.setBackgroundResource(R.drawable.bg_superuser_card_gray)
            flIconContainer.setBackgroundResource(R.drawable.bg_circle_root_icon_gray)
            ivDfRootMini.imageTintList = null
            ivDfRootMini.clearColorFilter()
            ivShizukuMini.imageTintList = null
            ivShizukuMini.clearColorFilter()

            if (status.type != SuperuserDetector.SuperuserType.NONE && status.type != SuperuserDetector.SuperuserType.SHIZUKU) {
                // Root manager is installed on device, but not authorized
                ivIcon.setImageResource(status.type.iconRes)
                ivIcon.imageTintList = null
                ivIcon.clearColorFilter()

                tvManagerHeader.visibility = View.VISIBLE
                tvManagerHeader.setBackgroundResource(R.drawable.bg_superuser_card_gray)
                tvManagerHeader.setTextColor(0xFF94A3B8.toInt())
                tvManagerHeader.text = context.getString(R.string.om_su_detected_format, status.type.displayName)

                tvStatus.text = "No Root"
                tvStatus.setTextColor(0xFFE2E8F0.toInt())

                tvJailbreakTag.text = context.getString(R.string.om_su_unauthorized_tag)
                tvJailbreakTag.visibility = View.VISIBLE
                ivDfRootMini.visibility = View.GONE
                ivShizukuMini.visibility = View.GONE

                ivCornerStatus.setImageResource(R.drawable.ic_circle_error_red)
                ivCornerStatus.visibility = View.VISIBLE
            } else if (status.isShizukuAvailable && !status.isShizukuReady) {
                // Shizuku service is running on device, but OpenManager lacks execution permission
                ivIcon.setImageResource(R.drawable.ic_shizuku_logo)
                ivIcon.imageTintList = null
                ivIcon.clearColorFilter()

                tvManagerHeader.visibility = View.VISIBLE
                tvManagerHeader.setBackgroundResource(R.drawable.bg_superuser_header_badge_green)
                tvManagerHeader.setTextColor(0xFF38BDF8.toInt())
                tvManagerHeader.text = context.getString(R.string.om_su_shizuku_detected)

                tvStatus.text = "No Root"
                tvStatus.setTextColor(0xFFE2E8F0.toInt())

                tvJailbreakTag.text = context.getString(R.string.om_su_request_exec_perm_tag)
                tvJailbreakTag.visibility = View.VISIBLE
                ivDfRootMini.visibility = View.GONE
                ivShizukuMini.visibility = View.GONE

                ivCornerStatus.setImageResource(R.drawable.ic_circle_error_red)
                ivCornerStatus.visibility = View.VISIBLE
            } else {
                // Primary icon: Shield locked
                ivIcon.setImageResource(R.drawable.ic_shield_locked)
                ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFB0BEC5.toInt())
                ivIcon.clearColorFilter()

                tvManagerHeader.visibility = View.GONE
                tvStatus.text = "No Root"
                tvStatus.setTextColor(0xFFE2E8F0.toInt())

                tvJailbreakTag.visibility = View.GONE
                ivDfRootMini.visibility = View.GONE
                ivShizukuMini.visibility = View.GONE

                ivCornerStatus.setImageResource(R.drawable.ic_circle_error_red)
                ivCornerStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun showDetailsDialog(status: SuperuserDetector.SuperuserStatus) {
        val hasRoot = status.isRootEnabledInSettings && status.isRootGranted
        val hasShizuku = status.isShizukuReady

        val dialogTitle = when {
            hasRoot && status.hasDfRoot && hasShizuku -> context.getString(R.string.om_su_dlg_title_all_active_format, status.type.displayName)
            hasRoot && status.hasDfRoot -> context.getString(R.string.om_su_dlg_title_jailbreak_format, status.type.displayName)
            hasRoot && hasShizuku -> context.getString(R.string.om_su_dlg_title_root_shizuku_format, status.type.displayName)
            hasRoot && status.type != SuperuserDetector.SuperuserType.NONE -> context.getString(R.string.om_su_dlg_title_type_active_format, status.type.displayName)
            hasRoot -> context.getString(R.string.om_su_dlg_title_active)
            hasShizuku -> context.getString(R.string.om_su_dlg_title_shizuku_active)
            status.isShizukuAvailable -> context.getString(R.string.om_su_dlg_title_shizuku_unauth)
            else -> context.getString(R.string.om_su_dlg_title_no_root)
        }

        val message = buildString {
            if (hasRoot) {
                append(context.getString(R.string.om_su_line_status_working)).append("\n")
                append(context.getString(R.string.om_su_line_manager_format, status.type.displayName)).append("\n")
                if (status.hasDfRoot) {
                    append(context.getString(R.string.om_su_line_dfroot_active)).append("\n")
                }
                append(context.getString(R.string.om_su_line_root_granted)).append("\n")
                if (status.suVersion != null) {
                    append(context.getString(R.string.om_su_line_su_ver_format, status.suVersion)).append("\n")
                }
                if (status.detectedBinaryPath != null) {
                    append(context.getString(R.string.om_su_line_driver_format, status.detectedBinaryPath)).append("\n")
                }
                if (hasShizuku) {
                    val mode = if (status.shizukuUid == 0) "Root (UID 0)" else "ADB Shell (UID 2000)"
                    append(context.getString(R.string.om_su_line_shizuku_connected)).append("\n")
                    append(context.getString(R.string.om_su_line_shizuku_mode_format, mode)).append("\n")
                    if (status.shizukuVersion > 0) append(context.getString(R.string.om_su_line_api_ver_format, status.shizukuVersion)).append("\n")
                    append(context.getString(R.string.om_su_line_exec_perm_active)).append("\n")
                } else if (status.isShizukuAvailable) {
                    append(context.getString(R.string.om_su_line_shizuku_unauth)).append("\n")
                }
            } else if (hasShizuku) {
                append(context.getString(R.string.om_su_line_status_shizuku)).append("\n")
                val mode = if (status.shizukuUid == 0) "Root (UID 0)" else "ADB Shell (UID 2000)"
                append(context.getString(R.string.om_su_line_exec_mode_format, mode)).append("\n")
                if (status.shizukuVersion > 0) {
                    append(context.getString(R.string.om_su_line_shizuku_api_ver_format, status.shizukuVersion)).append("\n")
                }
                append(context.getString(R.string.om_su_line_exec_perm_granted)).append("\n")
                append(context.getString(R.string.om_su_line_root_not_granted)).append("\n")
                append(context.getString(R.string.om_su_line_shizuku_desc)).append("\n")
            } else if (status.isShizukuAvailable && !status.isShizukuReady) {
                append(context.getString(R.string.om_su_line_status_no_root)).append("\n")
                append(context.getString(R.string.om_su_line_shizuku_running)).append("\n")
                append(context.getString(R.string.om_su_line_exec_perm_not_auth)).append("\n\n")
                append(context.getString(R.string.om_su_line_shizuku_prompt))
            } else {
                append(context.getString(R.string.om_su_line_status_no_root)).append("\n")
                if (status.type != SuperuserDetector.SuperuserType.NONE && status.type != SuperuserDetector.SuperuserType.SHIZUKU) {
                    append(context.getString(R.string.om_su_line_installed_mgr_format, status.type.displayName)).append("\n")
                    if (!status.isRootEnabledInSettings) {
                        append(context.getString(R.string.om_su_line_note_disabled)).append("\n")
                    } else {
                        append(context.getString(R.string.om_su_line_note_no_perm)).append("\n")
                    }
                } else if (status.hasDfRoot) {
                    append(context.getString(R.string.om_su_line_dfroot_inactive)).append("\n")
                } else {
                    append(context.getString(R.string.om_su_line_no_mgr_detected)).append("\n")
                }
                append(context.getString(R.string.om_su_line_root_not_granted)).append("\n")
                append(context.getString(R.string.om_su_line_shizuku_not_running)).append("\n")
                append(context.getString(R.string.om_su_line_standard_mode)).append("\n")
            }

            if (status.managerPackage != null) {
                append(context.getString(R.string.om_su_line_mgr_pkg_format, status.managerPackage)).append("\n")
            }
        }

        val dialogIcon = when {
            hasRoot -> if (status.type == SuperuserDetector.SuperuserType.DFROOT) R.drawable.ic_root_dfroot else status.type.iconRes
            hasShizuku || status.isShizukuAvailable -> R.drawable.ic_shizuku_logo
            else -> R.drawable.ic_circle_error_red
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(dialogTitle)
            .setIcon(dialogIcon)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)

        val act = findActivity(context)

        // Request Shizuku permission button if available but not ready
        if (!hasShizuku && status.isShizukuAvailable && act != null) {
            builder.setPositiveButton(R.string.om_su_btn_request_shizuku) { _, _ ->
                ShizukuManager.requestPermission(act) { granted ->
                    refresh()
                }
            }
            builder.setNegativeButton(R.string.close, null)
        }

        if (status.managerPackage != null) {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(status.managerPackage)
            if (launchIntent != null) {
                builder.setNeutralButton(context.getString(R.string.om_su_btn_open_mgr_format, status.type.displayName)) { _, _ ->
                    context.startActivity(launchIntent)
                }
            }
        } else if (status.isShizukuAvailable || ShizukuManager.isInstalled(context)) {
            builder.setNeutralButton(R.string.om_su_btn_open_shizuku) { _, _ ->
                ShizukuManager.openShizukuApp(context)
            }
        }

        builder.show()
    }
}
