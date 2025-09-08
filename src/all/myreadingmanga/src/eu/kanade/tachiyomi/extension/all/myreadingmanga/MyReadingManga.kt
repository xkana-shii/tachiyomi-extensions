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
import eu.kanade.tachiyomi.network.await
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
import org.jsoup.parser.Parser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

open class MyReadingManga(override val lang: String, private val siteLang: String, private val latestLang: String) : ParsedHttpSource(), ConfigurableSource {

    /*
     *  ========== Basic Info ==========
     */
    override val name = "MyReadingManga"
    final override val baseUrl = "https://myreadingmanga.info"
    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.7258.159 Mobile Safari/537.36")
            .add("X-Requested-With", randomString((1..20).random()))

    private val preferences: SharedPreferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    private val credentials: Credential get() = Credential(
        username = preferences.getString(USERNAME_PREF, "") ?: "",
        password = preferences.getString(PASSWORD_PREF, "") ?: "",
    )
    private data class Credential(val username: String, val password: String)
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

        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            return chain.proceed(request)
        }

        if (isLoggedIn) {
            return chain.proceed(request)
        }

        try {
            val loginForm = FormBody.Builder()
                .add("log", credentials.username)
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
        } catch (_: Exception) {
            return chain.proceed(request)
        }
    }

    // Preference Screen
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val application = Injekt.get<Application>()
        val usernamePref = EditTextPreference(screen.context).apply {
            key = USERNAME_PREF
            title = "Username"
            summary = "Enter your username"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(application, "Restart the app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
        }
        val passwordPref = EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Password"
            summary = "Enter your password"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(application, "Restart the app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(usernamePref)
        screen.addPreference(passwordPref)
    }

    /*
     *  ========== Popular - Random ==========
     */
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/popular/", headers)
    }

    override fun popularMangaNextPageSelector() = null
    override fun popularMangaSelector() = ".wpp-list li:not(:has(img[src*=vlcsnap]))"
    override fun popularMangaFromElement(element: Element) = buildManga(element.select(".wpp-post-title").first()!!, element.select(".wpp-thumbnail").first())
    override fun popularMangaParse(response: Response): MangasPage {
        cacheAssistant()
        return super.popularMangaParse(response)
    }

    /*
     * ========== Latest ==========
     */
    @SuppressLint("DefaultLocale")
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/lang/${latestLang.lowercase()}" + if (page > 1) "/page/$page/" else "", headers) // Home Page - Latest Manga
    }

    override fun latestUpdatesNextPageSelector() = "li.pagination-next"
    override fun latestUpdatesSelector() = "article:not(.category-video)"
    override fun latestUpdatesFromElement(element: Element) = buildManga(element.select("a.entry-title-link").first()!!, element.select("a.entry-image-link img").first())
    override fun latestUpdatesParse(response: Response): MangasPage {
        cacheAssistant()
        return super.latestUpdatesParse(response)
    }

    /*
     * ========== Search ==========
     */
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val uri = Uri.parse("$baseUrl/page/$page/").buildUpon()
            .appendQueryParameter("s", query)
        filterList.forEach { filter ->
            // If enforce language is checked, then apply language filter automatically
            if (filter is EnforceLanguageFilter && filter.state) {
                filter.addToUri(uri)
            } else if (filter is UriFilter) {
                filter.addToUri(uri)
            }
        }
        return GET(uri.toString(), headers)
    }

    override fun searchMangaNextPageSelector(): String? = "li.pagination-next"
    override fun searchMangaSelector() = "article:not(.category-video)"
    override fun searchMangaFromElement(element: Element) = buildManga(element.select("a.entry-title-link").first()!!, element.select("a.entry-image-link img").first())
    override fun searchMangaParse(response: Response): MangasPage {
        cacheAssistant()
        return super.searchMangaParse(response)
    }

    /*
     * ========== Building manga from element ==========
     */
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

    // Manga Details
    override suspend fun getMangaDetails(manga: SManga): SManga {
        val needCover = manga.thumbnail_url?.let { !client.newCall(GET(it, headers)).execute().isSuccessful } ?: true

        val response = client.newCall(mangaDetailsRequest(manga)).await()
        return mangaDetailsParse(response.asJsoup(), needCover).apply { initialized = true }
    }

    private fun mangaDetailsParse(document: Document, needCover: Boolean = true): SManga {
        return SManga.create().apply {
            title = cleanTitle(document.select("h1").text())
            author = document.select(".entry-terms a[href*=artist]").firstOrNull()?.text()
            artist = author
            genre = document.select(".entry-header p a[href*=genre], [href*=tag], span.entry-categories a").joinToString { it.text() }
            val basicDescription = document.select("h1").text()
            // too troublesome to achieve 100% accuracy assigning scanlator group during chapterListParse
            val scanlatedBy = document.select(".entry-terms:has(a[href*=group])").firstOrNull()
                ?.select("a[href*=group]")?.joinToString(prefix = "Scanlated by: ") { it.text() }
            val extendedDescription = document.select(".entry-content p:not(p:containsOwn(|)):not(.chapter-class + p)").joinToString("\n") { it.text() }
            description = listOfNotNull(basicDescription, scanlatedBy, extendedDescription).joinToString("\n").trim()
            status = when (document.select("a[href*=status]").first()?.text()) {
                "Completed" -> SManga.COMPLETED
                "Ongoing" -> SManga.ONGOING
                "Licensed" -> SManga.LICENSED
                "Dropped" -> SManga.CANCELLED
                "Discontinued" -> SManga.CANCELLED
                "Hiatus" -> SManga.ON_HIATUS
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

    /*
     * ========== Building chapters from element ==========
     */
    override fun chapterListSelector() = "a[class=page-numbers]"

    @SuppressLint("DefaultLocale")
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        val date = parseDate(document.select(".entry-time").text())
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

    /*
     * ========== Building pages from element ==========
     */
    override fun pageListParse(document: Document): List<Page> {
        return (document.select("div.entry-content img") + document.select("div.separator img[data-src]"))
            .mapNotNull { getImage(it) }
            .distinct()
            .mapIndexed { i, url -> Page(i, "", url) }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // ========== FILTERS ==========

    // ========== DYNAMIC XML SITEMAP FILTERS ==========
    private var genresSitemap: List<MrmFilter>? = null
    private var categoriesSitemap: List<MrmFilter>? = null
    private var pairingsSitemap: List<MrmFilter>? = null
    private var postTagSitemap: List<MrmFilter>? = null
    private var artistsSitemap: List<MrmFilter>? = null
    private var statusSitemap: List<MrmFilter>? = null

    private fun getSitemapUrls(type: String): List<String> {
        val indexUrl = "$baseUrl/sitemap_index.xml"
        val response = client.newCall(GET(indexUrl, headers)).execute()
        val xml = response.body.string()
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("sitemap > loc")
            .map { it.text() }
            .filter { it.contains("$type-sitemap") }
    }

    private fun getGenresFromXmlSitemap(): List<MrmFilter> {
        if (genresSitemap != null) return genresSitemap!!
        val urls = getSitemapUrls("genre")
        val all = mutableListOf<MrmFilter>()
        for (url in urls) {
            try { all += fetchSitemap(url) } catch (_: Exception) {}
        }
        genresSitemap = all
        return genresSitemap!!
    }

    private fun getCategoriesFromXmlSitemap(): List<MrmFilter> {
        if (categoriesSitemap != null) return categoriesSitemap!!
        val urls = getSitemapUrls("category")
        val all = mutableListOf<MrmFilter>()
        for (url in urls) {
            try { all += fetchSitemap(url) } catch (_: Exception) {}
        }
        categoriesSitemap = all
        return categoriesSitemap!!
    }

    private fun getPairingsFromXmlSitemap(): List<MrmFilter> {
        if (pairingsSitemap != null) return pairingsSitemap!!
        val urls = getSitemapUrls("pairing")
        val all = mutableListOf<MrmFilter>()
        for (url in urls) {
            try { all += fetchSitemap(url) } catch (_: Exception) {}
        }
        pairingsSitemap = all
        return pairingsSitemap!!
    }

    private fun getPostTagsFromXmlSitemap(): List<MrmFilter> {
        if (postTagSitemap != null) return postTagSitemap!!
        val urls = getSitemapUrls("post_tag")
        val all = mutableListOf<MrmFilter>()
        for (url in urls) {
            try { all += fetchSitemap(url) } catch (_: Exception) {}
        }
        postTagSitemap = all
        return postTagSitemap!!
    }

    private fun getArtistsFromXmlSitemaps(): List<MrmFilter> {
        if (artistsSitemap != null) return artistsSitemap!!
        val urls = getSitemapUrls("artist")
        val all = mutableListOf<MrmFilter>()
        for (url in urls) {
            try { all += fetchSitemap(url) } catch (_: Exception) {}
        }
        artistsSitemap = all
        return artistsSitemap!!
    }

    private fun getStatusFromXmlSitemap(): List<MrmFilter> {
        if (statusSitemap != null) return statusSitemap!!
        val urls = getSitemapUrls("status")
        val all = mutableListOf<MrmFilter>()
        for (url in urls) {
            try { all += fetchSitemap(url) } catch (_: Exception) {}
        }
        statusSitemap = all
        return statusSitemap!!
    }

    // Helper for all the XML sitemaps
    private fun fetchSitemap(sitemapUrl: String): List<MrmFilter> {
        val response = client.newCall(GET(sitemapUrl, headers)).execute()
        val xml = response.body.string()
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val urls = doc.select("url > loc")
        return urls.map { locElem ->
            val url = locElem.text()
            val slug = url.trimEnd('/').split("/").last()
            val name = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            MrmFilter(name, slug)
        }
    }

    private var filtersCached = false
    private var mainPage = ""
    private var searchPage = ""

    // Grabs page containing filters and puts it into cache
    private fun filterAssist(url: String): String {
        val response = client.newCall(GET(url, headers)).execute()
        return response.body.string()
    }

    private fun cacheAssistant() {
        if (!filtersCached) {
            mainPage = filterAssist(baseUrl)
            searchPage = filterAssist("$baseUrl/?s=")
            filtersCached = true
        }
    }

    // Parses main page for filters
    private fun getFiltersFromMainPage(filterTitle: String): List<MrmFilter> {
        val document = if (mainPage == "") {
            filtersCached = false
            null
        } else {
            filtersCached = true
            Jsoup.parse(mainPage)
        }
        val parent = document?.select(".widget-title")?.first { it.text() == filterTitle }?.parent()
        return parent?.select(".tag-cloud-link")
            ?.map { MrmFilter(it.text(), it.attr("href").split("/").reversed()[1]) }
            ?: listOf(MrmFilter("Press 'Reset' to load filters", ""))
    }

    // Parses search page for filters
    private fun getFiltersFromSearchPage(filterTitle: String): List<MrmFilter> {
        val document = if (searchPage == "") {
            filtersCached = false
            null
        } else {
            filtersCached = true
            Jsoup.parse(searchPage)
        }
        val parent = document?.select(".ep-filter-title")?.first { it.text() == filterTitle }?.parent()
        return parent?.select(".term")?.map { MrmFilter(it.text(), it.attr("data-term-slug")) }
            ?: listOf(MrmFilter("Press 'Reset' to load filters", ""))
    }

    /*
     * ========== Filter toggles ==========
     */

    private class UseHtmlFiltersToggle : Filter.CheckBox("Use site filters (main/search page)", false)

    // Generates the filter lists for app
    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>()
        filters += EnforceLanguageFilter(siteLang)
        filters += UseHtmlFiltersToggle()
        // Check toggle state to select filter source
        val useHtmlFilters = (filters[1] as UseHtmlFiltersToggle).state

        val genres = try {
            if (useHtmlFilters) getFiltersFromMainPage("Genres") else getGenresFromXmlSitemap()
        } catch (_: Exception) {
            getFiltersFromMainPage("Genres")
        }
        val categories = try {
            if (useHtmlFilters) getFiltersFromMainPage("Category") else getCategoriesFromXmlSitemap()
        } catch (_: Exception) {
            getFiltersFromMainPage("Category")
        }
        val pairings = try {
            if (useHtmlFilters) getFiltersFromSearchPage("Pairing") else getPairingsFromXmlSitemap()
        } catch (_: Exception) {
            getFiltersFromSearchPage("Pairing")
        }
        val postTags = try {
            if (useHtmlFilters) getFiltersFromSearchPage("Tag") else getPostTagsFromXmlSitemap()
        } catch (_: Exception) {
            getFiltersFromSearchPage("Tag")
        }
        val artists = try {
            if (useHtmlFilters) getFiltersFromSearchPage("Circle/ artist") else getArtistsFromXmlSitemaps()
        } catch (_: Exception) {
            getFiltersFromSearchPage("Circle/ artist")
        }
        val statuses = try {
            if (useHtmlFilters) getFiltersFromSearchPage("Status") else getStatusFromXmlSitemap()
        } catch (_: Exception) {
            getFiltersFromSearchPage("Status")
        }
        filters += GenreFilter(genres)
        filters += CatFilter(categories)
        filters += TagFilter(postTags)
        filters += ArtistFilter(artists)
        filters += PairingFilter(pairings)
        filters += StatusFilter(statuses)
        return FilterList(filters)
    }

    private class EnforceLanguageFilter(val siteLang: String) : Filter.CheckBox("Enforce language", true), UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            if (state) uri.appendQueryParameter("ep_filter_lang", siteLang)
        }
    }

    private class GenreFilter(GENRES: List<MrmFilter>) : UriSelectFilter("Genre", "ep_filter_genre", GENRES)
    private class CatFilter(CATID: List<MrmFilter>) : UriSelectFilter("Category", "ep_filter_category", CATID)
    private class TagFilter(POPTAG: List<MrmFilter>) : UriSelectFilter("Tag", "ep_filter_post_tag", POPTAG)
    private class ArtistFilter(POPART: List<MrmFilter>) : UriSelectFilter("Artist", "ep_filter_artist", POPART)
    private class PairingFilter(PAIR: List<MrmFilter>) : UriSelectFilter("Pairing", "ep_filter_pairing", PAIR)
    private class StatusFilter(STATUS: List<MrmFilter>) : UriSelectFilter("Status", "ep_filter_status", STATUS)

    private class MrmFilter(name: String, val value: String) : Filter.CheckBox(name)
    private open class UriSelectFilter(
        displayName: String,
        val uriParam: String,
        val vals: List<MrmFilter>,
    ) : Filter.Group<MrmFilter>(displayName, vals), UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            val checked = state.filter { it.state }.ifEmpty { return }
                .joinToString(",") { it.value }

            uri.appendQueryParameter(uriParam, checked)
        }
    }

    /**
     * Represents a filter that is able to modify a URI.
     */
    private interface UriFilter {
        fun addToUri(uri: Uri.Builder)
    }

    companion object {
        private const val USERNAME_PREF = "MYREADINGMANGA_USERNAME"
        private const val PASSWORD_PREF = "MYREADINGMANGA_PASSWORD"
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }
}
