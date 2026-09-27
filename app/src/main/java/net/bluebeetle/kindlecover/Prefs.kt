package net.bluebeetle.kindlecover

import android.content.Context
import android.os.Environment
import java.io.File

const val KINDLE_PACKAGE = "com.amazon.kindle"

val SCREENSAVER_DIR: File
    get() = File(Environment.getExternalStorageDirectory(), "Screensaver/cloud")

val DEBUG_DUMP_FILE: File
    get() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "kindlecover-dump.txt"
    )

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("kindlecover", Context.MODE_PRIVATE)

    /** File name inside SCREENSAVER_DIR that Boox currently uses as the screensaver. */
    var targetName: String?
        get() = sp.getString("target_name", null)
        set(v) = sp.edit().putString("target_name", v).apply()

    val targetFile: File?
        get() = targetName?.let { File(SCREENSAVER_DIR, it) }

    /** Optional regex matched against Kindle view ids to find the title (overrides the default guess). */
    var titleIdRegex: String
        get() = sp.getString("title_id_regex", "") ?: ""
        set(v) = sp.edit().putString("title_id_regex", v).apply()

    var debugDump: Boolean
        get() = sp.getBoolean("debug_dump", false)
        set(v) = sp.edit().putBoolean("debug_dump", v).apply()

    /** Crop the cover to fill the screen instead of fitting it with borders. */
    var fillScreen: Boolean
        get() = sp.getBoolean("fill_screen", false)
        set(v) = sp.edit().putBoolean("fill_screen", v).apply()

    /** Optional Google Books API key; without one Google often answers HTTP 429. */
    var googleApiKey: String
        get() = sp.getString("google_api_key", "") ?: ""
        set(v) = sp.edit().putString("google_api_key", v).apply()

    var lastTitle: String?
        get() = sp.getString("last_title", null)
        set(v) = sp.edit().putString("last_title", v).apply()

    var lastAuthor: String?
        get() = sp.getString("last_author", null)
        set(v) = sp.edit().putString("last_author", v).apply()
}
