package io.itchstore.app

import org.json.JSONObject

data class Game(
    val id: String,
    val title: String,
    val url: String,
    val cover: String?,
    val author: String,
    val blurb: String,
    val genre: String,
    val price: String?,
    val dlKey: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("url", url).put("cover", cover ?: "")
        .put("author", author).put("blurb", blurb).put("genre", genre)
        .put("price", price ?: "").put("dlKey", dlKey ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Game(
            o.optString("id"), o.optString("title"), o.optString("url"),
            o.optString("cover").ifBlank { null }, o.optString("author"),
            o.optString("blurb"), o.optString("genre"),
            o.optString("price").ifBlank { null }, o.optString("dlKey").ifBlank { null }
        )
    }
}

data class Detail(
    val title: String,
    val cover: String?,
    val description: String,
    val shots: List<String>,
    val info: List<Pair<String, String>>,
    val price: String?,
    val gameId: String?
)

data class Me(val name: String, val url: String, val avatar: String?)

/** slug is the itch.io URL segment: tag-horror, genre-rpg, platform-android, free, price-sale... */
data class Filter(val slug: String, val label: String)

data class Upload(
    val id: Long,
    val name: String,
    val filename: String,
    val size: Long,
    val platforms: List<String>
) {
    val isApk get() = filename.lowercase().endsWith(".apk")
}
