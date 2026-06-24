package eu.kanade.tachiyomi.extension.all.patreon

import android.app.Application
import android.text.InputType
import android.webkit.CookieManager
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

class Patreon :
    HttpSource(),
    ConfigurableSource {
    override val name = "Patreon"
    override val baseUrl = "https://www.patreon.com"
    override val lang = "all"
    override val supportsLatest = true

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val postPagesCache: MutableMap<String, List<String>> = LruCache(POST_PAGES_CACHE_SIZE)
    private val searchCursorCache: MutableMap<String, MutableMap<Int, String?>> = LruCache(SEARCH_CURSOR_CACHE_SIZE)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Accept", "application/json, text/plain, */*")
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("X-Requested-With", "XMLHttpRequest")

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        if (page > 1) return@fromCallable MangasPage(emptyList(), false)

        runCatching {
            fetchExploreSections()
        }.getOrNull()?.let { result ->
            if (result.mangas.isNotEmpty()) {
                return@fromCallable result
            }
        }

        MangasPage(listOf(resolveConfiguredCampaign()), false)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = fetchPopularManga(page)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable.fromCallable {
        if (filters.membershipsOnly()) {
            if (page > 1) return@fromCallable MangasPage(emptyList(), false)

            val memberships = fetchCurrentUserMemberships().mangas
            val filteredMemberships = if (query.isBlank()) {
                memberships
            } else {
                memberships.filter { manga ->
                    manga.title.contains(query, ignoreCase = true) ||
                        manga.author.orEmpty().contains(query, ignoreCase = true) ||
                        manga.artist.orEmpty().contains(query, ignoreCase = true)
                }
            }

            return@fromCallable MangasPage(filteredMemberships, false)
        }

        if (query.isBlank()) {
            if (page > 1) return@fromCallable MangasPage(emptyList(), false)
            return@fromCallable fetchExploreSections()
        }

        if (patreonCookie().isNotBlank()) {
            runCatching {
                fetchLoggedInSearch(page, query)
            }.getOrNull()?.let { result ->
                if (result.mangas.isNotEmpty()) {
                    return@fromCallable result
                }
            }
        }

        runCatching {
            fetchAnonymousSearch(page, query)
        }.getOrNull()?.let { result ->
            if (result.mangas.isNotEmpty()) {
                return@fromCallable result
            }
        }

        fetchSearchHtmlFallback(page, query)
    }

    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        val campaignId = manga.url.extractCampaignIdFromSourceUrl() ?: resolveCampaignId(manga.url)

        fetchCampaignManga(campaignId).apply {
            title = manga.title.takeIf { it.isNotBlank() } ?: title
            thumbnail_url = manga.thumbnail_url ?: thumbnail_url
            initialized = true
        }
    }

    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val campaignId = manga.url.extractCampaignIdFromSourceUrl() ?: resolveCampaignId(manga.url)
        val maxPages = preferences.getString(POST_PAGES_PREF, POST_PAGES_DEFAULT)!!.toInt()
        val requestHeaders = patreonHeaders()

        val chapters = mutableListOf<SChapter>()
        var nextUrl: String? = postsApiUrl(campaignId)
        var page = 0

        while (!nextUrl.isNullOrBlank() && page < maxPages) {
            val requestUrl = nextUrl ?: break
            page++

            client.newCall(GET(requestUrl, requestHeaders)).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Patreon HTTP ${response.code}. Check your login and that your account can view this creator.")
                }

                val root = json.decodeFromString<PatreonApiRoot>(response.body.string())

                root.dataPosts(json).forEach { post ->
                    if (post.isLocked()) {
                        if (!hideLockedChapters()) {
                            chapters.add(post.toSChapter(campaignId, locked = true))
                        }
                        return@forEach
                    }

                    val imageUrls = post.imageUrls(root, json)
                    if (imageUrls.isNotEmpty()) {
                        postPagesCache[post.id] = imageUrls
                        chapters.add(post.toSChapter(campaignId))
                    }
                }

                nextUrl = root.links?.next?.absolutePatreonUrl()
            }
        }

        chapters.distinctBy { it.url }
    }

    override fun chapterListRequest(manga: SManga): Request = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun getChapterUrl(chapter: SChapter): String {
        val postId = chapter.url.extractPostIdFromChapterUrl()
        return "$baseUrl/posts/$postId"
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val postId = chapter.url.extractPostIdFromChapterUrl()

        if (chapter.url.isLockedChapterUrl()) {
            throw IOException("This Patreon post is locked. You need a higher membership tier to read it.")
        }

        postPagesCache[postId]?.let { cachedUrls ->
            return@fromCallable cachedUrls.toPages()
        }

        client.newCall(pageListRequest(chapter)).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Patreon HTTP ${response.code}. This post may be locked, expired, or blocked by Patreon.")
            }

            pageListParse(response)
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val postId = chapter.url.extractPostIdFromChapterUrl()
        return GET(postApiUrl(postId), patreonHeaders())
    }

    override fun pageListParse(response: Response): List<Page> {
        val root = json.decodeFromString<PatreonApiRoot>(response.body.string())
        val post = root.dataPosts(json).firstOrNull() ?: return emptyList()
        val urls = post.imageUrls(root, json)

        postPagesCache[post.id] = urls

        return urls.toPages()
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = patreonHeaders(requireLogin = false).newBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList(
        MembershipsOnlyFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = CAMPAIGN_ID_PREF
            title = "Default campaign ID"
            summary = "Optional fallback if Popular fails. First log in to Patreon using the app WebView for member-only posts."
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = CREATOR_NAME_PREF
            title = "Default creator display name"
            summary = "Optional fallback title before the campaign API is loaded."
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = POST_PAGES_PREF
            title = "Maximum post pages to load"
            summary = "Loading more pages costs more time and network traffic. Currently: %s"
            entryValues = Array(POST_PAGES_MAX) { (it + 1).toString() }
            entries = Array(POST_PAGES_MAX) { "${it + 1} pages" }
            setDefaultValue(POST_PAGES_DEFAULT)
        }.let(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = HIDE_LOCKED_CHAPTERS_PREF
            title = "Hide locked chapters"
            summary = "Hide Patreon posts that your current membership cannot view. When disabled, locked posts appear with a 🔒 prefix."
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    private fun fetchCurrentUserMemberships(): MangasPage {
        client.newCall(GET(currentUserMembershipsApiUrl(), patreonHeaders())).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Patreon memberships HTTP ${response.code}. Log in to Patreon using the app WebView first.")
            }

            val root = json.decodeFromString<PatreonApiRoot>(response.body.string())
            val mangas = root.currentUserMembershipResults(json)

            return MangasPage(mangas, false)
        }
    }

    private fun fetchExploreSections(): MangasPage {
        client.newCall(GET(exploreSectionsApiUrl(), patreonHeaders(requireLogin = false))).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Patreon explore HTTP ${response.code}")
            }

            val root = json.decodeFromString<PatreonApiRoot>(response.body.string())
            val mangas = root.exploreCampaignResults(json)

            return MangasPage(mangas, false)
        }
    }

    private fun fetchLoggedInSearch(page: Int, query: String): MangasPage {
        val cursor = if (page <= 1) {
            ""
        } else {
            searchCursorCache[query.searchCursorKey()]?.get(page).orEmpty()
        }

        if (page > 1 && cursor.isBlank()) {
            return MangasPage(emptyList(), false)
        }

        client.newCall(GET(loggedInSearchApiUrl(query, cursor), patreonHeaders(requireLogin = false))).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Patreon logged-in search HTTP ${response.code}")
            }

            val root = json.decodeFromString<PatreonApiRoot>(response.body.string())
            val mangas = root.searchFeedCampaignResults(json)
            val next = root.links?.next

            saveNextSearchCursor(query, page, next)

            return MangasPage(mangas, !next.isNullOrBlank())
        }
    }

    private fun fetchAnonymousSearch(page: Int, query: String): MangasPage {
        client.newCall(GET(anonymousSearchApiUrl(query, page), headers)).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Patreon anonymous search HTTP ${response.code}")
            }

            val root = json.decodeFromString<PatreonApiRoot>(response.body.string())
            val mangas = root.searchResults(json, baseUrl)

            return MangasPage(mangas, !root.links?.next.isNullOrBlank())
        }
    }

    private fun fetchSearchHtmlFallback(page: Int, query: String): MangasPage {
        val searchUrl = "$baseUrl/search?q=${query.encode()}&p=$page"

        client.newCall(GET(searchUrl, headers)).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Patreon search HTTP ${response.code}")
            }

            val document = Jsoup.parse(response.body.string(), baseUrl)
            val results = document.select("[data-tag=campaign-result] a[data-tag=campaign-result-container], .CreatorTile-module__aMsLzq__creatorTileContainer")
                .mapNotNull { element ->
                    val link = if (element.`is`("a")) {
                        element
                    } else {
                        element.selectFirst("a[href*=patreon.com]")
                    } ?: return@mapNotNull null

                    val href = link.attr("abs:href").ifBlank { link.attr("href") }
                    val title = element.selectFirst("h1")?.text()?.trim()
                        ?: element.selectFirst("span")?.text()?.trim()
                        ?: return@mapNotNull null

                    val thumbnail = element.selectFirst("img")?.attr("abs:src")?.ifBlank {
                        element.selectFirst("img")?.attr("src").orEmpty()
                    }.orEmpty()

                    val campaignId = CAMPAIGN_ID_FROM_MEDIA_REGEX.find(thumbnail)?.groupValues?.getOrNull(1)
                    val username = href.usernameFromPatreonUrl() ?: title

                    SManga.create().apply {
                        this.url = campaignId?.let { "/campaign/$it" } ?: href.toSourcePath(baseUrl)
                        this.title = title
                        author = username
                        artist = username
                        thumbnail_url = thumbnail.takeIf { it.isNotBlank() }
                        description = ""
                        initialized = true
                    }
                }
                .distinctBy { it.url }

            val hasNextPage = document.selectFirst("a[href*=/search][href*=p=${page + 1}], a[href*=\"p=${page + 1}\"]") != null

            return MangasPage(results, hasNextPage)
        }
    }

    private fun patreonCookie(): String = CookieManager.getInstance().getCookie(baseUrl).orEmpty()

    private fun patreonHeaders(requireLogin: Boolean = true): Headers {
        val cookie = patreonCookie().trim()

        if (requireLogin && cookie.isBlank()) {
            throw Exception("Log in to Patreon using the app WebView first, then try again.")
        }

        return headers.newBuilder().apply {
            if (cookie.isNotBlank()) {
                set("Cookie", cookie)
            }
        }.build()
    }

    private fun resolveConfiguredCampaign(): SManga {
        val campaignId = preferences.getString(CAMPAIGN_ID_PREF, "")?.trim().orEmpty()

        if (campaignId.isBlank()) {
            throw Exception("Set a default campaign ID in extension settings, or search a Patreon creator URL.")
        }

        return fetchCampaignManga(campaignId)
    }

    private fun resolveCampaignId(query: String): String {
        val trimmed = query.trim()

        if (trimmed.isBlank()) {
            return preferences.getString(CAMPAIGN_ID_PREF, "")?.trim().takeUnless { it.isNullOrBlank() }
                ?: throw Exception("Search with a Patreon creator URL, creator slug, or campaign ID.")
        }

        if (trimmed.matches(Regex("""\d+"""))) {
            return trimmed
        }

        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> "$baseUrl$trimmed"
            trimmed.contains('/') -> "$baseUrl/$trimmed"
            else -> "$baseUrl/$trimmed"
        }

        return fetchCampaignIdFromPage(url)
    }

    private fun fetchCampaignIdFromPage(url: String): String {
        client.newCall(GET(url, patreonHeaders(requireLogin = false))).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Could not open Patreon page: HTTP ${response.code}")
            }

            val html = response.body.string()

            CAMPAIGN_ID_REGEXES.forEach { regex ->
                regex.find(html)?.groupValues?.getOrNull(1)?.let { return it }
            }
        }

        throw Exception("Could not find campaign ID. Try searching with the numeric campaign ID instead.")
    }

    private fun fetchCampaignManga(campaignId: String): SManga {
        val fallbackName = preferences.getString(CREATOR_NAME_PREF, "")?.trim().orEmpty()

        return try {
            client.newCall(GET(campaignApiUrl(campaignId), patreonHeaders())).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }

                val root = json.decodeFromString<PatreonApiRoot>(response.body.string())
                val campaign = root.dataResource(json)

                campaign.toSManga(campaignId, fallbackName)
            }
        } catch (_: Exception) {
            SManga.create().apply {
                this.url = "/campaign/$campaignId"
                title = fallbackName.ifBlank { "Patreon campaign $campaignId" }
                author = "Patreon"
                artist = "Patreon"
                description = ""
                initialized = true
            }
        }
    }

    private fun saveNextSearchCursor(query: String, page: Int, nextUrl: String?) {
        val cursor = nextUrl?.let { PAGE_CURSOR_REGEX.find(it)?.groupValues?.getOrNull(1) }
            ?.decodeUrl()
            ?: return

        val key = query.searchCursorKey()
        val map = searchCursorCache.getOrPut(key) { mutableMapOf() }
        map[page + 1] = cursor
    }

    private fun currentUserMembershipsApiUrl(): String = "$baseUrl/api/current_user" +
        "?fields%5Buser%5D=" +
        "&fields%5Bmember%5D=is_free_member%2Cis_free_trial" +
        "&fields%5Bcampaign%5D=avatar_photo_image_urls%2Ccreation_name%2Cis_nsfw%2Cname%2Cpublished_at%2Curl%2Curl_for_current_user%2Cvanity%2Csummary" +
        "&include=active_memberships%2Cactive_memberships.campaign" +
        "&json-api-version=1.0" +
        "&json-api-use-default-includes=false"

    private fun exploreSectionsApiUrl(): String = "$baseUrl/api/explore/sections" +
        "?fields%5Bexplore-campaign%5D=campaign_id%2Cname%2Ccreation_name%2Csummary%2Cis_nsfw%2Cavatar_photo_url%2Cis_free_member%2Cis_paid_member%2Curl%2Cvanity%2Cprimary_theme_color%2Cmembership_emphasization_preference%2Crecommendation_reason" +
        "&fields%5Bexplore-section%5D=display_type%2Csection_type%2Ctitle%2Cdescription%2Curl%2Cdisplay_meta" +
        "&fields%5Bexplore-topic%5D=label%2Cvalue%2Cdisplay_meta" +
        "&fields%5Bexplore-filter-option%5D=filter_type%2Clabel%2Cvalue" +
        "&include=items%2Citems.campaign%2Citems.topic%2Cfilter" +
        "&filter%5Banchor_topic%5D=" +
        "&filter%5Bselected_topic%5D=" +
        "&filter%5Binclude_nsfw%5D=true" +
        "&filter%5Bchurned_campaign_id%5D=null" +
        "&json-api-version=1.0" +
        "&json-api-use-default-includes=false"

    private fun loggedInSearchApiUrl(query: String, cursor: String): String = "$baseUrl/api/search_feed/v1/campaign" +
        "?filter%5Bquery%5D=${query.encode()}" +
        "&filter%5Bis_for_preview%5D=false" +
        "&filter%5Binclude_nsfw%5D=true" +
        "&fields%5Bcampaign%5D=currency%2Cshow_audio_post_download_links%2Cavatar_photo_url%2Cavatar_photo_image_urls%2Cis_nsfw%2Cis_monthly%2Cname%2Csummary%2Curl%2Cpatron_count%2Cprimary_theme_color%2Ccampaign_id%2Ccreation_name%2Cavatar_photo_blurred_url%2Cis_free_member%2Cis_paid_member%2Cmember_count%2Cpost_count%2Cmembership_emphasization_preference%2Cvanity" +
        "&include=card_campaign.campaign" +
        "&page%5Bsize%5D=24" +
        "&page%5Bcursor%5D=${cursor.encode()}" +
        "&json-api-version=1.0" +
        "&json-api-use-default-includes=false"

    private fun anonymousSearchApiUrl(query: String, page: Int): String = "$baseUrl/api/search?q=${query.encode()}&page%5Bnumber%5D=$page&json-api-version=1.0&json-api-use-default-includes=false&include=[]"

    private fun postsApiUrl(campaignId: String): String = "$baseUrl/api/posts?$POSTS_QUERY&filter%5Bcampaign_id%5D=${campaignId.encode()}"

    private fun postApiUrl(postId: String): String = "$baseUrl/api/posts/${postId.encode()}?$POST_DETAIL_QUERY"

    private fun campaignApiUrl(campaignId: String): String = "$baseUrl/api/campaigns/${campaignId.encode()}" +
        "?fields%5Bcampaign%5D=avatar_photo_url%2Ccover_photo_url%2Ccurrent_user_is_free_member%2Ccurrent_user_is_teammate_or_owner%2Chas_rss%2Cid%2Cmain_video_embed%2Cmain_video_url%2Cname%2Csummary%2Curl%2Cvanity" +
        "&fields%5Bconnected_socials%5D=app_name%2Cexternal_profile_url%2Cis_public" +
        "&fields%5Bpledge%5D=amount_cents" +
        "&fields%5Buser%5D=id" +
        "&include=connected_socials%2Ccreator%2Ccurrent_user_pledge" +
        "&json-api-version=1.0" +
        "&json-api-use-default-includes=false"

    private fun String.absolutePatreonUrl(): String = when {
        startsWith("http://") || startsWith("https://") -> this
        startsWith("/") -> "$baseUrl$this"
        else -> "$baseUrl/$this"
    }

    private fun String.extractCampaignIdFromSourceUrl(): String? = Regex("""/campaign/(\d+)""").find(this)?.groupValues?.getOrNull(1)

    private fun String.encode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun String.decodeUrl(): String = URLDecoder.decode(this, Charsets.UTF_8.name())

    private fun String.searchCursorKey(): String = trim().lowercase()

    private fun hideLockedChapters(): Boolean = preferences.getBoolean(HIDE_LOCKED_CHAPTERS_PREF, false)

    private fun String.extractPostIdFromChapterUrl(): String = substringAfterLast("/post/")
        .substringBefore('/')
        .substringBefore('?')

    private fun String.isLockedChapterUrl(): Boolean = substringAfter('?', "")
        .split('&')
        .any { it == "locked=true" }

    private fun FilterList.membershipsOnly(): Boolean = filterIsInstance<MembershipsOnlyFilter>().firstOrNull()?.state == true

    private class MembershipsOnlyFilter : Filter.CheckBox("Only memberships", false)

    private class LruCache<K, V>(private val maxEntries: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxEntries
    }

    companion object {
        private const val CAMPAIGN_ID_PREF = "PATREON_CAMPAIGN_ID"
        private const val CREATOR_NAME_PREF = "PATREON_CREATOR_NAME"
        private const val POST_PAGES_PREF = "PATREON_POST_PAGES"
        private const val HIDE_LOCKED_CHAPTERS_PREF = "PATREON_HIDE_LOCKED_CHAPTERS"
        private const val POST_PAGES_DEFAULT = "1"
        private const val POST_PAGES_MAX = 20

        private const val POST_PAGES_CACHE_SIZE = 200
        private const val SEARCH_CURSOR_CACHE_SIZE = 50

        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"

        private const val POSTS_QUERY =
            "include=user%2Cattachments_media%2Ccampaign%2Cpoll.choices%2Cpoll.current_user_responses.user%2Cpoll.current_user_responses.choice%2Cpoll.current_user_responses.poll%2Caccess_rules.tier.null%2Cimages.null%2Caudio.null" +
                "&fields%5Bpost%5D=change_visibility_at%2Ccomment_count%2Ccontent%2Ccontent_json_string%2Ccurrent_user_can_delete%2Ccurrent_user_can_view%2Ccurrent_user_has_liked%2Cembed%2Cimage%2Cis_paid%2Clike_count%2Cmin_cents_pledged_to_view%2Cpost_file%2Cpost_metadata%2Cpublished_at%2Cpatron_count%2Cpatreon_url%2Cpost_type%2Cpledge_url%2Cthumbnail_url%2Cteaser_text%2Ctitle%2Cupgrade_url%2Curl%2Cwas_posted_by_campaign_owner" +
                "&fields%5Buser%5D=image_url%2Cfull_name%2Curl" +
                "&fields%5Bcampaign%5D=show_audio_post_download_links%2Cavatar_photo_url%2Cearnings_visibility%2Cis_nsfw%2Cis_monthly%2Cname%2Curl%2Cvanity" +
                "&fields%5Baccess_rule%5D=access_rule_type%2Camount_cents" +
                "&fields%5Bmedia%5D=id%2Cimage_urls%2Cdownload_url%2Cmetadata%2Cfile_name" +
                "&sort=-published_at" +
                "&filter%5Bis_draft%5D=false&filter%5Bcontains_exclusive_posts%5D=true&json-api-use-default-includes=false&json-api-version=1.0"

        private const val POST_DETAIL_QUERY =
            "include=user%2Cattachments_media%2Ccampaign%2Caccess_rules.tier.null%2Cimages.null%2Caudio.null" +
                "&fields%5Bpost%5D=content%2Ccontent_json_string%2Ccurrent_user_can_view%2Cembed%2Cimage%2Cis_paid%2Cpost_file%2Cpost_metadata%2Cpublished_at%2Cpatreon_url%2Cpost_type%2Cthumbnail_url%2Cteaser_text%2Ctitle%2Curl" +
                "&fields%5Bmedia%5D=id%2Cimage_urls%2Cdownload_url%2Cmetadata%2Cfile_name" +
                "&json-api-use-default-includes=false&json-api-version=1.0"

        private val CAMPAIGN_ID_REGEXES = listOf(
            Regex(""""self"\s*:\s*"https:\\/\\/www\.patreon\.com\\/api\\/campaigns\\/(\d+)"""),
            Regex("""https:\\/\\/www\.patreon\.com\\/api\\/campaigns\\/(\d+)"""),
            Regex("""/api/campaigns/(\d+)"""),
            Regex("""/campaign/(\d+)/"""),
        )

        private val CAMPAIGN_ID_FROM_MEDIA_REGEX =
            Regex("""/campaign/(\d+)/""")

        private val PAGE_CURSOR_REGEX =
            Regex("""page(?:%5B|\[)cursor(?:%5D|])=([^&]+)""")
    }
}
