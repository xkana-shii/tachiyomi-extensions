package eu.kanade.tachiyomi.extension.all.myreadingmanga

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
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
import java.text.SimpleDateFormat
import java.util.Locale

open class MyReadingManga(override val lang: String, private val siteLang: String, private val latestLang: String) : ParsedHttpSource(), ConfigurableSource {

    // Basic Info
    override val name = "MyReadingManga"
    final override val baseUrl = "https://myreadingmanga.info"
    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", USER_AGENT)
            .add("X-Requested-With", randomString((1..20).random()))

    private val preferences: SharedPreferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    private val credentials: Credential get() = Credential(
        email = preferences.getString(USERNAME_PREF, "") ?: "",
        password = preferences.getString(PASSWORD_PREF, "") ?: "",
    )
    private data class Credential(val email: String, val password: String)
    private var isLoggedIn: Boolean = false

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .addInterceptor(::loginInterceptor)
        .build()

    override val supportsLatest = true

    // Login Interceptor
    private fun loginInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (credentials.email.isBlank() || credentials.password.isBlank()) {
            return chain.proceed(request)
        }

        if (isLoggedIn) {
            return chain.proceed(request)
        }

        try {
            val loginForm = FormBody.Builder()
                .add("log", credentials.email)
                .add("pwd", credentials.password)
                .add("wp-submit", "Log In")
                .add("redirect_to", "$baseUrl/")
                .add("testcookie", "1")
                .build()

            val loginRequest = POST("$baseUrl/wp-login.php", headers, loginForm)
            val loginResponse = network.cloudflareClient.newCall(loginRequest).execute()

            if (loginResponse.isSuccessful) {
                isLoggedIn = true
                return chain.proceed(request)
            } else {
                Toast.makeText(Injekt.get<Application>(), "MyReadingManga login failed. Please check your credentials.", Toast.LENGTH_LONG).show()
            }
            return chain.proceed(request)
        } catch (e: Exception) {
            return chain.proceed(request)
        }
    }

    // Preference Screen
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val application = Injekt.get<Application>()
        val usernamePref = EditTextPreference(screen.context).apply {
            key = USERNAME_PREF
            title = "Email"
            summary = "Enter your MyReadingManga.info email"
            dialogMessage = "Your email address for MyReadingManga.info"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(application, "Restart the app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
        }
        val passwordPref = EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Password"
            summary = "Enter your MyReadingManga.info password"
            dialogMessage = "Your password for MyReadingManga.info"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(application, "Restart the app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(usernamePref)
        screen.addPreference(passwordPref)
    }

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/popular/", headers)
    }

    override fun popularMangaNextPageSelector() = "li.pagination-next"
    override fun popularMangaSelector() = "div.entry-content ul.wpp-list > li"
    override fun popularMangaFromElement(element: Element) = buildManga(element.select(".wpp-post-title").first()!!, element.select("img.wpp-thumbnail").first())
    override fun popularMangaParse(response: Response): MangasPage {
        cacheAssistant()
        return super.popularMangaParse(response)
    }

    // Latest
    @SuppressLint("DefaultLocale")
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/lang/${latestLang.lowercase()}" + if (page > 1) "/page/$page/" else "", headers) // Home Page - Latest Manga
    }

    override fun latestUpdatesNextPageSelector() = "li.pagination-next"
    override fun latestUpdatesSelector() = "div.content-archive article.post:not(.category-video)"
    override fun latestUpdatesFromElement(element: Element) = buildManga(element.select("a[rel]").first()!!, element.select("a.entry-image-link img").first())
    override fun latestUpdatesParse(response: Response): MangasPage {
        cacheAssistant()
        return super.latestUpdatesParse(response)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        // whether enforce language is true will change the index of the loop below
        val indexModifier = filterList.filterIsInstance<EnforceLanguageFilter>().first().indexModifier()

        val uri = Uri.parse("$baseUrl/search/").buildUpon()
            .appendQueryParameter("wpsolr_q", query)
        filterList.forEachIndexed { i, filter ->
            if (filter is UriFilter) {
                filter.addToUri(uri, "wpsolr_fq[${i - indexModifier}]")
            }
        }
        uri.appendQueryParameter("wpsolr_page", page.toString())

        return GET(uri.toString(), headers)
    }

    override fun searchMangaNextPageSelector() = "div.archive-pagination li.pagination-next a"
    override fun searchMangaSelector() = "div.content-archive article.post"
    private var mangaParsedSoFar = 0
    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val currentUrl = document.location()
        if (!currentUrl.contains("/page/") || currentUrl.contains("/page/1/")) {
            mangaParsedSoFar = 0
        }

        val mangas = document.select(searchMangaSelector()).map { searchMangaFromElement(it) }
            .also { mangaParsedSoFar += it.count() }

        val totalResultsText = document.select("h2.ep-search-count").first()?.text()
        val totalResults = totalResultsText?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

        val hasNextPage = if (mangas.isEmpty()) false else mangaParsedSoFar < totalResults

        return MangasPage(mangas, hasNextPage)
    }
    override fun searchMangaFromElement(element: Element) = buildManga(element.select("h2.entry-title a.entry-title-link").first()!!, element.select("a.entry-image-link img.post-image").first())

    // Build Manga From Element
    private fun buildManga(titleElement: Element, thumbnailElement: Element?): SManga {
        val manga = SManga.create().apply {
            setUrlWithoutDomain(titleElement.attr("href"))
            title = cleanTitle(titleElement.text())
        }
        if (thumbnailElement != null) manga.thumbnail_url = getThumbnail(getImage(thumbnailElement))
        return manga
    }

    private val extensionRegex = Regex("""\.(jpg|png|jpeg|webp)""")

    private fun getImage(element: Element): String? {
        val url = when {
            element.attr("data-src").contains(extensionRegex) -> element.attr("abs:data-src")
            element.attr("data-cfsrc").contains(extensionRegex) -> element.attr("abs:data-cfsrc")
            element.attr("src").contains(extensionRegex) -> element.attr("abs:src")
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
    private val titleRegex = Regex("""\[[^]]*]""")
    private fun cleanTitle(title: String) = title.replace(titleRegex, "").substringBeforeLast("(").trim()

    private fun cleanAuthor(author: String) = author.substringAfter("[").substringBefore("]").trim()

    // Manga Details
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val needCover = manga.thumbnail_url?.let { !client.newCall(GET(it, headers)).execute().isSuccessful } ?: true

        return client.newCall(mangaDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response.asJsoup(), needCover).apply { initialized = true }
            }
    }

    private fun mangaDetailsParse(document: Document, needCover: Boolean = true): SManga {
        return SManga.create().apply {
            title = cleanTitle(document.select("h1").text())
            author = cleanAuthor(document.select("h1").text())
            artist = author
            genre = document.select(".entry-header p a[href*=genre], [href*=tag], span.entry-categories a").joinToString { it.text() }
            val basicDescription = document.select("h1").text()
            // too troublesome to achieve 100% accuracy assigning scanlator group during chapterListParse
            val scanlatedBy = document.select(".entry-terms:has(a[href*=group])").firstOrNull()
                ?.select("a[href*=group]")?.joinToString(prefix = "Scanlated by: ") { it.text() }
            val extendedDescription = document.select(".entry-content p:not(p:containsOwn(|)):not(.chapter-class + p)").joinToString("\n") { it.text() }
            description = listOfNotNull(basicDescription, scanlatedBy, extendedDescription).joinToString("\n").trim()
            status = when (document.select("a[href*=status]").first()?.text()) {
                "Ongoing" -> SManga.ONGOING
                "Completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            if (needCover) {
                thumbnail_url = getThumbnail(
                    getImage(
                        client.newCall(GET("$baseUrl/search/?search=${document.location()}", headers))
                            .execute().asJsoup().select("div.wdm_results div.p_content img").first()!!,
                    ),
                )
            }
        }
    }

    override fun mangaDetailsParse(document: Document) = throw UnsupportedOperationException()

    // Start Chapter Get
    override fun chapterListSelector() = "a[class=page-numbers]"

    @SuppressLint("DefaultLocale")
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        val date = parseDate(document.select(".entry-time").text())
        val mangaUrl = document.baseUri()
        // create first chapter since its on main manga page
        chapters.add(createChapter("1", document.baseUri(), date, "Ch. 1"))
        // see if there are multiple chapters or not
        val lastChapterNumber = document.select(chapterListSelector()).last()?.text()
        if (lastChapterNumber != null) {
            // There are entries with more chapters but those never show up,
            // so we take the last one and loop it to get all hidden ones.
            // Example: 1 2 3 4 .. 7 8 9 Next
            for (i in 2..lastChapterNumber.toInt()) {
                chapters.add(createChapter(i.toString(), document.baseUri(), date, "Ch. $i"))
            }
        }
        chapters.reverse()
        return chapters
    }

    private fun parseDate(date: String): Long {
        return SimpleDateFormat("MMM dd, yyyy", Locale.US).parse(date)?.time ?: 0
    }

    private fun createChapter(pageNumber: String, mangaUrl: String, date: Long, chname: String): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain("$mangaUrl/$pageNumber")
        chapter.name = chname
        chapter.date_upload = date
        return chapter
    }

    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()

    // Pages

    override fun pageListParse(document: Document): List<Page> {
        return (document.select("div.entry-content img") + document.select("div.separator img[data-src]"))
            .mapNotNull { getImage(it) }
            .distinct()
            .mapIndexed { i, url -> Page(i, "", url) }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // Filter Parsing, grabs pages as document and filters out Genres, Popular Tags, and Categories, Parings, and Scan Groups
    private var filtersCached = false
    private val filterMap = mutableMapOf<String, String>()

    // Grabs page containing filters and puts it into cache
    private fun filterAssist(url: String) {
        val response = client.newCall(GET(url, headers)).execute()
        filterMap[url] = response.body.string()
    }

    private fun cacheAssistant() {
        if (!filtersCached) {
            cachedPagesUrls.onEach { filterAssist(it.value) }
            filtersCached = true
        }
    }

    // Parses cached page for filters
    private fun returnFilter(url: String, css: String): Array<String> {
        val document = if (filterMap.isNullOrEmpty()) {
            filtersCached = false
            null
        } else {
            filtersCached = true
            Jsoup.parse(filterMap[url]!!)
        }
        return document?.select(css)?.map { it.text() }?.toTypedArray()
            ?: arrayOf("Press 'Reset' to load filters")
    }

    // URLs for the pages we need to cache
    private val cachedPagesUrls = hashMapOf(
        Pair("genres", baseUrl),
        Pair("tags", baseUrl),
        Pair("categories", "$baseUrl/cats/"),
        Pair("pairings", "$baseUrl/pairing/"),
        Pair("groups", "$baseUrl/group/"),
    )

    // Generates the filter lists for app
    override fun getFilterList(): FilterList {
        return FilterList(
            EnforceLanguageFilter(siteLang),
            GenreFilter(returnFilter(cachedPagesUrls["genres"]!!, ".tagcloud a[href*=/genre/]")),
            TagFilter(returnFilter(cachedPagesUrls["tags"]!!, ".tagcloud a[href*=/tag/]")),
            CatFilter(returnFilter(cachedPagesUrls["categories"]!!, ".links a")),
            PairingFilter(returnFilter(cachedPagesUrls["pairings"]!!, ".links a")),
            ScanGroupFilter(returnFilter(cachedPagesUrls["groups"]!!, ".links a")),
        )
    }

    private class EnforceLanguageFilter(val siteLang: String) : Filter.CheckBox("Enforce language", true), UriFilter {
        fun indexModifier() = if (state) 0 else 1
        override fun addToUri(uri: Uri.Builder, uriParam: String) {
            if (state) uri.appendQueryParameter(uriParam, "ep_filter_lang=$siteLang")
        }
    }

    private class GenreFilter(GENRES: Array<String>) : UriSelectFilter("Genre", "ep_filter_genre", arrayOf("Any", *GENRES))
    private class TagFilter(POPTAG: Array<String>) : UriSelectFilter("Popular Tags", "ep_filter_post_tag", arrayOf("Any", *POPTAG))
    private class CatFilter(CATID: Array<String>) : UriSelectFilter("Categories", "ep_filter_category", arrayOf("Any", *CATID))
    private class PairingFilter(PAIR: Array<String>) : UriSelectFilter("Pairing", "pairing_str", arrayOf("Any", *PAIR))
    private class ScanGroupFilter(GROUP: Array<String>) : UriSelectFilter("Scanlation Group", "group_str", arrayOf("Any", *GROUP))

    /**
     * Class that creates a select filter. Each entry in the dropdown has a name and a display name.
     * If an entry is selected it is appended as a query parameter onto the end of the URI.
     * If `firstIsUnspecified` is set to true, if the first entry is selected, nothing will be appended on the the URI.
     */
    private open class UriSelectFilter(
        displayName: String,
        val uriValuePrefix: String,
        val vals: Array<String>,
        val firstIsUnspecified: Boolean = true,
        defaultValue: Int = 0,
    ) :
        Filter.Select<String>(displayName, vals.map { it }.toTypedArray(), defaultValue), UriFilter {
        override fun addToUri(uri: Uri.Builder, uriParam: String) {
            if (state != 0 || !firstIsUnspecified) {
                val splitFilter = vals[state].split(",")
                when {
                    splitFilter.size == 2 -> {
                        val reversedFilter = splitFilter.reversed().joinToString(" | ").trim()
                        uri.appendQueryParameter(uriParam, "$uriValuePrefix:$reversedFilter")
                    }
                    else -> {
                        uri.appendQueryParameter(uriParam, "$uriValuePrefix:${vals[state]}")
                    }
                }
            }
        }
    }

    /**
     * Represents a filter that is able to modify a URI.
     */
    private interface UriFilter {
        fun addToUri(uri: Uri.Builder, uriParam: String)
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
        private const val USERNAME_PREF = "MYREADINGMANGA_USERNAME"
        private const val PASSWORD_PREF = "MYREADINGMANGA_PASSWORD"
        private const val TAG = "MyReadingMangaLogin"
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }
}
