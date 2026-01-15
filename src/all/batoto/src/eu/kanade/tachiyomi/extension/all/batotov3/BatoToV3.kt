package eu.kanade.tachiyomi.extension.all.batotov3

import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
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

    // exposed to app (webview/intents) — main site path includes /v3x
    override val baseUrl: String
        get() {
            val index = preferences.getString(MIRROR_PREF_KEY, "0")!!.toInt()
                .coerceAtMost(mirrors.size - 1)
            return "${mirrors[index]}/v3x"
        }

    // mirror root used for API/thumbnail construction (no /v3x)
    private val mirrorRoot: String
        get() {
            val index = preferences.getString(MIRROR_PREF_KEY, "0")!!.toInt()
                .coerceAtMost(mirrors.size - 1)
            return mirrors[index]
        }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Mirror selection (shared)
        ListPreference(screen.context).apply {
            key = MIRROR_PREF_KEY
            title = "Preferred Mirror"
            entries = mirrors
            entryValues = Array(mirrors.size) { it.toString() }
            summary = "%s"
            setDefaultValue("0")
        }.let { screen.addPreference(it) }

        // Remove version info checkbox (per-language)
        CheckBoxPreference(screen.context).apply {
            key = "${REMOVE_TITLE_VERSION_PREF}_$lang"
            title = "Remove version information from entry titles"
            summary = "This removes version tags like '(Official)' from entry titles."
            setDefaultValue(false)
        }.let { screen.addPreference(it) }

        // Custom remove regex (shared key)
        EditTextPreference(screen.context).apply {
            key = BATOTO_REMOVE_TITLE_CUSTOM_PREF
            title = "Custom regex to be removed from title"
            summary = customRemoveTitle()
            setDefaultValue("")

            val validate = { str: String ->
                runCatching { Regex(str) }
                    .map { true to "" }
                    .getOrElse { false to it.message }
            }

            setOnBindEditTextListener { editText ->
                editText.addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                        override fun afterTextChanged(editable: Editable?) {
                            editable ?: return
                            val text = editable.toString()
                            val valid = validate(text)
                            editText.error = if (!valid.first) valid.second else null
                            editText.rootView.findViewById<Button>(android.R.id.button1)?.isEnabled = editText.error == null
                        }
                    },
                )
            }

            setOnPreferenceChangeListener { _, newValue ->
                val (isValid, message) = validate(newValue as String)
                if (isValid) {
                    summary = newValue
                } else {
                    Toast.makeText(screen.context, message, Toast.LENGTH_LONG).show()
                }
                isValid
            }
        }.let { screen.addPreference(it) }
    }

    private fun isRemoveTitleVersion(): Boolean =
        preferences.getBoolean("${REMOVE_TITLE_VERSION_PREF}_$lang", false)

    private fun customRemoveTitle(): String =
        preferences.getString(BATOTO_REMOVE_TITLE_CUSTOM_PREF, "")!!

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

    // Use mirrorRoot as referer for API calls and images (avoid /v3x leaking into endpoints)
    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$mirrorRoot/")

    // Keep popular simple: delegate to searchMangaRequest with views_d000 sort
    override fun popularMangaRequest(page: Int) = searchMangaRequest(page, "", FilterList(SortFilter("views_d000")))

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int) = searchMangaRequest(page, "", FilterList(SortFilter("field_upload")))

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith(SEARCH_PREFIX)) {
            val id = query.substringAfter(SEARCH_PREFIX)
            val manga = SManga.create().apply { url = id }
            return fetchMangaDetails(manga).map {
                MangasPage(listOf(it), false)
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    private fun getMangaId(url: String): String {
        val trimmed = url.trim().trim('/')
        val parts = trimmed.split('/')
        return if (parts.size >= 2 && parts[0] == "series") parts[1] else url
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

        val mangas = searchResponse.data.search.items
            .map { items ->
                // ensure thumbnail is returned by using mirrorRoot as base,
                // and apply title cleaning for display
                items.data.toSManga(mirrorRoot, ::cleanTitleIfNeeded)
                    .apply { initialized = true }
            }

        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList() = filters

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = getMangaId(manga.url)
        val payloadObj = ApiQueryPayload(id, DETAILS_QUERY)
        return apiRequest(payloadObj)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaData = response.parseAs<ApiDetailsResponse>()

        // Build SManga using the raw title (identity) so we can collect removed parts, then set cleaned title.
        val manga = mangaData.data.comicNode.data.toSManga(mirrorRoot, { it })

        val removedParts = mutableListOf<String>()
        var cleanedTitle = manga.title ?: ""

        fun removeAndCollect(regex: Regex) {
            regex.findAll(cleanedTitle).forEach { removedParts.add(it.value.trim()) }
            cleanedTitle = cleanedTitle.replace(regex, "")
        }

        customRemoveTitle().takeIf { it.isNotEmpty() }?.let { regexStr ->
            removeAndCollect(Regex(regexStr, RegexOption.IGNORE_CASE))
        }
        if (isRemoveTitleVersion()) removeAndCollect(titleRegex)
        cleanedTitle = cleanedTitle.trim()

        // Set cleaned title (so UI shows the cleaned title)
        manga.title = cleanedTitle

        val description = buildString {
            if (!manga.description.isNullOrBlank()) append(manga.description)
            if (removedParts.isNotEmpty()) {
                append("\n\n----\n#### **Removed From Title**\n")
                removedParts.forEach { append("- `$it`\n") }
            }
        }.trim().let { autoMarkdownLinks(it) }

        manga.description = description

        return manga
    }

    private fun cleanTitleIfNeeded(title: String): String {
        var tempTitle = title
        customRemoveTitle().takeIf { it.isNotEmpty() }?.let { customRegex ->
            runCatching {
                tempTitle = tempTitle.replace(Regex(customRegex), "")
            }
        }
        if (isRemoveTitleVersion()) {
            tempTitle = tempTitle.replace(titleRegex, "")
        }
        return tempTitle.trim()
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
        // return mirror root URL for manga (no /v3x) so webview opens correct path
        return "$mirrorRoot/title/${getMangaId(manga.url)}"
    }

    override fun chapterListRequest(manga: SManga): Request {
        val id = getMangaId(manga.url)
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
        // return mirror root URL for chapter (no /v3x) so webview opens correct path
        return "$mirrorRoot/title/chapter/${chapter.url}"
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url
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

        // POST to mirror root /apo so API calls use mirrorRoot (no /v3x) while UI/opening uses baseUrl (/v3x)
        return POST("$mirrorRoot/apo", apiHeaders, payload)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        }
        val chapterNumRegex by lazy { Regex("""\.0+$""") }
        const val SEARCH_PREFIX = "ID:"
        private const val RESTART_TACHIYOMI = "Restart Tachiyomi to apply new setting."

        // Mirror preference key shared with v2/v4 (index-based)
        private const val MIRROR_PREF_KEY = "MIRROR"
        private val mirrors = arrayOf(
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

        // Preference key for removing version info (per-language)
        private const val REMOVE_TITLE_VERSION_PREF = "REMOVE_TITLE_VERSION"

        // Title regex used to strip version info (matches bracketed or parenthesized tags, same as v2/v4)
        private val titleRegex by lazy { Regex("""\s*(?:\([^)]*\)|\[[^\]]*\])""", RegexOption.IGNORE_CASE) }

        // Custom remove regex key (also available on the wrapper)
        const val BATOTO_REMOVE_TITLE_CUSTOM_PREF = "BATOTO_REMOVE_TITLE_CUSTOM"
    }
}
