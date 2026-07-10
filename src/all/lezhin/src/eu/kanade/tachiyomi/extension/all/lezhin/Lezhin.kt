package eu.kanade.tachiyomi.extension.all.lezhin

import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

private const val LOG_TAG = "Lezhin"

abstract class Lezhin(
    sourceLang: String,
    baseUrlParam: String,
    uiName: String,
) : HttpSource(),
    ConfigurableSource {

    override val name: String = uiName
    override val baseUrl: String = baseUrlParam.removeSuffix("/")
    override val lang: String = sourceLang
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(Interceptor(::authInterceptor))
        .addNetworkInterceptor(Interceptor(::imageDescrambler))
        .build()

    protected abstract val pathSegment: String
    protected abstract val siteLocale: String

    private val apiBase: String = "${baseUrl.trimEnd('/')}/lz-api/v2/"

    private val preferences: SharedPreferences by getPreferencesLazy()

    private companion object {
        const val PREF_EMAIL = "lezhin_email"
        const val PREF_PASSWORD = "lezhin_password"
        const val PREF_HIDE_PREMIUM = "lezhin_hide_locked"
        const val PREF_TOKEN = "lezhin_token"
        const val PREF_TOKEN_EXPIRES = "lezhin_token_expires"
        const val PREF_USER_ID = "lezhin_user_id"

        // HTML extraction retry timestamp and interval
        const val PREF_HTML_TRIED_TS = "lezhin_html_tried_ts"
        const val HTML_TRY_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
    }

    @Volatile
    private var hidePremium: Boolean = preferences.getBoolean(PREF_HIDE_PREMIUM, false)

    private val tokenManager = TokenManager()

    // cached parsed divisions (tags) for the lifetime of the source instance
    private var cachedDivisions: List<LezhinDivision>? = null

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        EditTextPreference(context).apply {
            key = PREF_EMAIL
            title = "Email"
            summary = "Email for automatic login (optional)"
            setDefaultValue("")
            dialogTitle = "Lezhin email"
            setOnPreferenceChangeListener { _, newValue ->
                Log.d(LOG_TAG, "Preference change: email updated (present=${(newValue as? String).isNullOrBlank().not()}) - clearing tokens")
                tokenManager.clearTokens()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(context).apply {
            key = PREF_PASSWORD
            title = "Password"
            summary = "Password for automatic login (optional)"
            setDefaultValue("")
            dialogTitle = "Lezhin password"
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, _ ->
                Log.d(LOG_TAG, "Preference change: password updated - clearing tokens")
                tokenManager.clearTokens()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(context).apply {
            key = PREF_HIDE_PREMIUM
            title = "Hide premium (locked) chapters"
            summary = "When enabled, premium (unbought) chapters are hidden from the chapter list. Default: off"
            setDefaultValue(false)
            hidePremium = preferences.getBoolean(PREF_HIDE_PREMIUM, false)
            setOnPreferenceChangeListener { _, newValue ->
                hidePremium = (newValue as? Boolean) ?: false
                Log.d(LOG_TAG, "Preference change: hidePremium = $hidePremium")
                true
            }
        }.also(screen::addPreference)

        tokenManager.initializeIfNeeded()
    }

    override fun popularMangaRequest(page: Int): Request {
        val per = 500
        val offset = (page - 1) * per
        val url = "${apiBase}contents?menu=general&limit=$per&offset=$offset&order=popular"
        Log.d(LOG_TAG, "popularMangaRequest(page=$page) -> $url")
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun popularMangaParse(response: Response): MangasPage {
        Log.d(LOG_TAG, "popularMangaParse -> ${response.request.url}")
        val text = response.body.string()
        Log.v(LOG_TAG, "popularMangaParse: response preview=${text.take(200)}")
        val root = JSONObject(text)
        val list = mutableListOf<SManga>()
        val data = root.optJSONArray("data") ?: JSONArray()
        for (i in 0 until data.length()) {
            val it = data.getJSONObject(i)
            val id = it.optLong("id")
            val alias = it.optString("alias")
            val title = it.optString("title").trim()

            // parse artists array to map roles
            val artistsArr = it.optJSONArray("artists") ?: JSONArray()
            val authors = mutableListOf<String>()
            val painters = mutableListOf<String>()
            for (j in 0 until artistsArr.length()) {
                val a = artistsArr.optJSONObject(j) ?: continue
                val role = a.optString("role").lowercase().trim()
                val name = a.optString("name").trim()
                if (name.isEmpty()) continue
                when (role) {
                    "scripter", "original", "writer" -> authors.add(name)
                    "painter", "artist" -> painters.add(name)
                    else -> {
                        // ignore other roles
                    }
                }
            }

            // build thumbnail url using known pattern (always tall)
            val thumbnailUrl = "https://ccdn.lezhin.com/v2/comics/$id/images/tall.jpg"

            val manga = SManga.create().apply {
                url = "/$pathSegment/comic/$alias"
                this.title = title
                if (authors.isNotEmpty()) author = authors.joinToString(", ")
                if (painters.isNotEmpty()) artist = painters.joinToString(", ")
                thumbnail_url = thumbnailUrl
            }
            Log.v(LOG_TAG, "popularMangaParse: item[$i] id=$id alias=$alias title=$title authors=${authors.joinToString()} artists=${painters.joinToString()} thumbnail=$thumbnailUrl")
            list.add(manga)
        }
        val hasNext = root.optBoolean("hasNext", false)
        Log.d(LOG_TAG, "popularMangaParse -> items=${list.size}, hasNext=$hasNext")
        return MangasPage(list, hasNext)
    }

    // SEARCH: use multitags endpoint with int_id when available; try mapping names -> ids
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val perPage = 30
        val offset = (page - 1) * perPage

        // Collect selected int_ids (real tag ids >= 0)
        val selectedIds = selectedTagIds(filters).toMutableList()

        // Collect selected tag NAMES when int_id is not available (synthetic negative ids)
        val selectedTagNames = mutableListOf<String>()
        filters.ifEmpty { getFilterList() }.forEach { f ->
            if (f is Filter.Group<*>) {
                @Suppress("UNCHECKED_CAST")
                val group = f as Filter.Group<LezhinTagFilter>
                group.state.forEach { tf ->
                    if (tf.state && tf.tagId < 0L) {
                        selectedTagNames.add(tf.name)
                    }
                }
            }
        }

        val hasQuery = query.isNotBlank()

        // If there's no query and no tags selected, fall back to popular endpoint to avoid BAD_REQUEST
        if (!hasQuery && selectedIds.isEmpty() && selectedTagNames.isEmpty()) {
            Log.d(LOG_TAG, "searchMangaRequest: no query and no tag filters selected -> falling back to popularMangaRequest")
            return popularMangaRequest(page)
        }

        // If no int_ids but there are selected names, attempt mapping from cachedDivisions ->
        // tagId using parsed divisions (try to populate if null).
        if (selectedIds.isEmpty() && selectedTagNames.isNotEmpty()) {
            try {
                if (cachedDivisions == null) {
                    // Trigger fetch & parse of divisions (this also caches them)
                    getFilterList()
                }
                val divisions = cachedDivisions
                if (!divisions.isNullOrEmpty()) {
                    val mapped = mutableListOf<Long>()
                    val lowerToId = divisions.flatMap { it.tags }
                        .associateBy { it.name.lowercase() }
                    val lowerToTagKey = divisions.flatMap { it.tags }
                        .associateBy { it.tag.lowercase() }

                    for (name in selectedTagNames) {
                        val l = name.lowercase()
                        lowerToId[l]?.let {
                            mapped.add(it.tagId)
                            continue
                        }
                        lowerToTagKey[l]?.let {
                            mapped.add(it.tagId)
                            continue
                        }
                        // also try contains (loose match)
                        divisions.forEach { div ->
                            div.tags.forEach { t ->
                                if (t.name.equals(name, true) || t.tag.equals(name, true) || t.name.lowercase().contains(l) || t.tag.lowercase().contains(l)) {
                                    mapped.add(t.tagId)
                                }
                            }
                        }
                    }

                    if (mapped.isNotEmpty()) {
                        // dedupe and use mapped ids
                        val dedup = mapped.distinct()
                        selectedIds.addAll(dedup)
                        Log.d(LOG_TAG, "searchMangaRequest: mapped selectedTagNames -> int_ids=${dedup.joinToString(",")}")
                    } else {
                        Log.v(LOG_TAG, "searchMangaRequest: mapping selectedTagNames -> int_ids yielded none")
                    }
                } else {
                    Log.v(LOG_TAG, "searchMangaRequest: no cachedDivisions available for mapping")
                }
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "searchMangaRequest: error mapping tag names to ids", e)
            }
        }

        // If we have real int_ids (either directly selected or mapped), use the multitags endpoint (preferred)
        if (selectedIds.isNotEmpty()) {
            val idsCsv = selectedIds.joinToString(",")
            val encodedIds = URLEncoder.encode(idsCsv, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/lz-api/v2/advanced-search/multitags" +
                "?int_id=$encodedIds&ext_id=&filter=exact_match&order=relevant&tab=general" +
                "&limit=$perPage&offset=$offset"
            Log.d(LOG_TAG, "searchMangaRequest (multitags) -> $url (int_id_csv=$idsCsv)")
            return Request.Builder().url(url).headers(defaultHeaders()).get().build()
        }

        // No real ids -> fallback to advanced-search:
        // - if query present, include it
        // - if only synthetic names present, use &tags=<CSV> (best-effort)
        val base = "${baseUrl.trimEnd('/')}/lz-api/v2/advanced-search"
        val qParam = if (hasQuery) URLEncoder.encode(query, "UTF-8") else ""
        val sb = StringBuilder()
        sb.append("$base?q=$qParam&t=all&order=popular&offset=$offset&limit=$perPage")

        if (selectedTagNames.isNotEmpty()) {
            val tagParam = URLEncoder.encode(selectedTagNames.joinToString(","), "UTF-8")
            sb.append("&tags=").append(tagParam)
        }

        val url = sb.toString()
        Log.d(LOG_TAG, "searchMangaRequest (advanced-search) -> $url (queryPresent=$hasQuery, tagNames=${selectedTagNames.size})")
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        Log.d(LOG_TAG, "searchMangaParse -> ${response.request.url}")
        val text = response.body.string()
        Log.v(LOG_TAG, "searchMangaParse: response preview=${text.take(200)}")
        val root = JSONObject(text)
        val list = mutableListOf<SManga>()
        val data = root.optJSONArray("data") ?: JSONArray()
        for (i in 0 until data.length()) {
            val it = data.getJSONObject(i)
            val id = it.optLong("id")
            val alias = it.optString("alias")
            val title = it.optString("title").trim()

            val artistsArr = it.optJSONArray("artists") ?: JSONArray()
            val authors = mutableListOf<String>()
            val painters = mutableListOf<String>()
            for (j in 0 until artistsArr.length()) {
                val a = artistsArr.optJSONObject(j) ?: continue
                val role = a.optString("role").lowercase().trim()
                val name = a.optString("name").trim()
                if (name.isEmpty()) continue
                when (role) {
                    "scripter", "original", "writer" -> authors.add(name)
                    "painter", "artist" -> painters.add(name)
                    else -> { /* ignore */ }
                }
            }

            val thumbnailUrl = "https://ccdn.lezhin.com/v2/comics/$id/images/tall.jpg"

            val manga = SManga.create().apply {
                url = "/$pathSegment/comic/$alias"
                this.title = title
                if (authors.isNotEmpty()) author = authors.joinToString(", ")
                if (painters.isNotEmpty()) artist = painters.joinToString(", ")
                thumbnail_url = thumbnailUrl
            }
            Log.v(LOG_TAG, "searchMangaParse: item[$i] id=$id alias=$alias title=$title authors=${authors.joinToString()} artists=${painters.joinToString()} thumbnail=$thumbnailUrl")
            list.add(manga)
        }
        val hasNext = root.optBoolean("hasNext", false)
        Log.d(LOG_TAG, "searchMangaParse -> items=${list.size}, hasNext=$hasNext")
        return MangasPage(list, hasNext)
    }

    // Provide filters by scraping tags page and parsing embedded JSON with LezhinTagsParser
    override fun getFilterList(): FilterList {
        try {
            cachedDivisions?.let { divisions ->
                Log.v(LOG_TAG, "getFilterList: using cached divisions (${divisions.size})")
                return divisionsToFilterList(divisions)
            }

            val tagsUrl = "${baseUrl.trimEnd('/')}/$pathSegment/tags?filter=exact_match&order=relevant&tab=general"
            Log.d(LOG_TAG, "getFilterList: fetching tags page -> $tagsUrl")
            val req = Request.Builder().url(tagsUrl).headers(defaultHeaders()).get().build()
            val res = client.newCall(req).execute()
            val html = res.body?.string().orEmpty()
            val divisions = LezhinTagsParser.parseDivisionsFromHtml(html)
            if (divisions.isNotEmpty()) {
                cachedDivisions = divisions
                return divisionsToFilterList(divisions)
            }
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "getFilterList: failed to fetch/parse tags", e)
        }

        // fallback: empty with header
        return FilterList(Filter.Header("Text search is ignored for Lezhin; use tag checkboxes"))
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "${baseUrl.trimEnd('/')}${manga.url}"
        Log.d(LOG_TAG, "mangaDetailsRequest(${manga.url}) -> $url")
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun mangaDetailsParse(response: Response): SManga {
        Log.d(LOG_TAG, "mangaDetailsParse -> ${response.request.url}")
        val body = response.body.string()
        Log.v(LOG_TAG, "mangaDetailsParse: response preview=${body.take(200)}")
        val doc = Jsoup.parse(body)

        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title()
        val description = doc.selectFirst("meta[name=description]")?.attr("content") ?: doc.selectFirst("p.summary")?.text() ?: ""

        // 1) Prefer explicit picture/img tall cover if present (this avoids wide.jpg banner overwrite)
        val coverImgCandidates = mutableListOf<String>()
        // picture img
        doc.select("picture img[src]").forEach { coverImgCandidates.add(it.attr("src")) }
        // image inside known cover container
        doc.select("div.episodeListCover__yMRVY img[src], div.comicEpisodeList__cover__tu49G img[src]").forEach { coverImgCandidates.add(it.attr("src")) }
        // fallback og:image
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { coverImgCandidates.add(it) }

        // choose the first candidate that contains "/images/tall" or endsWith tall.(jpg|webp)
        val chosenCover = coverImgCandidates.firstOrNull {
            it.contains("/images/tall", ignoreCase = true) ||
                it.endsWith("tall.jpg", true) ||
                it.endsWith("tall.webp", true)
        } ?: coverImgCandidates.firstOrNull()

        // 2) Parse artists/creators block (writer/artist/original/painter/scripter)
        val authorList = mutableListOf<String>()
        val artistList = mutableListOf<String>()
        val originalList = mutableListOf<String>()

        // Known artist block class from HTML snippet: .episodeListDetail__artist__MWexm
        doc.select(".episodeListDetail__artist__MWexm").forEach { node ->
            try {
                val roleLabel = node.selectFirst(".episodeListDetail__artistName__gD_OK")?.text()?.trim()?.lowercase()
                    ?: node.selectFirst("span")?.text()?.trim()?.lowercase()
                val name = node.selectFirst("a")?.text()?.trim() ?: node.ownText()?.trim() ?: ""
                if (name.isBlank()) return@forEach
                when {
                    roleLabel?.contains("writer") == true || roleLabel?.contains("scripter") == true || roleLabel?.contains("script") == true -> authorList.add(name)
                    roleLabel?.contains("painter") == true || roleLabel?.contains("artist") == true -> artistList.add(name)
                    roleLabel?.contains("original") == true || roleLabel?.contains("origin") == true -> originalList.add(name)
                    else -> authorList.add(name)
                }
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "mangaDetailsParse: error parsing artist node", e)
            }
        }

        // 3) Tags: select anchors that point to tags path e.g. /en/tags/...
        val tags = doc.select("a[href*=/tags/]").mapNotNull { it.text()?.trim()?.takeIf { t -> t.isNotEmpty() } }

        // 4) Status detection: check for "NEW" -> ongoing, "Completed" -> completed
        val pageText = doc.text()
        val parsedStatus = when {
            pageText.contains("NEW", ignoreCase = true) -> SManga.ONGOING
            pageText.contains("COMPLETED", ignoreCase = true) || pageText.contains("COMPLETE", ignoreCase = true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        return SManga.create().apply {
            this.title = title
            this.description = description
            // set authors/artists if present
            if (authorList.isNotEmpty()) {
                author = authorList.joinToString(", ")
                Log.v(LOG_TAG, "mangaDetailsParse: authors parsed=$author")
            }
            if (artistList.isNotEmpty()) {
                artist = artistList.joinToString(", ")
                Log.v(LOG_TAG, "mangaDetailsParse: artists parsed=$artist")
            }
            if (originalList.isNotEmpty()) {
                val combinedAuthors = (authorList + originalList).distinct()
                author = combinedAuthors.joinToString(", ")
                Log.v(LOG_TAG, "mangaDetailsParse: originals appended to authors=$author")
            }
            // tags -> genre
            if (tags.isNotEmpty()) {
                genre = tags.joinToString(", ")
                Log.v(LOG_TAG, "mangaDetailsParse: tags parsed=$genre")
            }
            // status
            this.status = parsedStatus
            Log.v(LOG_TAG, "mangaDetailsParse: status parsed=$parsedStatus")

            // set thumbnail: prefer chosenCover (tall), else leave thumbnail unset to preserve list-provided tall
            if (!chosenCover.isNullOrBlank()) {
                // normalize to tall if possible: swap wide -> tall
                val normalized = if (chosenCover.contains("/images/wide", ignoreCase = true)) {
                    chosenCover.replace("/images/wide", "/images/tall")
                } else {
                    chosenCover
                }

                if ((normalized.contains("/images/tall", ignoreCase = true)) ||
                    normalized.endsWith("tall.jpg", true) ||
                    normalized.endsWith("tall.webp", true)
                ) {
                    thumbnail_url = normalized
                    Log.v(LOG_TAG, "mangaDetailsParse: set thumbnail from chosenCover=$normalized")
                } else {
                    // fallback: set only if no thumbnail set yet
                    if (thumbnail_url.isNullOrBlank()) {
                        thumbnail_url = normalized
                        Log.v(LOG_TAG, "mangaDetailsParse: set thumbnail fallback from chosenCover=$normalized")
                    } else {
                        Log.v(LOG_TAG, "mangaDetailsParse: skipped non-tall cover ($normalized) to preserve canonical thumbnail")
                    }
                }
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = "${baseUrl.trimEnd('/')}${manga.url}"
        Log.d(LOG_TAG, "chapterListRequest(${manga.url}) -> $url")
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    // SCRAPING implementation for episode list (uses HTML from the manga page)
    override fun chapterListParse(response: Response): List<SChapter> {
        Log.d(LOG_TAG, "chapterListParse -> ${response.request.url}")
        val body = response.body.string()
        Log.v(LOG_TAG, "chapterListParse: page preview=${body.take(200)}")
        val doc = Jsoup.parse(body)

        // select episode nodes by data-id attribute (robust for the snippet provided)
        val nodes = doc.select("div[data-id]")
        Log.d(LOG_TAG, "chapterListParse: found ${nodes.size} episode nodes via scraping")

        val chapters = mutableListOf<SChapter>()

        for ((idx, node) in nodes.withIndex()) {
            try {
                val a = node.selectFirst("a[href]") ?: run {
                    Log.v(LOG_TAG, "chapterListParse[$idx]: skipping node without anchor")
                    continue
                }
                val href = a.attr("href").trim()
                if (href.isEmpty()) {
                    Log.v(LOG_TAG, "chapterListParse[$idx]: empty href, skipping")
                    continue
                }

                // Title: prefer h3
                val title = a.selectFirst("h3")?.text()?.trim()
                    ?: a.selectFirst(".lzTypography-BodyLg, .lzTypography-BodySm")?.text()?.trim()
                    ?: a.text().trim()

                // Episode number element (usually a <p> with episode number)
                val epNumText = a.selectFirst("p.lzTypography-BodySm")?.text()?.trim()
                    ?: node.selectFirst("p")?.text()?.trim()
                val chapterNumber = epNumText
                    ?.substringAfterLast(' ')
                    ?.filter { it.isDigit() || it == '.' }
                    ?.let { it.toFloatOrNull() }
                    ?: href.substringAfterLast('/', "")
                        .takeIf { it.isNotEmpty() }
                        ?.filter { ch -> ch.isDigit() || ch == '.' }
                        ?.toFloatOrNull()
                    ?: 0f

                // Price / purchase text detection
                val pricePs = a.select("div.flex.items-end.justify-between p")
                val priceText = when {
                    pricePs.size >= 2 -> pricePs[1].text().trim()
                    pricePs.size == 1 -> pricePs[0].text().trim()
                    else -> a.select("p").lastOrNull()?.text()?.trim() ?: ""
                }

                // Determine purchased/free state:
                val purchased = priceText.equals("purchased", ignoreCase = true) || priceText.equals("free", ignoreCase = true)

                val isLocked = !purchased
                if (hidePremium && isLocked) {
                    Log.v(LOG_TAG, "chapterListParse[$idx]: skipping locked episode=$href due to hidePremium")
                    continue
                }

                val lockPrefix = if (isLocked) "🔒 " else ""
                val displayName = lockPrefix + (if (title.isNotBlank()) title else href)

                val chap = SChapter.create().apply {
                    url = href
                    name = displayName
                    chapter_number = chapterNumber
                }

                Log.v(LOG_TAG, "chapterListParse[$idx]: href=$href chapter_number=$chapterNumber purchased=$purchased title=${title.takeIf { it.isNotBlank() }} priceText=$priceText")

                chapters.add(chap)
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "chapterListParse: error parsing episode node #$idx", e)
            }
        }

        // Revert chapter order so earliest appears first (chronological)
        chapters.reverse()
        Log.d(LOG_TAG, "chapterListParse -> returning ${chapters.size} chapters (order reversed)")

        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val url = "${baseUrl.trimEnd('/')}${chapter.url}"
        Log.d(LOG_TAG, "pageListRequest(${chapter.url}) -> $url")
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun pageListParse(response: Response): List<Page> {
        Log.d(LOG_TAG, "pageListParse -> ${response.request.url}")
        val requestUrl = response.request.url
        val segments = requestUrl.pathSegments
        val alias = segments.getOrNull(2) ?: throw Exception("Invalid chapter URL segments")
        val name = segments.lastOrNull() ?: throw Exception("Invalid chapter URL segments")

        var purchased: Boolean
        try {
            val checkUri = "${baseUrl.trimEnd('/')}/lz-api/contents/v3/$alias/episodes/$name?referrerViewType=NORMAL&objectType=comic"
            Log.d(LOG_TAG, "pageListParse: purchase check -> $checkUri")
            val checkReq = Request.Builder().url(checkUri).headers(apiHeaders()).get().build()
            val checkRes = client.newCall(checkReq).execute()
            val checkText = checkRes.body.string()
            Log.v(LOG_TAG, "pageListParse: purchase response preview=${checkText.take(200)}")
            purchased = if (checkText.isNotEmpty()) {
                JSONObject(checkText).optJSONObject("data")?.optJSONObject("episode")?.optBoolean("isCollected") ?: false
            } else {
                false
            }
            Log.d(LOG_TAG, "pageListParse: purchased=$purchased")
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "pageListParse: purchase check failed", e)
            purchased = false
        }

        val uri = "${apiBase}inventory_groups/comic_viewer?platform=web&store=web&preload=false&type=comic_episode&alias=$alias&name=$name"
        Log.d(LOG_TAG, "pageListParse: inventory viewer -> $uri")
        val req = Request.Builder().url(uri).headers(apiHeaders()).get().build()
        val res = client.newCall(req).execute()
        val resBody = res.body
        val text = resBody.string()
        Log.v(LOG_TAG, "pageListParse: inventory response preview=${text.take(200)}")
        val root = JSONObject(text)
        val data = root.getJSONObject("data")
        val extra = data.getJSONObject("extra")
        val comic = extra.getJSONObject("comic")
        val episode = extra.getJSONObject("episode")
        val scrollsInfo = if (episode.has("scrollsInfo")) episode.getJSONArray("scrollsInfo") else null
        val pagesInfo = if (episode.has("pagesInfo")) episode.getJSONArray("pagesInfo") else null

        val episodeId = episode.optInt("id", -1)
        val comicId = episode.optInt("idComic", -1)
        val imageShuffle = comic.optJSONObject("metadata")?.optBoolean("imageShuffle") ?: false

        updateCDN()

        val format = ".webp"
        val arr = pagesInfo ?: scrollsInfo ?: JSONArray()
        val pages = mutableListOf<Page>()
        for (i in 0 until arr.length()) {
            val path = arr.getJSONObject(i).optString("path", "")
            if (path.isEmpty()) continue
            val baseCandidate = if (cdnBase != null) {
                "${cdnBase!!.trimEnd('/')}/v2${path}$format"
            } else {
                "${baseUrl.trimEnd('/')}/v2${path}$format"
            }
            val finalUrl = try {
                Log.d(LOG_TAG, "pageListParse: creating signed image url comicId=$comicId episodeId=$episodeId path=$path")
                createSignedImageUrl(comicId, episodeId, purchased, baseCandidate)
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "pageListParse: createSignedImageUrl failed, using baseCandidate", e)
                baseCandidate
            }

            val safeUrl = finalUrl.ifEmpty { baseCandidate }
            Log.v(LOG_TAG, "pageListParse: page[$i] safeUrl=$safeUrl")

            if (imageShuffle && episodeId >= 0) {
                val fragment = "lezhin_eid=$episodeId;cols=5"
                pages.add(Page(i, "", "$safeUrl#$fragment"))
                continue
            }

            pages.add(Page(i, "", safeUrl))
        }
        Log.d(LOG_TAG, "pageListParse -> pages=${pages.size}")
        return pages
    }

    override fun imageUrlParse(response: Response): String = response.request.url.toString()
    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    private fun defaultHeaders(): Headers {
        val b = Headers.Builder()
        b.add("User-Agent", "Mozilla/5.0 (Android)")
        b.add("Origin", baseUrl)
        b.add("Referer", baseUrl)
        b.add("x-lz-locale", siteLocale)
        tokenManager.getToken()?.let {
            Log.v(LOG_TAG, "defaultHeaders: token present (masked)")
            b.set("Authorization", "Bearer ${it.takeLast(4).padStart(4, '*')} (hidden)")
        }
        return b.build()
    }

    private fun apiHeaders(): Headers {
        val b = Headers.Builder()
        b.add("User-Agent", "Mozilla/5.0 (Android)")
        b.add("Origin", baseUrl)
        b.add("Referer", baseUrl)
        b.add("x-lz-locale", siteLocale)
        b.add("X-LZ-Adult", "2")
        b.add("X-LZ-AllowAdult", "true")
        tokenManager.getToken()?.let {
            Log.v(LOG_TAG, "apiHeaders: token present (masked)")
            b.set("Authorization", "Bearer ${it.takeLast(4).padStart(4, '*')} (hidden)")
        }
        return b.build()
    }

    private var cdnBase: String? = null

    private fun updateCDN() {
        try {
            val req = Request.Builder().url("$baseUrl/account").headers(defaultHeaders()).get().build()
            Log.d(LOG_TAG, "updateCDN: GET ${req.url}")
            val res = client.newCall(req).execute()
            val body = res.body.string()
            Log.v(LOG_TAG, "updateCDN: account page preview=${body.take(200)}")
            val idx = body.indexOf("window.__LZ_CONFIG__")
            if (idx >= 0) {
                val snippetStart = (idx - 200).coerceAtLeast(0)
                val snippetEnd = (idx + 3000).coerceAtMost(body.length)
                val snippet = body.substring(snippetStart, snippetEnd)
                Regex("""contentsCdnUrl\s*:\s*['"]([^'"]+)['"]""").find(snippet)?.let {
                    cdnBase = it.groupValues[1]
                    Log.i(LOG_TAG, "updateCDN: found cdnBase=$cdnBase")
                    return
                }
            }
            if (body.contains("id=\"lz-static\"")) {
                val doc = Jsoup.parse(body)
                val elem = doc.selectFirst("#lz-static")
                val env = elem?.attr("data-env")
                if (!env.isNullOrEmpty()) {
                    val json = JSONObject(env)
                    if (json.has("CONTENT_CDN_URL")) {
                        cdnBase = json.getString("CONTENT_CDN_URL")
                        Log.i(LOG_TAG, "updateCDN: found cdnBase in data-env=$cdnBase")
                        return
                    }
                }
            }
            Log.w(LOG_TAG, "updateCDN: CONTENT_CDN_URL not found")
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "updateCDN failed", e)
        }
    }

    private fun resolveCdnOrAbsolute(path: String): String = if (path.startsWith("http")) {
        path
    } else {
        cdnBase?.trimEnd('/')?.plus(path) ?: (baseUrl.trimEnd('/') + path)
    }

    private fun createSignedImageUrl(comicId: Int, episodeId: Int, purchased: Boolean, imageUrl: String): String {
        val purchasedStr = if (purchased) "true" else "false"
        val uri = "${apiBase}cloudfront/signed-url/generate?contentId=$comicId&episodeId=$episodeId&purchased=$purchasedStr&q=40&firstCheckType=P"
        Log.d(LOG_TAG, "createSignedImageUrl: POST $uri")
        val req = Request.Builder().url(uri).headers(apiHeaders()).get().build()
        val res = client.newCall(req).execute()
        val text = res.body.string()
        Log.v(LOG_TAG, "createSignedImageUrl: response preview=${text.take(200)}")
        val root = JSONObject(text)
        val data = root.getJSONObject("data")
        val policy = data.optString("Policy")
        val signature = data.optString("Signature")
        val keyPair = data.optString("Key-Pair-Id")
        val query = "Policy=${URLEncoder.encode(policy, "UTF-8")}&Signature=${URLEncoder.encode(signature, "UTF-8")}&Key-Pair-Id=${URLEncoder.encode(keyPair, "UTF-8")}"
        val signed = if (imageUrl.contains("?")) "$imageUrl&$query" else "$imageUrl?$query"
        Log.d(LOG_TAG, "createSignedImageUrl: signed url created")
        return signed
    }

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        var request = chain.request()
        tokenManager.getToken()?.let { token ->
            Log.v(LOG_TAG, "authInterceptor: adding Authorization header (masked) to ${request.url}")
            request = request.newBuilder().header("Authorization", "Bearer ${token.takeLast(4).padStart(4, '*')} (hidden)").build()
        }
        val response = chain.proceed(request)

        if (response.code == 401) {
            Log.w(LOG_TAG, "authInterceptor: 401 for ${request.url}")
            response.close()
            val relogged = tokenManager.attemptRelogin()
            Log.d(LOG_TAG, "authInterceptor: relogin attempted => $relogged")
            if (relogged) {
                tokenManager.getToken()?.let { token2 ->
                    val retryReq = request.newBuilder().header("Authorization", "Bearer ${token2.takeLast(4).padStart(4, '*')} (hidden)").build()
                    Log.d(LOG_TAG, "authInterceptor: retrying ${request.url} with new token (masked)")
                    return chain.proceed(retryReq)
                }
            }
        }
        return response
    }

    private fun imageDescrambler(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fragment = request.url.fragment ?: return chain.proceed(request)
        if (!fragment.startsWith("lezhin_eid=")) return chain.proceed(request)

        Log.d(LOG_TAG, "imageDescrambler: intercept ${request.url} fragment=$fragment")
        val params = fragment.split(';').associate {
            val (k, v) = it.split('=', limit = 2)
            k to v
        }
        val eps = params["lezhin_eid"]?.toIntOrNull() ?: return chain.proceed(request)
        val cols = params["cols"]?.toIntOrNull() ?: 5
        Log.d(LOG_TAG, "imageDescrambler: parsed eps=$eps cols=$cols")

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response

        val bytes = try {
            response.body.bytes()
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "imageDescrambler: failed to read bytes", e)
            null
        } ?: return response

        val descrambled = try {
            LezhinDescrambler.descramble(bytes, eps, cols).also {
                Log.i(LOG_TAG, "imageDescrambler: descrambled eps=$eps bytes_in=${bytes.size} bytes_out=${it.size}")
            }
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "imageDescrambler: descramble failed for eps=$eps", e)
            null
        } ?: return response

        val mediaType = "image/png".toMediaTypeOrNull()
        return response.newBuilder()
            .body(descrambled.toResponseBody(mediaType))
            .build()
    }

    private inner class TokenManager {
        @Suppress("unused")
        fun isLogged(): Boolean = (preferences.getString(PREF_TOKEN, "") ?: "").isNotBlank()

        @Synchronized
        fun getToken(): String? {
            Log.v(LOG_TAG, "TokenManager.getToken called")
            val now = System.currentTimeMillis()
            val token = preferences.getString(PREF_TOKEN, "") ?: ""
            val expires = preferences.getLong(PREF_TOKEN_EXPIRES, 0L)
            if (token.isNotBlank() && (expires == 0L || now < expires)) {
                Log.v(LOG_TAG, "TokenManager.getToken: returning cached token (masked)")
                return token
            }

            // periodic HTML extraction: only attempt if last attempt is older than HTML_TRY_INTERVAL_MS
            val lastHtmlTried = preferences.getLong(PREF_HTML_TRIED_TS, 0L)
            if (now - lastHtmlTried > HTML_TRY_INTERVAL_MS) {
                try {
                    Log.d(LOG_TAG, "TokenManager: attempting HTML extraction from $baseUrl (periodic retry)")
                    val req = Request.Builder().url(baseUrl).get().build()
                    val res = network.cloudflareClient.newCall(req).execute()
                    val body = res.body.string()
                    Log.v(LOG_TAG, "TokenManager: HTML preview=${body.take(200)}")
                    Regex("""accessToken['"]?\s*:\s*['"]([A-Za-z0-9._=\-]+)['"]""").find(body)?.let {
                        val found = it.groupValues[1]
                        if (found.isNotBlank()) {
                            saveToken(found, 0L)
                            Log.i(LOG_TAG, "TokenManager: extracted token from HTML (masked)")
                            // update last tried timestamp to now
                            preferences.edit().putLong(PREF_HTML_TRIED_TS, now).apply()
                            return found
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(LOG_TAG, "TokenManager: HTML extraction failed", e)
                } finally {
                    // update last tried timestamp to now even if extraction failed so we respect interval
                    preferences.edit().putLong(PREF_HTML_TRIED_TS, now).apply()
                }
            } else {
                Log.v(LOG_TAG, "TokenManager: skipping HTML extraction (last tried ${now - lastHtmlTried} ms ago)")
            }

            val email = preferences.getString(PREF_EMAIL, "") ?: ""
            val password = preferences.getString(PREF_PASSWORD, "") ?: ""
            if (email.isNotBlank() && password.isNotBlank()) {
                Log.d(LOG_TAG, "TokenManager: attempting login (credentials present)")
                try {
                    if (loginAndSave(email, password)) {
                        Log.i(LOG_TAG, "TokenManager: login successful")
                        return preferences.getString(PREF_TOKEN, "")
                    } else {
                        Log.w(LOG_TAG, "TokenManager: login returned false")
                    }
                } catch (e: Throwable) {
                    Log.e(LOG_TAG, "TokenManager: login failed", e)
                }
            } else {
                Log.v(LOG_TAG, "TokenManager: no saved credentials, skipping login")
            }

            Log.v(LOG_TAG, "TokenManager.getToken -> no token available")
            return null
        }

        fun initializeIfNeeded() {
            try {
                Log.d(LOG_TAG, "TokenManager.initializeIfNeeded")
                getToken()
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "TokenManager.initializeIfNeeded failed", e)
            }
        }

        @Synchronized
        fun attemptRelogin(): Boolean {
            Log.d(LOG_TAG, "TokenManager.attemptRelogin called")
            val email = preferences.getString(PREF_EMAIL, "") ?: ""
            val password = preferences.getString(PREF_PASSWORD, "") ?: ""
            if (email.isNotBlank() && password.isNotBlank()) {
                try {
                    val ok = loginAndSave(email, password)
                    Log.d(LOG_TAG, "TokenManager.attemptRelogin -> $ok")
                    return ok
                } catch (e: Throwable) {
                    Log.e(LOG_TAG, "TokenManager.attemptRelogin failed", e)
                }
            }
            return false
        }

        fun clearTokens() {
            Log.d(LOG_TAG, "TokenManager.clearTokens called")
            preferences.edit()
                .remove(PREF_TOKEN)
                .remove(PREF_TOKEN_EXPIRES)
                .remove(PREF_USER_ID)
                .putLong(PREF_HTML_TRIED_TS, 0L)
                .apply()
            // clear cached divisions so filters can be refreshed after credential changes if needed
            cachedDivisions = null
        }

        private fun loginAndSave(email: String, password: String): Boolean {
            try {
                val apiLoginUrl = if (baseUrl.endsWith("/")) "$baseUrl/api/authentication/login" else "$baseUrl/api/authentication/login"
                Log.d(LOG_TAG, "TokenManager.loginAndSave -> POST $apiLoginUrl")
                val json = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                    put("remember", false)
                    put("provider", "email")
                    put("language", siteLocale.split("-").firstOrNull() ?: siteLocale)
                }
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val req = Request.Builder().url(apiLoginUrl).post(body).build()
                val res = network.cloudflareClient.newCall(req).execute()
                if (!res.isSuccessful) {
                    Log.w(LOG_TAG, "TokenManager.loginAndSave: login response not successful code=${res.code}")
                    res.close()
                    return false
                }
                val txt = res.body.string()
                Log.v(LOG_TAG, "TokenManager.loginAndSave: login response preview=${txt.take(200)}")
                if (txt.isEmpty()) return false
                val root = JSONObject(txt)
                val access = when {
                    root.has("accessToken") -> root.optString("accessToken")
                    root.optJSONObject("data")?.has("accessToken") == true -> root.optJSONObject("data")!!.optString("accessToken")
                    root.optJSONObject("appConfig")?.has("accessToken") == true -> root.optJSONObject("appConfig")!!.optString("accessToken")
                    else -> ""
                }
                val expiresIn = when {
                    root.has("expiresIn") -> root.optLong("expiresIn", 0L)
                    root.optJSONObject("data")?.has("expiresIn") == true -> root.optJSONObject("data")!!.optLong("expiresIn", 0L)
                    else -> 0L
                }
                if (access.isNotBlank()) {
                    saveToken(access, expiresIn)
                    val uid = when {
                        root.optJSONObject("appConfig")?.has("id") == true -> root.optJSONObject("appConfig")!!.optString("id")
                        root.optJSONObject("data")?.optJSONObject("user")?.has("id") == true -> root.optJSONObject("data")!!.optJSONObject("user")!!.optString("id")
                        else -> null
                    }
                    uid?.let { preferences.edit().putString(PREF_USER_ID, it).apply() }
                    Log.i(LOG_TAG, "TokenManager.loginAndSave: login succeeded (token saved masked)")
                    // clear cached divisions so filters can be re-scraped if needed after login (adult restrictions etc.)
                    cachedDivisions = null
                    return true
                }
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "TokenManager.loginAndSave: exception during login", e)
            }
            return false
        }

        private fun saveToken(access: String, expiresInSeconds: Long) {
            val expiryTs = if (expiresInSeconds > 0L) System.currentTimeMillis() + expiresInSeconds * 1000L else 0L
            preferences.edit()
                .putString(PREF_TOKEN, access)
                .putLong(PREF_TOKEN_EXPIRES, expiryTs)
                .apply()
            Log.d(LOG_TAG, "TokenManager.saveToken: token saved (masked), expiry=$expiryTs")
        }
    }
}
