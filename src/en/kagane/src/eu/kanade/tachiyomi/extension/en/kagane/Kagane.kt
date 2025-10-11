package eu.kanade.tachiyomi.extension.en.kagane

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Kagane : HttpSource(), ConfigurableSource {

    override val name = "Kagane"

    private val domain = "kagane.org"
    private val apiUrl = "https://api.$domain"
    override val baseUrl = "https://$domain"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client = network.cloudflareClient.newBuilder()
        .cookieJar(
            object : CookieJar {
                private val cookieManager by lazy { CookieManager.getInstance() }

                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val urlString = url.toString()
                    cookies.forEach { cookieManager.setCookie(urlString, it.toString()) }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val cookies = cookieManager.getCookie(url.toString()).orEmpty()
                    val cookieList = mutableListOf<Cookie>()
                    var hasNsfwCookie = false

                    cookies.split(";").mapNotNullTo(cookieList) { c ->
                        var cookieValue = c
                        if (url.host == domain && c.contains("kagane_mature_content")) {
                            hasNsfwCookie = true
                            val (key, _) = c.split("=")
                            cookieValue = "$key=${preferences.showNsfw}"
                        }

                        Cookie.parse(url, cookieValue)
                    }

                    if (!hasNsfwCookie && url.host == domain) {
                        Cookie.parse(url, "kagane_mature_content=${preferences.showNsfw}")?.let {
                            cookieList.add(it)
                        }
                    }

                    return cookieList
                }
            },
        )
        .addInterceptor(ImageInterceptor())
        .addInterceptor(::refreshTokenInterceptor)
        .rateLimit(2)
        .build()

    private fun refreshTokenInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!url.queryParameterNames.contains("token")) {
            return chain.proceed(request)
        }

        val seriesId = url.pathSegments[3]
        val chapterId = url.pathSegments[5]

        var response = chain.proceed(
            request.newBuilder()
                .url(url.newBuilder().setQueryParameter("token", accessToken).build())
                .build(),
        )
        if (response.code == 401) {
            response.close()
            val challenge = try {
                getChallengeResponse(seriesId, chapterId)
            } catch (_: Exception) {
                throw IOException("Failed to retrieve token")
            }
            accessToken = challenge.accessToken
            response = chain.proceed(
                request.newBuilder()
                    .url(url.newBuilder().setQueryParameter("token", accessToken).build())
                    .build(),
            )
        }

        return response
    }

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int) =
        searchMangaRequest(page, "", getFilterList().apply { /* default to sort=Latest for popular/latest */ })

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) =
        searchMangaRequest(page, "", getFilterList().apply { /* default to sort=Latest for popular/latest */ })

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val body = buildJsonObject { }
            .toJsonString()
            .toRequestBody("application/json".toMediaType())

        val url = "$apiUrl/api/v1/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", (page - 1).toString())
            addQueryParameter("mature", preferences.showNsfw.toString())
            addQueryParameter("size", "35")
            if (query.isNotBlank()) {
                addQueryParameter("name", query)
            }
            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> addQueryParameter("sort", filter.selected)
                    is SourceGroupFilter -> filter.selected.forEach { addQueryParameter("source", it) }
                    is GenreGroupFilter -> {
                        filter.included.forEach { addQueryParameter("genre_include", it) }
                        filter.excluded.forEach { addQueryParameter("genre_exclude", it) }
                    }
                    is TagGroupFilter -> {
                        filter.included.forEach { addQueryParameter("tag_include", it) }
                        filter.excluded.forEach { addQueryParameter("tag_exclude", it) }
                    }
                    else -> { /* ignore other filter types */ }
                }
            }
        }

        return POST(url.toString(), headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchDto>()
        val mangas = dto.content.map { it.toSManga(apiUrl) }
        return MangasPage(mangas, hasNextPage = dto.hasNextPage())
    }

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseAs<DetailsDto>()
        return dto.toSManga()
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$apiUrl/api/v1/series/${manga.url}", apiHeaders)
    }

    override fun getMangaUrl(manga: SManga): String {
        return "$baseUrl/series/${manga.url}"
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val dto = response.parseAs<ChapterDto>()
        return dto.content.mapIndexed { i, it -> it.toSChapter(i) }.reversed()
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET("$apiUrl/api/v1/books/${manga.url}", apiHeaders)
    }

    // =============================== Pages ================================

    private val apiHeaders = headers.newBuilder().apply {
        add("Origin", baseUrl)
        add("Referer", "$baseUrl/")
    }.build()

    private fun getCertificate(): String {
        return client.newCall(GET("$apiUrl/api/v1/static/bin.bin", apiHeaders)).execute()
            .body.bytes()
            .toBase64()
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        if (chapter.url.count { it == ';' } != 2) throw Exception("Chapter url error, please refresh chapter list.")
        var (seriesId, chapterId, pageCount) = chapter.url.split(";")

        val challengeResp = getChallengeResponse(seriesId, chapterId)
        accessToken = challengeResp.accessToken
        if (preferences.dataSaver) {
            chapterId = chapterId + "_ds"
        }

        val pages = (0 until pageCount.toInt()).map { page ->
            val pageUrl = "$apiUrl/api/v1/books".toHttpUrl().newBuilder().apply {
                addPathSegment(seriesId)
                addPathSegment("file")
                addPathSegment(chapterId)
                addPathSegment((page + 1).toString())
                addQueryParameter("token", accessToken)
            }.build().toString()

            Page(page, imageUrl = pageUrl)
        }

        return Observable.just(pages)
    }

    private var accessToken: String = ""
    private fun getChallengeResponse(seriesId: String, chapterId: String): ChallengeDto {
        val f = "$seriesId:$chapterId".sha256().sliceArray(0 until 16)

        val interfaceName = "jsInterface"
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <title>Title</title>
            </head>
            <body>
                <script>
                    function base64ToArrayBuffer(base64) {
                        var binaryString = atob(base64);
                        var bytes = new Uint8Array(binaryString.length);
                        for (var i = 0; i < binaryString.length; i++) {
                            bytes[i] = binaryString.charCodeAt(i);
                        }
                        return bytes.buffer;
                    }

                    async function getData() {
                        const g = base64ToArrayBuffer("${getCertificate()}");
                        let t = await navigator.requestMediaKeySystemAccess("com.widevine.alpha", [{
                          initDataTypes: ["cenc"],
                          audioCapabilities: [],
                          videoCapabilities: [{
                            contentType: 'video/mp4; codecs="avc1.42E01E"'
                          }]
                        }]);

                        let e = await t.createMediaKeys();
                        await e.setServerCertificate(g);
                        let n = e.createSession();
                        let i = new Promise((resolve, reject) => {
                          function onMessage(event) {
                            n.removeEventListener("message", onMessage);
                            resolve(event.message);
                          }

                          function onError() {
                            n.removeEventListener("error", onError);
                            reject(new Error("Failed to generate license challenge"));
                          }

                          n.addEventListener("message", onMessage);
                          n.addEventListener("error", onError);
                        });

                        await n.generateRequest("cenc", base64ToArrayBuffer("${getPssh(f).toBase64()}"));
                        let o = await i;
                        let m = new Uint8Array(o);
                        let v = btoa(String.fromCharCode(...m));
                        window.$interfaceName.passPayload(v);
                    }
                    getData();
                </script>
            </body>
            </html>
        """.trimIndent()

        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val jsInterface = JsInterface(latch)
        var webView: WebView? = null

        handler.post {
            val innerWv = WebView(Injekt.get<Application>())

            webView = innerWv
            innerWv.settings.domStorageEnabled = true
            innerWv.settings.javaScriptEnabled = true
            innerWv.settings.blockNetworkImage = true
            innerWv.settings.userAgentString = headers["User-Agent"]
            innerWv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            innerWv.addJavascriptInterface(jsInterface, interfaceName)

            innerWv.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request?.resources?.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID) == true) {
                        request.grant(request.resources)
                    } else {
                        super.onPermissionRequest(request)
                    }
                }
            }

            innerWv.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        }

        latch.await(10, TimeUnit.SECONDS)
        handler.post { webView?.destroy() }

        if (latch.count == 1L) {
            throw Exception("Timed out getting drm challenge")
        }

        if (jsInterface.challenge.isEmpty()) {
            throw Exception("Failed to get drm challenge")
        }

        val challengeUrl =
            "$apiUrl/api/v1/books/$seriesId/file/$chapterId".toHttpUrl().newBuilder().apply {
                if (preferences.dataSaver) {
                    addQueryParameter("datasaver", true.toString())
                }
            }.build()
        val challengeBody = buildJsonObject {
            put("challenge", jsInterface.challenge)
        }.toJsonString().toRequestBody("application/json".toMediaType())

        return client.newCall(POST(challengeUrl.toString(), apiHeaders, challengeBody)).execute()
            .parseAs<ChallengeDto>()
    }

    private fun concat(vararg arrays: ByteArray): ByteArray =
        arrays.reduce { acc, bytes -> acc + bytes }

    private fun getPssh(t: ByteArray): ByteArray {
        val e = Base64.decode("7e+LqXnWSs6jyCfc1R0h7Q==", Base64.DEFAULT)
        val zeroes = ByteArray(4)

        val i = byteArrayOf(18, t.size.toByte()) + t
        val s = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(i.size).array()

        val innerBox = concat(zeroes, e, s, i)
        val outerSize =
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(innerBox.size + 8).array()
        val psshHeader = "pssh".toByteArray(StandardCharsets.UTF_8)

        return concat(outerSize, psshHeader, innerBox)
    }

    internal class JsInterface(private val latch: CountDownLatch) {
        var challenge: String = ""

        @JavascriptInterface
        @Suppress("UNUSED")
        fun passPayload(rawData: String) {
            try {
                challenge = rawData
                latch.countDown()
            } catch (_: Exception) {
                return
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        throw UnsupportedOperationException()
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================ Preferences =============================

    private val SharedPreferences.showNsfw
        get() = this.getBoolean(SHOW_NSFW_KEY, true)
    private val SharedPreferences.dataSaver
        get() = this.getBoolean(DATA_SAVER, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_NSFW_KEY
            title = "Show nsfw entries"
            setDefaultValue(true)
        }.let(screen::addPreference)
        SwitchPreferenceCompat(screen.context).apply {
            key = DATA_SAVER
            title = "Data saver"
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    // ============================= Utilities ==============================

    companion object {
        private const val SHOW_NSFW_KEY = "pref_show_nsfw"
        private const val DATA_SAVER = "data_saver_default"
    }

    // ============================= Filters ==============================

    // Metadata cache for session
    private var cachedMetadata: MetadataDto? = null

    // Fetch and cache metadata from API
    private fun fetchMetadata(): MetadataDto {
        cachedMetadata?.let { return it }
        val response = client.newCall(GET("$apiUrl/api/v1/metadata", apiHeaders)).execute()
        val metadata = response.parseAs<MetadataDto>()
        cachedMetadata = metadata
        return metadata
    }

    // --- FILTER OPTION CLASSES ---
    // Single select filter option
    class SelectFilterOption(val name: String, val value: String)

    // Checkbox filter option for group
    class CheckboxFilterOption(val value: String, name: String, default: Boolean = false) : Filter.CheckBox(name, default)

    // Tri-state filter option for group
    class TriStateFilterOption(val value: String, name: String, default: Int = 0) : Filter.TriState(name, default)

    // --- FILTER GROUPS ---
    // Select filter (single select)
    class SortFilter(options: List<SelectFilterOption>, default: Int = 0) :
        Filter.Select<String>("Sort By", options.map { it.name }.toTypedArray(), default) {
        val optionList = options
        val selected: String
            get() = optionList[state].value
    }

    // Checkbox group filter (multi-select)
    class SourceGroupFilter(options: List<CheckboxFilterOption>) :
        Filter.Group<CheckboxFilterOption>("Source", options) {
        val selected: List<String>
            get() = state.filter { it.state }.map { it.value }
    }

    // Tri-state group filter (multi-select, include/exclude/ignore)
    class GenreGroupFilter(options: List<TriStateFilterOption>) :
        Filter.Group<TriStateFilterOption>("Genres", options) {
        val included: List<String>
            get() = state.filter { it.isIncluded() }.map { it.value }
        val excluded: List<String>
            get() = state.filter { it.isExcluded() }.map { it.value }
    }

    class TagGroupFilter(options: List<TriStateFilterOption>) :
        Filter.Group<TriStateFilterOption>("Tags", options) {
        val included: List<String>
            get() = state.filter { it.isIncluded() }.map { it.value }
        val excluded: List<String>
            get() = state.filter { it.isExcluded() }.map { it.value }
    }

    // Sort options helper
    private fun getSortOptions() = listOf(
        SelectFilterOption("Relevance", ""),
        SelectFilterOption("Latest", "updated_at"),
        SelectFilterOption("By Name", "series_name"),
        SelectFilterOption("Books count", "books_count"),
        SelectFilterOption("Created at", "created_at"),
    )

    override fun getFilterList(): FilterList {
        val metadata = fetchMetadata()
        return FilterList(
            SortFilter(getSortOptions()),
            SourceGroupFilter(metadata.sources.map { CheckboxFilterOption(it.name, it.name) }),
            GenreGroupFilter(metadata.genres.map { TriStateFilterOption(it.name, it.name) }),
            TagGroupFilter(metadata.tags.map { TriStateFilterOption(it.name, it.name) }),
        )
    }
}
