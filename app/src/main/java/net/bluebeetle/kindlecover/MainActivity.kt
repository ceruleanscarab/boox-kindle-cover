package net.bluebeetle.kindlecover

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val PICK_IMAGE = 42
    }

    private lateinit var prefs: Prefs
    private lateinit var storageStatus: TextView
    private lateinit var storageButton: Button
    private lateinit var a11yStatus: TextView
    private lateinit var a11yButton: Button
    private lateinit var targetSpinner: Spinner
    private lateinit var currentBook: TextView
    private lateinit var preview: ImageView
    private lateinit var logView: TextView
    private var targetFiles: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }

        // --- Setup ---
        root.addView(heading("Setup"))
        storageStatus = body("")
        root.addView(storageStatus)
        storageButton = button("Grant all-files access") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        root.addView(storageButton)

        a11yStatus = body("")
        root.addView(a11yStatus)
        a11yButton = button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        root.addView(a11yButton)

        // --- Target file ---
        root.addView(heading("Screensaver image to replace"))
        root.addView(body("Pick the image Boox is currently using from Screensaver/cloud. Newest first."))
        targetSpinner = Spinner(this)
        root.addView(targetSpinner)
        root.addView(button("Refresh file list") { loadTargets() })

        // --- Current book ---
        root.addView(heading("Current book"))
        currentBook = body("")
        root.addView(currentBook)
        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        root.addView(preview)

        // --- Manual ---
        root.addView(heading("Set a cover manually"))
        val titleIn = input("Title")
        val authorIn = input("Author (optional)")
        root.addView(titleIn)
        root.addView(authorIn)
        root.addView(button("Find cover and set screensaver") {
            val t = titleIn.text.toString().trim()
            if (t.isEmpty()) toast("Enter a title first")
            else {
                CoverWorker.apply(this, BookInfo(t, authorIn.text.toString().trim().ifEmpty { null }), "Manual")
                toast("Looking up \"$t\"…")
            }
        })
        root.addView(button("Use an image file instead…") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                },
                PICK_IMAGE
            )
        })
        root.addView(button("Restore original screensaver image") {
            toast(if (CoverWorker.restore(this)) "Original restored" else "No backup for this file yet")
            refresh()
        })

        // --- Options ---
        root.addView(heading("Options"))
        root.addView(check("Crop cover to fill the whole screen", prefs.fillScreen) { prefs.fillScreen = it })
        root.addView(check("Debug: record Kindle screens to Download/kindlecover-dump.txt", prefs.debugDump) {
            prefs.debugDump = it
        })
        root.addView(body("Title view-id regex (leave blank to use the built-in guess):"))
        val regexIn = input("e.g. reader_title").apply { setText(prefs.titleIdRegex) }
        root.addView(regexIn)
        root.addView(button("Save regex") {
            val r = regexIn.text.toString().trim()
            if (r.isNotEmpty() && runCatching { Regex(r) }.isFailure) toast("That isn't a valid regex")
            else { prefs.titleIdRegex = r; prefs.lastTitle = null; toast("Saved") }
        })

        root.addView(body("Google Books API key (optional, fixes \"HTTP 429\" errors):"))
        val keyIn = input("API key").apply { setText(prefs.googleApiKey) }
        root.addView(keyIn)
        root.addView(button("Save API key") {
            prefs.googleApiKey = keyIn.text.toString().trim()
            toast("Saved")
        })

        // --- Log ---
        root.addView(heading("Activity log (newest first)"))
        root.addView(button("Clear log") { AppLog.clear(this) })
        logView = body("").apply { setTextIsSelectable(true); textSize = 13f }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
        loadTargets()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let {
                CoverWorker.applyImage(this, it)
                toast("Setting screensaver…")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.onChange = { runOnUiThread { refresh() } }
        loadTargets()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        AppLog.onChange = null
    }

    private fun loadTargets() {
        val canRead = Environment.isExternalStorageManager()
        targetFiles = if (canRead) {
            SCREENSAVER_DIR.listFiles { f ->
                f.isFile && !f.name.startsWith(".") &&
                    f.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else emptyList()

        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.US)
        val labels = if (targetFiles.isEmpty()) {
            listOf(if (canRead) "(no images in Screensaver/cloud)" else "(grant all-files access first)")
        } else targetFiles.map { "${it.name}  ·  ${fmt.format(Date(it.lastModified()))}" }

        targetSpinner.onItemSelectedListener = null
        targetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = targetFiles.indexOfFirst { it.name == prefs.targetName }
        if (idx >= 0) targetSpinner.setSelection(idx, false)
        targetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val f = targetFiles.getOrNull(pos) ?: return
                if (f.name != prefs.targetName) {
                    prefs.targetName = f.name
                    AppLog.i(this@MainActivity, "Target set to ${f.name}")
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun refresh() {
        val storageOk = Environment.isExternalStorageManager()
        storageStatus.text = if (storageOk) "✓ All-files access granted" else "✗ Needs all-files access to write the screensaver"
        storageButton.visibility = if (storageOk) View.GONE else View.VISIBLE

        val a11yOk = isWatcherEnabled()
        a11yStatus.text = if (a11yOk) "✓ Kindle watcher is on" else "✗ Turn on \"Kindle Cover\" under Accessibility"
        a11yButton.visibility = if (a11yOk) View.GONE else View.VISIBLE

        currentBook.text = prefs.lastTitle?.let { t -> t + (prefs.lastAuthor?.let { "\nby $it" } ?: "") }
            ?: "Nothing detected yet. Open a book in Kindle and tap the middle of the page once."

        val pf = CoverWorker.previewFile(this)
        if (pf.exists()) {
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            preview.setImageBitmap(BitmapFactory.decodeFile(pf.absolutePath, o))
        }

        logView.text = AppLog.recent(this)
    }

    private fun isWatcherEnabled(): Boolean {
        val me = ComponentName(this, KindleWatcherService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    // --- tiny view helpers ---
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 20f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
    }

    private fun check(t: String, initial: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = t
        isChecked = initial
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
