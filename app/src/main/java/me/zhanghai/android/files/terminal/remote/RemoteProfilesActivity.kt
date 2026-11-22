/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.terminal.TerminalActivity
import me.zhanghai.android.files.util.startActivitySafe

/** Saved SSH/Telnet connections: tap to connect, "+" to add, overflow to edit. */
class RemoteProfilesActivity : AppActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private val adapter = ProfileAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.remote_profiles_activity)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.om_remote_profiles_title)

        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            startActivity(RemoteProfileEditActivity.createIntent(this, null))
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
        val profiles = RemoteProfileStore.listSorted(this)
        adapter.submit(profiles)
        emptyView.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
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

    private inner class ProfileAdapter : RecyclerView.Adapter<ProfileHolder>() {
        private var items: List<RemoteProfile> = emptyList()

        init {
            setHasStableIds(true)
        }

        fun submit(profiles: List<RemoteProfile>) {
            items = profiles
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileHolder =
            ProfileHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.remote_profile_item, parent, false)
            )

        override fun onBindViewHolder(holder: ProfileHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class ProfileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val badge: TextView = view.findViewById(R.id.protocolBadge)
        private val name: TextView = view.findViewById(R.id.nameText)
        private val endpoint: TextView = view.findViewById(R.id.endpointText)
        private val detail: TextView = view.findViewById(R.id.detailText)
        private val menu: ImageButton = view.findViewById(R.id.menuButton)

        fun bind(profile: RemoteProfile) {
            val isSsh = profile.protocol == RemoteProtocol.SSH
            badge.text = if (isSsh) "SSH" else "TEL"
            val color = if (isSsh) 0xFF8B5CF6.toInt() else 0xFFF59E0B.toInt()
            badge.setTextColor(color)
            badge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color and 0x00FFFFFF or 0x22000000)
                setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), color)
            }
            name.text = profile.displayName
            endpoint.text = profile.endpoint
            detail.text = describe(itemView.context, profile)
            itemView.setOnClickListener { connect(this@RemoteProfilesActivity, profile) }
            itemView.setOnLongClickListener {
                showProfileMenu(menu, profile)
                true
            }
            menu.setOnClickListener { showProfileMenu(it, profile) }
        }
    }

    companion object {
        private const val MENU_LOCAL = 100

        fun createIntent(context: Context): Intent = Intent(context, RemoteProfilesActivity::class.java)

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
