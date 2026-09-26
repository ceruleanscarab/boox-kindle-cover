package net.bluebeetle.kindlecover

import android.view.accessibility.AccessibilityNodeInfo

data class BookInfo(val title: String, val author: String?)

data class NodeText(
    val id: String,
    val cls: String,
    val text: String,
    val desc: String,
    val depth: Int,
)

/**
 * Pulls the current book's title (and author, when visible) out of the Kindle app's screen.
 *
 * The Kindle app's view ids aren't documented, so this starts with a best guess and can be
 * tuned from the app's settings with a regex once the debug dump shows the real ids.
 */
object TitleExtractor {

    private const val MAX_NODES = 600

    private val DEFAULT_TITLE_ID = Regex(
        "(?i)(book_?title|reader_?title|title_?(text|view|label)|toolbar_?title|" +
            "action_?bar_?title|chrome_?title|header_?title|:id/title$)"
    )
    private val AUTHOR_ID = Regex("(?i)author")

    /** Screen and menu labels that are never a book title. */
    private val BLOCKLIST = setOf(
        "kindle", "library", "home", "settings", "more", "search", "notebook", "notes",
        "collections", "all", "downloaded", "books", "go to", "about this book", "aa",
        "store", "kindle store", "reading", "discover", "back", "menu", "filter", "sort",
        "table of contents", "contents", "bookmarks", "highlights", "x-ray", "share",
    )

    fun collect(root: AccessibilityNodeInfo): List<NodeText> {
        val out = ArrayList<NodeText>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || out.size >= MAX_NODES) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
                out.add(NodeText(id, node.className?.toString().orEmpty(), text, desc, depth))
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    fun extract(nodes: List<NodeText>, customIdRegex: String): BookInfo? {
        val titleRegex = customIdRegex.takeIf { it.isNotBlank() }
            ?.let { runCatching { Regex(it) }.getOrNull() }
            ?: DEFAULT_TITLE_ID

        val title = nodes.asSequence()
            .filter { titleRegex.containsMatchIn(it.id) }
            .map { it.text.ifEmpty { it.desc } }
            .map { clean(it) }
            .firstOrNull { looksLikeTitle(it) }
            ?: return null

        val author = nodes.asSequence()
            .filter { AUTHOR_ID.containsMatchIn(it.id) }
            .map { clean(it.text.ifEmpty { it.desc }).removePrefix("by ").removePrefix("By ").trim() }
            .firstOrNull { it.length in 2..120 && !it.equals(title, ignoreCase = true) }

        return BookInfo(title, author)
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun looksLikeTitle(s: String): Boolean {
        if (s.length !in 2..200) return false
        if (s.lowercase() in BLOCKLIST) return false
        // Reader chrome like "Location 1234", "Page 12 of 300", "45%", "3 mins left in chapter"
        if (Regex("(?i)^(location|page|loc)\\b").containsMatchIn(s)) return false
        if (Regex("^\\d+\\s*%$").matches(s)) return false
        if (Regex("(?i)\\b(min|mins|hr|hrs)\\b.*\\bleft\\b").containsMatchIn(s)) return false
        return s.any { it.isLetter() }
    }
}
