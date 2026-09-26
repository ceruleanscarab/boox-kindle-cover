package net.bluebeetle.kindlecover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import java.util.concurrent.Executors

/** Fetches a cover, renders it at screen size, and overwrites the Boox screensaver file. */
object CoverWorker {

    private const val DEFAULT_W = 1860
    private const val DEFAULT_H = 2480
    private val exec = Executors.newSingleThreadExecutor()

    fun previewFile(ctx: Context) = File(ctx.applicationContext.filesDir, "preview.png")
    private fun backupDir(ctx: Context) = File(ctx.applicationContext.filesDir, "backups")

    fun apply(context: Context, info: BookInfo, source: String) {
        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        // Remember the title right away so repeated screens don't queue duplicate lookups.
        prefs.lastTitle = info.title
        prefs.lastAuthor = info.author
        exec.execute { run(ctx, prefs, info, source) }
    }

    private fun run(ctx: Context, prefs: Prefs, info: BookInfo, source: String) {
        val log = { m: String -> AppLog.i(ctx, m) }
        log("[$source] \"${info.title}\"" + (info.author?.let { " by $it" } ?: ""))

        val target = prefs.targetFile
        if (target == null) {
            log("No screensaver file chosen yet. Pick one in the app.")
            return
        }
        if (!target.exists()) {
            log("Screensaver file not found: ${target.name}")
            return
        }

        val cover = try {
            CoverFetcher.fetch(info, log)
        } catch (e: Exception) {
            log("Lookup error: ${e.message}")
            null
        }
        if (cover == null) {
            log("No cover found. Try the manual box with a simpler title.")
            return
        }

        try {
            val backup = ensureBackup(ctx, target)
            val (w, h) = imageSize(backup) ?: (DEFAULT_W to DEFAULT_H)
            val out = render(cover, w, h, prefs.fillScreen)
            writeImage(out, target)
            previewFile(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            log("Screensaver updated (${w}×${h}) → ${target.name}")
        } catch (e: Exception) {
            log("Couldn't write screensaver: ${e.message}")
        }
    }

    /** Keeps a copy of the original image the first time we overwrite a file. */
    private fun ensureBackup(ctx: Context, target: File): File {
        val dir = backupDir(ctx).apply { mkdirs() }
        val backup = File(dir, target.name)
        if (!backup.exists()) target.copyTo(backup)
        return backup
    }

    fun restore(context: Context): Boolean {
        val ctx = context.applicationContext
        val target = Prefs(ctx).targetFile ?: return false
        val backup = File(backupDir(ctx), target.name)
        if (!backup.exists()) return false
        backup.copyTo(target, overwrite = true)
        Prefs(ctx).lastTitle = null
        AppLog.i(ctx, "Restored original ${target.name}")
        return true
    }

    private fun imageSize(f: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    private fun render(src: Bitmap, w: Int, h: Int, fill: Boolean): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(edgeColor(src))
        val sx = w.toFloat() / src.width
        val sy = h.toFloat() / src.height
        val s = if (fill) maxOf(sx, sy) else minOf(sx, sy)
        val dw = src.width * s
        val dh = src.height * s
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        canvas.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Average colour around the cover's border, used to fill the letterbox bars. */
    private fun edgeColor(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(src, 16, 16, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (x in 0 until 16) for (y in 0 until 16) {
            if (x != 0 && y != 0 && x != 15 && y != 15) continue
            val c = small.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun writeImage(bmp: Bitmap, target: File) {
        val (format, quality) = when (target.extension.lowercase()) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG to 95
            "webp" -> Bitmap.CompressFormat.WEBP_LOSSY to 95
            else -> Bitmap.CompressFormat.PNG to 100
        }
        val tmp = File(target.parentFile, ".kindlecover.tmp")
        tmp.outputStream().use { bmp.compress(format, quality, it) }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
