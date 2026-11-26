/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R

/**
 * What holding each button of the explorer's bottom bar does (user request): a shortcut to what
 * you use most, such as the quick filter on ⇄ or search on +. Home keeps its bookmarks by default.
 */
object BottomBarLongPress {

    enum class Button(val key: String, @StringRes val labelRes: Int, val default: Action) {
        BACK("back", R.string.om_long_press_button_back, Action.NONE),
        FORWARD("forward", R.string.om_long_press_button_forward, Action.NONE),
        ADD("add", R.string.om_long_press_button_add, Action.NONE),
        SWAP("swap", R.string.om_long_press_button_swap, Action.NONE),
        HOME("home", R.string.om_long_press_button_home, Action.BOOKMARKS)
    }

    enum class Action(val id: String, @StringRes val labelRes: Int) {
        NONE("none", R.string.om_long_press_action_none),
        QUICK_FILTER("quick_filter", R.string.om_menu_filter),
        SEARCH("search", R.string.om_long_press_action_search),
        SEARCH_RESULTS("search_results", R.string.om_menu_search_results),
        BOOKMARKS("bookmarks", R.string.om_long_press_action_bookmarks),
        NEW_ITEM("new_item", R.string.om_long_press_action_new),
        REFRESH("refresh", R.string.om_long_press_action_refresh),
        TERMINAL("terminal", R.string.terminal_title),
        HIDDEN_FILES("hidden_files", R.string.file_list_action_show_hidden_files),
        SORT("sort", R.string.om_menu_sort_view)
    }

    private fun key(button: Button) = "bottom_long_press_" + button.key

    fun actionFor(context: Context, button: Button): Action {
        val id = PreferenceManager.getDefaultSharedPreferences(context).getString(key(button), null)
        return Action.values().firstOrNull { it.id == id } ?: button.default
    }

    fun set(context: Context, button: Button, action: Action) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(key(button), action.id).apply()
    }

    /** Picks the action for [button]; [onChosen] gets it once saved. */
    fun choose(activity: Activity, button: Button, onChosen: (Action) -> Unit = {}) {
        val actions = Action.values()
        val current = actionFor(activity, button)
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.om_long_press_choose_format, activity.getString(button.labelRes)))
            .setSingleChoiceItems(actions.map { activity.getString(it.labelRes) }.toTypedArray(), actions.indexOf(current)) { dialog, which ->
                set(activity, button, actions[which])
                dialog.dismiss()
                onChosen(actions[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Settings: every button with what holding it does; tap one to change it. */
    fun showEditor(activity: Activity) {
        val buttons = Button.values()
        val rows = buttons.map {
            activity.getString(it.labelRes) + "  →  " + activity.getString(actionFor(activity, it).labelRes)
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.om_long_press_title)
            .setItems(rows) { _, which -> choose(activity, buttons[which]) { showEditor(activity) } }
            .setNegativeButton(R.string.close, null)
            .show()
    }
}
