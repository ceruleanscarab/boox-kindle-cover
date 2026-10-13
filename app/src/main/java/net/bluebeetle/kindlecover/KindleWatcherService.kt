package net.bluebeetle.kindlecover

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watches the Kindle app. When it sees a book title it hasn't handled yet, it hands the title
 * to CoverWorker, which fetches the cover and overwrites the Boox screensaver image.
 */
class KindleWatcherService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanQueued = false
    private var lastDumpHash = 0
    private var widgetWatcher: KindleWidgetWatcher? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.i(this, "Watcher connected")
        // This service is always running, so it also hosts Kindle's "Last read book" widget.
        widgetWatcher = KindleWidgetWatcher(this) { info, smallCover ->
            if (isNew(info)) CoverWorker.apply(this, info, source = "Kindle widget", fallback = smallCover)
        }.also { it.start() }
    }

    override fun onDestroy() {
        widgetWatcher?.stop()
        widgetWatcher = null
        super.onDestroy()
    }

    /** A different book, or the same book now that we know its author. */
    private fun isNew(info: BookInfo): Boolean {
        val prefs = Prefs(this)
        if (!info.title.equals(prefs.lastTitle, ignoreCase = true)) return true
        return info.author != null && prefs.lastAuthor == null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != KINDLE_PACKAGE) return
        // Kindle fires lots of content-changed events; batch them into one scan.
        if (scanQueued) return
        scanQueued = true
        handler.postDelayed({
            scanQueued = false
            scan()
        }, 1200)
    }

    private fun scan() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != KINDLE_PACKAGE) return

        val prefs = Prefs(this)
        val nodes = TitleExtractor.collect(root)
        if (prefs.debugDump) dump(nodes)

        // With Kindle's widget linked, it reports every book reliably; reading titles off the
        // screen only risks picking up home-screen headings like "Continue series you've started".
        if (KindleWidgetWatcher.isLinked(this)) return

        var info = TitleExtractor.extract(nodes, prefs.titleIdRegex) ?: return
        // The reader never shows the author; borrow it from the Kindle widget when it's the same book.
        if (info.author == null && info.title.equals(prefs.widgetTitle, ignoreCase = true)) {
            info = info.copy(author = prefs.widgetAuthor)
        }
        if (!isNew(info)) return

        CoverWorker.apply(this, info, source = "Kindle")
    }

    private fun dump(nodes: List<NodeText>) {
        val body = nodes.joinToString("\n") { n ->
            "  ".repeat(n.depth.coerceAtMost(20)) +
                "[${n.id.substringAfter(":id/", n.id)}] ${n.cls.substringAfterLast('.')}" +
                (if (n.text.isNotEmpty()) "  text=\"${n.text}\"" else "") +
                (if (n.desc.isNotEmpty()) "  desc=\"${n.desc}\"" else "")
        }
        val hash = body.hashCode()
        if (hash == lastDumpHash) return
        lastDumpHash = hash
        try {
            val f = DEBUG_DUMP_FILE
            f.parentFile?.mkdirs()
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            f.appendText("\n===== Kindle screen at $stamp =====\n$body\n")
            if (f.length() > 400_000) {
                f.writeText(f.readText().takeLast(200_000))
            }
        } catch (e: Exception) {
            AppLog.i(this, "Debug dump failed: ${e.message}")
        }
    }

    override fun onInterrupt() {}
}
