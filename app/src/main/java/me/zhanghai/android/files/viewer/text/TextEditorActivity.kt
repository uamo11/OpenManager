/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Context
import java.io.File
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.extraPath

import android.os.Bundle
import android.view.View
import androidx.core.view.WindowCompat
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.putArgs

class TextEditorActivity : AppActivity() {
    private lateinit var fragment: TextEditorFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            // Opened without a file (side bar "Text editor"): start a new, untitled document
            // instead of closing at once.
            if (intent.extraPath == null) {
                val draft = createDraft(this)
                if (draft == null) {
                    finish()
                    return
                }
                intent.extraPath = draft
                intent.putExtra(EXTRA_DRAFT, true)
            }
            fragment = TextEditorFragment().putArgs(TextEditorFragment.Args(intent))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content)
                as TextEditorFragment
        }
    }

    companion object {
        /** Boolean: the file is a scratch file for a new document ("Save" asks where). */
        const val EXTRA_DRAFT = "com.y.om.extra.EDITOR_DRAFT"
        /** String: folder suggested by "Save as" for a new document (the open panel). */
        const val EXTRA_TARGET_DIR = "com.y.om.extra.EDITOR_TARGET_DIR"

        private const val DRAFT_DIR = "editor_drafts"
        private const val DRAFT_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

        /** An empty scratch file in the app's private storage; old leftovers are removed. */
        fun createDraft(context: Context): Path? = try {
            val dir = File(context.filesDir, DRAFT_DIR).apply { mkdirs() }
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { if (now - it.lastModified() > DRAFT_MAX_AGE_MS) it.delete() }
            val file = File(dir, "untitled-$now.txt")
            file.createNewFile()
            Paths.get(file.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        /** Removes a scratch file made by [createDraft] (never anything else). */
        fun deleteDraft(path: Path) {
            try {
                if (!path.isLinuxPath) return
                val file = path.toFile()
                if (file.parentFile?.name == DRAFT_DIR && file.name.startsWith("untitled-")) file.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (fragment.onSupportNavigateUp()) {
            return true
        }
        return super.onSupportNavigateUp()
    }
}
