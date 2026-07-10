package eu.kanade.tachiyomi.extension.all.cubari

import android.os.Build
import android.text.InputType
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
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

@Source
abstract class Cubari : HttpSource(), ConfigurableSource {

    // KNS
    private val preferences by getPreferencesLazy()
    // KNS

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            // KNS
            val token = preferences.getString(PREF_GITHUB_TOKEN, "").orEmpty()
            // KNS
            val headers = request.headers.newBuilder()
                .removeAll("Accept-Encoding")
                // KNS
                .apply {
                    if (token.isNotBlank() && isGithubHost(request.url.host)) {
                        set("Authorization", "token $token")
                    }
                }
                // KNS
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

            // KNS
            val finalUrl = proxyUrlIfGithubHost(page)
            Page(i, "", finalUrl)
            // KNS
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

            // KNS
            val finalUrl = proxyUrlIfGithubHost(page)
            Page(i, "", finalUrl)
            // KNS
        }
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    // KNS
    private fun isGithubHost(host: String): Boolean {
        val normalizedHost = host.lowercase()
        return normalizedHost == "github.com" ||
            normalizedHost.endsWith(".github.com") ||
            normalizedHost.endsWith(".githubusercontent.com")
    }

    private fun proxyUrlIfGithubHost(url: String): String {
        if (url.isBlank()) return url
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return url
        if (!isGithubHost(host)) return url
        val encodedUrl = Base64.encodeToString(
            url.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )
        return "$CUBARI_PROXY_PREFIX$encodedUrl"
    }
    // KNS

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

        // KNS
        query.startsWith("github:") -> {
            Observable.fromCallable {
                val mangasPage = githubRepoMangaList(query.removePrefix("github:").trim())
                require(mangasPage.mangas.isNotEmpty()) { "No .json files found in the specified GitHub repository." }
                mangasPage
            }
        }
        // KNS

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

    // KNS
    private fun languageCodeAlias(raw: String): String? = when (raw.lowercase()) {
        "en", "english" -> "en"
        "ja", "jp", "japanese" -> "ja"
        "ko", "kr", "korean" -> "ko"
        "zh", "chinese", "zh-cn", "zh-hans", "zh-hant" -> "zh"
        else -> null
    }

    private fun extractSupportedLang(description: String?): String? {
        if (description.isNullOrBlank()) return null
        val lowerDescription = description.lowercase()

        val linePattern = Regex(
            """^\s*lang(?:uage)?[\s:]+([a-z0-9\-_]+)\s*$""",
            RegexOption.MULTILINE,
        )
        linePattern.find(lowerDescription)
            ?.groupValues?.getOrNull(1)
            ?.let(::languageCodeAlias)
            ?.let { return it }

        Regex("""lang:\s*([a-z0-9\-_]+)""")
            .find(lowerDescription)
            ?.groupValues?.getOrNull(1)
            ?.let(::languageCodeAlias)
            ?.let { return it }

        return when {
            "english" in lowerDescription -> "en"
            "japanese" in lowerDescription -> "ja"
            "korean" in lowerDescription -> "ko"
            "chinese" in lowerDescription ||
                "zh-cn" in lowerDescription ||
                "zh-hans" in lowerDescription ||
                "zh-hant" in lowerDescription -> "zh"
            else -> null
        }
    }

    private fun githubRepoMangaList(repo: String): MangasPage {
        val token = preferences.getString(PREF_GITHUB_TOKEN, "").orEmpty()
        val branch = "master"

        fun Request.Builder.withGithubToken(): Request.Builder = apply {
            if (token.isNotBlank()) {
                header("Authorization", "token $token")
            }
        }

        val treeRequest = Request.Builder()
            .url("https://api.github.com/repos/$repo/git/trees/$branch?recursive=1")
            .header("Accept", "application/vnd.github+json")
            .withGithubToken()
            .build()

        val jsonPaths = client.newCall(treeRequest).execute().use { treeResponse ->
            if (!treeResponse.isSuccessful) return@use emptyList()

            val treeJson = JSONObject(treeResponse.body?.string().orEmpty())
            val treeArray = treeJson.optJSONArray("tree") ?: return@use emptyList()

            buildList {
                for (index in 0 until treeArray.length()) {
                    val node = treeArray.getJSONObject(index)
                    val type = node.optString("type")
                    val path = node.optString("path")
                    if (type == "blob" && path.endsWith(".json")) {
                        add(path)
                    }
                }
            }
        }

        val requiredKeys = arrayOf("title", "description", "artist", "author")
        val enforceLanguage = preferences.getBoolean(PREF_ENFORCE_LANGUAGE, true)
        val sourceLang = lang.lowercase()
        val shouldFilterByLang = enforceLanguage && sourceLang != "all" && sourceLang != "other"
        val expectedLang = if (shouldFilterByLang) languageCodeAlias(sourceLang) else null

        val mangaList = jsonPaths.mapNotNull { path ->
            val rawUrl = "https://raw.githubusercontent.com/$repo/$branch/$path"
            runCatching {
                val jsonRequest = Request.Builder()
                    .url(rawUrl)
                    .withGithubToken()
                    .build()

                client.newCall(jsonRequest).execute().use { jsonResponse ->
                    if (!jsonResponse.isSuccessful) return@use null

                    val json = JSONObject(jsonResponse.body?.string().orEmpty())
                    if (!requiredKeys.all(json::has)) return@use null

                    val descField = json.optString("description")
                    if (expectedLang != null && extractSupportedLang(descField) != expectedLang) {
                        return@use null
                    }

                    val displayTitle = json.optString("title")
                        .takeIf { it.isNotBlank() }
                        ?: path.substringAfterLast("/").removeSuffix(".json")

                    val gistBase64 = Base64.encodeToString(
                        "raw/$repo/$branch/$path".toByteArray(),
                        Base64.NO_PADDING or Base64.NO_WRAP,
                    )

                    SManga.create().apply {
                        title = displayTitle
                        url = "/read/gist/$gistBase64"
                        description = buildString {
                            append("GitHub: ").append(repo)
                            append("\nPath: ").append(path)
                            if (descField.isNotBlank()) {
                                append("\n\n").append(descField)
                            }
                        }
                        thumbnail_url = json.optString("cover")
                    }
                }
            }.getOrNull()
        }

        return MangasPage(mangaList, false)
    }
    // KNS

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
        // KNS
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
        // KNS

        url = mangaReference?.url ?: jsonObj["url"]!!.jsonPrimitive.content
        thumbnail_url = jsonObj["coverUrl"]?.jsonPrimitive?.content
            ?: jsonObj["cover"]?.jsonPrimitive?.content ?: ""
    }

    // ----------------- Things we aren't supporting -----------------

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // KNS
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

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ENFORCE_LANGUAGE
            title = "Enforce Language"
            summary = "When enabled, only manga matching the selected language will be shown for repository searches. Disable to show all."
            setDefaultValue(true)
        }.let(screen::addPreference)
    }
    // KNS

    companion object {
        const val AUTHOR_FALLBACK = "Unknown"
        const val ARTIST_FALLBACK = "Unknown"
        const val DESCRIPTION_FALLBACK = "No description."
        const val SEARCH_FALLBACK_MSG = "Please enter a valid Cubari URL"
        // KNS
        private const val CUBARI_PROXY_PREFIX = "cubari://proxy/"
        private const val PREF_GITHUB_TOKEN = "cubari_github_token"
        private const val PREF_ENFORCE_LANGUAGE = "cubari_enforce_language"
        // KNS

        enum class SortType {
            PINNED,
            UNPINNED,
            ALL,
        }
    }
}
