/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
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
        val ivIcon = holder.itemView.findViewById<ImageView>(R.id.ivRootIcon)
        val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvSuperuserTitle)
        val tvSubtitle = holder.itemView.findViewById<TextView>(R.id.tvSuperuserSubtitle)
        val tvTag = holder.itemView.findViewById<TextView>(R.id.tvSuperuserTag)
        val dfRootLayout = holder.itemView.findViewById<View>(R.id.dfRootBadgeLayout)

        currentStatus?.let { status ->
            bindStatus(status, ivIcon, tvTitle, tvSubtitle, tvTag, dfRootLayout)
        }

        card?.setOnClickListener {
            currentStatus?.let { showDetailsDialog(it) }
        }

        scope.launch {
            val status = SuperuserDetector.detect(context)
            currentStatus = status
            if (ivIcon != null && tvTitle != null && tvSubtitle != null && tvTag != null && dfRootLayout != null) {
                bindStatus(status, ivIcon, tvTitle, tvSubtitle, tvTag, dfRootLayout)
            }
        }
    }

    private fun bindStatus(
        status: SuperuserDetector.SuperuserStatus,
        ivIcon: ImageView,
        tvTitle: TextView,
        tvSubtitle: TextView,
        tvTag: TextView,
        dfRootLayout: View
    ) {
        ivIcon.setImageResource(status.type.iconRes)
        tvTitle.text = status.title
        tvSubtitle.text = status.subtitle

        dfRootLayout.visibility = if (status.hasDfRoot && status.type != SuperuserDetector.SuperuserType.DFROOT) {
            View.VISIBLE
        } else {
            View.GONE
        }

        tvTag.text = status.statusTag
        when (status.statusTag) {
            "ROOT" -> tvTag.setBackgroundResource(R.drawable.bg_superuser_badge)
            "SHIZUKU" -> tvTag.setBackgroundResource(R.drawable.bg_shizuku_badge)
            "EXPLOIT" -> tvTag.setBackgroundResource(R.drawable.bg_dfroot_badge)
            "DESACTIVADO" -> tvTag.setBackgroundResource(R.drawable.bg_disabled_badge)
            else -> tvTag.setBackgroundResource(R.drawable.bg_standard_badge)
        }
    }

    private fun showDetailsDialog(status: SuperuserDetector.SuperuserStatus) {
        val message = buildString {
            append("• Gestor principal: ${status.type.displayName}\n")
            append("• Solicitud de Root: ${if (status.isRootEnabledInSettings) "Activada en Ajustes" else "Desactivada en Ajustes"}\n")
            append("• Privilegios Root: ${if (status.isRootGranted) "Concedido (UID 0)" else if (!status.isRootEnabledInSettings) "Desactivado por el usuario" else "No disponible / no autorizado"}\n")
            if (status.suVersion != null) {
                append("• Versión de SU: ${status.suVersion}\n")
            }
            if (status.detectedBinaryPath != null) {
                append("• Driver / Binario: ${status.detectedBinaryPath}\n")
            }
            append("• Servidor Shizuku: ${if (status.isShizukuReady) "Conectado y Autorizado" else "No conectado"}\n")
            append("• DFRoot Jailbreak: ${if (status.hasDfRoot) "Detectado en dispositivo" else "No detectado"}\n")
            if (status.managerPackage != null) {
                append("• Paquete del gestor: ${status.managerPackage}\n")
            }
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(status.title)
            .setIcon(status.type.iconRes)
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
