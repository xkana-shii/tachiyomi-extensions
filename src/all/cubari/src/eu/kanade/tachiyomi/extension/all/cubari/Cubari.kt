package eu.kanade.tachiyomi.extension.all.cubari

import android.os.Build
import android.text.InputType
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import rx.Observable

class Cubari(override val lang: String) :
    HttpSource(),
    ConfigurableSource {

    override val name = "Cubari"

    override val baseUrl = "https://cubari.moe"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val token = preferences.getString(PREF_GITHUB_TOKEN, "") ?: ""
            val headers = request.headers.newBuilder()
                .removeAll("Accept-Encoding")
                .apply {
                    if (token.isNotBlank() && isGithubHost(request.url.host)) {
                        set("Authorization", "token $token")
                    }
                }
                .build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .build()

    private val cubariHeaders = super.headersBuilder()
        .set(
            "User-Agent",
            "(Android ${Build.VERSION.RELEASE}; " +
                "${Build.MANUFACTURER} ${Build.MODEL}) " +
                "Tachiyomi/${AppInfo.getVersionName()} ${Build.ID} " +
                "Keiyoushi",
        ).build()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", cubariHeaders)

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = client.newBuilder()
        .addInterceptor(RemoteStorageUtils.HomeInterceptor())
        .build()
        .newCall(latestUpdatesRequest(page))
        .asObservableSuccess()
        .map { response -> latestUpdatesParse(response) }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = response.parseAs<JsonArray>()
        return parseMangaList(result, SortType.UNPINNED)
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/", cubariHeaders)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = client.newBuilder()
        .addInterceptor(RemoteStorageUtils.HomeInterceptor())
        .build()
        .newCall(popularMangaRequest(page))
        .asObservableSuccess()
        .map { response -> popularMangaParse(response) }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<JsonArray>()
        return parseMangaList(result, SortType.PINNED)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = client.newCall(mangaDetailsRequest(manga))
        .asObservableSuccess()
        .map { response -> mangaDetailsParse(response, manga) }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = chapterListRequest(manga)

    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    private fun mangaDetailsParse(response: Response, manga: SManga): SManga {
        val result = response.parseAs<JsonObject>()
        return parseManga(result, manga)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = client.newCall(chapterListRequest(manga))
        .asObservable()
        .map { response -> chapterListParse(response, manga) }

    // Gets the chapter list based on the series being viewed
    override fun chapterListRequest(manga: SManga): Request {
        val urlComponents = manga.url.split("/")
        val source = urlComponents[2]
        val slug = urlComponents[3]

        return GET("$baseUrl/read/api/$source/series/$slug/", cubariHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // Called after the request
    private fun chapterListParse(response: Response, manga: SManga): List<SChapter> = parseChapterList(response, manga)

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = when {
        chapter.url.contains("/chapter/") -> {
            client.newCall(pageListRequest(chapter))
                .asObservableSuccess()
                .map { response ->
                    directPageListParse(response)
                }
        }

        else -> {
            client.newCall(pageListRequest(chapter))
                .asObservableSuccess()
                .map { response ->
                    seriesJsonPageListParse(response, chapter)
                }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = when {
        chapter.url.contains("/chapter/") -> {
            GET("$baseUrl${chapter.url}", cubariHeaders)
        }

        else -> {
            val url = chapter.url.split("/")
            val source = url[2]
            val slug = url[3]

            GET("$baseUrl/read/api/$source/series/$slug/", cubariHeaders)
        }
    }

    private fun directPageListParse(response: Response): List<Page> {
        val pages = response.parseAs<JsonArray>()

        return pages.mapIndexed { i, jsonEl ->
            val page = if (jsonEl is JsonObject) {
                jsonEl.jsonObject["src"]!!.jsonPrimitive.content
            } else {
                jsonEl.jsonPrimitive.content
            }

            val finalUrl = proxyUrlIfGithubHost(page)
            Page(i, "", finalUrl)
        }
    }

    private fun seriesJsonPageListParse(response: Response, chapter: SChapter): List<Page> {
        val jsonObj = response.parseAs<JsonObject>()
        val groups = jsonObj["groups"]!!.jsonObject
        val groupMap = groups.entries.associateBy({ it.value.jsonPrimitive.content.ifEmpty { "default" } }, { it.key })
        val chapterScanlator = chapter.scanlator ?: "default" // workaround for "" as group causing NullPointerException (#13772)

        // prevent NullPointerException when chapters.key is 084 and chapter.chapter_number is 84
        val chapters = jsonObj["chapters"]!!.jsonObject.mapKeys {
            it.key.replace(Regex("^0+(?!$)"), "")
        }

        val pages = if (chapters[chapter.chapter_number.toString()] != null) {
            chapters[chapter.chapter_number.toString()]!!
                .jsonObject["groups"]!!
                .jsonObject[groupMap[chapterScanlator]]!!
                .jsonArray
        } else {
            chapters[chapter.chapter_number.toInt().toString()]!!
                .jsonObject["groups"]!!
                .jsonObject[groupMap[chapterScanlator]]!!
                .jsonArray
        }

        return pages.mapIndexed { i, jsonEl ->
            val page = if (jsonEl is JsonObject) {
                jsonEl.jsonObject["src"]!!.jsonPrimitive.content
            } else {
                jsonEl.jsonPrimitive.content
            }

            val finalUrl = proxyUrlIfGithubHost(page)
            Page(i, "", finalUrl)
        }
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    private fun isGithubHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "github.com" || h.endsWith(".github.com") || h.endsWith(".githubusercontent.com")
    }

    private fun proxyUrlIfGithubHost(url: String): String {
        if (url.isBlank()) return url
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return url
        if (!isGithubHost(host)) return url
        val encoded = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "cubari://proxy/$encoded"
    }

    override suspend fun getImage(page: Page): Response = withContext(Dispatchers.IO) {
        val imageUrl = page.imageUrl ?: throw IllegalArgumentException("imageUrl is null")

        if (!imageUrl.startsWith("cubari://proxy/")) {
            return@withContext network.client.newCall(Request.Builder().url(imageUrl).get().build()).execute()
        }

        val originalUrl = runCatching {
            String(Base64.decode(imageUrl.removePrefix("cubari://proxy/"), Base64.URL_SAFE or Base64.NO_WRAP))
        }.getOrElse { throw IllegalArgumentException("Invalid proxy URL") }

        runCatching { client.newCall(Request.Builder().url(originalUrl).get().build()).execute() }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?: network.client.newCall(Request.Builder().url(originalUrl).get().build()).execute()
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = when {
        // handle direct links or old cubari:source/id format
        query.startsWith("https://") || query.startsWith("cubari:") -> {
            val (source, slug) = deepLinkHandler(query)
            // Only tag for recently read on search
            client.newBuilder()
                .addInterceptor(RemoteStorageUtils.TagInterceptor())
                .build()
                .newCall(GET("$baseUrl/read/api/$source/series/$slug/", cubariHeaders))
                .asObservableSuccess()
                .map { response ->
                    val result = response.parseAs<JsonObject>()
                    val manga = SManga.create().apply {
                        url = "/read/$source/$slug"
                    }
                    val mangaList = listOf(parseManga(result, manga))

                    MangasPage(mangaList, false)
                }
        }

        query.startsWith("github:") -> {
            Observable.fromCallable {
                val mangasPage = githubRepoMangaList(query.removePrefix("github:").trim())
                require(mangasPage.mangas.isNotEmpty()) { "No .json files found in the specified GitHub repository." }
                mangasPage
            }
        }

        else -> {
            client.newBuilder()
                .addInterceptor(RemoteStorageUtils.HomeInterceptor())
                .build()
                .newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response, query)
                }
                .map { mangasPage ->
                    require(mangasPage.mangas.isNotEmpty()) { SEARCH_FALLBACK_MSG }
                    mangasPage
                }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/", cubariHeaders)

    private fun languageCodeAlias(raw: String): String? = mapOf(
        "en" to "en", "english" to "en",
        "ja" to "ja", "jp" to "ja", "japanese" to "ja",
        "ko" to "ko", "kr" to "ko", "korean" to "ko",
        "zh" to "zh", "chinese" to "zh", "zh-cn" to "zh", "zh-hans" to "zh", "zh-hant" to "zh",
    )[raw.lowercase()]

    private fun extractSupportedLang(description: String?): String? {
        if (description == null) return null
        val lower = description.lowercase()

        val lineRegex = Regex("""^\s*lang(?:uage)?[\s:]+([a-z0-9\-_]+)\s*$""", RegexOption.MULTILINE)
        lineRegex.find(lower)?.groupValues?.get(1)?.let { languageCodeAlias(it)?.let { c -> return c } }

        Regex("""lang:\s*([a-z0-9\-_]+)""").find(lower)?.groupValues?.get(1)
            ?.let { languageCodeAlias(it)?.let { c -> return c } }

        return listOf("english" to "en", "japanese" to "ja", "korean" to "ko", "chinese" to "zh")
            .firstOrNull { (name, _) -> lower.contains(name) }?.second
            ?: if (lower.contains("zh-cn") || lower.contains("zh-hans") || lower.contains("zh-hant")) "zh" else null
    }

    private fun githubRepoMangaList(repo: String): MangasPage {
        val branch = "master"
        val token = preferences.getString(PREF_GITHUB_TOKEN, "") ?: ""

        fun Request.Builder.withToken() = apply { if (token.isNotEmpty()) header("Authorization", "token $token") }

        val treeResp = client.newCall(
            Request.Builder()
                .url("https://api.github.com/repos/$repo/git/trees/$branch?recursive=1")
                .header("Accept", "application/vnd.github+json")
                .withToken().build(),
        ).execute()

        val jsonFiles: List<String> = if (treeResp.isSuccessful) {
            val tree = JSONObject(treeResp.body?.string() ?: "").getJSONArray("tree")
            (0 until tree.length())
                .map { tree.getJSONObject(it) }
                .filter { it.getString("type") == "blob" && it.getString("path").endsWith(".json") }
                .map { it.getString("path") }
        } else {
            emptyList()
        }

        val requiredKeys = listOf("title", "description", "artist", "author")
        val enforceLang = preferences.getBoolean(PREF_ENFORCE_LANGUAGE, true)
        val sourceLang = lang.lowercase()
        val showAllLangs = sourceLang == "all" || sourceLang == "other"
        val sourceCode = if (!showAllLangs && enforceLang) languageCodeAlias(sourceLang) else null

        val validMangaList = jsonFiles.mapNotNull { path ->
            val rawUrl = "https://raw.githubusercontent.com/$repo/$branch/$path"
            runCatching {
                val resp = client.newCall(Request.Builder().url(rawUrl).withToken().build()).execute()
                if (!resp.isSuccessful) return@mapNotNull null
                val json = JSONObject(resp.body?.string() ?: return@mapNotNull null)
                if (!requiredKeys.all { json.has(it) }) return@mapNotNull null

                val descField = json.optString("description", "")
                if (sourceCode != null && extractSupportedLang(descField) != sourceCode) return@mapNotNull null

                val displayTitle = json.optString("title").takeIf { it.isNotBlank() }
                    ?: path.substringAfterLast("/").removeSuffix(".json")
                val gistBase64 = Base64.encodeToString(
                    "raw/$repo/$branch/$path".toByteArray(),
                    Base64.NO_PADDING or Base64.NO_WRAP,
                )

                SManga.create().apply {
                    title = displayTitle
                    url = "/read/gist/$gistBase64"
                    description = "GitHub: $repo\nPath: $path" + if (descField.isNotBlank()) "\n\n$descField" else ""
                    thumbnail_url = json.optString("cover", "")
                }
            }.getOrNull()
        }

        return MangasPage(validMangaList, false)
    }

    private fun deepLinkHandler(query: String): Pair<String, String> = if (query.startsWith("cubari:")) { // legacy cubari:source/slug format
        val queryFragments = query.substringAfter("cubari:").split("/", limit = 2)
        queryFragments[0] to queryFragments[1]
    } else { // direct url searching
        val url = query.toHttpUrl()
        val host = url.host
        val pathSegments = url.pathSegments

        if (
            host.endsWith("imgur.com") &&
            pathSegments.size >= 2 &&
            pathSegments[0] in listOf("a", "gallery")
        ) {
            "imgur" to pathSegments[1]
        } else if (
            host.endsWith("reddit.com") &&
            pathSegments.size >= 2 &&
            pathSegments[0] == "gallery"
        ) {
            "reddit" to pathSegments[1]
        } else if (
            host == "imgchest.com" &&
            pathSegments.size >= 2 &&
            pathSegments[0] == "p"
        ) {
            "imgchest" to pathSegments[1]
        } else if (
            host.endsWith("catbox.moe") &&
            pathSegments.size >= 2 &&
            pathSegments[0] == "c"
        ) {
            "catbox" to pathSegments[1]
        } else if (
            host.endsWith("cubari.moe") &&
            pathSegments.size >= 3
        ) {
            pathSegments[1] to pathSegments[2]
        } else if (
            host.endsWith(".githubusercontent.com")
        ) {
            val src = host.substringBefore(".")
            val path = url.encodedPath

            "gist" to Base64.encodeToString("$src$path".toByteArray(), Base64.NO_PADDING)
        } else {
            throw Exception(SEARCH_FALLBACK_MSG)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    private fun searchMangaParse(response: Response, query: String): MangasPage {
        val result = response.parseAs<JsonArray>()

        val filterList = result.asSequence()
            .map { it as JsonObject }
            .filter { it["title"].toString().contains(query.trim(), true) }
            .toList()

        return parseMangaList(JsonArray(filterList), SortType.ALL)
    }

    // ------------- Helpers and whatnot ---------------

    private val volumeNotSpecifiedTerms = setOf("Uncategorized", "null", "")

    private fun parseChapterList(response: Response, manga: SManga): List<SChapter> {
        val jsonObj = response.parseAs<JsonObject>()
        val groups = jsonObj["groups"]!!.jsonObject
        val chapters = jsonObj["chapters"]!!.jsonObject

        val chapterList = chapters.entries.flatMap { chapterEntry ->
            val chapterNum = chapterEntry.key
            val chapterObj = chapterEntry.value.jsonObject
            val chapterGroups = chapterObj["groups"]!!.jsonObject
            val volume = chapterObj["volume"]!!.jsonPrimitive.content.let {
                if (volumeNotSpecifiedTerms.contains(it)) null else it
            }
            val title = chapterObj["title"]!!.jsonPrimitive.content

            chapterGroups.entries.map { groupEntry ->
                val groupNum = groupEntry.key
                val releaseDate = chapterObj["release_date"]?.jsonObject?.get(groupNum)

                SChapter.create().apply {
                    scanlator = groups[groupNum]!!.jsonPrimitive.content
                    chapter_number = chapterNum.toFloatOrNull() ?: -1f

                    date_upload = if (releaseDate != null) {
                        releaseDate.jsonPrimitive.double.toLong() * 1000
                    } else {
                        0L
                    }

                    name = buildString {
                        if (!volume.isNullOrBlank()) append("Vol.$volume ")
                        append("Ch.$chapterNum")
                        if (title.isNotBlank()) append(" - $title")
                    }

                    url = if (chapterGroups[groupNum] is JsonArray) {
                        "${manga.url}/$chapterNum/$groupNum"
                    } else {
                        chapterGroups[groupNum]!!.jsonPrimitive.content
                    }
                }
            }
        }

        return chapterList.sortedByDescending { it.chapter_number }
    }

    private fun parseMangaList(payload: JsonArray, sortType: SortType): MangasPage {
        val mangaList = payload.mapNotNull { jsonEl ->
            val jsonObj = jsonEl.jsonObject
            val pinned = jsonObj["pinned"]!!.jsonPrimitive.boolean

            if (sortType == SortType.PINNED && pinned) {
                parseManga(jsonObj)
            } else if (sortType == SortType.UNPINNED && !pinned) {
                parseManga(jsonObj)
            } else if (sortType == SortType.ALL) {
                parseManga(jsonObj)
            } else {
                null
            }
        }

        return MangasPage(mangaList, false)
    }

    private fun parseManga(jsonObj: JsonObject, mangaReference: SManga? = null): SManga = SManga.create().apply {
        title = jsonObj["title"]!!.jsonPrimitive.content
        artist = jsonObj["artist"]?.jsonPrimitive?.content ?: ARTIST_FALLBACK
        author = jsonObj["author"]?.jsonPrimitive?.content ?: AUTHOR_FALLBACK

        val descriptionFull = jsonObj["description"]?.jsonPrimitive?.content
        description = descriptionFull?.substringBefore("Status: ")?.trim() ?: DESCRIPTION_FALLBACK
        genre = descriptionFull?.let {
            if (it.contains("Tags: ")) {
                it.substringAfter("Tags: ")
            } else {
                ""
            }
        } ?: ""
        status = when {
            descriptionFull?.contains("Status: Completed", ignoreCase = true) == true -> SManga.COMPLETED
            descriptionFull?.contains("Status: Ongoing", ignoreCase = true) == true -> SManga.ONGOING
            descriptionFull?.contains("Status: Licensed", ignoreCase = true) == true -> SManga.LICENSED
            descriptionFull?.contains("Status: Publishing Finished", ignoreCase = true) == true -> SManga.PUBLISHING_FINISHED
            descriptionFull?.contains("Status: Cancelled", ignoreCase = true) == true -> SManga.CANCELLED
            descriptionFull?.contains("Status: On Hiatus", ignoreCase = true) == true -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        url = mangaReference?.url ?: jsonObj["url"]!!.jsonPrimitive.content
        thumbnail_url = jsonObj["coverUrl"]?.jsonPrimitive?.content
            ?: jsonObj["cover"]?.jsonPrimitive?.content ?: ""
    }

    // ----------------- Things we aren't supporting -----------------

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_GITHUB_TOKEN
            title = "GitHub Personal Access Token"
            summary = "Use to increase GitHub API rate limit for repository searches and authenticated fetches."
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }.let(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ENFORCE_LANGUAGE
            title = "Enforce Language"
            summary = "When enabled, only manga matching the selected language will be shown for repository searches. Disable to show all."
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    companion object {
        const val AUTHOR_FALLBACK = "Unknown"
        const val ARTIST_FALLBACK = "Unknown"
        const val DESCRIPTION_FALLBACK = "No description."
        const val SEARCH_FALLBACK_MSG = "Please enter a valid Cubari URL"
        private const val PREF_GITHUB_TOKEN = "cubari_github_token"
        private const val PREF_ENFORCE_LANGUAGE = "cubari_enforce_language"

        enum class SortType {
            PINNED,
            UNPINNED,
            ALL,
        }
    }
}
