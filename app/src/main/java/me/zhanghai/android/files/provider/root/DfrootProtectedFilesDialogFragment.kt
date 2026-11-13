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
import androidx.fragment.app.FragmentActivity
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
        binding.tvHeaderStatus.text = getString(
            if (isProtectionActive) R.string.om_dfroot_status_active else R.string.om_dfroot_status_inactive
        )
        binding.tvHeaderStatus.setTextColor(
            if (isProtectionActive) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        )

        val categories = listOf(
            CategoryGroup(
                title = getString(R.string.dead_man_category_boot_images),
                badgeText = getString(R.string.om_dfroot_badge_critical),
                badgeColor = Color.parseColor("#E53935"),
                items = listOf(
                    ProtectedFileItem("boot.img", getString(R.string.om_dfroot_item_boot)),
                    ProtectedFileItem("dtbo.img", getString(R.string.om_dfroot_item_dtbo)),
                    ProtectedFileItem("init_boot.img", getString(R.string.om_dfroot_item_init_boot)),
                    ProtectedFileItem("recovery.img", getString(R.string.om_dfroot_item_recovery)),
                    ProtectedFileItem("vendor_boot.img", getString(R.string.om_dfroot_item_vendor_boot))
                )
            ),
            CategoryGroup(
                title = getString(R.string.dead_man_category_avb_firmware),
                badgeText = getString(R.string.om_dfroot_badge_security),
                badgeColor = Color.parseColor("#FB8C00"),
                items = listOf(
                    ProtectedFileItem("abl.img / xbl.img", getString(R.string.om_dfroot_item_abl)),
                    ProtectedFileItem("tz.img / hyp.img", getString(R.string.om_dfroot_item_tz)),
                    ProtectedFileItem("vbmeta.img", getString(R.string.om_dfroot_item_vbmeta)),
                    ProtectedFileItem("vbmeta_system.img", getString(R.string.om_dfroot_item_vbmeta_sys)),
                    ProtectedFileItem("vbmeta_vendor.img", getString(R.string.om_dfroot_item_vbmeta_ven))
                )
            ),
            CategoryGroup(
                title = getString(R.string.dead_man_category_startup_files),
                badgeText = getString(R.string.om_dfroot_badge_boot),
                badgeColor = Color.parseColor("#FDD835"),
                items = listOf(
                    ProtectedFileItem("/default.prop", getString(R.string.om_dfroot_item_defprop)),
                    ProtectedFileItem("/init", getString(R.string.om_dfroot_item_init)),
                    ProtectedFileItem("/system/bin/init", getString(R.string.om_dfroot_item_sbin_init)),
                    ProtectedFileItem("/system/etc/fstab.*", getString(R.string.om_dfroot_item_sys_fstab)),
                    ProtectedFileItem("/vendor/etc/fstab.*", getString(R.string.om_dfroot_item_ven_fstab))
                )
            ),
            CategoryGroup(
                title = getString(R.string.dead_man_category_block_devices),
                badgeText = "RAW",
                badgeColor = Color.parseColor("#757575"),
                items = listOf(
                    ProtectedFileItem("/dev/block/by-name/boot*", getString(R.string.om_dfroot_item_raw_boot)),
                    ProtectedFileItem("/dev/block/by-name/dtbo*", getString(R.string.om_dfroot_item_raw_dtbo)),
                    ProtectedFileItem("/dev/block/by-name/init_boot*", getString(R.string.om_dfroot_item_raw_init_boot)),
                    ProtectedFileItem("/dev/block/by-name/recovery*", getString(R.string.om_dfroot_item_raw_recovery)),
                    ProtectedFileItem("/dev/block/by-name/vbmeta*", getString(R.string.om_dfroot_item_raw_vbmeta)),
                    ProtectedFileItem("/dev/block/by-name/vendor_boot*", getString(R.string.om_dfroot_item_raw_vendor_boot))
                )
            ),
            CategoryGroup(
                title = getString(R.string.dead_man_category_system_partitions),
                badgeText = getString(R.string.om_dfroot_badge_readonly),
                badgeColor = Color.parseColor("#8E24AA"),
                items = listOf(
                    ProtectedFileItem("/apex", getString(R.string.om_dfroot_item_apex)),
                    ProtectedFileItem("/odm", getString(R.string.om_dfroot_item_odm)),
                    ProtectedFileItem("/product", getString(R.string.om_dfroot_item_product)),
                    ProtectedFileItem("/system", getString(R.string.om_dfroot_item_system)),
                    ProtectedFileItem("/system_ext", getString(R.string.om_dfroot_item_system_ext)),
                    ProtectedFileItem("/vendor", getString(R.string.om_dfroot_item_vendor))
                )
            ),
            CategoryGroup(
                title = getString(R.string.dead_man_category_allowed_zone),
                badgeText = getString(R.string.om_dfroot_badge_allowed),
                badgeColor = Color.parseColor("#43A047"),
                items = listOf(
                    ProtectedFileItem(
                        "/data/local/tmp/",
                        getString(R.string.om_dfroot_item_tmp),
                        isAllowed = true
                    ),
                    ProtectedFileItem(
                        "/storage/emulated/0/ (/sdcard)",
                        getString(R.string.om_dfroot_item_sdcard),
                        isAllowed = true
                    ),
                    ProtectedFileItem(
                        getString(R.string.om_dfroot_item_data_caches_title),
                        getString(R.string.om_dfroot_item_data_caches_desc),
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

        fun show(activity: FragmentActivity) {
            DfrootProtectedFilesDialogFragment().show(activity.supportFragmentManager, DfrootProtectedFilesDialogFragment::class.java.name)
        }
    }
}
