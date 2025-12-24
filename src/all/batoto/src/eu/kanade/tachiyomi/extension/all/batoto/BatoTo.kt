package eu.kanade.tachiyomi.extension.all.batoto

import android.app.Application
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.cryptoaes.Deobfuscator
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
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

open class BatoTo(
    final override val lang: String,
    private val siteLang: String,
) : ConfigurableSource, ParsedHttpSource() {

    private val preferences by getPreferencesLazy { migrateMirrorPref() }

    override val name: String = "Bato.to"

    override val baseUrl: String
        get() {
            val raw = when (getVersion()) {
                "V2X" -> getMirrorPrefV2X()
                "V4X" -> getMirrorPrefV4X()
                else -> getMirrorPrefV2X()
            }
            return ensureUrlHasScheme(raw)
        }

    private fun ensureUrlHasScheme(url: String): String {
        return if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            url
        } else {
            "https://$url"
        }
    }

    override val id: Long = when (lang) {
        "zh-Hans" -> 2818874445640189582
        "zh-Hant" -> 38886079663327225
        "ro-MD" -> 8871355786189601023
        else -> super.id
    }

    override val supportsLatest = true
    private val json: Json by injectLazy()

    private val encodedSiteLangV4X = siteLang
        .takeIf { it.isNotBlank() }
        ?.let { URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }

    private val encodedSiteLangV3X = siteLang
        .takeIf { it.isNotBlank() }
        ?.let { URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }

    override val client = network.cloudflareClient.newBuilder().apply {
        addInterceptor(::imageFallbackInterceptor)
    }.build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val versionPref = ListPreference(screen.context).apply {
            key = "${VERSION_PREF_KEY}_$lang"
            title = VERSION_PREF_TITLE
            entries = VERSION_PREF_ENTRIES
            entryValues = VERSION_PREF_ENTRY_VALUES
            setDefaultValue(VERSION_PREF_DEFAULT_VALUE)
            summary = "%s"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Restart the app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(versionPref)

        when (getVersion()) {
            "V2X" -> setupPreferenceScreenV2X(screen)
            "V3X" -> setupPreferenceScreenV2X(screen)
            "V4X" -> setupPreferenceScreenV4X(screen)
            else -> setupPreferenceScreenV2X(screen)
        }
    }

    private fun setupPreferenceScreenV2X(screen: PreferenceScreen) {
        val mirrorPref = ListPreference(screen.context).apply {
            key = "${MIRROR_PREF_KEY_V2X}_$lang"
            title = if (getVersion() == "V3X") "Mirror (V3X)" else MIRROR_PREF_TITLE_V2X
            entries = MIRROR_PREF_ENTRIES_V2X
            entryValues = MIRROR_PREF_ENTRY_VALUES_V2X
            setDefaultValue(MIRROR_PREF_DEFAULT_VALUE_V2X)
            summary = "%s"
        }
        val altChapterListPref = CheckBoxPreference(screen.context).apply {
            key = "${ALT_CHAPTER_LIST_PREF_KEY}_$lang"
            title = ALT_CHAPTER_LIST_PREF_TITLE
            summary = ALT_CHAPTER_LIST_PREF_SUMMARY
            setDefaultValue(ALT_CHAPTER_LIST_PREF_DEFAULT_VALUE)
        }
        val removeOfficialPref = CheckBoxPreference(screen.context).apply {
            key = "${REMOVE_TITLE_VERSION_PREF}_$lang"
            title = "Remove version information from entry titles"
            summary = "This removes version tags like '(Official)' from entry titles."
            setDefaultValue(false)
        }
        val removeCustomPref = EditTextPreference(screen.context).apply {
            key = "${REMOVE_TITLE_CUSTOM_PREF}_$lang"
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
        }
        screen.addPreference(mirrorPref)
        screen.addPreference(altChapterListPref)
        screen.addPreference(removeOfficialPref)
        screen.addPreference(removeCustomPref)
    }

    private fun getMirrorPrefV2X(): String {
        if (System.getenv("CI") == "true") {
            return MIRROR_PREF_ENTRY_VALUES_V2X.drop(1).joinToString("#, ")
        }

        return preferences.getString("${MIRROR_PREF_KEY_V2X}_$lang", MIRROR_PREF_DEFAULT_VALUE_V2X)
            ?.takeUnless { it == MIRROR_PREF_DEFAULT_VALUE_V2X }
            ?: run {
                val seed = runCatching {
                    val pm = Injekt.get<Application>().packageManager
                    pm.getPackageInfo(BuildConfig.APPLICATION_ID, 0).lastUpdateTime
                }.getOrElse {
                    BuildConfig.VERSION_NAME.hashCode().toLong()
                }

                val pool = MIRROR_PREF_ENTRY_VALUES_V2X.drop(1)
                pool.random(kotlin.random.Random(seed))
            }
    }

    private fun getMirrorPrefV4X(): String {
        if (System.getenv("CI") == "true") {
            return MIRROR_PREF_ENTRY_VALUES_V4X.drop(1).joinToString("#, ")
        }

        return preferences.getString("${MIRROR_PREF_KEY_V4X}_$lang", MIRROR_PREF_DEFAULT_VALUE_V4X)
            ?.takeUnless { it == MIRROR_PREF_DEFAULT_VALUE_V4X }
            ?: run {
                val seed = runCatching {
                    val pm = Injekt.get<Application>().packageManager
                    pm.getPackageInfo(BuildConfig.APPLICATION_ID, 0).lastUpdateTime
                }.getOrElse {
                    BuildConfig.VERSION_NAME.hashCode().toLong()
                }

                val pool = MIRROR_PREF_ENTRY_VALUES_V4X.drop(1)
                pool.random(kotlin.random.Random(seed))
            }
    }

    private fun setupPreferenceScreenV4X(screen: PreferenceScreen) {
        val mirrorPref = ListPreference(screen.context).apply {
            key = "${MIRROR_PREF_KEY_V4X}_$lang"
            title = MIRROR_PREF_TITLE_V4X
            entries = MIRROR_PREF_ENTRIES_V4X
            entryValues = MIRROR_PREF_ENTRY_VALUES_V4X
            setDefaultValue(MIRROR_PREF_DEFAULT_VALUE_V4X)
            summary = "%s"
        }
        val altChapterListPref = CheckBoxPreference(screen.context).apply {
            key = "${ALT_CHAPTER_LIST_PREF_KEY}_$lang"
            title = ALT_CHAPTER_LIST_PREF_TITLE
            summary = ALT_CHAPTER_LIST_PREF_SUMMARY
            setDefaultValue(ALT_CHAPTER_LIST_PREF_DEFAULT_VALUE)
        }
        val removeOfficialPref = CheckBoxPreference(screen.context).apply {
            key = "${REMOVE_TITLE_VERSION_PREF}_$lang"
            title = "Remove version information from entry titles"
            summary = "This removes version tags like '(Official)' or '(Yaoi)' from entry titles " +
                "and helps identify duplicate entries in your library." +
                "To update existing entries, remove them from your library (unfavorite) and refresh manually."
            setDefaultValue(false)
        }
        val removeCustomPref = EditTextPreference(screen.context).apply {
            key = "${REMOVE_TITLE_CUSTOM_PREF}_$lang"
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
        }
        screen.addPreference(mirrorPref)
        screen.addPreference(altChapterListPref)
        screen.addPreference(removeOfficialPref)
        screen.addPreference(removeCustomPref)
    }

    override fun headersBuilder() = super.headersBuilder().apply {
        if (getVersion() == "V4X") {
            add("Referer", "$baseUrl/")
        }
        if (getVersion() == "V3X") {
            add("Referer", "${baseUrl.trimEnd('/')}/v3x/")
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = when (getVersion()) {
            "V2X" -> "$baseUrl/browse?langs=$siteLang&sort=update&page=$page"
            "V3X" -> withLangV4X("${baseUrl.trimEnd('/')}/v3x-search?order=desc&sort=field_upload&page=$page")
            "V4X" -> withLangV4X("$baseUrl/comics?sortby=field_update&order=desc&page=$page")
            else -> "$baseUrl/browse?langs=$siteLang&sort=update&page=$page"
        }
        Log.d(TAG, "latestUpdatesRequest URL: $url")
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String {
        return when (getVersion()) {
            "V2X" -> when (siteLang) {
                "" -> "div#series-list div.col"
                "en,en_us" -> "div#series-list div.col.no-flag"
                else -> "div#series-list div.col:has([data-lang=\"$siteLang\"])"
            }
            "V3X", "V4X" -> ""
            else -> when (siteLang) {
                "" -> "div#series-list div.col"
                "en,en_us" -> "div#series-list div.col.no-flag"
                else -> "div#series-list div.col:has([data-lang=\"$siteLang\"])"
            }
        }
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        return when (getVersion()) {
            "V2X" -> latestUpdatesFromElementV2X(element)
            "V3X", "V4X" -> SManga.create()
            else -> latestUpdatesFromElementV2X(element)
        }
    }

    private fun latestUpdatesFromElementV2X(element: Element): SManga {
        val manga = SManga.create()
        val item = element.select("a.item-cover")
        val imgurl = item.select("img").attr("abs:src")
        val rawUrl = stripSeriesUrl(item.attr("href"))
        manga.setUrlWithoutDomain(normalizeMangaUrl(rawUrl))
        manga.title = element.select("a.item-title").text().removeEntities()
            .cleanTitleIfNeeded()
        manga.thumbnail_url = imgurl
        return manga
    }

    override fun latestUpdatesNextPageSelector() = when (getVersion()) {
        "V2X" -> "div#mainer nav.d-none .pagination .page-item:last-of-type:not(.disabled)"
        "V3X", "V4X" -> null
        else -> "div#mainer nav.d-none .pagination .page-item:last-of-type:not(.disabled)"
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return when (getVersion()) {
            "V2X" -> super.latestUpdatesParse(response)
            "V3X", "V4X" -> parseMangaListV4X(response.asJsoup(), pageFromResponseV4X(response))
            else -> super.latestUpdatesParse(response)
        }
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = when (getVersion()) {
            "V2X" -> "$baseUrl/browse?langs=$siteLang&sort=views_a&page=$page"
            "V3X" -> withLangV4X("${baseUrl.trimEnd('/')}/v3x-search?order=desc&sort=views_d000&page=$page")
            "V4X" -> withLangV4X("$baseUrl/comics?sortby=views_d000&order=desc&page=$page")
            else -> "$baseUrl/browse?langs=$siteLang&sort=views_a&page=$page"
        }
        Log.d(TAG, "popularMangaRequest URL: $url")
        return GET(url, headers)
    }

    override fun popularMangaSelector() = latestUpdatesSelector()

    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun popularMangaParse(response: Response): MangasPage {
        return when (getVersion()) {
            "V2X" -> super.popularMangaParse(response)
            "V3X", "V4X" -> parseMangaListV4X(response.asJsoup(), pageFromResponseV4X(response))
            else -> super.popularMangaParse(response)
        }
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when (getVersion()) {
            "V2X" -> fetchSearchMangaV2X(page, query, filters)
            "V3X", "V4X" -> fetchSearchMangaV4X(page, query, filters)
            else -> fetchSearchMangaV2X(page, query, filters)
        }
    }

    private fun fetchSearchMangaV2X(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith("ID:") -> {
                val id = query.substringAfter("ID:")
                val url = "$baseUrl/series/$id"
                Log.d(TAG, "search (V2X, ID) URL: $url")
                client.newCall(GET(url, headers)).asObservableSuccess()
                    .map { response ->
                        queryIDParseV2X(response)
                    }
            }
            query.isNotBlank() -> {
                val url = "$baseUrl/search".toHttpUrl().newBuilder()
                    .addQueryParameter("word", query)
                    .addQueryParameter("page", page.toString())
                filters.forEach { filter ->
                    when (filter) {
                        is LetterFilter -> {
                            if (filter.state == 1) {
                                url.addQueryParameter("mode", "letter")
                            }
                        }
                        else -> { /* Do Nothing */ }
                    }
                }
                val finalUrl = url.build()
                Log.d(TAG, "search (V2X, text) URL: $finalUrl")
                client.newCall(GET(finalUrl, headers)).asObservableSuccess()
                    .map { response ->
                        queryParseV2X(response)
                    }
            }
            else -> {
                val url = "$baseUrl/browse".toHttpUrl().newBuilder()
                var min = ""
                var max = ""
                filters.forEach { filter ->
                    when (filter) {
                        is UtilsFilter -> {
                            if (filter.state != 0) {
                                val filterUrl = "$baseUrl/_utils/comic-list?type=${filter.selected}"
                                Log.d(TAG, "search (V2X, utils) URL: $filterUrl")
                                return client.newCall(GET(filterUrl, headers)).asObservableSuccess()
                                    .map { response ->
                                        queryUtilsParseV2X(response)
                                    }
                            }
                        }
                        is HistoryFilter -> {
                            if (filter.state != 0) {
                                val filterUrl = "$baseUrl/ajax.my.${filter.selected}.paging"
                                Log.d(TAG, "search (V2X, history) URL: $filterUrl")
                                return client.newCall(POST(filterUrl, headers, formBuilder().build())).asObservableSuccess()
                                    .map { response ->
                                        queryHistoryParseV2X(response)
                                    }
                            }
                        }
                        is LangGroupFilter -> {
                            if (filter.selected.isEmpty()) {
                                url.addQueryParameter("langs", siteLang)
                            } else {
                                val selection = "${filter.selected.joinToString(",")},$siteLang"
                                url.addQueryParameter("langs", selection)
                            }
                        }
                        is GenreGroupFilter -> {
                            with(filter) {
                                url.addQueryParameter(
                                    "genres",
                                    included.joinToString(",") + "|" + excluded.joinToString(","),
                                )
                            }
                        }
                        is StatusFilter -> url.addQueryParameter("release", filter.selected)
                        is SortFilter -> {
                            if (filter.state != null) {
                                val sort = getSortFilter()[filter.state!!.index].value
                                val value = when (filter.state!!.ascending) {
                                    true -> "az"
                                    false -> "za"
                                }
                                url.addQueryParameter("sort", "$sort.$value")
                            }
                        }
                        is OriginGroupFilter -> {
                            if (filter.selected.isNotEmpty()) {
                                url.addQueryParameter("origs", filter.selected.joinToString(","))
                            }
                        }
                        is MinChapterTextFilter -> min = filter.state
                        is MaxChapterTextFilter -> max = filter.state
                        else -> { /* Do Nothing */ }
                    }
                }
                url.addQueryParameter("page", page.toString())

                if (max.isNotEmpty() or min.isNotEmpty()) {
                    url.addQueryParameter("chapters", "$min-$max")
                }

                val finalUrl = url.build()
                Log.d(TAG, "search (V2X, filter) URL: $finalUrl")
                client.newCall(GET(finalUrl, headers)).asObservableSuccess()
                    .map { response ->
                        queryParseV2X(response)
                    }
            }
        }
    }

    private fun fetchSearchMangaV4X(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith(ID_PREFIX)) {
            val id = query.substringAfter(ID_PREFIX).trim()
            if (id.isBlank()) {
                return Observable.just(MangasPage(emptyList(), false))
            }
            val url = when (getVersion()) {
                "V3X" -> withLangV4X("${baseUrl.trimEnd('/')}/v3x/title/$id")
                else -> withLangV4X("$baseUrl/title/$id")
            }
            Log.d(TAG, "search (ID) URL: $url")
            return client.newCall(GET(url, headers)).asObservableSuccess()
                .map { queryIdParseV4X(it) }
        }

        val urlBuilder = when (getVersion()) {
            "V3X" -> "${baseUrl.trimEnd('/')}/v3x-search".toHttpUrl().newBuilder()
            else -> "$baseUrl/comics".toHttpUrl().newBuilder()
        }

        val trimmedQuery = query.trim()
        if (trimmedQuery.isNotBlank()) {
            urlBuilder.addQueryParameter("word", trimmedQuery)
        }
        urlBuilder.addQueryParameter("page", page.toString())
        applyFiltersV4X(urlBuilder, filters)
        val finalUrl = urlBuilder.build()
        Log.d(TAG, "search (V4X/V3X) URL: $finalUrl")
        return client.newCall(GET(finalUrl, headers)).asObservableSuccess()
            .map { response ->
                parseMangaListV4X(response.asJsoup(), pageFromResponseV4X(response))
            }
    }

    private fun queryIDParseV2X(response: Response): MangasPage {
        val document = response.asJsoup()
        val infoElement = document.select("div#mainer div.container-fluid")
        val manga = SManga.create()
        manga.title = infoElement.select("h3").text().removeEntities()
            .cleanTitleIfNeeded()
        manga.thumbnail_url = document.select("div.attr-cover img")
            .attr("abs:src")
        val rawUrl = stripSeriesUrl(infoElement.select("h3 a").attr("abs:href"))
        manga.setUrlWithoutDomain(normalizeMangaUrl(rawUrl))
        return MangasPage(listOf(manga), false)
    }

    private fun queryParseV2X(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector())
            .map { element -> latestUpdatesFromElement(element) }
        val nextPage = document.select(latestUpdatesNextPageSelector()).first() != null
        return MangasPage(mangas, nextPage)
    }

    private fun queryUtilsParseV2X(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("tbody > tr")
            .map { element -> searchUtilsFromElementV2X(element) }
        return MangasPage(mangas, false)
    }

    private fun queryHistoryParseV2X(response: Response): MangasPage {
        val json = json.decodeFromString<JsonObject>(response.body.string())
        val html = json.jsonObject["html"]!!.jsonPrimitive.content

        val document = Jsoup.parse(html, response.request.url.toString())
        val mangas = document.select(".my-history-item")
            .map { element -> searchHistoryFromElementV2X(element) }
        return MangasPage(mangas, false)
    }

    private fun searchUtilsFromElementV2X(element: Element): SManga {
        val manga = SManga.create()
        val rawUrl = stripSeriesUrl(element.select("td a").attr("href"))
        manga.setUrlWithoutDomain(normalizeMangaUrl(rawUrl))
        manga.title = element.select("td a").text()
            .cleanTitleIfNeeded()
        manga.thumbnail_url = element.select("img").attr("abs:src")
        return manga
    }

    private fun searchHistoryFromElementV2X(element: Element): SManga {
        val manga = SManga.create()
        val rawUrl = stripSeriesUrl(element.select(".position-relative a").attr("href"))
        manga.setUrlWithoutDomain(normalizeMangaUrl(rawUrl))
        manga.title = element.select(".position-relative a").text()
            .cleanTitleIfNeeded()
        manga.thumbnail_url = element.select("img").attr("abs:src")
        return manga
    }

    open fun formBuilder() = FormBody.Builder().apply {
        add("_where", "browse")
        add("first", "0")
        add("limit", "0")
        add("prevPos", "null")
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    override fun searchMangaSelector() = throw UnsupportedOperationException()
    override fun searchMangaFromElement(element: Element) = throw UnsupportedOperationException()
    override fun searchMangaNextPageSelector() = throw UnsupportedOperationException()

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = manga.url

        val normalizedId = normalizeMangaUrl(url)

        val actualUrl = when (getVersion()) {
            "V2X" -> "$baseUrl/series/$normalizedId"
            "V3X", "V4X" -> "$baseUrl/title/$normalizedId"
            else -> "$baseUrl/series/$normalizedId"
        }

        return GET(actualUrl, headers)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        return when (getVersion()) {
            "V2X" -> mangaDetailsParseV2X(document)
            "V3X" -> mangaDetailsParseV4X(document)
            "V4X" -> mangaDetailsParseV4X(document)
            else -> mangaDetailsParseV2X(document)
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val manga = when (getVersion()) {
            "V2X" -> super.mangaDetailsParse(response)
            "V3X" -> mangaDetailsParseV4X(response.asJsoup())
            "V4X" -> mangaDetailsParseV4X(response.asJsoup())
            else -> super.mangaDetailsParse(response)
        }
        val rawPath = extractPath(response.request.url.toString()) ?: response.request.url.encodedPath

        val updatedUrlPath = when {
            rawPath.contains("/title/") -> {
                stripTitleSlug(stripChapterSlug(rawPath))
            }
            rawPath.contains("/series/") -> {
                val id = seriesIdRegex.find(rawPath)?.groups?.get(1)?.value
                if (!id.isNullOrBlank()) "/series/$id" else stripSeriesUrl(rawPath)
            }
            else -> {
                val id = seriesIdRegex.find(rawPath)?.groups?.get(1)?.value
                if (!id.isNullOrBlank()) "/series/$id" else rawPath
            }
        }

        manga.setUrlWithoutDomain(normalizeMangaUrl(updatedUrlPath))

        return manga
    }

    private fun normalizeMangaUrl(url: String): String {
        val id = seriesIdRegex.find(url)?.groups?.get(1)?.value
        if (id.isNullOrBlank()) {
            return url
        }
        return id
    }

    private fun mangaDetailsParseV2X(document: Document): SManga {
        val infoElement = document.selectFirst("div#mainer div.container-fluid")!!
        val manga = SManga.create()
        val workStatus = infoElement.selectFirst("div.attr-item:contains(original work) span")?.text()
        val uploadStatus = infoElement.selectFirst("div.attr-item:contains(upload status) span")?.text()
        val originalTitle = infoElement.select("h3").text().removeEntities()

        val removedParts = mutableListOf<String>()
        var cleanedTitle = originalTitle

        fun removeAndCollect(regex: Regex) {
            regex.findAll(cleanedTitle).forEach { removedParts.add(it.value.trim()) }
            cleanedTitle = cleanedTitle.replace(regex, "")
        }

        customRemoveTitle().takeIf { it.isNotEmpty() }?.let { removeAndCollect(Regex(it, RegexOption.IGNORE_CASE)) }
        if (isRemoveTitleVersion()) removeAndCollect(titleRegex)
        cleanedTitle = cleanedTitle.trim()

        val description = buildString {
            infoElement.selectFirst("h5:containsOwn(Summary:) + div #limit-height-ctrl-summary #limit-height-body-summary .limit-html")?.also {
                append("\n\n----\n#### **Summary**\n${it.wholeText()}")
            }
            infoElement.selectFirst(".episode-list > .alert-warning")?.also {
                append("\n\n${it.text()}")
            }
            infoElement.selectFirst("h5:containsOwn(Extra Info:) + div")?.also {
                append("\n\n----\n#### **Extra Info**\n${it.wholeText()}")
            }
            document.selectFirst("div.pb-2.alias-set.line-b-f")?.takeIf { it.hasText() }?.also {
                append("\n\n----\n#### **Alternative Titles**\n")
                append(it.text().split('/').joinToString("\n- ", prefix = "- "))
            }
            if (removedParts.isNotEmpty()) {
                append("\n\n----\n#### **Removed From Title**\n")
                removedParts.forEach { append("- `$it`\n") }
            }
        }.trim().let { desc -> autoMarkdownLinks(desc) }

        manga.title = cleanedTitle
        manga.author = infoElement.select("div.attr-item:contains(author) span").text()
        manga.artist = infoElement.select("div.attr-item:contains(artist) span").text()
        manga.status = parseStatusV2X(workStatus, uploadStatus)
        manga.genre = infoElement.select(".attr-item b:contains(genres) + span ").joinToString { it.text() }
        manga.description = description
        manga.thumbnail_url = document.select("div.attr-cover img").attr("abs:src")
        return manga
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

    private fun parseStatusV2X(workStatus: String?, uploadStatus: String?): Int {
        val status = workStatus ?: uploadStatus
        return when {
            status == null -> SManga.UNKNOWN
            status.contains("Ongoing") -> SManga.ONGOING
            status.contains("Cancelled") -> SManga.CANCELLED
            status.contains("Hiatus") -> SManga.ON_HIATUS
            status.contains("Completed") -> when {
                uploadStatus?.contains("Ongoing") == true -> SManga.PUBLISHING_FINISHED
                else -> SManga.COMPLETED
            }
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = manga.url
        val normalizedId = normalizeMangaUrl(url)

        if (getAltChapterListPref() && normalizedId.isNotBlank() && getVersion() == "V2X") {
            return GET("$baseUrl/rss/series/$normalizedId.xml", headers)
        }

        val actualUrl = when (getVersion()) {
            "V2X" -> "$baseUrl/series/$normalizedId"
            "V3X", "V4X" -> "$baseUrl/title/$normalizedId"
            else -> "$baseUrl/series/$normalizedId"
        }

        return GET(actualUrl, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return when (getVersion()) {
            "V2X" -> if (getAltChapterListPref()) altChapterParseV2X(response) else chapterListParseV2X(response)
            "V3X", "V4X" -> {
                if (getAltChapterListPref()) {
                    altChapterParseV4X(response.asJsoup())
                } else {
                    chapterListParseV4X(response)
                }
            }
            else -> chapterListParseV2X(response)
        }
    }

    private fun chapterListParseV2X(response: Response): List<SChapter> {
        if (getAltChapterListPref()) {
            return altChapterParseV2X(response)
        }

        val document = response.asJsoup()

        if (checkChapterLists(document)) {
            throw Exception("Deleted from site")
        }

        return document.select(chapterListSelector())
            .map(::chapterFromElementV2X)
    }

    private fun chapterListParseV4X(response: Response): List<SChapter> {
        val document = response.asJsoup()

        if (checkChapterLists(document)) {
            throw Exception("Deleted from site")
        }
        return if (getAltChapterListPref()) {
            altChapterParseV4X(document)
        } else {
            document.select("div.px-2.py-2.flex.flex-wrap.justify-between")
                .map { chapterFromElementV4X(it) }
        }
    }

    private fun altChapterParseV2X(response: Response): List<SChapter> {
        return Jsoup.parse(response.body.string(), response.request.url.toString(), Parser.xmlParser())
            .select("channel > item").map { item ->
                SChapter.create().apply {
                    setUrlWithoutDomain(item.selectFirst("guid")!!.text())
                    name = item.selectFirst("title")!!.text()
                    date_upload = parseAltChapterDate(item.selectFirst("pubDate")!!.text())
                }
            }
    }

    private fun altChapterParseV4X(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        for (link in document.select("a[href*=\"/title/\"]")) {
            val path = link.attr("href")
            val name = link.text().trim()
            if (name.isNotBlank()) {
                chapters.add(
                    SChapter.create().apply {
                        url = path
                        this.name = name
                    },
                )
            }
        }
        return chapters
    }

    private val altDateFormat = SimpleDateFormat("E, dd MMM yyyy H:m: s Z", Locale.US)
    private fun parseAltChapterDate(date: String): Long {
        return try {
            altDateFormat.parse(date)!!.time
        } catch (_: ParseException) {
            0L
        }
    }

    private fun checkChapterLists(document: Document): Boolean {
        val deletedMsg = "This comic has been marked as deleted and the chapter list is not available."
        return listOf(
            document.select(".episode-list > .alert-warning").text(),
            document.select(".alert-outline.alert-outline-warning").text(),
        ).any { it.contains(deletedMsg, ignoreCase = true) }
    }

    override fun chapterListSelector() = "div.main div.p-2"

    override fun chapterFromElement(element: Element): SChapter {
        return when (getVersion()) {
            "V2X" -> chapterFromElementV2X(element)
            "V3X", "V4X" -> chapterFromElementV4X(element)
            else -> chapterFromElementV2X(element)
        }
    }

    private fun chapterFromElementV2X(element: Element): SChapter {
        val chapter = SChapter.create()
        val urlElement = element.select("a.chapt")
        val group = element.select("div.extra > a:not(.ps-3)").text()
        val user = element.select("div.extra > a.ps-3").text()
        val time = element.select("div.extra > i.ps-3").text()
        chapter.setUrlWithoutDomain(normalizeMangaUrl(urlElement.attr("href")))
        chapter.name = urlElement.text()
        chapter.scanlator = when {
            group.isNotBlank() -> group
            user.isNotBlank() -> user
            else -> "Unknown"
        }
        if (time != "") {
            chapter.date_upload = parseChapterDate(time)
        }
        return chapter
    }

    private fun chapterFromElementV4X(element: Element): SChapter {
        val chapter = SChapter.create()
        val chapterAnchor = element.selectFirst("a.link-hover.link-primary")
        chapter.name = chapterAnchor?.text() ?: ""
        chapter.url = chapterAnchor?.attr("href") ?: ""

        chapter.scanlator = element.selectFirst("div.inline-flex.items-center.space-x-1 a span")?.text()?.takeIf { it.isNotBlank() }

        chapter.date_upload = element.selectFirst("time[data-time]")?.attr("data-time")?.toLongOrNull() ?: 0L

        return chapter
    }
    private fun parseChapterDate(date: String): Long {
        val value = date.split(' ')[0].toInt()

        return when {
            "secs" in date -> Calendar.getInstance().apply {
                add(Calendar.SECOND, -value)
            }.timeInMillis
            "mins" in date -> Calendar.getInstance().apply {
                add(Calendar.MINUTE, -value)
            }.timeInMillis
            "hours" in date -> Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, -value)
            }.timeInMillis
            "days" in date -> Calendar.getInstance().apply {
                add(Calendar.DATE, -value)
            }.timeInMillis
            "weeks" in date -> Calendar.getInstance().apply {
                add(Calendar.DATE, -value * 7)
            }.timeInMillis
            "months" in date -> Calendar.getInstance().apply {
                add(Calendar.MONTH, -value)
            }.timeInMillis
            "years" in date -> Calendar.getInstance().apply {
                add(Calendar.YEAR, -value)
            }.timeInMillis
            "sec" in date -> Calendar.getInstance().apply {
                add(Calendar.SECOND, -value)
            }.timeInMillis
            "min" in date -> Calendar.getInstance().apply {
                add(Calendar.MINUTE, -value)
            }.timeInMillis
            "hour" in date -> Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, -value)
            }.timeInMillis
            "day" in date -> Calendar.getInstance().apply {
                add(Calendar.DATE, -value)
            }.timeInMillis
            "week" in date -> Calendar.getInstance().apply {
                add(Calendar.DATE, -value * 7)
            }.timeInMillis
            "month" in date -> Calendar.getInstance().apply {
                add(Calendar.MONTH, -value)
            }.timeInMillis
            "year" in date -> Calendar.getInstance().apply {
                add(Calendar.YEAR, -value)
            }.timeInMillis
            else -> {
                return 0
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.url.startsWith("http")) {
            // Check if trying to use a deprecated mirror, force current mirror
            val httpUrl = chapter.url.toHttpUrl()
            if ("https://${httpUrl.host}" in DEPRECATED_MIRRORS) {
                val newHttpUrl = httpUrl.newBuilder().host(baseUrl.toHttpUrl().host)
                return GET(newHttpUrl.build(), headers)
            }
            return GET(chapter.url, headers)
        }
        return super.pageListRequest(chapter)
    }

    override fun pageListParse(document: Document): List<Page> {
        return when (getVersion()) {
            "V2X" -> pageListParseV2X(document)
            "V3X", "V4X" -> emptyList()
            else -> pageListParseV2X(document)
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        return when (getVersion()) {
            "V2X" -> super.pageListParse(response)
            "V3X", "V4X" -> parsePageListV4X(response.asJsoup())
            else -> super.pageListParse(response)
        }
    }

    private fun pageListParseV2X(document: Document): List<Page> {
        val script = document.selectFirst("script:containsData(imgHttps):containsData(batoWord):containsData(batoPass)")?.html()
            ?: throw RuntimeException("Couldn't find script with image data.")

        val imgHttpsString = script.substringAfter("const imgHttps =").substringBefore(";").trim()
        val imageUrls = json.parseToJsonElement(imgHttpsString).jsonArray.map { it.jsonPrimitive.content }
        val batoWord = script.substringAfter("const batoWord =").substringBefore(";").trim()
        val batoPass = script.substringAfter("const batoPass =").substringBefore(";").trim()

        val evaluatedPass: String = Deobfuscator.deobfuscateJsPassword(batoPass)
        val imgAccListString = CryptoAES.decrypt(batoWord.removeSurrounding("\""), evaluatedPass)
        val imgAccList = json.parseToJsonElement(imgAccListString).jsonArray.map { it.jsonPrimitive.content }

        return imageUrls.mapIndexed { i, it ->
            val acc = imgAccList.getOrNull(i)
            val url = if (acc != null) {
                "$it?$acc"
            } else {
                it
            }

            Page(i, imageUrl = url)
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String {
        return when (getVersion()) {
            "V2X" -> ""
            "V3X", "V4X" -> ""
            else -> ""
        }
    }

    private fun parseMangaListV4X(document: Document, page: Int): MangasPage {
        val objs = parseQwikObjs(document) ?: return MangasPage(emptyList(), false)
        val cache = mutableMapOf<Int, Any?>()
        val mangaList = mutableListOf<SManga>()
        var rawCount = 0

        for (i in 0 until objs.length()) {
            val obj = objs.optJSONObject(i) ?: continue
            if (!obj.has("urlPath") || !obj.has("name")) continue

            val resolved = resolveQwikObject(obj, objs, cache)
            val urlPath = asString(resolved.opt("urlPath")) ?: continue
            if (!urlPath.startsWith("/title/")) continue
            rawCount++
            if (!obj.has("urlCover600") && !obj.has("urlCover300") && !obj.has("urlCover")) continue
            val title = (asString(resolved.opt("name")) ?: continue).cleanTitleIfNeeded()
            val cover = firstNonBlank(
                asString(resolved.opt("urlCover600")),
                asString(resolved.opt("urlCover300")),
                asString(resolved.opt("urlCover")),
            )

            val manga = SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(normalizeMangaUrl(stripTitleSlug(urlPath)))
                thumbnail_url = absoluteUrlOrNull(cover)
            }
            mangaList.add(manga)
        }

        val pagingInfo = findPagingInfo(objs, cache, page)
        val pageSize = findPageSize(objs, cache, page)
        val deduped = mangaList.distinctBy { it.url }
        val hasNextPage = when {
            pagingInfo?.next != null -> pagingInfo.next > 0
            pagingInfo?.page != null && pagingInfo.pages != null -> pagingInfo.page < pagingInfo.pages
            pageSize != null -> rawCount >= pageSize
            else -> deduped.isNotEmpty()
        }
        return MangasPage(deduped, hasNextPage)
    }

    private fun queryIdParseV4X(response: Response): MangasPage {
        val manga = mangaDetailsParseV4X(response.asJsoup())
        manga.setUrlWithoutDomain(normalizeMangaUrl(stripTitleSlug(response.request.url.encodedPath)))
        return MangasPage(listOf(manga), false)
    }

    private fun mangaDetailsParseV4X(document: Document): SManga {
        val details = SManga.create()
        val objs = parseQwikObjs(document)
        val cache = mutableMapOf<Int, Any?>()
        val resolved = objs?.let { findComicDetails(it, cache) }

        val removedParts = mutableListOf<String>()
        var cleanedTitle = ""

        val h3LinkTitle = document.selectFirst("h3 a.link.link-hover, h3 a.link-hover, h3 a")?.text()?.trim()
        val resolvedName = resolved?.let { asString(it.opt("name")) }
        val originalTitle = when (getVersion()) {
            "V3X" -> h3LinkTitle ?: resolvedName ?: ""
            else -> resolvedName ?: h3LinkTitle ?: ""
        }

        if (originalTitle.isNotBlank()) {
            cleanedTitle = originalTitle
        }

        fun removeAndCollect(regex: Regex) {
            regex.findAll(cleanedTitle).forEach { removedParts.add(it.value.trim()) }
            cleanedTitle = cleanedTitle.replace(regex, "")
        }

        customRemoveTitle().takeIf { it.isNotEmpty() }?.let { removeAndCollect(Regex(it, RegexOption.IGNORE_CASE)) }
        if (isRemoveTitleVersion()) removeAndCollect(titleRegex)
        cleanedTitle = cleanedTitle.trim()
        details.title = cleanedTitle

        val authorsList = resolved?.let { extractStringList(it.opt("authors")) } ?: emptyList()
        if (authorsList.isNotEmpty()) details.author = authorsList.joinToString()

        val artistsList = resolved?.let { extractStringList(it.opt("artists")) } ?: emptyList()
        if (artistsList.isNotEmpty()) details.artist = artistsList.joinToString()

        val genreList = document.select("div.flex.items-center.flex-wrap > span span.font-bold")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        if (genreList.isNotEmpty()) {
            details.genre = genreList.joinToString()
        } else if (resolved != null) {
            val genresFromJson = extractStringList(resolved.opt("genres"))
            if (genresFromJson.isNotEmpty()) details.genre = genresFromJson.joinToString()
        }

        val statusText = document.select("span.font-bold.uppercase")
            .map { it.text().trim() }
            .firstOrNull { it.equals("Ongoing", true) || it.equals("Completed", true) || it.equals("Hiatus", true) || it.equals("Cancelled", true) }
            ?: asString(resolved?.opt("statusName"))
            ?: asString(resolved?.opt("status"))
        details.status = parseStatusV4X(statusText)

        details.thumbnail_url = run {
            if (resolved != null) {
                firstNonBlank(
                    asString(resolved.opt("urlCover600")),
                    asString(resolved.opt("urlCover300")),
                    asString(resolved.opt("urlCover")),
                )
            } else {
                null
            }
        }?.let { absoluteUrlOrNull(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        if (details.title.isBlank()) {
            val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
            details.title = (ogTitle ?: document.selectFirst("title")?.text()?.substringBefore("|")?.trim().orEmpty())
                .cleanTitleIfNeeded()
        }

        val description = buildString {
            document.selectFirst("div.limit-html.prose .limit-html-p")
                ?.also {
                    append("\n\n----\n#### **Summary**\n${it.wholeText()}")
                }

            document.select("div.mt-5.space-y-3").firstOrNull {
                it.selectFirst("b.text-lg.font-bold")?.text()?.contains("Extra info", ignoreCase = true) == true
            }?.selectFirst("div.limit-html.prose .limit-html-p")
                ?.also {
                    append("\n\n----\n#### **Extra Info**\n${it.wholeText()}")
                }
            document.select(".alert-outline.alert-outline-warning").lastOrNull()
                ?.also {
                    append("\n\n${it.text()}")
                }
            val altTitles = document
                .select("div.mt-1.text-xs.md\\:text-base.opacity-80 span:not(.text-sm)")
                .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
                .distinct()
            if (altTitles.isNotEmpty()) {
                append("\n\n----\n#### **Alternative Titles**\n")
                append(altTitles.joinToString("\n- ", prefix = "- "))
            }
            if (removedParts.isNotEmpty()) {
                append("\n\n----\n#### **Removed From Title**\n")
                removedParts.forEach { append("- `$it`\n") }
            }
        }.trim().let { desc -> autoMarkdownLinks(desc) }

        details.description = description
        details.initialized = true
        return details
    }

    private fun parsePageListV4X(document: Document): List<Page> {
        val objs = parseQwikObjs(document) ?: return emptyList()
        val urls = mutableListOf<String>()

        for (i in 0 until objs.length()) {
            val value = objs.opt(i) as? String ?: continue
            if (!isPageImageUrl(value)) continue
            urls.add(normalizeImageUrl(absoluteUrl(value)))
        }

        return urls.distinct().mapIndexed { i, url -> Page(i, "", url) }
    }

    private fun parseQwikObjs(document: Document): JSONArray? {
        val scripts = document.select("script[type=qwik/json]")
        for (script in scripts) {
            val jsonText = script.html().trim()
            if (jsonText.isEmpty()) continue
            val root = try {
                JSONObject(jsonText)
            } catch (_: Exception) {
                continue
            }
            val objs = root.optJSONArray("objs")
            if (objs != null) return objs
        }
        return null
    }

    private fun resolveQwikObject(
        obj: JSONObject,
        objs: JSONArray,
        cache: MutableMap<Int, Any?>,
        visited: MutableSet<Int> = mutableSetOf(),
    ): JSONObject {
        val resolved = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = resolveQwikValue(obj.opt(key), objs, cache, visited)
            resolved.put(key, value ?: JSONObject.NULL)
        }
        return resolved
    }

    private fun resolveQwikValue(
        value: Any?,
        objs: JSONArray,
        cache: MutableMap<Int, Any?>,
        visited: MutableSet<Int> = mutableSetOf(),
    ): Any? {
        if (value == null || value === JSONObject.NULL) return null
        if (value is String) {
            val index = value.toIntOrNull(36)
            if (index != null && index in 0 until objs.length()) {
                if (cache.containsKey(index)) return cache[index]
                if (!visited.add(index)) {
                    return null
                }
                val resolved = resolveQwikValue(objs.opt(index), objs, cache, visited)
                visited.remove(index)
                cache[index] = resolved
                return resolved
            }
            return value
        }
        if (value is JSONObject) {
            return resolveQwikObject(value, objs, cache, visited)
        }
        if (value is JSONArray) {
            val resolved = JSONArray()
            for (i in 0 until value.length()) {
                val item = resolveQwikValue(value.opt(i), objs, cache, visited)
                resolved.put(item ?: JSONObject.NULL)
            }
            return resolved
        }
        return value
    }

    private fun findComicDetails(objs: JSONArray, cache: MutableMap<Int, Any?>): JSONObject? {
        for (i in 0 until objs.length()) {
            val obj = objs.optJSONObject(i) ?: continue
            if (!obj.has("urlPath") || !obj.has("name")) continue

            val resolved = resolveQwikObject(obj, objs, cache)
            val urlPath = asString(resolved.opt("urlPath")) ?: continue
            if (!urlPath.startsWith("/title/")) continue
            if (resolved.has("urlCover600") || resolved.has("urlCover300") || resolved.has("urlCover")) {
                return resolved
            }
        }
        return null
    }

    private fun extractStringList(value: Any?): List<String> {
        return when (value) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> {
                val results = mutableListOf<String>()
                for (i in 0 until value.length()) {
                    val item = extractString(value.opt(i))
                    if (item != null) results.add(item)
                }
                results
            }
            else -> extractString(value)?.let { listOf(it) } ?: emptyList()
        }
    }

    private fun extractString(value: Any?): String? {
        return when (value) {
            null, JSONObject.NULL -> null
            is String -> value
            is JSONObject -> asString(value.opt("name"))
            else -> null
        }
    }

    private fun asString(value: Any?): String? {
        return when (value) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> null
        }
    }

    private fun asLong(value: Any?): Long? {
        return when (value) {
            null, JSONObject.NULL -> null
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        for (value in values) {
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun findPageSize(objs: JSONArray, cache: MutableMap<Int, Any?>, page: Int): Int? {
        for (i in 0 until objs.length()) {
            val obj = objs.optJSONObject(i) ?: continue
            if (!obj.has("page") || !obj.has("size")) continue
            val resolved = resolveQwikObject(obj, objs, cache)
            val size = asLong(resolved.opt("size"))?.toInt()
            val pageValue = asLong(resolved.opt("page"))?.toInt()
            if (size != null && (pageValue == null || pageValue == page)) {
                return size
            }
        }
        return null
    }

    private fun findPagingInfo(objs: JSONArray, cache: MutableMap<Int, Any?>, page: Int): PagingInfo? {
        for (i in 0 until objs.length()) {
            val obj = objs.optJSONObject(i) ?: continue
            val resolved = resolveQwikObject(obj, objs, cache)
            val pagingObj = when {
                resolved.has("paging") -> resolved.optJSONObject("paging")
                resolved.has("pages") || resolved.has("total") -> resolved
                else -> null
            } ?: continue

            val pageValue = asLong(pagingObj.opt("page"))?.toInt()
            val pages = asLong(pagingObj.opt("pages"))?.toInt()
            val next = asLong(pagingObj.opt("next"))?.toInt()
            if (pageValue != null && pages != null && pageValue == page) {
                return PagingInfo(pageValue, pages, next)
            }
            if (pageValue == null && pages != null) {
                return PagingInfo(pageValue, pages, next)
            }
        }
        return null
    }

    private fun parseStatusV4X(status: String?): Int {
        val normalized = status.orEmpty()
        return when {
            normalized.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
            normalized.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
            normalized.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            normalized.contains("Cancelled", ignoreCase = true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun pageFromResponseV4X(response: Response): Int {
        return response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
    }

    private fun isPageImageUrl(url: String): Boolean {
        val marker = "/media/"
        val idx = url.indexOf(marker)
        if (idx < 0) return false
        val after = url.substring(idx + marker.length)
        return after.startsWith("mbch/") || after.firstOrNull()?.isDigit() == true
    }

    private fun withLangV4X(url: String): String {
        val langValue = when (getVersion()) {
            "V4X" -> encodedSiteLangV4X
            "V3X" -> encodedSiteLangV3X ?: encodedSiteLangV4X
            else -> encodedSiteLangV4X
        } ?: return url
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}lang=$langValue"
    }

    private fun absoluteUrl(url: String): String =
        if (url.startsWith("http")) url else "$baseUrl$url"

    private fun normalizeImageUrl(url: String): String {
        return when {
            url.startsWith("https://k") -> "https://n" + url.removePrefix("https://k")
            url.startsWith("http://k") -> "http://n" + url.removePrefix("http://k")
            else -> url
        }
    }

    private fun absoluteUrlOrNull(url: String?): String? =
        if (url.isNullOrBlank()) null else absoluteUrl(url)

    private fun extractPath(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("http")) {
            return runCatching { trimmed.toHttpUrl().encodedPath }.getOrNull()
        }
        return trimmed.substringBefore('?').substringBefore('#')
    }

    private fun applyFiltersV4X(urlBuilder: okhttp3.HttpUrl.Builder, filters: FilterList) {
        val includeGenres = mutableListOf<String>()
        val excludeGenres = mutableListOf<String>()
        var selectedLangs: List<String> = emptyList()
        var selectedOrigins: List<String> = emptyList()
        var status: String? = null
        var minChapter = ""
        var maxChapter = ""
        var sortBy: String? = null
        var sortOrder: String? = null

        filters.forEach { filter ->
            when (filter) {
                is GenreGroupFilter -> {
                    includeGenres.addAll(filter.included)
                    excludeGenres.addAll(filter.excluded)
                }
                is LangGroupFilter -> selectedLangs = filter.selected
                is OriginGroupFilter -> selectedOrigins = filter.selected
                is StatusFilter -> {
                    val value = filter.selected
                    if (value.isNotBlank()) status = value
                }
                is SortFilter -> {
                    if (filter.state != null) {
                        val sort = getSortFilter()[filter.state!!.index]
                        sortBy = when (sort.value) {
                            "title" -> "field_name"
                            "update" -> "field_update"
                            "create" -> "field_create"
                            "views_a" -> "field_views"
                            "views_y" -> "field_views_y"
                            "views_m" -> "field_views_m"
                            "views_w" -> "field_views_w"
                            "views_d" -> "field_views_d"
                            "views_h" -> "field_views_h"
                            else -> "field_update"
                        }
                        sortOrder = if (filter.state!!.ascending) "asc" else "desc"
                    }
                }
                is MinChapterTextFilter -> minChapter = filter.state
                is MaxChapterTextFilter -> maxChapter = filter.state
                else -> Unit
            }
        }

        val langParam = when {
            selectedLangs.isNotEmpty() -> selectedLangs.joinToString(",")
            siteLang.isNotBlank() -> siteLang
            else -> null
        }
        if (langParam != null) {
            urlBuilder.addQueryParameter("lang", langParam)
        }
        if (selectedOrigins.isNotEmpty()) {
            urlBuilder.addQueryParameter("orig", selectedOrigins.joinToString(","))
        }
        if (includeGenres.isNotEmpty() || excludeGenres.isNotEmpty()) {
            val value = buildString {
                append(includeGenres.joinToString(","))
                if (excludeGenres.isNotEmpty()) {
                    append("|")
                    append(excludeGenres.joinToString(","))
                }
            }
            urlBuilder.addQueryParameter("genres", value)
        }
        if (status != null) {
            urlBuilder.addQueryParameter("status", status)
        }
        if (maxChapter.isNotEmpty() || minChapter.isNotEmpty()) {
            urlBuilder.addQueryParameter("chapters", "$minChapter-$maxChapter")
        }

        if (sortBy != null) {
            urlBuilder.addQueryParameter("sortby", sortBy)
            if (sortOrder != null) {
                urlBuilder.addQueryParameter("order", sortOrder)
            }
        }
    }

    override fun getFilterList(): FilterList {
        return FilterList(
            LetterFilter(getLetterFilter(), 0),
            Filter.Separator(),
            Filter.Header("NOTE: Ignored if using text search! "),
            Filter.Separator(),
            SortFilter(getSortFilter().map { it.name }.toTypedArray()),
            StatusFilter(getStatusFilter(), 0),
            GenreGroupFilter(getGenreFilter()),
            OriginGroupFilter(getOrginFilter()),
            LangGroupFilter(getLangFilter()),
            MinChapterTextFilter(),
            MaxChapterTextFilter(),
            Filter.Separator(),
            Filter.Header("NOTE: Filters below are incompatible with any other filters! "),
            Filter.Header("NOTE: Login Required!"),
            Filter.Separator(),
            UtilsFilter(getUtilsFilter(), 0),
            HistoryFilter(getHistoryFilter(), 0),
        )
    }

    private fun String.removeEntities(): String = Parser.unescapeEntities(this, true)

    private fun String.cleanTitleIfNeeded(): String {
        var tempTitle = this
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

    private fun imageFallbackInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.isSuccessful) return response

        val urlString = request.url.toString()
        response.close()

        if (SERVER_PATTERN.containsMatchIn(urlString)) {
            val regex = Regex("""https://([kn])(\d{2})""")
            val match = regex.find(urlString)
            if (match != null) {
                val (currentLetter, number) = match.destructured
                val fallbackLetter = if (currentLetter == "k") "n" else "k"
                val newUrl = urlString.replaceFirst(regex, "https://$fallbackLetter$number")
                if (newUrl != urlString) {
                    val newRequest = request.newBuilder()
                        .url(newUrl)
                        .build()
                    try {
                        val newResponse = chain
                            .withConnectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                            .withReadTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .proceed(newRequest)
                        if (newResponse.isSuccessful) {
                            return newResponse
                        }
                        newResponse.close()
                    } catch (_: Exception) {
                    }
                }
            }

            val servers = listOf(
                "n01", "n03", "n04", "n00", "n05", "n06", "n07", "n08", "n09", "n10", "n02", "n11",
                "k05", "k07",
                "k01", "k03", "k04", "k00", "k06", "k08", "k09", "k10", "k02", "k11",
            )

            for (server in servers) {
                if (urlString.contains("https://$server")) continue
                val newUrl = urlString.replace(SERVER_PATTERN, "https://$server")
                if (newUrl == urlString) continue

                val newRequest = request.newBuilder()
                    .url(newUrl)
                    .build()

                try {
                    val newResponse = chain
                        .withConnectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .withReadTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .proceed(newRequest)

                    if (newResponse.isSuccessful) {
                        return newResponse
                    }
                    newResponse.close()
                } catch (_: Exception) {
                }
            }
        }

        return chain.proceed(request)
    }

    private fun stripTitleSlug(url: String): String {
        val regex = Regex("/title/(\\d+)(-[^/]*)?")
        return url.replace(regex, "/title/$1")
    }

    private fun stripChapterSlug(url: String): String {
        val regex = Regex("/title/(\\d+)(-[^/]*)?/(\\d+)(-[^/]*)?")
        return url.replace(regex, "/title/$1/$3")
    }

    private fun stripSeriesUrl(url: String): String {
        return url.substringBefore("?").substringBeforeLast("-")
    }

    private fun getVersion(): String {
        return preferences.getString("${VERSION_PREF_KEY}_$lang", VERSION_PREF_DEFAULT_VALUE) ?: VERSION_PREF_DEFAULT_VALUE
    }

    private fun getAltChapterListPref(): Boolean {
        return preferences.getBoolean("${ALT_CHAPTER_LIST_PREF_KEY}_$lang", ALT_CHAPTER_LIST_PREF_DEFAULT_VALUE)
    }

    private fun isRemoveTitleVersion(): Boolean {
        return preferences.getBoolean("${REMOVE_TITLE_VERSION_PREF}_$lang", false)
    }

    private fun customRemoveTitle(): String {
        return preferences.getString("${REMOVE_TITLE_CUSTOM_PREF}_$lang", "")!!
    }

    private fun SharedPreferences.migrateMirrorPref() {
        val selectedMirrorV2X = getString("${MIRROR_PREF_KEY_V2X}_$lang", MIRROR_PREF_DEFAULT_VALUE_V2X)
        if (selectedMirrorV2X in DEPRECATED_MIRRORS) {
            edit().putString("${MIRROR_PREF_KEY_V2X}_$lang", MIRROR_PREF_DEFAULT_VALUE_V2X).apply()
        }

        val selectedMirrorV4X = getString("${MIRROR_PREF_KEY_V4X}_$lang", MIRROR_PREF_DEFAULT_VALUE_V4X)
        if (selectedMirrorV4X in DEPRECATED_MIRRORS) {
            edit().putString("${MIRROR_PREF_KEY_V4X}_$lang", MIRROR_PREF_DEFAULT_VALUE_V4X).apply()
        }
    }

    private companion object {

        private const val TAG = "BatoTo"

        private const val VERSION_PREF_KEY = "VERSION"
        private const val VERSION_PREF_TITLE = "Site Version"
        private val VERSION_PREF_ENTRIES = arrayOf("V2X", "V3X", "V4X")
        private val VERSION_PREF_ENTRY_VALUES = arrayOf("V2X", "V3X", "V4X")
        private const val VERSION_PREF_DEFAULT_VALUE = "V2X"

        private const val MIRROR_PREF_KEY_V2X = "MIRROR_V2X"
        private const val MIRROR_PREF_TITLE_V2X = "Mirror (V2X)"
        private val MIRROR_PREF_ENTRIES_V2X = arrayOf(
            "Auto",
            // https://batotomirrors.pages.dev/
            "ato.to",
            "dto.to",
            "fto.to",
            "hto.to",
            "jto.to",
            "lto.to",
            "mto.to",
            "nto.to",
            "vto.to",
            "wto.to",
            "xto.to",
            "yto.to",
            "vba.to",
            "wba.to",
            "xba.to",
            "yba.to",
            "zba.to",
            "bato.ac",
            "bato.bz",
            "bato.cc",
            "bato.cx",
            "bato.id",
            "bato.pw",
            "bato.sh",
            "bato.to",
            "bato.vc",
            "bato.day",
            "bato.red",
            "bato.run",
            "batoto.in",
            "batoto.tv",
            "batotoo.com",
            "batotwo.com",
            "batpub.com",
            "batread.com",
            "battwo.com",
            "xbato.com",
            "xbato.net",
            "xbato.org",
            "zbato.com",
            "zbato.net",
            "zbato.org",
            "comiko.net",
            "comiko.org",
            "mangatoto.com",
            "mangatoto.net",
            "mangatoto.org",
            "batocomic.com",
            "batocomic.net",
            "batocomic.org",
            "readtoto.com",
            "readtoto.net",
            "readtoto.org",
            "kuku.to",
            "okok.to",
            "ruru.to",
            "xdxd.to",
        )

        private val MIRROR_PREF_ENTRY_VALUES_V2X = MIRROR_PREF_ENTRIES_V2X.mapIndexed { index, entry ->
            if (index == 0) "automatic" else "https://$entry"
        }.toTypedArray()
        private const val MIRROR_PREF_DEFAULT_VALUE_V2X = "automatic"

        private val DEPRECATED_MIRRORS = listOf(
            "https://batocc.com", // parked
        )

        private const val MIRROR_PREF_KEY_V4X = "MIRROR_V4X"
        private const val MIRROR_PREF_TITLE_V4X = "Mirror (V4X)"
        private val MIRROR_PREF_ENTRIES_V4X = arrayOf("bato.si", "bato.ing")
        private val MIRROR_PREF_ENTRY_VALUES_V4X = MIRROR_PREF_ENTRIES_V4X.map { "https://$it" }.toTypedArray()
        private val MIRROR_PREF_DEFAULT_VALUE_V4X = MIRROR_PREF_ENTRY_VALUES_V4X[0]

        private const val ALT_CHAPTER_LIST_PREF_KEY = "ALT_CHAPTER_LIST"
        private const val ALT_CHAPTER_LIST_PREF_TITLE = "Alternative Chapter List"
        private const val ALT_CHAPTER_LIST_PREF_SUMMARY = "If checked, uses an alternate chapter list"
        private const val ALT_CHAPTER_LIST_PREF_DEFAULT_VALUE = false

        private const val REMOVE_TITLE_VERSION_PREF = "REMOVE_TITLE_VERSION"
        private const val REMOVE_TITLE_CUSTOM_PREF = "REMOVE_TITLE_CUSTOM"

        private val titleRegex: Regex =
            Regex("\\([^()]*\\)|\\{[^{}]*\\}|\\[(?:(?!]).)*]|«[^»]*»|〘[^〙]*〙|「[^」]*」|『[^』]*』|≪[^≫]*≫|﹛[^﹜]*﹜|〖[^〖〗]*〗|\uD81A\uDD0D.+?\uD81A\uDD0D|《[^》]*》|⌜.+?⌝|⟨[^⟩]*⟩|【[^】]*】|([|].*)|([/].*)|([~].*)|-[^-]*-|‹[^›]*›|/Official|/ Official", RegexOption.IGNORE_CASE)

        private val seriesIdRegex = Regex("/(?:series|title)/(\\d+)")
        private const val ID_PREFIX = "ID:"
        private val SERVER_PATTERN = Regex("""https://[kn]\d{2}""")
    }
}

private data class PagingInfo(val page: Int?, val pages: Int?, val next: Int?)
