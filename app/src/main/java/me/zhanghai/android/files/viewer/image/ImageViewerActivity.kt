/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import java8.nio.file.Path
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.putArgs

class ImageViewerActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val intent = intent
            if (BuildConfig.DEBUG) {
                // E2E: open the images of a folder at the 19th, as from a search with many results.
                intent.getStringExtra("DEMO_IMAGE_DIR")?.let { dir ->
                    val images = java.io.File(dir).listFiles().orEmpty()
                        .filter { it.name.endsWith(".png") || it.name.endsWith(".jpg") }
                        .sortedBy { it.name }
                        .map { java8.nio.file.Paths.get(it.toURI()) }
                    putExtras(intent, images, minOf(18, images.lastIndex).coerceAtLeast(0))
                }
            }
            val position = intent.getIntExtra(EXTRA_POSITION, 0)
            val fragment = ImageViewerFragment()
                .putArgs(ImageViewerFragment.Args(position))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (BuildConfig.DEBUG) {
            val parcel = android.os.Parcel.obtain()
            try {
                parcel.writeBundle(outState)
                android.util.Log.d("OMImageViewer", "Saved state bytes=${parcel.dataSize()}")
            } finally {
                parcel.recycle()
            }
        }
    }

    companion object {
        private val EXTRA_POSITION = "${ImageViewerActivity::class.java.name}.extra.POSITION"

        fun putExtras(intent: Intent, paths: List<Path>, position: Int) {
            // All extra put here must be framework classes, or we may crash the resolver activity.
            intent.extraPathList = paths
            intent.putExtra(EXTRA_POSITION, position)
        }
    }
}
