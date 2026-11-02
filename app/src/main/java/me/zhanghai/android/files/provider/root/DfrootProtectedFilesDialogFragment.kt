/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DialogDfrootProtectedFilesBinding
import me.zhanghai.android.files.util.show

class DfrootProtectedFilesDialogFragment : AppCompatDialogFragment() {

    data class ProtectedFileItem(
        val name: String,
        val description: String,
        val isAllowed: Boolean = false
    )

    data class CategoryGroup(
        val title: String,
        val badgeText: String,
        val badgeColor: Int,
        val items: List<ProtectedFileItem>
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogDfrootProtectedFilesBinding.inflate(LayoutInflater.from(requireContext()))
        val context = requireContext()

        val isProtectionActive = DeadManProtectionManager.isDfrootProtectionActive(context)
        binding.tvHeaderStatus.text = if (isProtectionActive) {
            "PROTECCIÓN ACTIVA"
        } else {
            "PROTECCIÓN DESACTIVADA"
        }
        binding.tvHeaderStatus.setTextColor(
            if (isProtectionActive) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        )

        val categories = listOf(
            CategoryGroup(
                title = getString(R.string.dead_man_category_boot_images),
                badgeText = "CRÍTICO",
                badgeColor = Color.parseColor("#E53935"),
                items = listOf(
                    ProtectedFileItem("boot.img", "Kernel Linux y ramdisk del sistema"),
                    ProtectedFileItem("dtbo.img", "Device Tree Blob Overlay de hardware"),
                    ProtectedFileItem("init_boot.img", "Ramdisk genérico de inicio en Android 13+"),
                    ProtectedFileItem("recovery.img", "Partición de rescate y recuperación"),
                    ProtectedFileItem("vendor_boot.img", "Módulos y controladores de arranque del fabricante")
                )
            ),
            CategoryGroup(
                title = getString(R.string.dead_man_category_avb_firmware),
                badgeText = "SEGURIDAD",
                badgeColor = Color.parseColor("#FB8C00"),
                items = listOf(
                    ProtectedFileItem("abl.img / xbl.img", "Cargadores secundarios de arranque (Bootloader)"),
                    ProtectedFileItem("tz.img / hyp.img", "Firmware de seguridad TrustZone e hipervisor"),
                    ProtectedFileItem("vbmeta.img", "Descriptor criptográfico maestro Android Verified Boot"),
                    ProtectedFileItem("vbmeta_system.img", "Árbol de verificación de integridad de /system"),
                    ProtectedFileItem("vbmeta_vendor.img", "Árbol de verificación de integridad de /vendor")
                )
            ),
            CategoryGroup(
                title = getString(R.string.dead_man_category_startup_files),
                badgeText = "INICIO",
                badgeColor = Color.parseColor("#FDD835"),
                items = listOf(
                    ProtectedFileItem("/default.prop", "Propiedades iniciales del entorno de inicio"),
                    ProtectedFileItem("/init", "Binario primario de inicio raíz"),
                    ProtectedFileItem("/system/bin/init", "Enlace de ejecución de arranque del sistema"),
                    ProtectedFileItem("/system/etc/fstab.*", "Tabla de montaje de particiones del sistema"),
                    ProtectedFileItem("/vendor/etc/fstab.*", "Tabla de montaje de particiones del fabricante")
                )
            ),
            CategoryGroup(
                title = getString(R.string.dead_man_category_block_devices),
                badgeText = "RAW",
                badgeColor = Color.parseColor("#757575"),
                items = listOf(
                    ProtectedFileItem("/dev/block/by-name/boot*", "Acceso raw directo a partición boot"),
                    ProtectedFileItem("/dev/block/by-name/dtbo*", "Acceso raw directo a partición dtbo"),
                    ProtectedFileItem("/dev/block/by-name/init_boot*", "Acceso raw directo a partición init_boot"),
                    ProtectedFileItem("/dev/block/by-name/recovery*", "Acceso raw directo a partición recovery"),
                    ProtectedFileItem("/dev/block/by-name/vbmeta*", "Acceso raw directo a partición vbmeta"),
                    ProtectedFileItem("/dev/block/by-name/vendor_boot*", "Acceso raw directo a partición vendor_boot")
                )
            ),
            CategoryGroup(
                title = getString(R.string.dead_man_category_system_partitions),
                badgeText = "SOLO LECTURA",
                badgeColor = Color.parseColor("#8E24AA"),
                items = listOf(
                    ProtectedFileItem("/apex", "Contenedores modulares actualizables del sistema"),
                    ProtectedFileItem("/odm", "Capa original de diseño del fabricante"),
                    ProtectedFileItem("/product", "Personalizaciones y aplicaciones de la marca"),
                    ProtectedFileItem("/system", "Sistema operativo Android base"),
                    ProtectedFileItem("/system_ext", "Extensiones del marco de trabajo de Android"),
                    ProtectedFileItem("/vendor", "Controladores binarios y blobs del hardware")
                )
            ),
            CategoryGroup(
                title = getString(R.string.dead_man_category_allowed_zone),
                badgeText = "PERMITIDO",
                badgeColor = Color.parseColor("#43A047"),
                items = listOf(
                    ProtectedFileItem(
                        "/data/local/tmp/",
                        "Scripts (ej. test.sh), ejecutables y archivos creados por el usuario funcionan libremente",
                        isAllowed = true
                    ),
                    ProtectedFileItem(
                        "/storage/emulated/0/ (/sdcard)",
                        "Almacenamiento interno del usuario (Descargas, Documentos, Fotos)",
                        isAllowed = true
                    ),
                    ProtectedFileItem(
                        "/data/data/ y cachés",
                        "Directorios privados de aplicaciones y archivos de trabajo",
                        isAllowed = true
                    )
                )
            )
        )

        val dp = resources.displayMetrics.density

        for (category in categories) {
            // Category Header Card
            val catHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((4 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
            }

            val titleView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = category.title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (category.badgeText == "PERMITIDO") Color.parseColor("#81C784") else Color.parseColor("#E0E0E0"))
            }

            val badgeView = TextView(context).apply {
                text = " ${category.badgeText} "
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(category.badgeColor)
                setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            }

            catHeader.addView(titleView)
            catHeader.addView(badgeView)
            binding.categoriesContainer.addView(catHeader)

            // Items in card
            val cardView = MaterialCardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, (2 * dp).toInt(), 0, (6 * dp).toInt())
                }
                radius = 8 * dp
                cardElevation = 2 * dp
                setCardBackgroundColor(Color.parseColor("#252525"))
            }

            val cardContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            }

            category.items.sortedBy { it.name }.forEachIndexed { idx, item ->
                if (idx > 0) {
                    val divider = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * dp).toInt()
                        ).apply {
                            setMargins(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
                        }
                        setBackgroundColor(Color.parseColor("#383838"))
                    }
                    cardContainer.addView(divider)
                }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
                }

                val itemName = TextView(context).apply {
                    text = item.name
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    setTextColor(if (item.isAllowed) Color.parseColor("#81C784") else Color.parseColor("#FFFFFF"))
                }

                val itemDesc = TextView(context).apply {
                    text = item.description
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#AAAAAA"))
                }

                row.addView(itemName)
                row.addView(itemDesc)
                cardContainer.addView(row)
            }

            cardView.addView(cardContainer)
            binding.categoriesContainer.addView(cardView)
        }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_dead_man_protected_files_title)
            .setView(binding.root)
            .setPositiveButton(R.string.close, null)
            .create()
    }

    companion object {
        fun show(fragment: Fragment) {
            DfrootProtectedFilesDialogFragment().show(fragment)
        }
    }
}
