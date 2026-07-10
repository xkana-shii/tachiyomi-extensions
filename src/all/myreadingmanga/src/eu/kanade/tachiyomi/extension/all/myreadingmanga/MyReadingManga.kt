package eu.kanade.tachiyomi.extension.all.myreadingmanga

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.tryParse
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)

@Source
abstract class MyReadingManga : HttpSource(), ConfigurableSource {

    private val siteLang: String
        get() = when (lang) {
            "ar" -> "Arabic"
            "id" -> "Indonesia"
            "zh" -> "Chinese"
            "zh-hant" -> "Traditional-Chinese"
            "hr" -> "Croatian"
            "en" -> "English"
            "fil" -> "Filipino"
            "fr" -> "French"
            "de" -> "German"
            "hu" -> "Hungarian"
            "it" -> "Italian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "lt" -> "Lithuanian"
            "fa" -> "Persian"
            "pl" -> "Polish"
            "pt-BR" -> "Portuguese"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "sk" -> "Slovak"
            "es" -> "Spanish"
            "sv" -> "Swedish"
            "th" -> "Thai"
            "tr" -> "Turkish"
            "vi" -> "Vietnamese"
            else -> lang
        }

    private val latestLang: String get() = if (lang == "ja") "jp" else siteLang

    // Basic Info
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .add("X-Requested-With", randomString((1..20).random()))

    private val preferences: SharedPreferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    private val credentials: Credential
        get() = Credential(
            username = preferences.getString(USERNAME_PREF, "") ?: "",
            password = preferences.getString(PASSWORD_PREF, "") ?: "",
        )
    private data class Credential(val username: String, val password: String)

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        // KNS
        .addInterceptor(LoginInterceptor())
        // KNS
        .build()

    override val supportsLatest = true

    // KNS
    private inner class LoginInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath

            if (path == "/wp-login.php" || path == "/login" || path == "/login/" || path.startsWith("/login/")) {
                return chain.proceed(request)
            }

            val username = credentials.username
            val password = credentials.password

            val cookies = client.cookieJar.loadForRequest(request.url)
            val hasLoginCookie = cookies.any { it.name.startsWith("wordpress_logged_in_") }

            if (!hasLoginCookie && username.isNotBlank() && password.isNotBlank()) {
                val loginBody = FormBody.Builder()
                    .add("log", username)
                    .add("pwd", password)
                    .add("wp-submit", "Log In")
                    .add("redirect_to", "$baseUrl/")
                    .add("testcookie", "1")
                    .build()

                val loginRequest = POST("$baseUrl/wp-login.php", headers, loginBody)
                chain.proceed(loginRequest).use { loginResponse ->
                    if (!loginResponse.isSuccessful) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(
                                Injekt.get<Application>(),
                                "MyReadingManga login failed. Please check your credentials.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }

            val response = chain.proceed(request)
            val finalPath = response.request.url.encodedPath
            if (finalPath == "/login" || finalPath == "/login/" || finalPath.startsWith("/login/")) {
                response.close()
                throw IOException("Please log in via extension settings")
            }

            return response
        }
    }

    // Preference Screen
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val usernamePref = EditTextPreference(screen.context).apply {
            key = USERNAME_PREF
            title = "Username"
            summary = "Enter your username"
        }
        val passwordPref = EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Password"
            summary = "Enter your password"
            setOnBindEditTextListener { et ->
                et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        screen.addPreference(usernamePref)
        screen.addPreference(passwordPref)
    }
    // KNS

    // Popular - Random
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/page/$page/?s=&ep_sort=rand&ep_filter_lang=$siteLang", headers) // Random Manga as returned by search
    }

    override fun popularMangaParse(response: Response): MangasPage {
        cacheAssistant()
        return searchMangaParse(response)
    }

    // Latest
    @SuppressLint("DefaultLocale")
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/lang/${latestLang.lowercase()}" + if (page > 1) "/page/$page/" else "", headers) // Home Page - Latest Manga
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        cacheAssistant()
        val document = response.asJsoup()
        val mangas = document.select("article:not(.category-video)").map { element ->
            buildManga(element.selectFirst("a[rel]")!!, element.selectFirst("a.entry-image-link img"))
        }
        val hasNextPage = document.selectFirst("li.pagination-next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val uri = Uri.parse("$baseUrl/page/$page/").buildUpon()
            .appendQueryParameter("s", query)
        filterList.forEach { filter ->
            if (filter is UriFilter) {
                filter.addToUri(uri)
            }
            if (filter is SearchSortTypeList) {
                uri.appendQueryParameter("ep_sort", listOf("date", "date_asc", "rand", "")[filter.state])
            }
        }

        return GET(uri.toString(), headers)
    }

    private var mangaParsedSoFar = 0

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        if (document.location().contains("/page/1")) mangaParsedSoFar = 0
        val mangas = document.select("article:not(.category-video)").map { element ->
            buildManga(element.selectFirst("a[rel]")!!, element.selectFirst("a.entry-image-link img"))
        }.also { mangaParsedSoFar += it.count() }
        val totalResults = TOTAL_RESULTS_REGEX.find(document.selectFirst(".ep-search-count")?.text() ?: "")?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
        return MangasPage(mangas, mangaParsedSoFar < totalResults)
    }

    // Build Manga From Element
    private fun buildManga(titleElement: Element, thumbnailElement: Element?): SManga {
        val manga = SManga.create().apply {
            setUrlWithoutDomain(titleElement.absUrl("href"))
            title = cleanTitle(titleElement.text())
        }
        if (thumbnailElement != null) manga.thumbnail_url = getThumbnail(getImage(thumbnailElement))
        return manga
    }

    private fun getImage(element: Element): String? {
        val url = when {
            element.attr("data-src").contains(EXTENSION_REGEX) -> element.attr("abs:data-src")
            element.attr("data-cfsrc").contains(EXTENSION_REGEX) -> element.attr("abs:data-cfsrc")
            element.attr("src").contains(EXTENSION_REGEX) -> element.attr("abs:src")
            else -> element.attr("abs:data-lazy-src")
        }

        return if (URLUtil.isValidUrl(url)) url else null
    }

    // removes resizing
    private fun getThumbnail(thumbnailUrl: String?): String? {
        thumbnailUrl ?: return null
        val url = thumbnailUrl.substringBeforeLast("-") + "." + thumbnailUrl.substringAfterLast(".")
        return if (URLUtil.isValidUrl(url)) url else null
    }

    // cleans up the name removing author and language from the title
    private fun cleanTitle(title: String) = title.replace(TITLE_REGEX, "").replace(Regex("""\s+"""), " ").trim()

    private fun cleanAuthor(author: String) = author.substringAfter("[").substringBefore("]").trim()

    // Manga Details
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val needCover = manga.thumbnail_url?.let { url -> client.newCall(GET(url, headers)).execute().use { !it.isSuccessful } } ?: true

        return client.newCall(mangaDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response.asJsoup(), needCover).apply { initialized = true }
            }
    }

    private fun mangaDetailsParse(document: Document, needCover: Boolean = true): SManga = SManga.create().apply {
        title = cleanTitle(document.selectFirst("h1")?.text() ?: "")
        author = cleanAuthor(document.selectFirst("h1")?.text() ?: "")
        artist = author
        genre = document.select(".entry-header p a[href*=genre], [href*=tag], span.entry-categories a").joinToString { it.text() }
        val basicDescription = document.selectFirst("h1")?.text()
        // too troublesome to achieve 100% accuracy assigning scanlator group during chapterListParse
        val scanlatedBy = document.selectFirst(".entry-terms:has(a[href*=group])")
            ?.select("a[href*=group]")?.joinToString(prefix = "Scanlated by: ") { it.text() }
        val extendedDescription = document.select(".entry-content p:not(p:containsOwn(|)):not(.chapter-class + p)").joinToString("\n") { it.text() }
        description = listOfNotNull(basicDescription, scanlatedBy, extendedDescription).joinToString("\n").trim()
        status = when (document.selectFirst("a[href*=status]")?.text()) {
            "Ongoing" -> SManga.ONGOING
            "Completed" -> SManga.COMPLETED
            "Licensed" -> SManga.LICENSED
            "Dropped" -> SManga.CANCELLED
            "Discontinued" -> SManga.CANCELLED
            "Hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        if (needCover) {
            thumbnail_url = client.newCall(GET("$baseUrl/?s=${document.location()}", headers))
                .execute().use {
                    it.asJsoup().selectFirst("div.ep-search-content div.entry-content img")
                }?.let {
                    getThumbnail(getImage(it))
                }
        }
    }

    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    // Start Chapter Get
    @SuppressLint("DefaultLocale")
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        val date = dateFormat.tryParse(document.selectFirst(".entry-time")?.text())
        // create first chapter since its on main manga page
        chapters.add(createChapter("1", document.location(), date, "Ch. 1"))
        // see if there are multiple chapters or not
        val lastChapterNumber = document.select("a[class=page-numbers]").last()?.text()?.toIntOrNull()
        if (lastChapterNumber != null) {
            // There are entries with more chapters but those never show up,
            // so we take the last one and loop it to get all hidden ones.
            // Example: 1 2 3 4 .. 7 8 9 Next
            for (i in 2..lastChapterNumber) {
                chapters.add(createChapter(i.toString(), document.location(), date, "Ch. $i"))
            }
        }
        chapters.reverse()
        return chapters
    }

    private fun createChapter(pageNumber: String, mangaUrl: String, date: Long, chname: String): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain("$mangaUrl/$pageNumber")
        chapter.name = chname
        chapter.date_upload = date
        return chapter
    }

    // Pages
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return (document.select("div.entry-content img") + document.select("div.separator img[data-src]"))
            .mapNotNull { getImage(it) }
            .distinct()
            .mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Filter Parsing, grabs pages as document and filters out Genres, Popular Tags, and Categories, Parings, and Scan Groups
    private var filtersCached = false
    private val filterMap = mutableMapOf<String, String>()

    // Grabs page containing filters and puts it into cache
    private fun filterAssist(url: String) {
        filterMap[url] = client.newCall(GET(url, headers)).execute().use {
            it.body.string()
        }
    }

    private fun cacheAssistant() {
        if (!filtersCached) {
            cachedPagesUrls.forEach { filterAssist(it.value) }
            filtersCached = true
        }
    }

    // Parses cached page for filters
    private fun returnFilter(url: String, css: String): Array<Pair<String, String>> {
        val document = if (filterMap.isEmpty()) {
            filtersCached = false
            null
        } else {
            filtersCached = true
            Jsoup.parse(filterMap[url]!!)
        }
        return document?.select(css)?.map { Pair(it.text(), it.attr("href").split("/").dropLast(1).lastOrNull() ?: "") }?.toTypedArray()
            ?: arrayOf(Pair("Press 'Reset' to load filters", ""))
    }

    // URLs for the pages we need to cache
    private val cachedPagesUrls = mapOf(
        "genres" to baseUrl,
        "tags" to "$baseUrl/tags/",
        "categories" to "$baseUrl/cats/",
        "pairings" to "$baseUrl/pairing/",
        "groups" to "$baseUrl/group/",
    )

    // Generates the filter lists for app
    override fun getFilterList(): FilterList = FilterList(
        EnforceLanguageFilter(siteLang),
        SearchSortTypeList(),
        GenreFilter(returnFilter(cachedPagesUrls["genres"]!!, ".tagcloud a[href*=/genre/]")),
        TagFilter(returnFilter(cachedPagesUrls["tags"]!!, ".tag-groups-alphabetical-index a")),
        CatFilter(returnFilter(cachedPagesUrls["categories"]!!, ".tag-groups-alphabetical-index a")),
        PairingFilter(returnFilter(cachedPagesUrls["pairings"]!!, ".tag-groups-alphabetical-index a")),
        ScanGroupFilter(returnFilter(cachedPagesUrls["groups"]!!, ".tag-groups-alphabetical-index a")),
    )

    companion object {
        // KNS
        private const val USERNAME_PREF = "MYREADINGMANGA_USERNAME"
        private const val PASSWORD_PREF = "MYREADINGMANGA_PASSWORD"
        // KNS
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
        private val EXTENSION_REGEX = Regex("""\.(jpg|png|jpeg|webp)""")
        private val TITLE_REGEX = Regex("""^\s*\[[^]]*]\s*|\s*\[[^]]*].*$""")
        private val TOTAL_RESULTS_REGEX = Regex("""([\d,]+)""")
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }
}
