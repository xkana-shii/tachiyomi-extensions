package eu.kanade.tachiyomi.extension.all.batotov3

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

open class BatoToV3(
    override val lang: String,
    private val siteLang: String = lang,
    private val preferences: SharedPreferences,

) : ConfigurableSource, HttpSource() {

    override val name: String = "Bato.to V3"

    override val baseUrl: String = getMirrorPref()!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "${MIRROR_PREF_KEY}_$lang"
            title = MIRROR_PREF_TITLE
            entries = MIRROR_PREF_ENTRIES
            entryValues = MIRROR_PREF_ENTRY_VALUES
            setDefaultValue(MIRROR_PREF_DEFAULT_VALUE)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                Toast.makeText(screen.context, RESTART_TACHIYOMI, Toast.LENGTH_LONG).show()
                preferences.edit().putString("${MIRROR_PREF_KEY}_$lang", entry).commit()
            }
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = "${COVER_PREF_KEY}_$lang"
            title = COVER_PREF_TITLE
            entries = COVER_PREF_ENTRIES
            entryValues = COVER_PREF_ENTRIES
            setDefaultValue(COVER_PREF_DEFAULT_VALUE)
            summary = "%s"
        }.let { screen.addPreference(it) }
    }

    private fun getMirrorPref(): String? = preferences.getString("${MIRROR_PREF_KEY}_$lang", MIRROR_PREF_DEFAULT_VALUE)

    private fun coverQuality(): CoverQuality {
        return when (preferences.getString("${COVER_PREF_KEY}_$lang", COVER_PREF_DEFAULT_VALUE)) {
            "Medium" -> CoverQuality.Medium
            "Low" -> CoverQuality.Low
            else -> CoverQuality.Original
        }
    }

    override val supportsLatest = true

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(::imageFallbackInterceptor)
        .addNetworkInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Referer", "$baseUrl/")
                .build()

            chain.proceed(request)
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int) = searchMangaRequest(page, "", FilterList(SortFilter("views_d000")))

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int) = searchMangaRequest(page, "", FilterList(SortFilter("field_upload")))

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith(SEARCH_PREFIX)) {
            val id = query.substringAfter(SEARCH_PREFIX)
            val manga = SManga.create().apply { url = "/series/$id/" }
            return fetchMangaDetails(manga).map {
                MangasPage(listOf(it), false)
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var incTLangs = if (siteLang.isEmpty()) emptyList() else listOf(siteLang)
        val payloadObj = ApiSearchPayload(
            pageNumber = page,
            size = 30,
            query = query.trim(),
            incTLangs = incTLangs,
            sort = filters.firstInstanceOrNull<SortFilter>()?.selected,
            incGenres = filters.firstInstanceOrNull<GenreGroupFilter>()?.included,
            excGenres = filters.firstInstanceOrNull<GenreGroupFilter>()?.excluded,
            origStatus = filters.firstInstanceOrNull<OriginalStatusFilter>()?.selected,
            batoStatus = filters.firstInstanceOrNull<BatoStatusFilter>()?.selected,
            incOLangs = filters.firstInstanceOrNull<OriginGroupFilter>()?.selected,
            chapCount = filters.firstInstanceOrNull<ChapterCountFilter>()?.selected,
        )

        return apiRequest(payloadObj)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val searchResponse = response.parseAs<ApiSearchResponse>()

        val hasNextPage = searchResponse.data.search.paging.let {
            it.total > it.page
        }

        return searchResponse.data.search.items
            .map { items ->
                items.data.toSManga(coverQuality())
                    .apply { initialized = true }
            }
            .let { MangasPage(it, hasNextPage) }
    }

    private fun imageFallbackInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (request.url.fragment != PAGE_FRAGMENT || response.isSuccessful) {
            return response
        }

        response.closeQuietly()

        val urlString = request.url.toString()

        if (SERVER_PATTERN.containsMatchIn(urlString)) {
            val regex = Regex("""https://([kn])(\d{2})""")
            val match = regex.find(urlString)
            if (match != null) {
                val (currentLetter, number) = match.destructured
                val fallbackLetter = if (currentLetter == "k") "n" else "k"
                val swappedUrl = urlString.replaceFirst(regex, "https://$fallbackLetter$number")

                if (swappedUrl != urlString) {
                    val swappedRequest = request.newBuilder()
                        .url(swappedUrl)
                        .build()

                    try {
                        val swappedResponse = chain
                            .withConnectTimeout(5, TimeUnit.SECONDS)
                            .withReadTimeout(10, TimeUnit.SECONDS)
                            .proceed(swappedRequest)

                        if (swappedResponse.isSuccessful) {
                            return swappedResponse
                        }

                        swappedResponse.close()
                    } catch (_: Exception) {
                    }
                }
            }

            // Sorted list: Most reliable servers FIRST
            val servers = listOf("n03", "n00", "n01", "n02", "n04", "n05", "n06", "n07", "n08", "n09", "n10", "k03", "k06", "k07", "k00", "k01", "k02", "k04", "k05", "k08", "k09")

            for (server in servers) {
                val newUrl = urlString.replace(SERVER_PATTERN, "https://$server")

                val newRequest = request.newBuilder()
                    .url(newUrl)
                    .build()

                try {
                    val newResponse = chain
                        .withConnectTimeout(5, TimeUnit.SECONDS)
                        .withReadTimeout(10, TimeUnit.SECONDS)
                        .proceed(newRequest)

                    if (newResponse.isSuccessful) {
                        return newResponse
                    }

                    newResponse.close()
                } catch (_: Exception) {
                    // Connection error on this mirror, ignore and loop to next
                }
            }
        }

        return chain.proceed(request)
    }

    override fun getFilterList() = filters

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.split("/")[2]

        val payloadObj = ApiQueryPayload(id, DETAILS_QUERY)

        return apiRequest(payloadObj)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaData = response.parseAs<ApiDetailsResponse>()

        return mangaData.data.comicNode.data.toSManga(coverQuality())
    }

    private fun autoMarkdownLinks(input: String): String {
        val urlRegex = Regex("""(?:[a-zA-Z][a-zA-Z0-9+.-]*:[^\s<>()\[\]]+|(?:www\.|m\.)?(?:[a-zA-Z0-9-]+\.)+[A-Za-z]{2,}(?:/[^\s<>()\[\]]*)?)""")
        return urlRegex.replace(input) { matchResult ->
            val url = matchResult.value
            val start = matchResult.range.first
            val end = matchResult.range.last
            val isMarkdownLink = start >= 2 && input.substring(start - 2, start) == "]("
            val isAngleBracket = (start >= 1 && input[start - 1] == '<') && (end + 1 < input.length && input[end + 1] == '>')
            if (isMarkdownLink || isAngleBracket) {
                url
            } else {
                val label = try {
                    val host = when {
                        url.startsWith("https://www.") || url.startsWith("http://www.") ||
                            url.startsWith("https://m.") || url.startsWith("http://m.") -> {
                            val afterFirstDot = url.substringAfter("://").substringAfter('.')
                            afterFirstDot.substringBefore('.')
                        }
                        (url.startsWith("https://") || url.startsWith("http://")) &&
                            !url.startsWith("https://www.") && !url.startsWith("http://www.") &&
                            !url.startsWith("https://m.") && !url.startsWith("http://m.") -> {
                            val afterProtocol = url.substringAfter("://")
                            afterProtocol.substringBefore('.')
                        }
                        else -> try {
                            java.net.URL(
                                if (url.startsWith("www.") || url.startsWith("m.")) {
                                    "http://$url"
                                } else {
                                    url
                                },
                            ).host
                        } catch (_: Exception) {
                            url.substringBefore('/').substringBefore('?')
                        }
                    }
                    if (host.isNotEmpty() && host.any { it.isLetter() }) host.replaceFirstChar { it.uppercase() } else null
                } catch (_: Exception) { null }
                if (label != null) "[$label]($url)" else "<$url>"
            }
        }.trim()
    }

    override fun getMangaUrl(manga: SManga): String {
        val url = manga.url.split("/")
        return "$baseUrl/title/${url[2]}-${url[3]}"
    }

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.split("/")[2]

        val payloadObj = ApiQueryPayload(id, CHAPTERS_QUERY)

        return apiRequest(payloadObj)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapterList = response.parseAs<ApiChapterListResponse>()

        return chapterList.data.chapters
            .map { it.data.toSChapter() }
            .sortedByDescending { it.chapter_number }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return "$baseUrl/${chapter.url}"
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url
            .removeSuffix("/")
            .substringAfterLast("/")
            .substringBefore("-")

        val payloadObj = ApiQueryPayload(id, PAGES_QUERY)

        return apiRequest(payloadObj)
    }

    override fun pageListParse(response: Response): List<Page> {
        val pages = response.parseAs<ApiPageListResponse>()

        return pages.data.pageList.data.imageFiles?.mapIndexed { index, image ->
            Page(index, "", image)
        } ?: emptyList()
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not Used")
    }

    private inline fun <reified R> List<*>.firstInstanceOrNull(): R? =
        filterIsInstance<R>().firstOrNull()

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString(body.string())

    private inline fun <reified T> apiRequest(payloadObj: T): Request {
        val payload = json.encodeToString(payloadObj)
            .toRequestBody(JSON_MEDIA_TYPE)

        val apiHeaders = headersBuilder()
            .add("Content-Length", payload.contentLength().toString())
            .add("Content-Type", payload.contentType().toString())
            .build()

        return POST("$baseUrl/apo", apiHeaders, payload)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        }
        private val SERVER_PATTERN = Regex("https://[a-zA-Z]\\d{2}")
        private val seriesUrlRegex = Regex(""".*/series/(\d+)/.*""")
        private val seriesIdRegex = Regex("""series/(\d+)""")
        private val chapterIdRegex = Regex("""/chapter/(\d+)""") // /chapter/4016325
        private val idRegex = Regex("""(\d+)""")
        private const val MIRROR_PREF_KEY = "MIRROR"
        private const val REMOVE_TITLE_VERSION_PREF = "REMOVE_TITLE_VERSION"
        private const val REMOVE_TITLE_CUSTOM_PREF = "REMOVE_TITLE_CUSTOM"

        // https://batotomirrors.pages.dev/
        private val mirrors = arrayOf(
            "https://ato.to",
            "https://dto.to",
            "https://fto.to",
            "https://hto.to",
            "https://jto.to",
            "https://lto.to",
            "https://mto.to",
            "https://nto.to",
            "https://vto.to",
            "https://wto.to",
            "https://xto.to",
            "https://yto.to",
            "https://vba.to",
            "https://wba.to",
            "https://xba.to",
            "https://yba.to",
            "https://zba.to",
            "https://bato.ac",
            "https://bato.bz",
            "https://bato.cc",
            "https://bato.cx",
            "https://bato.id",
            "https://bato.pw",
            "https://bato.sh",
            "https://bato.to",
            "https://bato.vc",
            "https://bato.day",
            "https://bato.red",
            "https://bato.run",
            "https://batoto.in",
            "https://batoto.tv",
            "https://batotoo.com",
            "https://batotwo.com",
            "https://batpub.com",
            "https://batread.com",
            "https://battwo.com",
            "https://xbato.com",
            "https://xbato.net",
            "https://xbato.org",
            "https://zbato.com",
            "https://zbato.net",
            "https://zbato.org",
            "https://comiko.net",
            "https://comiko.org",
            "https://mangatoto.com",
            "https://mangatoto.net",
            "https://mangatoto.org",
            "https://batocomic.com",
            "https://batocomic.net",
            "https://batocomic.org",
            "https://readtoto.com",
            "https://readtoto.net",
            "https://readtoto.org",
            "https://kuku.to",
            "https://okok.to",
            "https://ruru.to",
            "https://xdxd.to",
        )

        private val DEPRECATED_MIRRORS = listOf(
            "https://batocc.com", // parked
        )

        private const val ALT_CHAPTER_LIST_PREF_KEY = "ALT_CHAPTER_LIST"
        private const val ALT_CHAPTER_LIST_PREF_TITLE = "Alternative Chapter List"
        private const val ALT_CHAPTER_LIST_PREF_SUMMARY = "If checked, uses an alternate chapter list"
        private const val ALT_CHAPTER_LIST_PREF_DEFAULT_VALUE = false

        private val titleRegex: Regex =
            Regex("\\([^()]*\\)|\\{[^{}]*\\}|\\[(?:(?!]).)*]|«[^»]*»|〘[^〙]*〙|「[^」]*」|『[^』]*』|≪[^≫]*≫|﹛[^﹜]*﹜|〖[^〖〗]*〗|\uD81A\uDD0D.+?\uD81A\uDD0D|《[^》]*》|⌜.+?⌝|⟨[^⟩]*⟩|【[^】]*】|([|].*)|([/].*)|([~].*)|-[^-]*-|‹[^›]*›|/Official|/ Official", RegexOption.IGNORE_CASE)
    }
}

