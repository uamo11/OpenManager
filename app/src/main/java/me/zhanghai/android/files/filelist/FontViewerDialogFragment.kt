package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.ui.resolveThemeColor
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.dpToDimensionPixelSize
import me.zhanghai.android.files.util.putArgs
import java.io.File
import java.io.FileOutputStream

class FontViewerDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()
    private var tempFontFile: File? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val padH = context.dpToDimensionPixelSize(24)
        val padV = context.dpToDimensionPixelSize(12)
        val padTopLabel = context.dpToDimensionPixelSize(8)
        val padBottomSample = context.dpToDimensionPixelSize(6)

        val primaryColor = context.resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE)
        val secondaryColor = context.resolveThemeColor(android.R.attr.textColorSecondary, 0xFF90A4AE.toInt())

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
        }

        val scroll = ScrollView(context).apply {
            addView(container)
        }

        val sampleViews = mutableListOf<TextView>()
        fun addSample(label: String, sample: String, sizeSp: Float) {
            val labelView = TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(secondaryColor)
                setPadding(0, padTopLabel, 0, padBottomSample / 2)
            }
            val sampleView = TextView(context).apply {
                text = sample
                textSize = sizeSp
                typeface = Typeface.DEFAULT
                setTextColor(primaryColor)
                setPadding(0, 0, 0, padBottomSample)
            }
            sampleViews.add(sampleView)
            container.addView(labelView)
            container.addView(sampleView)
        }

        addSample(getString(R.string.om_font_sample_paragraph), getString(R.string.open_as_font_sample_es), 16f)
        addSample(getString(R.string.om_font_sample_upper), "A B C D E F G H I J K L M N Ñ O P Q R S T U V W X Y Z", 20f)
        addSample(getString(R.string.om_font_sample_lower), "a b c d e f g h i j k l m n ñ o p q r s t u v w x y z", 20f)
        addSample(getString(R.string.om_font_sample_numbers), "0 1 2 3 4 5 6 7 8 9 ! @ # $ % & * ( ) _ +", 22f)
        addSample(getString(R.string.om_font_sample_headline), "OpenManager Font Viewer", 28f)

        val appContext = context.applicationContext
        lifecycleScope.launch {
            val loadedTypeface = withContext(Dispatchers.IO) {
                try {
                    val path = args.path
                    val file = if (path.isLinuxPath) {
                        path.toFile()
                    } else {
                        val temp = File.createTempFile("preview_font_", ".ttf", appContext.cacheDir)
                        tempFontFile = temp
                        path.newInputStream().use { input ->
                            FileOutputStream(temp).use { output ->
                                input.copyTo(output)
                            }
                        }
                        temp
                    }
                    if (file.exists() && file.length() > 0) {
                        Typeface.createFromFile(file)
                    } else {
                        Typeface.DEFAULT
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Typeface.DEFAULT
                }
            }
            if (isUiAlive) {
                sampleViews.forEach { it.typeface = loadedTypeface }
            }
        }

        return MaterialAlertDialogBuilder(context, theme)
            .setTitle("${getString(R.string.open_as_font_title)}: ${args.path.fileName}")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    override fun onDestroy() {
        super.onDestroy()
        tempFontFile?.delete()
        tempFontFile = null
    }

    companion object {
        fun show(path: Path, fragmentManager: androidx.fragment.app.FragmentManager) {
            FontViewerDialogFragment().putArgs(Args(path)).show(fragmentManager, null)
        }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}
