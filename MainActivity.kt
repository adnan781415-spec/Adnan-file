package com.adnan.manager

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private val root: File = Environment.getExternalStorageDirectory()
    private var cwd: File = root
    private var files: List<File> = emptyList()
    private var clip: File? = null
    private var move = false
    private var asking = false
    private lateinit var list: ListView
    private lateinit var pathView: TextView
    private lateinit var pasteBtn: Button
    private val adapter = FileAdapter()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFEEF1F4.toInt()) }
        col.addView(TextView(this).apply {
            text = "Adnan Manager"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF0E7C86.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFFFFFFFF.toInt()) }
        fun btn(t: String, a: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = t; isAllCaps = false; setOnClickListener { a() }
        }.also { bar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        btn("⬆ Up") { goUp() }
        btn("🏠 Home") { cwd = root; refresh() }
        btn("📁 New") { prompt("New folder", "") { n -> if (File(cwd, n).mkdirs()) refresh() else toast("Could not create folder") } }
        pasteBtn = btn("📋 Paste") { paste() }
        col.addView(bar)
        pathView = TextView(this).apply { textSize = 12f; setTextColor(0xFF667585.toInt()); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        col.addView(pathView)
        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, i, _ -> val f = files[i]; if (f.isDirectory) { cwd = f; refresh() } else openFile(f) }
            setOnItemLongClickListener { _, _, i, _ -> menu(files[i]); true }
        }
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) refresh() else askAccess()
    }

    private fun hasAccess() =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askAccess() {
        if (asking) return
        asking = true
        AlertDialog.Builder(this).setTitle("Allow storage access")
            .setMessage("Adnan Manager needs access to all files on your phone to show and manage them.")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ ->
                asking = false
                if (Build.VERSION.SDK_INT >= 30)
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                else requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }.show()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (hasAccess()) refresh()
    }

    private fun refresh() {
        files = cwd.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        adapter.notifyDataSetChanged()
        pathView.text = cwd.absolutePath
        pasteBtn.visibility = if (clip != null) View.VISIBLE else View.GONE
    }

    private fun goUp() {
        val p = cwd.parentFile
        if (cwd != root && p != null) { cwd = p; refresh() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (cwd != root && cwd.parentFile != null) goUp() else super.onBackPressed()
    }

    private fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1048576 -> "%.1f KB".format(b / 1024.0)
        b < 1073741824 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    private fun info(f: File): String {
        val d = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.lastModified()))
        return if (f.isDirectory) "${f.list()?.size ?: 0} items · $d" else "${fmt(f.length())} · $d"
    }

    inner class FileAdapter : BaseAdapter() {
        override fun getCount() = files.size
        override fun getItem(i: Int) = files[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, v: View?, p: ViewGroup?): View {
            val f = files[i]
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            row.addView(TextView(this@MainActivity).apply {
                text = (if (f.isDirectory) "📁  " else "📄  ") + f.name
                textSize = 16f; setTextColor(0xFF14202B.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            row.addView(TextView(this@MainActivity).apply {
                text = info(f); textSize = 12f; setTextColor(0xFF667585.toInt())
            })
            return row
        }
    }

    private fun openFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fp", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Open with"))
        } catch (e: Exception) { toast("No app found to open this file") }
    }

    private fun menu(f: File) {
        val ops = mutableListOf("Rename", "Copy", "Move", "Compress to ZIP")
        if (f.extension.equals("zip", true)) ops.add("Extract here")
        ops.addAll(listOf("Details", "Delete"))
        AlertDialog.Builder(this).setTitle(f.name).setItems(ops.toTypedArray()) { _, i ->
            when (ops[i]) {
                "Rename" -> prompt("Rename", f.name) { n -> if (f.renameTo(File(f.parentFile, n))) refresh() else toast("Rename failed") }
                "Copy" -> { clip = f; move = false; refresh(); toast("Open a folder and tap Paste") }
                "Move" -> { clip = f; move = true; refresh(); toast("Open a folder and tap Paste") }
                "Compress to ZIP" -> bg("Compressing…") { zip(f, unique(f.parentFile!!, f.nameWithoutExtension + ".zip")) }
                "Extract here" -> bg("Extracting…") { unzip(f, unique(f.parentFile!!, f.nameWithoutExtension)) }
                "Details" -> details(f)
                "Delete" -> AlertDialog.Builder(this).setTitle("Delete ${f.name}?").setMessage("This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ -> bg("Deleting…") { f.deleteRecursively() } }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun details(f: File) {
        Thread {
            val size = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Details").setMessage(
                    "Name: ${f.name}\nPath: ${f.absolutePath}\nSize: ${fmt(size)}\nModified: ${Date(f.lastModified())}"
                ).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun paste() {
        val src = clip ?: return
        if (src.isDirectory && (cwd.absolutePath == src.absolutePath || cwd.absolutePath.startsWith(src.absolutePath + "/"))) {
            toast("Cannot paste a folder into itself"); return
        }
        val dest = unique(cwd, src.name)
        val mv = move
        clip = null
        bg(if (mv) "Moving…" else "Copying…") {
            if (mv) { if (!src.renameTo(dest)) { src.copyRecursively(dest); src.deleteRecursively() } }
            else src.copyRecursively(dest)
        }
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 1
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        return f
    }

    private fun bg(msg: String, work: () -> Unit) {
        toast(msg)
        Thread {
            try { work(); runOnUiThread { toast("Done"); refresh() } }
            catch (e: Exception) { runOnUiThread { toast("Failed: ${e.message}"); refresh() } }
        }.start()
    }

    private fun zip(src: File, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            val base = src.parentFile!!
            src.walkTopDown().filter { it != out }.forEach { f ->
                val name = f.relativeTo(base).path.replace(File.separatorChar, '/') + if (f.isDirectory) "/" else ""
                z.putNextEntry(ZipEntry(name))
                if (f.isFile) f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    private fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        val canon = dest.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zi ->
            var e = zi.nextEntry
            while (e != null) {
                val t = File(dest, e.name)
                if (!t.canonicalPath.startsWith(canon + File.separator) && t.canonicalPath != canon)
                    throw SecurityException("Unsafe ZIP entry")
                if (e.isDirectory) t.mkdirs() else { t.parentFile?.mkdirs(); t.outputStream().use { zi.copyTo(it) } }
                zi.closeEntry()
                e = zi.nextEntry
            }
        }
    }

    private fun prompt(title: String, init: String, ok: (String) -> Unit) {
        val et = EditText(this).apply { setText(init); setSingleLine(); selectAll() }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ ->
                val v = et.text.toString().trim().replace("/", "")
                if (v.isNotEmpty()) ok(v)
            }.setNegativeButton("Cancel", null).show()
    }
}
