package eu.kanade.tachiyomi.extension.all.batotov3

import android.app.Application
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

open class BatoTo(
    final override val lang: String,
    private val siteLang: List<String>,
) : ConfigurableSource, HttpSource() {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val name: String = "Bato.to"

    override val baseUrl: String = getMirrorPref()!!

    override val id: Long = when (lang) {
        "zh-Hans" -> 2818874445640189582
        "zh-Hant" -> 38886079663327225
        "ro-MD" -> 8871355786189601023
        else -> super.id
    }

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
        val payloadObj = ApiSearchPayload(
            pageNumber = page,
            size = 30,
            query = query.trim(),
            incTLangs = siteLang,
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
        val whitespace by lazy { Regex("\\s+") }
        const val SEARCH_PREFIX = "ID:"
        private const val RESTART_TACHIYOMI = "Restart Tachiyomi to apply new setting."
        private const val COVER_PREF_KEY = "COVER"
        private const val COVER_PREF_TITLE = "Cover Quality"
        private val COVER_PREF_ENTRIES = arrayOf("Original", "Medium", "Low")
        private val COVER_PREF_DEFAULT_VALUE = COVER_PREF_ENTRIES[0]
        private const val MIRROR_PREF_KEY = "MIRROR"
        private const val MIRROR_PREF_TITLE = "Preferred Mirror"
        private val MIRROR_PREF_ENTRIES = arrayOf(
            "bato.to",
            "wto.to",
            "mto.to",
            "dto.to",
            "hto.to",
            "batotoo.com",
            "battwo.com",
            "batotwo.com",
            "comiko.net",
            "mangatoto.com",
            "mangatoto.net",
            "mangatoto.org",
            "comiko.org",
            "batocomic.com",
            "batocomic.net",
            "batocomic.org",
            "readtoto.com",
            "readtoto.net",
            "readtoto.org",
            "xbato.com",
            "xbato.net",
            "xbato.org",
            "zbato.com",
            "zbato.net",
            "zbato.org",
        )
        private val MIRROR_PREF_ENTRY_VALUES = arrayOf(
            "https://bato.to",
            "https://wto.to",
            "https://mto.to",
            "https://dto.to",
            "https://hto.to",
            "https://batotoo.com",
            "https://battwo.com",
            "https://batotwo.com",
            "https://comiko.net",
            "https://mangatoto.com",
            "https://mangatoto.net",
            "https://mangatoto.org",
            "https://comiko.org",
            "https://batocomic.com",
            "https://batocomic.net",
            "https://batocomic.org",
            "https://readtoto.com",
            "https://readtoto.net",
            "https://readtoto.org",
            "https://xbato.com",
            "https://xbato.net",
            "https://xbato.org",
            "https://zbato.com",
            "https://zbato.net",
            "https://zbato.org",
        )
        val MIRROR_PREF_DEFAULT_VALUE = MIRROR_PREF_ENTRY_VALUES[0]
    }
}
