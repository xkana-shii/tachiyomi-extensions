package eu.kanade.tachiyomi.extension.all.lezhin

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Shared Lezhin base source.
 *
 * Constructor:
 *   sourceLang: "en", "ko", etc.
 *   baseUrlParam: "https://www.lezhinus.com" or "https://www.lezhin.com"
 *   uiName: visible name like "Lezhin (EN)"
 */
abstract class Lezhin(
    sourceLang: String,
    baseUrlParam: String,
    uiName: String
) : HttpSource() {

    // Exposed properties initialized from constructor parameters
    override val name: String = uiName
    override val baseUrl: String = baseUrlParam.removeSuffix("/")
    override val lang: String = sourceLang
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient = network.cloudflareClient

    // API base derived from domain (lz-api v2)
    private val apiBase: String = baseUrl.trimEnd('/') + "/lz-api/v2/"

    // Path segment and site locale derived from language
    private val pathSegment: String = when (sourceLang.lowercase(Locale.US)) {
        "en" -> "en"
        "ko" -> "ko"
        "ja" -> "ja"
        else -> sourceLang
    }
    private val siteLocale: String = when (sourceLang.lowercase(Locale.US)) {
        "en" -> "en-US"
        "ko" -> "ko-KR"
        "ja" -> "ja-JP"
        else -> "en-US"
    }

    // Internal token manager (basic)
    private val tokenProvider = TokenProvider(baseUrl, siteLocale)

    // Public method to set credentials (wire from settings UI)
    fun setCredentials(username: String, password: String) {
        tokenProvider.setCredentials(username, password)
        try { tokenProvider.initialize(client) } catch (_: Throwable) {}
    }

    // ---------------------------
    // HttpSource required overrides
    // ---------------------------

    override fun popularMangaRequest(page: Int): Request {
        val mangasPerPage = 500
        val offset = (page - 1) * mangasPerPage
        val url = "${apiBase}contents?menu=general&limit=$mangasPerPage&offset=$offset&order=popular"
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val text = response.body?.string() ?: throw Exception("Empty response")
        val root = JSONObject(text)
        val list = mutableListOf<SManga>()
        val data = root.optJSONArray("data") ?: JSONArray()
        for (i in 0 until data.length()) {
            val it = data.getJSONObject(i)
            val alias = it.optString("alias")
            val title = it.optString("title").trim()
            val thumb = it.optString("thumbnail", "")
            val manga = SManga.create().apply {
                url = "/$pathSegment/comic/$alias"
                this.title = title
                if (thumb.isNotEmpty()) thumbnail_url = resolveCdnOrAbsolute(thumb)
            }
            list.add(manga)
        }
        val hasNext = root.optBoolean("hasNext", false)
        return MangasPage(list, hasNext)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val perPage = 100
        val offset = (page - 1) * perPage
        val url = "${apiBase}contents/search?keyword=$encoded&menu=general&limit=$perPage&offset=$offset"
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val text = response.body?.string() ?: throw Exception("Empty response")
        val root = JSONObject(text)
        val list = mutableListOf<SManga>()
        val data = root.optJSONArray("data") ?: JSONArray()
        for (i in 0 until data.length()) {
            val it = data.getJSONObject(i)
            val alias = it.optString("alias")
            val title = it.optString("title").trim()
            val thumb = it.optString("thumbnail", "")
            val manga = SManga.create().apply {
                url = "/$pathSegment/comic/$alias"
                this.title = title
                if (thumb.isNotEmpty()) thumbnail_url = resolveCdnOrAbsolute(thumb)
            }
            list.add(manga)
        }
        val hasNext = root.optBoolean("hasNext", false)
        return MangasPage(list, hasNext)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = baseUrl.trimEnd('/') + manga.url
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body?.string() ?: throw Exception("Empty")
        val doc = Jsoup.parse(body)
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title()
        val description = doc.selectFirst("meta[name=description]")?.attr("content") ?: doc.selectFirst("p.summary")?.text() ?: ""
        val thumb = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        return SManga.create().apply {
            this.title = title
            this.description = description
            if (!thumb.isNullOrEmpty()) thumbnail_url = thumb
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = baseUrl.trimEnd('/') + manga.url
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string() ?: throw Exception("Empty")
        val doc = Jsoup.parse(body)
        val chapters = mutableListOf<SChapter>()
        val anchors = doc.select("div#episode-list div[data-id] a")
        for (a in anchors) {
            val href = a.attr("href")
            val title = a.selectFirst("h3")?.text()?.trim() ?: a.text().trim()
            val chap = SChapter.create().apply {
                url = href
                name = title
            }
            chapters.add(chap)
        }
        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val url = baseUrl.trimEnd('/') + chapter.url
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun pageListParse(response: Response): List<Page> {
        val requestUrl = response.request.url
        val segments = requestUrl.pathSegments
        val alias = segments.getOrNull(2) ?: throw Exception("Invalid chapter URL segments")
        val name = segments.lastOrNull() ?: throw Exception("Invalid chapter URL segments")

        val uri = "${apiBase}inventory_groups/comic_viewer?platform=web&store=web&preload=false&type=comic_episode&alias=${alias}&name=${name}"
        val req = Request.Builder().url(uri).headers(apiHeaders()).get().build()
        val res = client.newCall(req).execute()
        val text = res.body?.string() ?: throw Exception("Empty inventory response")
        val root = JSONObject(text)
        val data = root.getJSONObject("data")
        val extra = data.getJSONObject("extra")
        val comic = extra.getJSONObject("comic")
        val episode = extra.getJSONObject("episode")
        val scrollsInfo = if (episode.has("scrollsInfo")) episode.getJSONArray("scrollsInfo") else null
        val pagesInfo = if (episode.has("pagesInfo")) episode.getJSONArray("pagesInfo") else null

        val updatedAt = episode.optLong("updatedAt", 0)
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
                cdnBase!!.trimEnd('/') + "/v2" + path + format
            } else {
                baseUrl.trimEnd('/') + "/v2" + path + format
            }
            val finalUrl = try {
                createSignedImageUrl(comicId, episodeId, updatedAt, baseCandidate)
            } catch (_: Exception) {
                baseCandidate
            }
            val safeUrl = finalUrl ?: baseCandidate
            pages.add(Page(i, "", safeUrl))
        }
        return pages
    }

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ---------------------------
    // Helpers
    // ---------------------------

    private fun defaultHeaders(): Headers {
        val b = Headers.Builder()
        b.add("User-Agent", "Mozilla/5.0 (Android)")
        b.add("Origin", baseUrl)
        b.add("Referer", baseUrl)
        b.add("x-lz-locale", siteLocale)
        tokenProvider.authorizationHeader?.let { b.set("Authorization", it) }
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
        tokenProvider.authorizationHeader?.let { b.set("Authorization", it) }
        return b.build()
    }

    private var cdnBase: String? = null

    private fun updateCDN() {
        try {
            val req = Request.Builder().url("$baseUrl/account").headers(defaultHeaders()).get().build()
            val res = client.newCall(req).execute()
            val body = res.body?.string() ?: return
            val idx = body.indexOf("window.__LZ_CONFIG__")
            if (idx >= 0) {
                val snippet = body.substring(idx, min(body.length, idx + 3000))
                val re = Regex("""contentsCdnUrl\s*:\s*['"]([^'"]+)['"]""")
                val match = re.find(snippet)
                if (match != null) {
                    cdnBase = match.groupValues[1]
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
                        return
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun resolveCdnOrAbsolute(path: String): String {
        return if (path.startsWith("http")) path else {
            cdnBase?.trimEnd('/')?.plus(path) ?: baseUrl.trimEnd('/') + path
        }
    }

    private fun createSignedImageUrl(comicId: Int, episodeId: Int, updatedAt: Long, imageUrl: String): String {
        val uri = "${apiBase}cloudfront/signed-url/generate?contentId=${comicId}&episodeId=${episodeId}&purchased=false&q=40&firstCheckType=P"
        val req = Request.Builder().url(uri).headers(apiHeaders()).get().build()
        val res = client.newCall(req).execute()
        val text = res.body?.string() ?: throw Exception("Signed URL generation failed")
        val root = JSONObject(text)
        val data = root.getJSONObject("data")
        val policy = data.optString("Policy")
        val signature = data.optString("Signature")
        val keyPair = data.optString("Key-Pair-Id")
        val query = "Policy=${URLEncoder.encode(policy, "UTF-8")}&Signature=${URLEncoder.encode(signature, "UTF-8")}&Key-Pair-Id=${URLEncoder.encode(keyPair, "UTF-8")}"
        return if (imageUrl.contains("?")) "$imageUrl&$query" else "$imageUrl?$query"
    }

    // ---------------------------
    // Internal TokenProvider (basic)
    // ---------------------------

    private class TokenProvider(private val baseUrl: String, private val siteLocale: String) {
        private var token: String? = null
        private var userId: String? = null
        private var username: String? = null
        private var password: String? = null

        val authorizationHeader: String?
            get() = token?.let { "Bearer $it" }

        fun setCredentials(username: String, password: String) {
            this.username = username
            this.password = password
        }

        fun initialize(client: OkHttpClient) {
            try { updateTokenFromNextData(client) } catch (_: Throwable) {}
            if (token.isNullOrEmpty() && !username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                try { attemptLogin(client) } catch (_: Throwable) {}
            }
        }

        private fun updateTokenFromNextData(client: OkHttpClient) {
            val req = Request.Builder().url(baseUrl).get().build()
            val res = client.newCall(req).execute()
            val body = res.body?.string() ?: return
            val idx = body.indexOf("accessToken")
            if (idx >= 0) {
                val substring = body.substring(max(0, idx - 200), min(body.length, idx + 200))
                val m = Regex("""accessToken['"]?\s*:\s*['"]([A-Za-z0-9\-._=]+)['"]""").find(substring)
                if (m != null) {
                    token = m.groupValues[1]
                }
            }
        }

        private fun attemptLogin(client: OkHttpClient) {
            val apiLoginUrl = if (baseUrl.endsWith("/")) baseUrl + "api/authentication/login" else "$baseUrl/api/authentication/login"
            val json = JSONObject().apply {
                put("email", username)
                put("password", password)
                put("remember", false)
                put("provider", "email")
                put("language", siteLocale.split("-").firstOrNull() ?: siteLocale)
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val req = Request.Builder().url(apiLoginUrl).post(body).build()
            val res = client.newCall(req).execute()
            val txt = res.body?.string() ?: return
            try {
                val root = JSONObject(txt)
                if (root.has("accessToken")) {
                    token = root.optString("accessToken")
                } else if (root.has("appConfig")) {
                    val app = root.optJSONObject("appConfig")
                    token = app?.optString("accessToken") ?: token
                    userId = app?.optInt("id")?.toString() ?: userId
                } else if (root.has("data")) {
                    val data = root.optJSONObject("data")
                    token = data?.optString("accessToken") ?: token
                }
            } catch (_: Throwable) {}
        }
    }
}
