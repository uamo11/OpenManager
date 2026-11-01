/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.AxmlDecoder
import com.topjohnwu.superuser.Shell
import me.zhanghai.android.files.filelist.ApkInstallHelper
import me.zhanghai.android.files.provider.root.ShizukuShell
import me.zhanghai.android.files.util.escapeShellArg
import java.io.File
import java.util.zip.ZipFile

object AppDetailsViewerHelper {

    enum class ComponentType {
        ACTIVITY, SERVICE, RECEIVER, PROVIDER, PERMISSION
    }

    data class ComponentItem(
        val name: String,
        val subtitle: String,
        val type: ComponentType,
        val isExported: Boolean = false,
        val isGranted: Boolean = false
    )

    /**
     * Displays a dialog exploring components: Activities (with launch action), Services,
     * Broadcast Receivers, Content Providers, and Permissions (App Manager Style).
     */
    fun showComponentsViewer(
        activity: Activity,
        packageName: String,
        appLabel: String,
        apkFile: File? = null
    ) {
        val pm = activity.packageManager
        var flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS

        val pkgInfo = try {
            if (apkFile != null && apkFile.exists()) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            } else {
                pm.getPackageInfo(packageName, flags)
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Error al cargar componentes: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        if (pkgInfo == null) {
            Toast.makeText(activity, "No se pudo leer la información del paquete", Toast.LENGTH_SHORT).show()
            return
        }

        val activitiesList = pkgInfo.activities?.map {
            ComponentItem(
                name = it.name,
                subtitle = "Exported: ${it.exported}${if (it.permission != null) " • Permiso: ${it.permission}" else ""}",
                type = ComponentType.ACTIVITY,
                isExported = it.exported
            )
        } ?: emptyList()

        val servicesList = pkgInfo.services?.map {
            ComponentItem(
                name = it.name,
                subtitle = "Exported: ${it.exported}${if (it.permission != null) " • Permiso: ${it.permission}" else ""}",
                type = ComponentType.SERVICE,
                isExported = it.exported
            )
        } ?: emptyList()

        val receiversList = pkgInfo.receivers?.map {
            ComponentItem(
                name = it.name,
                subtitle = "Exported: ${it.exported}${if (it.permission != null) " • Permiso: ${it.permission}" else ""}",
                type = ComponentType.RECEIVER,
                isExported = it.exported
            )
        } ?: emptyList()

        val providersList = pkgInfo.providers?.map {
            ComponentItem(
                name = it.name,
                subtitle = "Authority: ${it.authority} • Exported: ${it.exported}",
                type = ComponentType.PROVIDER,
                isExported = it.exported
            )
        } ?: emptyList()

        val permFlags = pkgInfo.requestedPermissionsFlags
        val permissionsList = pkgInfo.requestedPermissions?.mapIndexed { idx, perm ->
            val granted = if (permFlags != null && idx < permFlags.size) {
                (permFlags[idx] and PackageInfoCompat.REQUESTED_PERMISSION_GRANTED) != 0
            } else {
                pm.checkPermission(perm, packageName) == PackageManager.PERMISSION_GRANTED
            }
            ComponentItem(
                name = perm,
                subtitle = if (granted) "Estado: Concedido (Granted)" else "Estado: No concedido / Denegado",
                type = ComponentType.PERMISSION,
                isGranted = granted
            )
        } ?: emptyList()

        val dialog = AppCompatDialog(activity)
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_app_components_viewer, null)
        dialog.setContentView(view)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = activity.resources.displayMetrics
            val targetWidth = (dm.widthPixels * 0.95).toInt().coerceAtMost((600 * dm.density).toInt())
            val targetHeight = (dm.heightPixels * 0.85).toInt()
            setLayout(targetWidth, targetHeight)
        }

        view.findViewById<TextView>(R.id.tvDialogAppTitle).text = appLabel
        view.findViewById<TextView>(R.id.tvDialogAppSubtitle).text = packageName

        val cachedIcon = NativeAppIconLoader.getCached(packageName)
        if (cachedIcon != null) {
            view.findViewById<ImageView>(R.id.ivDialogAppIcon).setImageDrawable(cachedIcon)
        } else {
            view.findViewById<ImageView>(R.id.ivDialogAppIcon).setImageResource(R.drawable.file_apk_icon)
        }

        view.findViewById<Button>(R.id.btnDialogClose).setOnClickListener {
            dialog.dismiss()
        }

        val tabLayout = view.findViewById<TabLayout>(R.id.tabLayoutComponents)
        tabLayout.addTab(tabLayout.newTab().setText("Actividades (${activitiesList.size})"))
        tabLayout.addTab(tabLayout.newTab().setText("Servicios (${servicesList.size})"))
        tabLayout.addTab(tabLayout.newTab().setText("Receptores (${receiversList.size})"))
        tabLayout.addTab(tabLayout.newTab().setText("Proveedores (${providersList.size})"))
        tabLayout.addTab(tabLayout.newTab().setText("Permisos (${permissionsList.size})"))

        val rv = view.findViewById<RecyclerView>(R.id.rvComponents)
        val emptyText = view.findViewById<TextView>(R.id.tvEmptyComponents)
        rv.layoutManager = LinearLayoutManager(activity)

        var currentTabIndex = 0
        var filterQuery = ""

        fun getActiveList(): List<ComponentItem> {
            val baseList = when (currentTabIndex) {
                0 -> activitiesList
                1 -> servicesList
                2 -> receiversList
                3 -> providersList
                else -> permissionsList
            }
            if (filterQuery.isBlank()) return baseList
            val q = filterQuery.lowercase()
            return baseList.filter { it.name.lowercase().contains(q) || it.subtitle.lowercase().contains(q) }
        }

        val componentAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = getActiveList().size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_app_component, parent, false)
                return object : RecyclerView.ViewHolder(itemView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = getActiveList()[position]
                val v = holder.itemView
                val ivIcon = v.findViewById<ImageView>(R.id.ivComponentIcon)
                val tvName = v.findViewById<TextView>(R.id.tvComponentName)
                val tvSub = v.findViewById<TextView>(R.id.tvComponentSubtitle)
                val btnLaunch = v.findViewById<Button>(R.id.btnLaunchComponent)

                tvName.text = item.name
                tvSub.text = item.subtitle

                when (item.type) {
                    ComponentType.ACTIVITY -> {
                        ivIcon.setImageResource(R.drawable.exit_to_app_icon_white_24dp)
                        ivIcon.setColorFilter(Color.parseColor("#42A5F5"))
                        btnLaunch.visibility = View.VISIBLE
                        btnLaunch.setOnClickListener {
                            try {
                                val launchIntent = Intent().apply {
                                    setClassName(packageName, item.name)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                activity.startActivity(launchIntent)
                                Toast.makeText(activity, "Iniciando: ${item.name.substringAfterLast('.')}", Toast.LENGTH_SHORT).show()
                            } catch (se: SecurityException) {
                                // Try starting with elevated privileges (Root / Shizuku) for private or unexported activities
                                var startedElevated = false
                                val compName = "$packageName/${item.name}"
                                val amCmd = "am start -n $compName"
                                if (ApkInstallHelper.isRootAvailable()) {
                                    val res = Shell.cmd(amCmd).exec()
                                    if (res.isSuccess) startedElevated = true
                                } else if (ApkInstallHelper.isShizukuAvailable()) {
                                    val res = ShizukuShell.exec(amCmd)
                                    if (res.isSuccess) startedElevated = true
                                }

                                if (startedElevated) {
                                    Toast.makeText(activity, "Iniciando con privilegios: ${item.name.substringAfterLast('.')}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(activity, "Seguridad: Actividad privada / requiere permisos", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    ComponentType.SERVICE -> {
                        ivIcon.setImageResource(R.drawable.ic_running_apps)
                        ivIcon.setColorFilter(Color.parseColor("#66BB6A"))
                        btnLaunch.visibility = View.GONE
                    }
                    ComponentType.RECEIVER -> {
                        ivIcon.setImageResource(R.drawable.notification_icon_white_24dp)
                        ivIcon.setColorFilter(Color.parseColor("#FFA726"))
                        btnLaunch.visibility = View.GONE
                    }
                    ComponentType.PROVIDER -> {
                        ivIcon.setImageResource(R.drawable.shared_directory_icon_white_24dp)
                        ivIcon.setColorFilter(Color.parseColor("#AB47BC"))
                        btnLaunch.visibility = View.GONE
                    }
                    ComponentType.PERMISSION -> {
                        ivIcon.setImageResource(R.drawable.shield_icon_white_24dp)
                        if (item.isGranted) {
                            ivIcon.setColorFilter(Color.parseColor("#00E676"))
                            tvSub.setTextColor(Color.parseColor("#00E676"))
                        } else {
                            ivIcon.setColorFilter(Color.parseColor("#EF5350"))
                            tvSub.setTextColor(Color.parseColor("#EF5350"))
                        }
                        btnLaunch.visibility = View.GONE
                    }
                }

                v.setOnClickListener {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Component", item.name))
                    Toast.makeText(activity, "Copiado: ${item.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        rv.adapter = componentAdapter

        fun refreshList() {
            val list = getActiveList()
            emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            componentAdapter.notifyDataSetChanged()
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTabIndex = tab?.position ?: 0
                refreshList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        val searchView = view.findViewById<SearchView>(R.id.searchViewComponents)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterQuery = query.orEmpty()
                refreshList()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterQuery = newText.orEmpty()
                refreshList()
                return true
            }
        })

        refreshList()
        dialog.show()
    }

    /**
     * Decodes and displays AndroidManifest.xml from an APK or installed app in a dedicated viewer.
     */
    fun showManifestViewer(activity: Activity, apkFile: File, appLabel: String) {
        var effectiveApk = apkFile
        var tempCopiedFile: File? = null

        if (!effectiveApk.exists() || !effectiveApk.canRead()) {
            // Attempt elevated copy to cacheDir if root or Shizuku available (for protected system APKs)
            val cacheCopy = File(activity.cacheDir, "manifest_tmp_${System.currentTimeMillis()}.apk")
            var copied = false
            if (ApkInstallHelper.isRootAvailable()) {
                val res = Shell.cmd("cp ${effectiveApk.absolutePath.escapeShellArg()} ${cacheCopy.absolutePath.escapeShellArg()} && chmod 644 ${cacheCopy.absolutePath.escapeShellArg()}").exec()
                if (res.isSuccess && cacheCopy.exists() && cacheCopy.length() > 0L) {
                    copied = true
                }
            } else if (ApkInstallHelper.isShizukuAvailable()) {
                val res = ShizukuShell.exec("cp ${effectiveApk.absolutePath.escapeShellArg()} ${cacheCopy.absolutePath.escapeShellArg()} && chmod 644 ${cacheCopy.absolutePath.escapeShellArg()}")
                if (res.isSuccess && cacheCopy.exists() && cacheCopy.length() > 0L) {
                    copied = true
                }
            }

            if (copied) {
                effectiveApk = cacheCopy
                tempCopiedFile = cacheCopy
            } else {
                Toast.makeText(activity, "El archivo APK no es accesible", Toast.LENGTH_SHORT).show()
                return
            }
        }

        var xmlText: String? = null
        try {
            ZipFile(effectiveApk).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml")
                if (entry != null) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    xmlText = if (AxmlDecoder.isAxml(bytes)) {
                        AxmlDecoder.decode(bytes)
                    } else {
                        String(bytes, Charsets.UTF_8)
                    }
                }
            }
        } catch (e: Exception) {
            xmlText = "// Error al decodificar AndroidManifest.xml: ${e.message}\n"
        } finally {
            tempCopiedFile?.delete()
        }

        if (xmlText.isNullOrBlank()) {
            Toast.makeText(activity, "No se encontró AndroidManifest.xml en el APK", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = AppCompatDialog(activity)
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_manifest_viewer, null)
        dialog.setContentView(view)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = activity.resources.displayMetrics
            val targetWidth = (dm.widthPixels * 0.95).toInt().coerceAtMost((650 * dm.density).toInt())
            val targetHeight = (dm.heightPixels * 0.85).toInt()
            setLayout(targetWidth, targetHeight)
        }

        view.findViewById<TextView>(R.id.tvManifestTitle).text = "AndroidManifest.xml • $appLabel"
        val tvContent = view.findViewById<TextView>(R.id.tvManifestContent)
        tvContent.text = xmlText

        view.findViewById<Button>(R.id.btnCopyManifest).setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("AndroidManifest.xml", xmlText))
            Toast.makeText(activity, "Manifest copiado al portapapeles", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnCloseManifest).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private object PackageInfoCompat {
        const val REQUESTED_PERMISSION_GRANTED = 2
    }
}
