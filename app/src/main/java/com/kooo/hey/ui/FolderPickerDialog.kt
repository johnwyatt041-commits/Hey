package com.kooo.hey.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.storage.StorageManager
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.kooo.hey.R
import java.io.File

/**
 * Visual folder browser dialog for selecting a music directory.
 * Supports internal storage and USB drives detection.
 */
class FolderPickerDialog(
    private val context: Context,
    private val initialPath: String?,
    private val onFolderSelected: (String) -> Unit
) {
    private var currentDir: File = resolveInitialDir()
    private var dialog: Dialog? = null

    fun show() {
        val dlg = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(true)
        }
        dialog = dlg

        val rootLayout = buildLayout(dlg)
        dlg.setContentView(rootLayout)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val dialogWidth = context.resources.getDimensionPixelSize(R.dimen.dialog_folder_width)
        dlg.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    private fun resolveInitialDir(): File {
        val path = initialPath?.takeIf { it.isNotBlank() }
        if (path != null) {
            val f = File(path)
            if (f.exists() && f.isDirectory) return f
        }
        return Environment.getExternalStorageDirectory()
    }

    private fun buildLayout(dlg: Dialog): ViewGroup {
        val res = context.resources
        val density = res.displayMetrics.density
        val titleSize = res.getDimension(R.dimen.dialog_title_size)
        val bodySize = res.getDimension(R.dimen.dialog_body_size)
        val buttonSize = res.getDimension(R.dimen.dialog_button_size)
        val inputSize = res.getDimension(R.dimen.dialog_input_size)
        val pad = (20 * density).toInt()
        val padInner = (12 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog)
            setPadding(pad, pad, pad, pad)
        }

        // Title
        val title = TextView(context).apply {
            text = context.getString(R.string.music_folder_browse)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize)
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(title)

        // Storage shortcuts
        val shortcuts = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = padInner }
            isHorizontalScrollBarEnabled = false
        }
        val shortcutRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        addStorageShortcuts(shortcutRow, dlg, density, bodySize)
        shortcuts.addView(shortcutRow)
        container.addView(shortcuts)

        // Current path display
        val pathView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = padInner }
            text = currentDir.absolutePath
            setTextSize(TypedValue.COMPLEX_UNIT_PX, inputSize * 0.85f)
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_dim))
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_input)
            setPadding(padInner, (8 * density).toInt(), padInner, (8 * density).toInt())
            maxLines = 2
        }
        container.addView(pathView)

        // Folder list
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (300 * density).toInt()
            ).apply { topMargin = padInner }
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        container.addView(scroll)

        // Buttons row
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt() }
        }

        val cancelBtn = TextView(context).apply {
            text = context.getString(R.string.permission_dialog_cancel)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, buttonSize)
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_button)
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
            setOnClickListener { dlg.dismiss() }
        }

        val selectBtn = TextView(context).apply {
            text = context.getString(R.string.music_folder_select)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, buttonSize)
            setTextColor(ContextCompat.getColor(context, R.color.on_primary))
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_button_primary)
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (12 * density).toInt() }
            setOnClickListener {
                onFolderSelected(currentDir.absolutePath)
                dlg.dismiss()
            }
        }

        btnRow.addView(cancelBtn)
        btnRow.addView(selectBtn)
        container.addView(btnRow)

        populateFolderList(listContainer, pathView, dlg, density, bodySize)
        return container
    }

    private fun populateFolderList(
        container: LinearLayout, pathView: TextView, dlg: Dialog,
        density: Float, textSize: Float
    ) {
        container.removeAllViews()
        pathView.text = currentDir.absolutePath

        val children = try {
            currentDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        } catch (_: SecurityException) {
            Toast.makeText(context, context.getString(R.string.music_folder_no_access), Toast.LENGTH_SHORT).show()
            emptyList()
        }

        // Parent directory entry
        val parent = currentDir.parentFile
        if (parent != null && parent.canRead()) {
            val parentItem = createFolderItem("..", "返回上级", density, textSize, bold = true)
            parentItem.setOnClickListener {
                currentDir = parent
                populateFolderList(container, pathView, dlg, density, textSize)
            }
            container.addView(parentItem)
        }

        if (children.isEmpty()) {
            val empty = TextView(context).apply {
                text = context.getString(R.string.music_folder_empty)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * 0.9f)
                setTextColor(ContextCompat.getColor(context, R.color.on_surface_muted))
                gravity = Gravity.CENTER
                setPadding(0, (20 * density).toInt(), 0, (20 * density).toInt())
            }
            container.addView(empty)
            return
        }

        for (dir in children) {
            val subCount = try {
                val files = dir.listFiles()
                val dirs = files?.count { it.isDirectory } ?: 0
                val audios = files?.count {
                    it.isFile && it.extension.lowercase() in setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "wma")
                } ?: 0
                if (audios > 0) "$dirs 文件夹, $audios 音频" else if (dirs > 0) "$dirs 文件夹" else ""
            } catch (_: Exception) { "" }

            val item = createFolderItem(dir.name, subCount, density, textSize, bold = false)
            item.setOnClickListener {
                currentDir = dir
                populateFolderList(container, pathView, dlg, density, textSize)
            }
            container.addView(item)
        }
    }

    private fun createFolderItem(name: String, subtitle: String, density: Float, textSize: Float, bold: Boolean): LinearLayout {
        val padH = (14 * density).toInt()
        val padV = (10 * density).toInt()
        val marginB = (4 * density).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_folder_item)
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = marginB }
            isClickable = true
            isFocusable = true

            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                    marginEnd = (10 * density).toInt()
                }
                setImageResource(R.drawable.ic_folder)
                setColorFilter(ContextCompat.getColor(context, R.color.on_surface_muted))
            }
            addView(icon)

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = TextView(context).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                setTextColor(ContextCompat.getColor(context, if (bold) R.color.on_surface_dim else R.color.on_surface))
                if (bold) typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textCol.addView(nameView)
            if (subtitle.isNotEmpty()) {
                val subView = TextView(context).apply {
                    text = subtitle
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * 0.75f)
                    setTextColor(ContextCompat.getColor(context, R.color.on_surface_muted))
                }
                textCol.addView(subView)
            }
            addView(textCol)
        }
    }

    private fun addStorageShortcuts(row: LinearLayout, dlg: Dialog, density: Float, textSize: Float) {
        val storageLocations = mutableListOf<Pair<String, File>>()

        // Internal storage
        storageLocations.add("内部存储" to Environment.getExternalStorageDirectory())

        // Detect USB drives via StorageManager
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val volumes = sm.storageVolumes
            for (vol in volumes) {
                if (vol.isRemovable) {
                    val dirMethod = vol.javaClass.getMethod("getDirectory")
                    val dir = dirMethod.invoke(vol) as? File
                    if (dir != null && dir.exists()) {
                        val label = vol.getDescription(context) ?: "USB"
                        storageLocations.add(label to dir)
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: scan /storage for USB mounts
        try {
            val storageDir = File("/storage")
            storageDir.listFiles()?.forEach { f ->
                if (f.isDirectory && f.canRead() && f.name != "emulated" && f.name != "self") {
                    val already = storageLocations.any { it.second.absolutePath == f.absolutePath }
                    if (!already) storageLocations.add("USB: ${f.name}" to f)
                }
            }
        } catch (_: Exception) {}

        for ((label, dir) in storageLocations) {
            val isUsb = label != "内部存储"
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_button)
                setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * density).toInt() }
                isClickable = true
                isFocusable = true
            }
            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                    marginEnd = (6 * density).toInt()
                }
                setImageResource(if (isUsb) R.drawable.ic_usb else R.drawable.ic_folder)
                setColorFilter(ContextCompat.getColor(context, R.color.on_surface_dim))
            }
            val tv = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * 0.8f)
                setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            }
            chip.addView(icon)
            chip.addView(tv)
            chip.setOnClickListener {
                currentDir = dir
                dialog?.let { d ->
                    val content = (d.window?.decorView as? ViewGroup)
                    if (content != null) {
                        d.dismiss()
                        show()
                    }
                }
            }
            row.addView(chip)
        }
    }
}
