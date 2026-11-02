package io.itchstore.app

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class Scan {
    @Volatile var next = 1
    @Volatile var scanned = 0
    @Volatile var end = false
}

object Repo {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 ItchStore/3.0"
    private const val BASE = "https://itch.io/"
    private const val API = "https://api.itch.io/"
    private const val NOT_AVAILABLE = "Not available. If this game is paid, buy or claim it on itch.io first."

    private const val FEED_TTL = 10 * 60 * 1000L
    private const val SEARCH_TTL = 30 * 60 * 1000L
    private const val DETAIL_TTL = 6 * 60 * 60 * 1000L
    private const val TAGS_TTL = 24 * 60 * 60 * 1000L

    /** itch.io serves a 30 KB RSS feed per listing page; the HTML page is 100 KB+ and needs Jsoup. */
    private const val BATCH = 5
    private const val MAX_SCAN = 30

    private val GENRE_NAMES = setOf(
        "action", "adventure", "card-game", "educational", "fighting", "interactive-fiction",
        "platformer", "puzzle", "racing", "rpg", "shooter", "simulation", "sports",
        "strategy", "survival", "visual-novel", "other"
    )

    private val FEED_FIELDS = setOf(
        "guid", "title", "plainTitle", "imageurl", "price", "link", "description"
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Game and driver downloads run for minutes on a slow link, so the short call deadline that
    // suits browse requests would abort them; this client only bounds each read.
    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var cacheRoot: File? = null

    private val mem = object : LinkedHashMap<String, Pair<Long, String>>(64, 0.75f, true) {}

    @Synchronized
    fun init(ctx: Context) {
        if (cacheRoot == null) cacheRoot = File(ctx.cacheDir, "itch-store").apply { mkdirs() }
    }

    private fun req(url: String, key: String? = null): Request {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (!key.isNullOrBlank()) b.header("Authorization", "Bearer $key")
        return b.build()
    }

    private fun get(url: String, key: String? = null): String {
        http.newCall(req(url, key)).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    private suspend fun <T> io(f: () -> T): T = withContext(Dispatchers.IO) { f() }

    // ---------- cache ----------

    private fun memGet(url: String, ttl: Long): String? = synchronized(mem) {
        val e = mem[url]
        if (e != null && System.currentTimeMillis() - e.first <= ttl) e.second else null
    }

    private fun memPut(url: String, body: String) = synchronized(mem) {
        mem[url] = System.currentTimeMillis() to body
        while (mem.size > 96) {
            val it = mem.keys.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }
    }

    private fun cacheFile(url: String): File? {
        val dir = cacheRoot ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val name = buildString { digest.forEach { append("%02x".format(it.toInt() and 0xff)) } }
        return File(dir, name)
    }

    /** Memory, then disk, then network; a stale copy is served when the network fails. */
    private fun getCached(url: String, ttl: Long, key: String? = null): String {
        memGet(url, ttl)?.let { return it }
        val file = cacheFile(url)
        if (file != null && file.isFile && System.currentTimeMillis() - file.lastModified() <= ttl) {
            runCatching { file.readText() }.getOrNull()?.let {
                memPut(url, it)
                return it
            }
        }
        return try {
            val body = get(url, key)
            memPut(url, body)
            if (file != null) runCatching { file.writeText(body) }
            body
        } catch (e: Exception) {
            val stale = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull()
            if (stale.isNullOrEmpty()) throw e
            memPut(url, stale)
            stale
        }
    }

    // ---------- urls ----------

    private fun slugOf(tag: String): String {
        val t = tag.trim().lowercase()
        return when {
            t.isEmpty() -> ""
            t.startsWith("tag-") || t.startsWith("genre-") || t.startsWith("platform-") -> t
            t == "free" || t.startsWith("price-") -> t
            t in GENRE_NAMES -> "genre-$t"
            else -> "tag-$t"
        }
    }

    private fun feedUrl(path: String, tag: String?, page: Int): String {
        val sb = StringBuilder(BASE).append(path.trim('/'))
        if (!tag.isNullOrBlank()) sb.append('/').append(slugOf(tag))
        sb.append(".xml")
        if (page > 1) sb.append("?page=").append(page)
        return sb.toString()
    }

    private fun filterPath(slugs: List<String>): String {
        fun canon(s: String) = when (s) {
            "price-sale" -> "on-sale"
            "price-demo" -> "has-demo"
            else -> s
        }
        val rank = { s: String ->
            when {
                s == "free" -> 0
                s.startsWith("genre-") -> 1
                s == "on-sale" || s == "has-demo" -> 2
                s.startsWith("platform-") -> 3
                else -> 4
            }
        }
        return "games/" + slugs.map(::canon).distinct().sortedBy(rank).joinToString("/")
    }

    private fun filterUrl(path: String, page: Int): String {
        val sb = StringBuilder(BASE).append(path).append(".xml")
        if (page > 1) sb.append("?page=").append(page)
        return sb.toString()
    }

    private fun searchUrl(q: String, page: Int): String =
        BASE + "search?q=" + URLEncoder.encode(q, "UTF-8") + "&page=$page"

    // ---------- browse / search ----------

    suspend fun feed(path: String, tag: String?, page: Int): List<Game> = io {
        try {
            parseFeed(getCached(feedUrl(path, tag, page), FEED_TTL))
        } catch (_: Exception) {
            val html = BASE + path + (tag?.let { "/" + slugOf(it) } ?: "") + "?page=$page"
            cells(Jsoup.parse(getCached(html, FEED_TTL), BASE))
        }
    }

    suspend fun search(q: String, page: Int, key: String? = null): List<Game> = io {
        val api = if (!key.isNullOrBlank()) {
            runCatching { searchApi(q, page, key) }.getOrNull()
        } else null
        if (api != null && (api.isNotEmpty() || page > 1)) api
        else cells(Jsoup.parse(getCached(searchUrl(q, page), SEARCH_TTL), BASE))
    }

    /**
     * itch.io can't combine a title search with tag filters, so: browse the
     * tag-filtered feeds (slugs are AND-ed) and match the text locally.
     * Pages are fetched in parallel, and an empty page ends the scan.
     */
    suspend fun filtered(slugs: List<String>, text: String, scan: Scan, want: Int = 24): List<Game> =
        withContext(Dispatchers.IO) {
            val words = text.lowercase().split(' ').filter { it.isNotBlank() }
            val path = filterPath(slugs)

            if (words.isEmpty()) {
                if (scan.end) return@withContext emptyList()
                val page = scan.next
                val list = runCatching {
                    parseFeed(getCached(filterUrl(path, page), FEED_TTL))
                }.getOrDefault(emptyList())
                scan.next = page + 1
                if (list.isEmpty()) scan.end = true
                return@withContext list
            }

            val out = ArrayList<Game>(want)
            while (!scan.end && out.size < want) {
                val start = scan.next
                val pages = (start until start + BATCH).toList()
                val batch = coroutineScope {
                    pages.map { p ->
                        async {
                            p to runCatching {
                                parseFeed(getCached(filterUrl(path, p), FEED_TTL))
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                }
                var empty = false
                for ((p, list) in batch) {
                    scan.next = p + 1
                    if (list.isEmpty()) {
                        empty = true
                        continue
                    }
                    scan.scanned++
                    for (g in list) {
                        val hay = (g.title + " " + g.author + " " + g.blurb).lowercase()
                        if (words.all { hay.contains(it) }) out += g
                    }
                }
                if (empty || scan.scanned >= MAX_SCAN) scan.end = true
            }
            out
        }

    suspend fun tags(): List<Filter> = io {
        val doc = Jsoup.parse(getCached(BASE + "tags", TAGS_TTL), BASE)
        val seen = LinkedHashMap<String, Filter>()
        doc.select("a[href*=/games/]").forEach { a ->
            val seg = a.attr("href").substringAfter("/games/", "").substringBefore('?')
                .split('/').firstOrNull { it.startsWith("tag-") } ?: return@forEach
            val raw = seg.removePrefix("tag-")
            val label = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
            seen.putIfAbsent(seg, Filter(seg, label.replace('-', ' ').replaceFirstChar { it.uppercase() }))
        }
        seen.values.sortedBy { it.label.lowercase() }
    }

    // ---------- rss ----------

    private fun parseFeed(xml: String): List<Game> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        val out = ArrayList<Game>(40)
        var cur: HashMap<String, String>? = null
        var field: String? = null
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = parser.name
                    if (n == "item") {
                        cur = HashMap(12)
                        field = null
                    } else {
                        field = n
                    }
                }
                XmlPullParser.TEXT -> {
                    val c = cur
                    val f = field
                    if (c != null && f != null && f in FEED_FIELDS && !parser.isWhitespace) {
                        c[f] = (c[f] ?: "") + parser.text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item") {
                        cur?.toGame()?.let { out += it }
                        cur = null
                    }
                    field = null
                }
            }
            ev = parser.next()
        }
        return out
    }

    private fun Map<String, String>.toGame(): Game? {
        val url = (this["link"] ?: this["guid"]).orEmpty().trim()
        val title = (this["plainTitle"] ?: this["title"]).orEmpty().trim()
        if (url.isBlank() || title.isBlank()) return null
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        val price = this["price"]?.trim()?.takeUnless {
            it.isBlank() || it == "$0.00" || it == "0.00" || it.equals("Free", true)
        }
        return Game(
            id = url,
            title = title,
            url = url,
            cover = this["imageurl"]?.trim()?.ifBlank { null },
            author = if (host.endsWith(".itch.io")) host.removeSuffix(".itch.io").substringAfterLast('.') else "",
            blurb = scrub(this["description"].orEmpty()),
            genre = bracketGenre(this["title"].orEmpty()),
            price = price
        )
    }

    private fun scrub(html: String): String {
        if (html.isBlank()) return ""
        return html.replace(Regex("(?is)<img[^>]*>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun bracketGenre(title: String): String {
        val skip = setOf("free", "windows", "macos", "linux", "android", "web", "ios", "mobile")
        return Regex("\\[([^\\]]+)]").findAll(title)
            .map { it.groupValues[1].trim() }
            .firstOrNull { it.lowercase() !in skip }
            .orEmpty()
    }

    private fun cells(doc: Document): List<Game> =
        doc.select("div.game_cell").mapNotNull { c ->
            val a = c.selectFirst("a.title") ?: return@mapNotNull null
            val url = a.absUrl("href")
            if (url.isBlank()) return@mapNotNull null
            val img = c.selectFirst(".game_thumb img")
            val cover = img?.attr("data-lazy_src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
            Game(
                id = c.attr("data-game_id").ifBlank { url },
                title = a.text(),
                url = url,
                cover = cover,
                author = c.selectFirst(".game_author a")?.text() ?: "",
                blurb = c.selectFirst(".game_text")?.text() ?: "",
                genre = c.selectFirst(".game_genre")?.text() ?: "",
                price = c.selectFirst(".price_value")?.text()
            )
        }

    // ---------- api search ----------

    private fun gameFromJson(o: JSONObject, dlKey: String? = null): Game = Game(
        id = o.optString("id"),
        title = o.optString("title"),
        url = o.optString("url"),
        cover = o.optString("cover_url").ifBlank { null },
        author = o.optJSONObject("user")?.optString("display_name").orEmpty(),
        blurb = o.optString("short_text"),
        genre = o.optString("classification"),
        price = null,
        dlKey = dlKey
    )

    private fun searchApi(q: String, page: Int, key: String): List<Game> {
        val url = API + "search/games?query=" + URLEncoder.encode(q, "UTF-8") + "&page=$page"
        val body = get(url, key)
        val root = runCatching { JSONObject(body) }.getOrNull()
        val arr = root?.optJSONArray("games")
            ?: root?.optJSONArray("results")
            ?: runCatching { JSONArray(body) }.getOrNull()
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { gameFromJson(it) }
        }.filter { it.url.isNotBlank() && it.title.isNotBlank() }
    }

    // ---------- game page ----------

    suspend fun detail(g: Game): Detail = io {
        val html = getCached(g.url, DETAIL_TTL)
        val doc = Jsoup.parse(html, g.url)
        fun meta(p: String) = doc.selectFirst("meta[property=$p]")?.attr("content")?.takeIf { it.isNotBlank() }

        val desc = doc.selectFirst(".formatted_description")?.let { el ->
            el.select("br").append("\\n")
            el.select("p,li,h1,h2,h3").prepend("\\n")
            el.text().replace("\\n", "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        } ?: meta("og:description") ?: g.blurb

        val shots = doc.select(".screenshot_list a").map { it.absUrl("href") }.filter { it.isNotBlank() }
        val info = doc.select(".game_info_panel_widget tr").mapNotNull { tr ->
            val td = tr.select("td")
            if (td.size >= 2) td[0].text() to td[1].text() else null
        }.filter { it.first.isNotBlank() && it.second.isNotBlank() }.take(8)
        val price = doc.selectFirst(".buy_row .dollars, .buy_row .price_tag, .buy_message")?.text()
        val gid = doc.selectFirst("meta[name=itch:path]")?.attr("content")
            ?.substringAfter("games/", "")?.takeIf { s -> s.isNotEmpty() && s.all { it.isDigit() } }

        Detail(meta("og:title") ?: g.title, meta("og:image") ?: g.cover, desc, shots, info, price, gid)
    }

    // ---------- account ----------

    suspend fun me(key: String): Me = io {
        val j = JSONObject(get("${BASE}api/1/$key/me"))
        if (j.has("errors")) error("bad key")
        val u = j.getJSONObject("user")
        Me(
            u.optString("display_name").ifBlank { u.optString("username") },
            u.optString("url"),
            u.optString("cover_url").ifBlank { null }
        )
    }

    suspend fun owned(key: String, page: Int): List<Game> = io {
        val j = JSONObject(get("${BASE}api/1/$key/my-owned-keys?page=$page"))
        if (j.has("errors")) error("bad key")
        val arr = j.optJSONArray("owned_keys") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val e = arr.getJSONObject(i)
            val g = e.optJSONObject("game") ?: return@mapNotNull null
            gameFromJson(g, e.optString("id").ifBlank { null })
        }
    }

    // ---------- downloads ----------

    private fun parseUploads(j: JSONObject): List<Upload> {
        val arr = j.optJSONArray("uploads") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val u = arr.getJSONObject(i)
            val traits = mutableSetOf<String>()
            u.optJSONArray("traits")?.let { t -> for (k in 0 until t.length()) traits += t.optString(k) }
            fun has(p: String) = p in traits || u.optBoolean(p)
            val plats = buildList {
                if (has("p_windows")) add("Windows")
                if (has("p_osx")) add("macOS")
                if (has("p_linux")) add("Linux")
                if (has("p_android")) add("Android")
                if (u.optString("type") == "html") add("Web")
            }
            val file = u.optString("filename")
            Upload(
                u.optLong("id"),
                u.optString("display_name").ifBlank { file },
                file,
                u.optLong("size"),
                plats
            )
        }
    }

    suspend fun uploads(key: String, gameId: String, dlKey: String?): List<Upload> = io {
        val q = dlKey?.let { "?download_key_id=$it" } ?: ""
        try {
            parseUploads(JSONObject(get("${API}games/$gameId/uploads$q", key)))
        } catch (_: Exception) {
            try {
                parseUploads(JSONObject(get("${BASE}api/1/$key/game/$gameId/uploads$q")))
            } catch (_: Exception) {
                error(NOT_AVAILABLE)
            }
        }
    }

    private fun openDownload(key: String, up: Upload, dlKey: String?): Response {
        val q = dlKey?.let { "?download_key_id=$it" } ?: ""
        var r = http.newCall(req("${API}uploads/${up.id}/download$q", key)).execute()
        if (!r.isSuccessful) {
            r.close()
            r = http.newCall(req("${BASE}api/1/$key/upload/${up.id}/download$q")).execute()
        }
        if (!r.isSuccessful) {
            val c = r.code; r.close()
            error(if (c == 401 || c == 403 || c == 404) NOT_AVAILABLE else "HTTP $c")
        }
        if (r.header("Content-Type")?.contains("json") == true) {
            val u = JSONObject(r.body!!.string()).optString("url")
            r.close()
            if (u.isBlank()) error(NOT_AVAILABLE)
            r = http.newCall(req(u)).execute()
            if (!r.isSuccessful) { val c = r.code; r.close(); error("HTTP $c") }
        }
        return r
    }

    /** Blocking: call from Dispatchers.IO. */
    fun download(key: String, up: Upload, dlKey: String?, dest: File, onProgress: (Long, Long) -> Unit) {
        openDownload(key, up, dlKey).use { r ->
            val body = r.body ?: error("Empty response")
            val total = body.contentLength()
            val tmp = File(dest.path + ".part")
            body.byteStream().use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) error("Couldn't save file")
        }
    }

    /** Blocking: call from Dispatchers.IO. Used for driver packs and other direct downloads. */
    fun fetchTo(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        http.newCall(req(url)).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body ?: error("Empty response")
            val total = body.contentLength()
            val tmp = File(dest.path + ".part")
            body.byteStream().use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) error("Couldn't save file")
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return buildString { md.digest().forEach { append("%02x".format(it.toInt() and 0xff)) } }
    }
}
