package io.itchstore.app

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

val FEEDS = listOf(
    "Popular" to "games",
    "Top rated" to "games/top-rated",
    "Top sellers" to "games/top-sellers",
    "Newest" to "games/newest",
    "Free" to "games/free",
    "On sale" to "games/on-sale"
)

val TAGS = listOf(
    "horror", "puzzle", "platformer", "rpg", "pixel-art", "visual-novel",
    "roguelike", "shooter", "adventure", "simulation", "cozy", "multiplayer"
)

val PRICE_FILTERS = listOf(
    Filter("free", "Free"), Filter("price-sale", "On sale"), Filter("price-demo", "With demo")
)
val PLATFORM_FILTERS = listOf(
    Filter("platform-android", "Android"), Filter("platform-windows", "Windows"),
    Filter("platform-osx", "macOS"), Filter("platform-linux", "Linux"), Filter("platform-web", "Web")
)
val GENRE_FILTERS = listOf(
    "action" to "Action", "adventure" to "Adventure", "card-game" to "Card game",
    "educational" to "Educational", "fighting" to "Fighting", "interactive-fiction" to "Interactive fiction",
    "platformer" to "Platformer", "puzzle" to "Puzzle", "racing" to "Racing", "rpg" to "Role playing",
    "shooter" to "Shooter", "simulation" to "Simulation", "sports" to "Sports",
    "strategy" to "Strategy", "survival" to "Survival", "visual-novel" to "Visual novel", "other" to "Other"
).map { (s, l) -> Filter("genre-$s", l) }
val ADULT_FILTERS = listOf(Filter("tag-nsfw", "NSFW"))

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("itch", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("key", "") ?: ""
        set(v) { sp.edit().putString("key", v).apply() }

    var adultOk: Boolean
        get() = sp.getBoolean("adult_ok", false)
        set(v) { sp.edit().putBoolean("adult_ok", v).apply() }

    fun loadSaved(): List<Game> = try {
        val a = JSONArray(sp.getString("saved", "[]"))
        (0 until a.length()).map { Game.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    fun saveSaved(list: List<Game>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        sp.edit().putString("saved", a.toString()).apply()
    }
}

class Pager(
    private val scope: CoroutineScope,
    private var fetch: suspend (Int) -> List<Game>
) {
    val items = mutableStateListOf<Game>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var end by mutableStateOf(false)
    private var page = 1
    private var job: Job? = null
    private var gen = 0
    private val seen = HashSet<String>()

    fun reset(f: (suspend (Int) -> List<Game>)? = null) {
        gen++
        job?.cancel()
        if (f != null) fetch = f
        items.clear()
        seen.clear()
        page = 1
        end = false
        error = null
        loading = false
        loadMore()
    }

    fun cancel() {
        gen++
        job?.cancel()
        loading = false
    }

    fun loadMore() {
        if (loading || end) return
        val mine = gen
        val p = page
        loading = true
        error = null
        job = scope.launch {
            try {
                val r = fetch(p)
                if (mine != gen) return@launch
                val fresh = ArrayList<Game>(r.size)
                for (g in r) if (seen.add(g.url)) fresh.add(g)
                if (fresh.isEmpty()) end = true else {
                    items.addAll(fresh)
                    page = p + 1
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == gen) error = e.message ?: "Network error"
            } finally {
                if (mine == gen) loading = false
            }
        }
    }
}

class DlState {
    var progress by mutableFloatStateOf(-1f)
    var status by mutableStateOf("idle")   // idle | downloading | installing | done | ready | error
    var stage by mutableStateOf("")
    var appKey by mutableStateOf<String?>(null)
    var file by mutableStateOf<File?>(null)
    var error by mutableStateOf<String?>(null)
}

class AppVM(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    var feed by mutableStateOf(FEEDS[0].second)
    var tag by mutableStateOf<String?>(null)

    var draft by mutableStateOf("")
    var query by mutableStateOf("")
    val searchFilters = mutableStateListOf<Filter>()
    var allTags by mutableStateOf<List<Filter>>(emptyList())
    var adultOk by mutableStateOf(store.adultOk)

    var apiKey by mutableStateOf(store.apiKey)
    var me by mutableStateOf<Me?>(null)
    var accountError by mutableStateOf<String?>(null)
    var accountLoading by mutableStateOf(false)
    var selected by mutableStateOf<Game?>(null)
    val saved = mutableStateListOf<Game>().apply { addAll(store.loadSaved()) }
    val downloads = mutableStateMapOf<Long, DlState>()

    val discover = Pager(viewModelScope) { p -> Repo.feed(feed, tag, p) }
    val search = Pager(viewModelScope) { _ -> emptyList() }
    val library = Pager(viewModelScope) { p -> Repo.owned(apiKey, p) }
    private var searchJob: Job? = null

    init {
        Repo.init(app)
        allTags = defaultTags()
        discover.reset()
        if (apiKey.isNotBlank()) refreshAccount()
        viewModelScope.launch {
            allTags = try {
                Repo.tags().takeIf { it.size >= 20 } ?: allTags
            } catch (_: Exception) {
                allTags
            }
        }
    }

    private fun defaultTags() = TAGS.map { Filter("tag-$it", it.replace('-', ' ').replaceFirstChar { c -> c.uppercase() }) }

    // ----- search -----

    /** Called on every keystroke: results update once typing pauses. */
    fun updateDraft(v: String) {
        draft = v
        query = v.trim()
        search.cancel()
        scheduleSearch(300)
    }

    private fun scheduleSearch(delayMs: Long) {
        searchJob?.cancel()
        val q = draft.trim()
        val f = searchFilters.map { it.slug }
        if (q.isBlank() && f.isEmpty()) {
            search.reset { _ -> emptyList() }
            return
        }
        searchJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            startSearch(q, f)
        }
    }

    fun runSearch() {
        searchJob?.cancel()
        val q = draft.trim()
        query = q
        startSearch(q, searchFilters.map { it.slug })
    }

    private fun startSearch(q: String, f: List<String>) {
        val key = apiKey
        when {
            q.isBlank() && f.isEmpty() -> search.reset { _ -> emptyList() }
            f.isEmpty() -> search.reset { p -> Repo.search(q, p, key) }
            else -> {
                val scan = Scan()
                search.reset { _ -> Repo.filtered(f, q, scan) }
            }
        }
    }

    fun toggleFilter(f: Filter) {
        val i = searchFilters.indexOfFirst { it.slug == f.slug }
        if (i >= 0) searchFilters.removeAt(i) else searchFilters.add(f)
        runSearch()
    }

    fun clearSearch() {
        draft = ""
        searchFilters.clear()
        runSearch()
    }

    fun confirmAdult() { adultOk = true; store.adultOk = true }

    // ----- saved -----

    fun toggleSave(g: Game) {
        val i = saved.indexOfFirst { it.url == g.url }
        if (i >= 0) saved.removeAt(i) else saved.add(0, g)
        store.saveSaved(saved)
    }

    // ----- account -----

    fun connect(key: String) {
        apiKey = key.trim()
        store.apiKey = apiKey
        refreshAccount()
    }

    fun logout() {
        apiKey = ""
        store.apiKey = ""
        me = null
        accountError = null
        library.items.clear()
    }

    fun refreshAccount() {
        accountError = null
        if (apiKey.isBlank()) { me = null; library.items.clear(); return }
        accountLoading = true
        viewModelScope.launch {
            try {
                me = Repo.me(apiKey)
                library.reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                me = null
                accountError = "Couldn't sign in. Check your API key."
            }
            accountLoading = false
        }
    }

    // ----- downloads -----

    private val downloadJobs = HashMap<Long, Job>()

    fun pauseDownload(uploadId: Long) {
        downloadJobs[uploadId]?.cancel()
        downloadJobs.remove(uploadId)
        val st = downloads[uploadId] ?: return
        if (st.status == "downloading" || st.status == "installing") {
            st.status = "paused"
            st.stage = "Paused"
        }
    }

    fun resumeDownload(up: Upload, dlKey: String?, autoInstall: Boolean = true, game: Game? = null, linux: Boolean = false, windows: Boolean = false) {
        download(up, dlKey, autoInstall, game, linux, windows)
    }

    fun cancelDownload(uploadId: Long) {
        downloadJobs[uploadId]?.cancel()
        downloadJobs.remove(uploadId)
        downloads.remove(uploadId)
    }

    fun download(up: Upload, dlKey: String?, autoInstall: Boolean = true, game: Game? = null, linux: Boolean = false, windows: Boolean = false) {
        val st = downloads.getOrPut(up.id) { DlState() }
        if (st.status == "downloading" || st.status == "installing") return
        st.status = "downloading"
        st.progress = -1f
        st.error = null
        val app = getApplication<Application>()
        val key = apiKey

        val job = viewModelScope.launch {
            try {
                if ((linux || windows) && game != null) {
                    st.status = "downloading"
                    st.stage = "Downloading & Extracting..."
                    var last = 0L
                    val problem = withContext(Dispatchers.IO) {
                        LinuxInstall.installStream(app, game, up, key, dlKey, windows) { stage, done, total ->
                            val now = System.currentTimeMillis()
                            if (now - last > 150) {
                                last = now
                                st.stage = stage
                                if (total > 0 && done >= 0) st.progress = done.toFloat() / total
                            }
                        }
                    }
                    if (problem != null) {
                        st.status = "error"
                        st.error = problem
                    } else {
                        st.appKey = LinuxInstall.get(app, game.url)?.key
                        st.progress = 1f
                        st.status = "ready"
                    }
                } else {
                    val base = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
                    val dir = File(base, up.id.toString()).apply { mkdirs() }
                    val name = up.filename.ifBlank { "itch-${up.id}" }.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    val file = File(dir, name)
                    var last = 0L
                    withContext(Dispatchers.IO) {
                        Repo.download(key, up, dlKey, file) { done, total ->
                            val now = System.currentTimeMillis()
                            if (now - last > 200) {
                                last = now
                                st.progress = if (total > 0) done.toFloat() / total else -1f
                            }
                        }
                    }
                    st.file = file
                    st.progress = 1f
                    st.status = "done"
                    if (up.isApk && autoInstall) installApk(app, file)
                }
            } catch (e: CancellationException) {
                st.status = "paused"
                st.stage = "Paused"
                throw e
            } catch (e: Exception) {
                st.status = "error"
                st.error = e.message ?: "Download failed"
            } finally {
                downloadJobs.remove(up.id)
            }
        }
        downloadJobs[up.id] = job
    }
}
