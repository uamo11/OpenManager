/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
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

    private fun init() {
        layoutResource = R.layout.preference_superuser_flag
        isSelectable = false
    }

    override fun onAttached() {
        super.onAttached()
        try {
            Settings.REQUEST_ROOT_PERMISSION.observeForever(rootPermissionObserver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDetached() {
        try {
            Settings.REQUEST_ROOT_PERMISSION.removeObserver(rootPermissionObserver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDetached()
    }

    fun refresh() {
        scope.launch {
            val status = SuperuserDetector.detect(context)
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
        val ivCornerStatus = holder.itemView.findViewById<ImageView>(R.id.ivCornerStatus)
        val tvManagerHeader = holder.itemView.findViewById<TextView>(R.id.tvManagerHeader)

        val status = currentStatus
        if (cardInner != null && flIconContainer != null && ivIcon != null &&
            tvStatus != null && tvJailbreakTag != null && ivDfRootMini != null &&
            ivCornerStatus != null && tvManagerHeader != null
        ) {
            if (status != null) {
                bindStatus(
                    status, cardInner, flIconContainer, ivIcon,
                    tvStatus, tvJailbreakTag, ivDfRootMini,
                    tvManagerHeader, ivCornerStatus
                )
            } else {
                bindDefaultGrayState(
                    cardInner, flIconContainer, ivIcon,
                    tvStatus, tvJailbreakTag, ivDfRootMini,
                    tvManagerHeader, ivCornerStatus
                )
            }
        }

        card?.setOnClickListener {
            currentStatus?.let { showDetailsDialog(it) }
        }

        scope.launch {
            val detected = SuperuserDetector.detect(context)
            currentStatus = detected
            if (cardInner != null && flIconContainer != null && ivIcon != null &&
                tvStatus != null && tvJailbreakTag != null && ivDfRootMini != null &&
                ivCornerStatus != null && tvManagerHeader != null
            ) {
                bindStatus(
                    detected, cardInner, flIconContainer, ivIcon,
                    tvStatus, tvJailbreakTag, ivDfRootMini,
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
        tvManagerHeader: TextView,
        ivCornerStatus: ImageView
    ) {
        cardInner.setBackgroundResource(R.drawable.bg_superuser_card_gray)
        flIconContainer.setBackgroundResource(R.drawable.bg_circle_root_icon_gray)
        ivIcon.setImageResource(R.drawable.ic_shield_locked)
        ivIcon.setColorFilter(0xFFB0BEC5.toInt(), PorterDuff.Mode.SRC_IN)
        tvManagerHeader.visibility = View.GONE
        tvStatus.text = "No Root"
        tvStatus.setTextColor(0xFFE2E8F0.toInt())
        tvJailbreakTag.visibility = View.GONE
        ivDfRootMini.visibility = View.GONE
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
        tvManagerHeader: TextView,
        ivCornerStatus: ImageView
    ) {
        val hasRoot = status.isRootEnabledInSettings && status.isRootGranted
        val isShizukuOnly = status.type == SuperuserDetector.SuperuserType.SHIZUKU && !status.isRootGranted && status.isShizukuReady
        val isActive = hasRoot || isShizukuOnly

        if (isActive) {
            // GREEN GRADIENT CARD
            cardInner.setBackgroundResource(R.drawable.bg_superuser_card_green)
            flIconContainer.setBackgroundResource(R.drawable.bg_circle_root_icon_green)
            ivIcon.clearColorFilter()
            if (status.type == SuperuserDetector.SuperuserType.DFROOT) {
                ivIcon.setImageResource(R.drawable.ic_root_dfroot)
            } else {
                ivIcon.setImageResource(status.type.iconRes)
            }

            // Top Header: Manager name centered over top border line
            tvManagerHeader.visibility = View.VISIBLE
            tvManagerHeader.setBackgroundResource(R.drawable.bg_superuser_header_badge_green)
            tvManagerHeader.setTextColor(0xFF34D399.toInt())
            val headerText = when {
                status.hasDfRoot && status.type != SuperuserDetector.SuperuserType.DFROOT && status.type != SuperuserDetector.SuperuserType.NONE ->
                    "${status.type.displayName} + DFRoot"
                status.type != SuperuserDetector.SuperuserType.NONE ->
                    status.type.displayName
                status.hasDfRoot ->
                    "DFRoot"
                else ->
                    "Superusuario"
            }
            tvManagerHeader.text = headerText

            // Status beside icon
            tvStatus.text = "Working"
            tvStatus.setTextColor(0xFFFFFFFF.toInt())

            if (status.hasDfRoot || status.type == SuperuserDetector.SuperuserType.DFROOT) {
                tvJailbreakTag.text = "[Jailbreak Root]"
                tvJailbreakTag.visibility = View.VISIBLE
                ivDfRootMini.visibility = View.VISIBLE
            } else if (isShizukuOnly) {
                tvJailbreakTag.text = "[Shizuku]"
                tvJailbreakTag.visibility = View.VISIBLE
                ivDfRootMini.visibility = View.GONE
            } else {
                tvJailbreakTag.visibility = View.GONE
                ivDfRootMini.visibility = View.GONE
            }

            // Bottom-right corner: Green check circle
            ivCornerStatus.setImageResource(R.drawable.ic_check_circle_green)
            ivCornerStatus.visibility = View.VISIBLE
        } else {
            // NO ROOT: GRAY GRADIENT CARD
            cardInner.setBackgroundResource(R.drawable.bg_superuser_card_gray)
            flIconContainer.setBackgroundResource(R.drawable.bg_circle_root_icon_gray)

            // Primary icon: Shield locked
            ivIcon.setImageResource(R.drawable.ic_shield_locked)
            ivIcon.setColorFilter(0xFFB0BEC5.toInt(), PorterDuff.Mode.SRC_IN)

            // Top header: GONE ("en el nombre del manager no dirá nd")
            tvManagerHeader.visibility = View.GONE

            // Text beside icon: "No Root"
            tvStatus.text = "No Root"
            tvStatus.setTextColor(0xFFE2E8F0.toInt())

            tvJailbreakTag.visibility = View.GONE
            ivDfRootMini.visibility = View.GONE

            // Bottom-right corner: Red circle error / X
            ivCornerStatus.setImageResource(R.drawable.ic_circle_error_red)
            ivCornerStatus.visibility = View.VISIBLE
        }
    }

    private fun showDetailsDialog(status: SuperuserDetector.SuperuserStatus) {
        val hasRoot = status.isRootEnabledInSettings && status.isRootGranted

        val dialogTitle = if (hasRoot) {
            when {
                status.hasDfRoot && status.type != SuperuserDetector.SuperuserType.DFROOT && status.type != SuperuserDetector.SuperuserType.NONE ->
                    "${status.type.displayName} (Jailbreak Root Activo)"
                status.type != SuperuserDetector.SuperuserType.NONE ->
                    "${status.type.displayName} (Superusuario Activo)"
                else ->
                    "DFRoot Jailbreak (Activo)"
            }
        } else if (status.type == SuperuserDetector.SuperuserType.SHIZUKU && status.isShizukuReady) {
            "Shizuku Conectado"
        } else {
            "Estado de Superusuario: No Root"
        }

        val message = buildString {
            if (hasRoot) {
                append("• Estado: Working\n")
                append("• Gestor principal: ${status.type.displayName}\n")
                if (status.hasDfRoot) {
                    append("• DFRoot Jailbreak: Activo [Jailbreak Root]\n")
                }
                append("• Privilegios Root: Concedido (UID 0)\n")
                if (status.suVersion != null) {
                    append("• Versión de SU: ${status.suVersion}\n")
                }
                if (status.detectedBinaryPath != null) {
                    append("• Driver / Binario: ${status.detectedBinaryPath}\n")
                }
                if (status.isShizukuReady) {
                    append("• Shizuku: Conectado y Autorizado\n")
                }
            } else {
                append("• Estado: No Root\n")
                if (status.type != SuperuserDetector.SuperuserType.NONE && status.type != SuperuserDetector.SuperuserType.SHIZUKU) {
                    append("• Gestor instalado en el sistema: ${status.type.displayName}\n")
                    if (!status.isRootEnabledInSettings) {
                        append("• Nota: La solicitud de root está desactivada en los Ajustes de OpenManager.\n")
                    } else {
                        append("• Nota: La app del gestor está presente, pero el dispositivo no tiene acceso root o OpenManager no tiene permisos concedidos.\n")
                    }
                } else if (status.hasDfRoot) {
                    append("• Herramienta instalada: DFRoot (Sin acceso root activo)\n")
                } else {
                    append("• No se detectó ningún gestor de superusuario activo en el sistema.\n")
                }
                append("• Privilegios Root: No concedido\n")
                append("• La aplicación opera en modo estándar seguro sin elevación de privilegios.\n")
            }
            if (status.managerPackage != null) {
                append("• Paquete del gestor: ${status.managerPackage}\n")
            }
        }

        val dialogIcon = if (hasRoot) {
            if (status.type == SuperuserDetector.SuperuserType.DFROOT) R.drawable.ic_root_dfroot else status.type.iconRes
        } else {
            R.drawable.ic_circle_error_red
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(dialogTitle)
            .setIcon(dialogIcon)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)

        if (status.managerPackage != null) {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(status.managerPackage)
            if (launchIntent != null) {
                builder.setNeutralButton("Abrir ${status.type.displayName}") { _, _ ->
                    context.startActivity(launchIntent)
                }
            }
        }

        builder.show()
    }
}
