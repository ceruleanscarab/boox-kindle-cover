package net.bluebeetle.kindlecover

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView

/**
 * Hosts the Kindle app's own "Last read book" home-screen widget, invisibly, and reads the
 * book's title, author and (small) cover out of it.
 *
 * Kindle refreshes that widget whenever a book is opened, so this sees book changes even when
 * the reader's title bar is hidden, and it gets the author, which the reader screen never shows.
 * The widget's cover is Kindle's small thumbnail, so it's only used when the online lookup fails.
 */
class KindleWidgetWatcher(context: Context, private val onBook: (BookInfo, Bitmap?) -> Unit) {

    companion object {
        /** Arbitrary id for our widget host; must stay the same across app versions. */
        const val HOST_ID = 0x4B435657

        private const val PROVIDER_CLASS = "com.amazon.kindle.appwidget.lastreadbook.LastReadBookWidgetReceiver"

        /** Text Kindle shows when it has no last-read book (English build). */
        private val PLACEHOLDER_TEXT = setOf("read something new", "find new books on kindle")

        /** The running watcher (owned by KindleWatcherService), so the setup screen can reload it. */
        @Volatile var instance: KindleWidgetWatcher? = null

        fun host(context: Context) = AppWidgetHost(context.applicationContext, HOST_ID)

        /** Kindle's widget provider, or null if Kindle has the widget switched off on this device. */
        fun findProvider(context: Context): ComponentName? {
            val mgr = AppWidgetManager.getInstance(context)
            val providers = mgr.getInstalledProvidersForPackage(KINDLE_PACKAGE, null)
            return providers.firstOrNull { it.provider.className == PROVIDER_CLASS }?.provider
                ?: providers.firstOrNull { it.provider.className.contains("LastReadBook") }?.provider
        }

        fun isLinked(context: Context): Boolean {
            val id = Prefs(context).widgetId
            return id != AppWidgetManager.INVALID_APPWIDGET_ID &&
                AppWidgetManager.getInstance(context).getAppWidgetInfo(id) != null
        }

        fun unlink(context: Context) {
            val prefs = Prefs(context)
            val id = prefs.widgetId
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                runCatching { host(context).deleteAppWidgetId(id) }
            }
            prefs.widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            instance?.reload()
        }
    }

    private val ctx = context.applicationContext
    private val mgr = AppWidgetManager.getInstance(ctx)
    private var host: AppWidgetHost? = null
    private var hostView: AppWidgetHostView? = null
    private var lastKey: String? = null

    fun start() {
        instance = this
        reload()
    }

    fun stop() {
        if (instance === this) instance = null
        teardown()
    }

    /** (Re)attaches to whatever widget id is saved in Prefs. */
    fun reload() {
        teardown()
        val prefs = Prefs(ctx)
        val id = prefs.widgetId
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val info = mgr.getAppWidgetInfo(id)
        if (info == null) {
            AppLog.i(ctx, "Kindle widget link was lost (Kindle updated or reinstalled?). Link it again in the app.")
            prefs.widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            return
        }

        val h = object : AppWidgetHost(ctx, HOST_ID) {
            override fun onCreateView(
                context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?
            ): AppWidgetHostView = ReadingView(context)
        }
        try {
            h.startListening()
            val view = h.createView(ctx, id, info)
            sizeFor(view, id)
            host = h
            hostView = view
            AppLog.i(ctx, "Listening to Kindle's \"Last read book\" widget")
        } catch (e: Exception) {
            AppLog.i(ctx, "Couldn't attach to Kindle's widget: ${e.message}")
            runCatching { h.stopListening() }
        }
    }

    private fun teardown() {
        runCatching { host?.stopListening() }
        host = null
        hostView = null
    }

    /**
     * Kindle's widget is built with Glance, which draws a layout per size. Give it a roomy
     * size so it renders the full title + author + cover layout rather than a compact one.
     */
    private fun sizeFor(view: AppWidgetHostView, id: Int) {
        val w = 320f
        val h = 140f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
        } else {
            mgr.updateAppWidgetOptions(id, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w.toInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w.toInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h.toInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h.toInt())
            })
        }
        layoutOffscreen(view)
        // Laying out at the new size can switch Glance to a different layout without a new update.
        runCatching { read(view) }
    }

    private fun layoutOffscreen(view: View) {
        val dm = ctx.resources.displayMetrics
        val wPx = (320 * dm.density).toInt()
        val hPx = (140 * dm.density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, wPx, hPx)
    }

    /** Called with every new rendering Kindle pushes to the widget. */
    private fun read(root: View) {
        val texts = ArrayList<String>()
        var cover: Bitmap? = null
        var coverArea = 0

        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            when (v) {
                is TextView -> v.text?.toString()?.replace(Regex("\\s+"), " ")?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
                is ImageView -> (v.drawable as? BitmapDrawable)?.bitmap?.let { b ->
                    val area = b.width * b.height
                    // The cover is the biggest bitmap; the Kindle logo is small and wide.
                    if (area > coverArea && b.height >= b.width) { cover = b; coverArea = area }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        if (Prefs(ctx).debugDump) {
            AppLog.i(ctx, "Kindle widget texts: ${texts.joinToString(" | ")}" +
                (cover?.let { " · image ${it.width}×${it.height}" } ?: ""))
        }

        val title = texts.getOrNull(0) ?: return
        if (title.lowercase() in PLACEHOLDER_TEXT) return
        val author = texts.getOrNull(1)
            ?.takeIf { it.lowercase() !in PLACEHOLDER_TEXT && !it.equals(title, ignoreCase = true) }
            ?.removePrefix("by ")?.removePrefix("By ")?.trim()

        val key = "$title|$author"
        if (key == lastKey) return
        lastKey = key

        val prefs = Prefs(ctx)
        prefs.widgetTitle = title
        prefs.widgetAuthor = author
        val copy = cover?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
        onBook(BookInfo(title, author), copy)
    }

    private inner class ReadingView(context: Context) : AppWidgetHostView(context) {
        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            layoutOffscreen(this)
            try {
                read(this)
            } catch (e: Exception) {
                AppLog.i(ctx, "Couldn't read Kindle's widget: ${e.message}")
            }
        }
    }
}
