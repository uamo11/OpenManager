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

class DeadManProtectedRulesDialogFragment : AppCompatDialogFragment() {

    data class RuleItem(
        val name: String,
        val description: String,
        val isAllowed: Boolean = false
    )

    data class CategoryGroup(
        val title: String,
        val badgeText: String,
        val badgeColor: Int,
        val items: List<RuleItem>
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogDfrootProtectedFilesBinding.inflate(LayoutInflater.from(requireContext()))
        val context = requireContext()

        val isProtectionActive = DeadManProtectionManager.isDeadManProtectionActive(context)
        binding.ivHeaderIcon.setImageResource(R.drawable.ic_dead_man_skull)
        binding.tvHeaderTitle.text = getString(R.string.settings_dead_man_root_rules_title)

        binding.tvHeaderStatus.text = if (isProtectionActive) {
            "DEAD MAN: ACTIVO"
        } else {
            "DEAD MAN: DESACTIVADO"
        }
        binding.tvHeaderStatus.setTextColor(
            if (isProtectionActive) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        )

        val categories = listOf(
            CategoryGroup(
                title = "Particiones Críticas del Sistema",
                badgeText = "SISTEMA",
                badgeColor = Color.parseColor("#E53935"),
                items = listOf(
                    RuleItem("/system", "Núcleo del sistema operativo Android y binarios esenciales"),
                    RuleItem("/vendor", "Controladores propietarios (HALs) del fabricante del SoC"),
                    RuleItem("/product & /system_ext", "Servicios de soporte extendido del sistema"),
                    RuleItem("/apex", "Módulos modulares de actualización de Android Mainline"),
                    RuleItem("/data/adb & /data/system", "Configuración central de superusuario y base del sistema")
                )
            ),
            CategoryGroup(
                title = "Dispositivos de Bloque y Particiones Raw",
                badgeText = "RAW BLOCK",
                badgeColor = Color.parseColor("#FF9800"),
                items = listOf(
                    RuleItem("/dev/block/by-name/*", "Acceso directo a particiones NAND/UFS"),
                    RuleItem("mkfs & wipefs", "Comandos de formateo masivo de particiones del disco"),
                    RuleItem("dd of=/dev/block/", "Sobreescritura directa de bloques crudos de almacenamiento")
                )
            ),
            CategoryGroup(
                title = "Protección Anti-Wipe Masivo",
                badgeText = "ANTI-WIPE",
                badgeColor = Color.parseColor("#D32F2F"),
                items = listOf(
                    RuleItem("rm -rf / o rm -rf /*", "Intento de borrado recursivo desde el directorio raíz"),
                    RuleItem("rm -rf /system", "Borrado masivo del árbol de directorios del sistema"),
                    RuleItem("find ... -delete", "Búsqueda destructiva automatizada sobre particiones del sistema")
                )
            ),
            CategoryGroup(
                title = "Zona Permitida de Usuario (Sin Restricciones)",
                badgeText = "PERMITIDO",
                badgeColor = Color.parseColor("#4CAF50"),
                items = listOf(
                    RuleItem("/data/local/tmp/", "Scripts y binarios temporales de prueba (ej. test.sh)"),
                    RuleItem("/sdcard/ & /storage/emulated/0/", "Almacenamiento interno del usuario y descargas"),
                    RuleItem("Cache privado de la app", "Directorio seguro de trabajo y ejecución")
                )
            )
        )

        buildCategoriesUi(binding.categoriesContainer, categories)

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_dead_man_root_rules_title)
            .setIcon(R.drawable.ic_dead_man_skull)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    private fun buildCategoriesUi(container: LinearLayout, categories: List<CategoryGroup>) {
        val density = resources.displayMetrics.density

        for (category in categories) {
            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (12 * density).toInt())
                }
                radius = 12 * density
                cardElevation = 2 * density
                setCardBackgroundColor(Color.parseColor("#1E1E1E"))
            }

            val cardContent = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            }

            val headerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvTitle = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                text = category.title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            headerLayout.addView(tvTitle)

            val tvBadge = TextView(requireContext()).apply {
                text = category.badgeText
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(category.badgeColor)
                setBackgroundColor(Color.parseColor("#26FFFFFF"))
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            }
            headerLayout.addView(tvBadge)
            cardContent.addView(headerLayout)

            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt()
                ).apply {
                    setMargins(0, (8 * density).toInt(), 0, (8 * density).toInt())
                }
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
            }
            cardContent.addView(divider)

            for (item in category.items) {
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                }

                val statusDot = View(requireContext()).apply {
                    val dotSize = (6 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                        setMargins(0, 0, (8 * density).toInt(), 0)
                    }
                    val dotColor = if (item.isAllowed || category.badgeColor == Color.parseColor("#4CAF50")) {
                        Color.parseColor("#4CAF50")
                    } else {
                        Color.parseColor("#E53935")
                    }
                    setBackgroundColor(dotColor)
                }
                row.addView(statusDot)

                val itemTextLayout = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }

                val tvName = TextView(requireContext()).apply {
                    text = item.name
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#EEEEEE"))
                }
                itemTextLayout.addView(tvName)

                val tvDesc = TextView(requireContext()).apply {
                    text = item.description
                    textSize = 11f
                    setTextColor(Color.parseColor("#9E9E9E"))
                }
                itemTextLayout.addView(tvDesc)

                row.addView(itemTextLayout)
                cardContent.addView(row)
            }

            card.addView(cardContent)
            container.addView(card)
        }
    }

    companion object {
        fun show(fragment: Fragment) {
            DeadManProtectedRulesDialogFragment().show(fragment)
        }

        fun show(activity: androidx.fragment.app.FragmentActivity) {
            DeadManProtectedRulesDialogFragment().show(
                activity.supportFragmentManager,
                DeadManProtectedRulesDialogFragment::class.java.name
            )
        }
    }
}
