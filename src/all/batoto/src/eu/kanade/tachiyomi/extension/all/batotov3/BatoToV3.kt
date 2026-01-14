package eu.kanade.tachiyomi.extension.all.batotov3

import android.content.SharedPreferences
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
import java.text.SimpleDateFormat
import java.util.Locale

open class BatoToV3(
    final override val lang: String,
    private val siteLang: String = lang,
    private val preferences: SharedPreferences,
) : ConfigurableSource, HttpSource() {

    override val name: String = "Bato.to V3"

    override val baseUrl: String
        get() {
            val index = preferences.getString(MIRROR_PREF_KEY, "0")!!.toInt()
                .coerceAtMost(mirrors.size - 1)
            return mirrors[index]
        }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Mirror selection like v2: index-based mirror preference (shared, not per-language)
        ListPreference(screen.context).apply {
            key = MIRROR_PREF_KEY
            title = "Preferred Mirror"
            entries = mirrors
            entryValues = Array(mirrors.size) { it.toString() }
            summary = "%s"
            setDefaultValue("0")
        }.let { screen.addPreference(it) }

        // Cover quality (per-language, keep original behavior)
        ListPreference(screen.context).apply {
            key = "${COVER_PREF_KEY}_$lang"
            title = COVER_PREF_TITLE
            entries = COVER_PREF_ENTRIES
            entryValues = COVER_PREF_ENTRIES
            setDefaultValue(COVER_PREF_DEFAULT_VALUE)
            summary = "%s"
        }.let { screen.addPreference(it) }
    }

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

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(4)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int) = searchMangaRequest(page, "", FilterList(SortFilter("field_score")))

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
        val incTLangsList = if (siteLang.isEmpty()) emptyList() else listOf(siteLang)

        val payloadObj = ApiSearchPayload(
            pageNumber = page,
            size = 30,
            query = query.trim(),
            incTLangs = incTLangsList,
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
        val chapterNumRegex by lazy { Regex("""\.0+$""") }
        const val SEARCH_PREFIX = "ID:"
        private const val RESTART_TACHIYOMI = "Restart Tachiyomi to apply new setting."
        private const val COVER_PREF_KEY = "COVER"
        private const val COVER_PREF_TITLE = "Cover Quality"
        private val COVER_PREF_ENTRIES = arrayOf("Original", "Medium", "Low")
        private val COVER_PREF_DEFAULT_VALUE = COVER_PREF_ENTRIES[0]

        // Mirror preference key shared with v2/v4 (index-based)
        private const val MIRROR_PREF_KEY = "MIRROR"
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
    }
}
