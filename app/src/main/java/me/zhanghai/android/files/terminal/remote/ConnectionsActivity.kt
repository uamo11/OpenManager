/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.ftpserver.FtpServerActivity
import me.zhanghai.android.files.webaccess.WebAccessActivity
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.AddLanSmbServerActivity
import me.zhanghai.android.files.storage.EditFtpServerActivity
import me.zhanghai.android.files.storage.EditFtpServerFragment
import me.zhanghai.android.files.storage.EditSftpServerActivity
import me.zhanghai.android.files.storage.EditSftpServerFragment
import me.zhanghai.android.files.storage.EditWebDavServerActivity
import me.zhanghai.android.files.storage.EditWebDavServerFragment
import me.zhanghai.android.files.storage.FtpServer
import me.zhanghai.android.files.storage.SftpServer
import me.zhanghai.android.files.storage.SmbServer
import me.zhanghai.android.files.storage.Storage
import me.zhanghai.android.files.storage.WebDavServer
import me.zhanghai.android.files.terminal.TerminalActivity
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat

/**
 * Connections hub: SSH/Telnet terminal profiles, remote file servers (FTP, SFTP, SMB, WebDAV)
 * and this device's FTP server, with a categorized "+" picker.
 */
class ConnectionsActivity : AppActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private val adapter = ConnectionsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.remote_profiles_activity)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.om_connections_title)

        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { showAddSheet(this) }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_ADD, false)) {
            recyclerView.post { showAddSheet(this) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_LOCAL, Menu.NONE, R.string.om_remote_open_local_terminal)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_LOCAL -> {
            startActivitySafe(TerminalActivity.createIntent(this))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val rows = mutableListOf<Row>()
        val profiles = RemoteProfileStore.listSorted(this)
        if (profiles.isNotEmpty()) {
            rows += Row.Header(getString(R.string.om_connections_section_terminal))
            profiles.mapTo(rows) { Row.Profile(it) }
        }
        val storages = Settings.STORAGES.valueCompat.filter { ConnectionType.forStorage(it) != null }
        if (storages.isNotEmpty()) {
            rows += Row.Header(getString(R.string.om_connections_section_files))
            storages.mapTo(rows) { Row.Server(it) }
        }
        rows += Row.Header(getString(R.string.om_connections_section_device))
        rows += Row.LocalFtpServer
        rows += Row.LocalWebAccess
        adapter.submit(rows)
        emptyView.visibility = if (profiles.isEmpty() && storages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showProfileMenu(anchor: View, profile: RemoteProfile) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, 1, 1, R.string.om_remote_connect)
        popup.menu.add(Menu.NONE, 2, 2, R.string.om_remote_profile_edit)
        popup.menu.add(Menu.NONE, 3, 3, R.string.om_remote_profile_duplicate)
        if (profile.protocol == RemoteProtocol.SSH) {
            popup.menu.add(Menu.NONE, 4, 4, R.string.om_remote_profile_forget_hostkey)
        }
        popup.menu.add(Menu.NONE, 5, 5, R.string.om_remote_profile_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> connect(this, profile)
                2 -> startActivity(RemoteProfileEditActivity.createIntent(this, profile.id))
                3 -> {
                    val copy = profile.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = getString(R.string.om_remote_profile_copy_name, profile.displayName),
                        lastUsedMillis = 0L
                    )
                    RemoteProfileStore.save(this, copy)
                    refresh()
                }
                4 -> {
                    KnownHostsStore.forget(this, profile.host, profile.port)
                    Toast.makeText(this, R.string.om_remote_hostkey_forgotten, Toast.LENGTH_SHORT).show()
                }
                5 -> MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.om_remote_profile_delete)
                    .setMessage(getString(R.string.om_remote_profile_delete_message, profile.displayName))
                    .setPositiveButton(R.string.om_remote_profile_delete) { _, _ ->
                        RemoteProfileStore.delete(this, profile.id)
                        refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
        popup.show()
    }

    private fun showServerMenu(anchor: View, storage: Storage) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, 1, 1, R.string.om_connections_open_files)
        popup.menu.add(Menu.NONE, 2, 2, R.string.om_remote_profile_edit)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openServer(storage)
                2 -> startActivitySafe(storage.createEditIntent())
            }
            true
        }
        popup.show()
    }

    private fun openServer(storage: Storage) {
        val path = storage.path ?: return
        startActivitySafe(FileListActivity.createViewIntent(path))
    }

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Profile(val profile: RemoteProfile) : Row()
        data class Server(val storage: Storage) : Row()
        object LocalFtpServer : Row()
        object LocalWebAccess : Row()
    }

    private inner class ConnectionsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            rows = newRows
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                object : RecyclerView.ViewHolder(
                    inflater.inflate(R.layout.connection_section_header, parent, false)
                ) {}
            } else {
                ItemHolder(inflater.inflate(R.layout.remote_profile_item, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).text = row.title
                is Row.Profile -> (holder as ItemHolder).bindProfile(row.profile)
                is Row.Server -> (holder as ItemHolder).bindServer(row.storage)
                Row.LocalFtpServer -> (holder as ItemHolder).bindLocalFtp()
                Row.LocalWebAccess -> (holder as ItemHolder).bindLocalWebAccess()
            }
        }
    }

    private inner class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tile: View = view.findViewById(R.id.typeTile)
        private val icon: ImageView = view.findViewById(R.id.typeIcon)
        private val name: TextView = view.findViewById(R.id.nameText)
        private val endpoint: TextView = view.findViewById(R.id.endpointText)
        private val detail: TextView = view.findViewById(R.id.detailText)
        private val menu: ImageButton = view.findViewById(R.id.menuButton)

        fun bindProfile(profile: RemoteProfile) {
            applyType(tile, icon, ConnectionType.forProtocol(profile.protocol))
            name.text = profile.displayName
            endpoint.text = profile.endpoint
            detail.text = describe(itemView.context, profile)
            detail.visibility = View.VISIBLE
            menu.visibility = View.VISIBLE
            itemView.setOnClickListener { connect(this@ConnectionsActivity, profile) }
            itemView.setOnLongClickListener {
                showProfileMenu(menu, profile)
                true
            }
            menu.setOnClickListener { showProfileMenu(it, profile) }
        }

        fun bindServer(storage: Storage) {
            val type = ConnectionType.forStorage(storage)!!
            applyType(tile, icon, type)
            name.text = storage.getName(itemView.context)
            endpoint.text = storage.description
            detail.text = itemView.context.getString(type.labelRes)
            detail.visibility = View.VISIBLE
            menu.visibility = View.VISIBLE
            itemView.setOnClickListener { openServer(storage) }
            itemView.setOnLongClickListener {
                showServerMenu(menu, storage)
                true
            }
            menu.setOnClickListener { showServerMenu(it, storage) }
        }

        fun bindLocalWebAccess() {
            applyType(tile, icon, ConnectionType.WEB_ACCESS)
            name.setText(R.string.om_web_access_title)
            endpoint.setText(R.string.om_web_access_caption)
            detail.visibility = View.GONE
            menu.visibility = View.GONE
            itemView.setOnClickListener {
                startActivitySafe(Intent(this@ConnectionsActivity, WebAccessActivity::class.java))
            }
            itemView.setOnLongClickListener(null)
        }

        fun bindLocalFtp() {
            applyType(tile, icon, ConnectionType.FTP_SERVER)
            name.setText(R.string.om_connections_type_ftp_server)
            endpoint.setText(R.string.om_connections_type_ftp_server_caption)
            detail.visibility = View.GONE
            menu.visibility = View.GONE
            itemView.setOnClickListener {
                startActivitySafe(Intent(this@ConnectionsActivity, FtpServerActivity::class.java))
            }
            itemView.setOnLongClickListener(null)
        }
    }

    /** Every kind of connection, with its icon, color and how to create one. */
    enum class ConnectionType(
        @DrawableRes val iconRes: Int,
        val color: Int,
        @StringRes val labelRes: Int,
        @StringRes val captionRes: Int
    ) {
        SSH(R.drawable.terminal_icon_white_24dp, 0xFF8B5CF6.toInt(), R.string.om_connections_type_ssh,
            R.string.om_connections_type_ssh_caption),
        TELNET(R.drawable.ic_conn_telnet, 0xFFF59E0B.toInt(), R.string.om_connections_type_telnet,
            R.string.om_connections_type_telnet_caption),
        FTP(R.drawable.ic_conn_ftp, 0xFF0EA5E9.toInt(), R.string.om_connections_type_ftp,
            R.string.om_connections_type_ftp_caption),
        SFTP(R.drawable.ic_conn_sftp, 0xFF10B981.toInt(), R.string.om_connections_type_sftp,
            R.string.om_connections_type_sftp_caption),
        SMB(R.drawable.computer_icon_white_24dp, 0xFF6366F1.toInt(), R.string.om_connections_type_smb,
            R.string.om_connections_type_smb_caption),
        WEBDAV(R.drawable.ic_conn_webdav, 0xFFEC4899.toInt(), R.string.om_connections_type_webdav,
            R.string.om_connections_type_webdav_caption),
        FTP_SERVER(R.drawable.shared_directory_icon_white_24dp, 0xFF14B8A6.toInt(),
            R.string.om_connections_type_ftp_server, R.string.om_connections_type_ftp_server_caption),
        WEB_ACCESS(R.drawable.computer_icon_white_24dp, 0xFF4F8CFF.toInt(),
            R.string.om_web_access_title, R.string.om_web_access_caption);

        companion object {
            fun forProtocol(protocol: RemoteProtocol): ConnectionType =
                if (protocol == RemoteProtocol.SSH) SSH else TELNET

            fun forStorage(storage: Storage): ConnectionType? = when (storage) {
                is FtpServer -> FTP
                is SftpServer -> SFTP
                is SmbServer -> SMB
                is WebDavServer -> WEBDAV
                else -> null
            }
        }
    }

    companion object {
        private const val MENU_LOCAL = 100
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val EXTRA_OPEN_ADD = "me.zhanghai.android.files.terminal.remote.extra.OPEN_ADD"

        fun createIntent(context: Context, openAdd: Boolean = false): Intent =
            Intent(context, ConnectionsActivity::class.java).putExtra(EXTRA_OPEN_ADD, openAdd)

        private fun applyType(tile: View, icon: ImageView, type: ConnectionType) {
            tile.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * tile.resources.displayMetrics.density
                setColor(type.color and 0x00FFFFFF or 0x2E000000)
            }
            icon.setImageResource(type.iconRes)
            icon.imageTintList = ColorStateList.valueOf(type.color)
        }

        /** Categorized picker: Terminal (SSH, Telnet), Files (FTP, SFTP, SMB, WebDAV), This device. */
        fun showAddSheet(activity: Activity) {
            val dialog = BottomSheetDialog(activity)
            val inflater = LayoutInflater.from(activity)
            val density = activity.resources.displayMetrics.density
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
            }
            root.addView(TextView(activity).apply {
                setText(R.string.om_connections_add_title)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
                setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
            })
            val groups = listOf(
                R.string.om_connections_section_terminal to listOf(ConnectionType.SSH, ConnectionType.TELNET),
                R.string.om_connections_section_files to listOf(
                    ConnectionType.FTP, ConnectionType.SFTP, ConnectionType.SMB, ConnectionType.WEBDAV
                ),
                R.string.om_connections_section_device to listOf(ConnectionType.FTP_SERVER, ConnectionType.WEB_ACCESS)
            )
            for ((titleRes, types) in groups) {
                val header = inflater.inflate(R.layout.connection_section_header, root, false) as TextView
                header.setText(titleRes)
                header.setPadding(0, header.paddingTop, 0, header.paddingBottom)
                root.addView(header)
                for (chunk in types.chunked(3)) {
                    val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    for (type in chunk) {
                        val tileView = inflater.inflate(R.layout.connection_type_tile, line, false)
                        applyType(tileView.findViewById(R.id.tile), tileView.findViewById(R.id.icon), type)
                        tileView.findViewById<TextView>(R.id.label).setText(type.labelRes)
                        tileView.findViewById<TextView>(R.id.caption).setText(type.captionRes)
                        tileView.contentDescription = activity.getString(type.labelRes)
                        tileView.setOnClickListener {
                            dialog.dismiss()
                            startAdd(activity, type)
                        }
                        line.addView(tileView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                    // Keep tiles the same width on incomplete rows.
                    repeat(3 - chunk.size) {
                        line.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                    }
                    root.addView(line)
                }
            }
            dialog.setContentView(androidx.core.widget.NestedScrollView(activity).apply { addView(root) })
            dialog.show()
        }

        private fun startAdd(activity: Activity, type: ConnectionType) {
            val intent = when (type) {
                ConnectionType.SSH -> RemoteProfileEditActivity.createIntent(activity, null, RemoteProtocol.SSH)
                ConnectionType.TELNET -> RemoteProfileEditActivity.createIntent(activity, null, RemoteProtocol.TELNET)
                ConnectionType.FTP -> EditFtpServerActivity::class.createIntent().putArgs(EditFtpServerFragment.Args())
                ConnectionType.SFTP -> EditSftpServerActivity::class.createIntent().putArgs(EditSftpServerFragment.Args())
                ConnectionType.SMB -> AddLanSmbServerActivity::class.createIntent()
                ConnectionType.WEBDAV ->
                    EditWebDavServerActivity::class.createIntent().putArgs(EditWebDavServerFragment.Args())
                ConnectionType.FTP_SERVER -> Intent(activity, FtpServerActivity::class.java)
                ConnectionType.WEB_ACCESS -> Intent(activity, WebAccessActivity::class.java)
            }
            activity.startActivitySafe(intent)
        }

        fun describe(context: Context, profile: RemoteProfile): String {
            val parts = mutableListOf<String>()
            parts += when {
                profile.protocol == RemoteProtocol.TELNET ->
                    context.getString(R.string.om_remote_detail_unencrypted)
                profile.authType == RemoteAuthType.PRIVATE_KEY -> context.getString(R.string.om_remote_detail_key)
                profile.hasSavedPassword -> context.getString(R.string.om_remote_detail_password_saved)
                else -> context.getString(R.string.om_remote_detail_password_ask)
            }
            RemoteProfile.parseCommandLines(profile.startupCommands).firstOrNull()?.let { parts += "▶ $it" }
            if (profile.shortcuts.isNotEmpty()) {
                parts += context.resources.getQuantityString(
                    R.plurals.om_remote_detail_shortcuts, profile.shortcuts.size, profile.shortcuts.size
                )
            }
            return parts.joinToString(" · ")
        }

        /**
         * Opens the terminal on [profile]. Telnet to a host outside the local network first asks the
         * user to acknowledge that everything, password included, travels in clear text.
         */
        fun connect(activity: Activity, profile: RemoteProfile) {
            val error = profile.validationError()
            if (error != null) {
                Toast.makeText(activity, R.string.om_remote_profile_incomplete, Toast.LENGTH_SHORT).show()
                activity.startActivity(RemoteProfileEditActivity.createIntent(activity, profile.id))
                return
            }
            val launch = {
                RemoteProfileStore.markUsed(activity, profile.id)
                activity.startActivitySafe(TerminalActivity.createRemoteIntent(activity, profile.id))
            }
            if (profile.protocol == RemoteProtocol.TELNET && !profile.telnetRiskAccepted &&
                !RemoteProfile.isLocalNetworkHost(profile.host)
            ) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.om_remote_telnet_confirm_title)
                    .setMessage(activity.getString(R.string.om_remote_telnet_confirm_message, profile.endpoint))
                    .setPositiveButton(R.string.om_remote_connect) { _, _ -> launch() }
                    .setNeutralButton(R.string.om_remote_telnet_confirm_always) { _, _ ->
                        RemoteProfileStore.save(activity, profile.copy(telnetRiskAccepted = true))
                        launch()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                launch()
            }
        }
    }
}
