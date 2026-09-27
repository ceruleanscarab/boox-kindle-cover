package net.bluebeetle.kindlecover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Finds a cover image for a title: Apple Books first, then Google Books, then Open Library. */
object CoverFetcher {

    private const val UA = "BooxKindleCover/0.1 (personal e-reader screensaver app)"

    private var log: (String) -> Unit = {}
    private var googleBlocked = false

    @Synchronized
    fun fetch(info: BookInfo, log: (String) -> Unit): Bitmap? {
        this.log = log
        googleBlocked = false
        for (title in titleVariants(info.title)) {
            for (author in listOf(info.author, null).distinct()) {
                appleBooks(title, author)?.let { return it }
                if (!googleBlocked) googleBooks(title, author)?.let { return it }
                openLibrary(title, author)?.let { return it }
            }
        }
        return null
    }

    /** "Dune: Deluxe Edition (Dune Book 1)" -> also try "Dune: Deluxe Edition" and "Dune". */
    private fun titleVariants(title: String): List<String> {
        val noParens = title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").trim()
        val noSubtitle = noParens.substringBefore(":").trim()
        return listOf(title, noParens, noSubtitle).filter { it.length >= 2 }.distinct()
    }

    /** Normalises a title for loose comparison: lowercase letters and digits only. */
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun appleBooks(title: String, author: String?): Bitmap? {
        val term = if (author.isNullOrBlank()) title else "$title $author"
        val url = "https://itunes.apple.com/search?term=${enc(term)}&media=ebook&entity=ebook&limit=10"
        val json = getText(url) ?: return null
        val results = JSONObject(json).optJSONArray("results")
        if (results == null || results.length() == 0) { log("Apple Books: no results for \"$title\""); return null }
        val want = norm(title)
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val name = norm(r.optString("trackName"))
            if (name.isEmpty() || !(name.startsWith(want) || want.startsWith(name))) continue
            val art = r.optString("artworkUrl100").takeIf { it.isNotEmpty() } ?: continue
            // Apple's image server renders any size you ask for in the file name.
            val big = art.replace(Regex("/\\d+x\\d+(bb)?\\.(jpg|png)$"), "/2400x2400bb.jpg")
            val bmp = getBitmap(big) ?: getBitmap(art)
            if (bmp != null && bmp.width >= 120) {
                log("Cover from Apple Books: ${r.optString("trackName")} (${bmp.width}×${bmp.height})")
                return bmp
            }
        }
        log("Apple Books: no matching title for \"$title\"")
        return null
    }

    private fun googleBooks(title: String, author: String?): Bitmap? {
        val q = buildString {
            append("intitle:\"").append(title).append('"')
            if (!author.isNullOrBlank()) append(" inauthor:\"").append(author).append('"')
        }
        val url = "https://www.googleapis.com/books/v1/volumes?q=${enc(q)}&maxResults=5&printType=books"
        val json = getText(url)
        if (json == null) { googleBlocked = true; return null }
        val items = JSONObject(json).optJSONArray("items")
        if (items == null) { log("Google Books: no results for \"$title\""); return null }
        for (i in 0 until items.length()) {
            val links = items.getJSONObject(i).optJSONObject("volumeInfo")
                ?.optJSONObject("imageLinks") ?: continue
            val raw = listOf("extraLarge", "large", "medium", "thumbnail", "smallThumbnail")
                .firstNotNullOfOrNull { links.optString(it).takeIf { s -> s.isNotEmpty() } }
                ?: continue
            // Ask Google's image server for a much larger rendition than the thumbnail.
            val big = raw.replace("http://", "https://")
                .replace("&edge=curl", "")
                .let { if ("fife=" in it) it else "$it&fife=w1600" }
            val bmp = getBitmap(big) ?: getBitmap(raw.replace("http://", "https://"))
            if (bmp != null && bmp.width >= 120) {
                log("Cover from Google Books (${bmp.width}×${bmp.height})")
                return bmp
            }
        }
        return null
    }

    private fun openLibrary(title: String, author: String?): Bitmap? {
        var url = "https://openlibrary.org/search.json?title=${enc(title)}&limit=5&fields=cover_i"
        if (!author.isNullOrBlank()) url += "&author=${enc(author)}"
        val json = getText(url) ?: return null
        val docs = JSONObject(json).optJSONArray("docs")
        if (docs == null || docs.length() == 0) { log("Open Library: no results for \"$title\""); return null }
        for (i in 0 until docs.length()) {
            val id = docs.getJSONObject(i).optLong("cover_i", 0)
            if (id <= 0) continue
            val bmp = getBitmap("https://covers.openlibrary.org/b/id/$id-L.jpg?default=false")
            if (bmp != null && bmp.width >= 120) {
                log("Cover from Open Library (${bmp.width}×${bmp.height})")
                return bmp
            }
        }
        log("Open Library: no cover image for \"$title\"")
        return null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(url: String): HttpURLConnection? {
        val host = url.substringAfter("://").substringBefore("/")
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            val code = c.responseCode
            if (code in 200..299) c
            else {
                log("$host → HTTP $code")
                c.disconnect()
                null
            }
        } catch (e: Exception) {
            log("$host → ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun getText(url: String): String? =
        open(url)?.let { c -> try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() } }

    private fun getBitmap(url: String): Bitmap? =
        open(url)?.let { c ->
            try {
                c.inputStream.use { BitmapFactory.decodeStream(it) }
                    ?: null.also { log("Couldn't decode image from ${url.substringAfter("://").substringBefore("/")}") }
            } catch (e: Exception) {
                log("Image download failed: ${e.message}")
                null
            }
            finally { c.disconnect() }
        }
}
