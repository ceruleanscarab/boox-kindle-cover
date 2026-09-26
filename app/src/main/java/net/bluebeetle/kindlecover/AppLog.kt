package net.bluebeetle.kindlecover

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny file-backed log shown on the app's main screen. */
object AppLog {
    private const val TAG = "KindleCover"
    private const val MAX_LINES = 200
    private val fmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)

    @Volatile
    var onChange: (() -> Unit)? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "log.txt")

    @Synchronized
    fun i(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        try {
            val f = file(ctx)
            val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
            lines.add("${fmt.format(Date())}  $msg")
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "log write failed", e)
        }
        onChange?.invoke()
    }

    @Synchronized
    fun recent(ctx: Context, count: Int = 60): String {
        val f = file(ctx)
        if (!f.exists()) return "(nothing yet)"
        return f.readLines().takeLast(count).reversed().joinToString("\n")
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
        onChange?.invoke()
    }
}
