package eu.kanade.tachiyomi.extension.all.lezhin

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
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
    }

    @Volatile
    private var hidePremium: Boolean = preferences.getBoolean(PREF_HIDE_PREMIUM, false)

    private val tokenManager = TokenManager()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        EditTextPreference(context).apply {
            key = PREF_EMAIL
            title = "Email"
            summary = "Email for automatic login (optional)"
            setDefaultValue("")
            dialogTitle = "Lezhin email"
            setOnPreferenceChangeListener { _, _ ->
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
                true
            }
        }.also(screen::addPreference)

        tokenManager.initializeIfNeeded()
    }

    override fun popularMangaRequest(page: Int): Request {
        val per = 500
        val offset = (page - 1) * per
        val url = "${apiBase}contents?menu=general&limit=$per&offset=$offset&order=popular"
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val text = response.body.string()
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
        val text = response.body.string()
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
        val url = "${baseUrl.trimEnd('/')}${manga.url}"
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        val doc = Jsoup.parse(body)
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title()
        val description = doc.selectFirst("meta[name=description]")?.attr("content") ?: doc.selectFirst("p.summary")?.text() ?: ""
        val thumb = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        return SManga.create().apply {
            this.title = title
            this.description = description
            if (thumb.isNotEmpty()) thumbnail_url = thumb
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = "${baseUrl.trimEnd('/')}${manga.url}"
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val doc = Jsoup.parse(body)
        val anchors = doc.select("div#episode-list div[data-id] a")
        val chapters = mutableListOf<SChapter>()

        val reqSegments = response.request.url.pathSegments
        val alias = reqSegments.getOrNull(2) ?: ""

        for (a in anchors) {
            val href = a.attr("href")
            val title = a.selectFirst("h3")?.text()?.trim() ?: a.text().trim()
            val epSegments = href.trimEnd('/').split('/')
            val episodeSlug = epSegments.lastOrNull() ?: continue

            var purchased: Boolean?
            try {
                val checkUri = "${baseUrl.trimEnd('/')}/lz-api/contents/v3/$alias/episodes/$episodeSlug?referrerViewType=NORMAL&objectType=comic"
                val checkReq = Request.Builder().url(checkUri).headers(apiHeaders()).get().build()
                val checkRes = client.newCall(checkReq).execute()
                val checkText = checkRes.body.string()
                purchased = if (checkText.isNotEmpty()) {
                    JSONObject(checkText).optJSONObject("data")?.optJSONObject("episode")?.optBoolean("isCollected")
                        ?: false
                } else {
                    null
                }
            } catch (_: Throwable) {
                purchased = null
            }

            val isLocked = purchased == false || purchased == null

            if (hidePremium && isLocked) continue

            val chap = SChapter.create().apply {
                url = href
                val lock = if (isLocked) "🔒 " else ""
                name = lock + title
            }
            chapters.add(chap)
        }

        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val url = "${baseUrl.trimEnd('/')}${chapter.url}"
        return Request.Builder().url(url).headers(defaultHeaders()).get().build()
    }

    override fun pageListParse(response: Response): List<Page> {
        val requestUrl = response.request.url
        val segments = requestUrl.pathSegments
        val alias = segments.getOrNull(2) ?: throw Exception("Invalid chapter URL segments")
        val name = segments.lastOrNull() ?: throw Exception("Invalid chapter URL segments")

        var purchased: Boolean
        try {
            val checkUri = "${baseUrl.trimEnd('/')}/lz-api/contents/v3/$alias/episodes/$name?referrerViewType=NORMAL&objectType=comic"
            val checkReq = Request.Builder().url(checkUri).headers(apiHeaders()).get().build()
            val checkRes = client.newCall(checkReq).execute()
            val checkText = checkRes.body.string()
            purchased = if (checkText.isNotEmpty()) {
                JSONObject(checkText).optJSONObject("data")?.optJSONObject("episode")?.optBoolean("isCollected") ?: false
            } else {
                false
            }
        } catch (_: Throwable) {
            purchased = false
        }

        val uri = "${apiBase}inventory_groups/comic_viewer?platform=web&store=web&preload=false&type=comic_episode&alias=$alias&name=$name"
        val req = Request.Builder().url(uri).headers(apiHeaders()).get().build()
        val res = client.newCall(req).execute()
        val resBody = res.body
        val text = resBody.string()
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
                createSignedImageUrl(comicId, episodeId, purchased, baseCandidate)
            } catch (_: Throwable) {
                baseCandidate
            }

            val safeUrl = finalUrl.ifEmpty { baseCandidate }

            if (imageShuffle && episodeId >= 0) {
                val fragment = "lezhin_eid=$episodeId;cols=5"
                pages.add(Page(i, "", "$safeUrl#$fragment"))
                continue
            }

            pages.add(Page(i, "", safeUrl))
        }
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
        tokenManager.getToken()?.let { b.set("Authorization", "Bearer $it") }
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
        tokenManager.getToken()?.let { b.set("Authorization", "Bearer $it") }
        return b.build()
    }

    private var cdnBase: String? = null

    private fun updateCDN() {
        try {
            val req = Request.Builder().url("$baseUrl/account").headers(defaultHeaders()).get().build()
            val res = client.newCall(req).execute()
            val body = res.body.string()
            val idx = body.indexOf("window.__LZ_CONFIG__")
            if (idx >= 0) {
                val snippetStart = (idx - 200).coerceAtLeast(0)
                val snippetEnd = (idx + 3000).coerceAtMost(body.length)
                val snippet = body.substring(snippetStart, snippetEnd)
                Regex("""contentsCdnUrl\s*:\s*['"]([^'"]+)['"]""").find(snippet)?.let {
                    cdnBase = it.groupValues[1]
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

    private fun resolveCdnOrAbsolute(path: String): String = if (path.startsWith("http")) {
        path
    } else {
        cdnBase?.trimEnd('/')?.plus(path) ?: (baseUrl.trimEnd('/') + path)
    }

    private fun createSignedImageUrl(comicId: Int, episodeId: Int, purchased: Boolean, imageUrl: String): String {
        val purchasedStr = if (purchased) "true" else "false"
        val uri = "${apiBase}cloudfront/signed-url/generate?contentId=$comicId&episodeId=$episodeId&purchased=$purchasedStr&q=40&firstCheckType=P"
        val req = Request.Builder().url(uri).headers(apiHeaders()).get().build()
        val res = client.newCall(req).execute()
        val text = res.body.string()
        val root = JSONObject(text)
        val data = root.getJSONObject("data")
        val policy = data.optString("Policy")
        val signature = data.optString("Signature")
        val keyPair = data.optString("Key-Pair-Id")
        val query = "Policy=${URLEncoder.encode(policy, "UTF-8")}&Signature=${URLEncoder.encode(signature, "UTF-8")}&Key-Pair-Id=${URLEncoder.encode(keyPair, "UTF-8")}"
        return if (imageUrl.contains("?")) "$imageUrl&$query" else "$imageUrl?$query"
    }

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        var request = chain.request()
        tokenManager.getToken()?.let { token ->
            request = request.newBuilder().header("Authorization", "Bearer $token").build()
        }
        val response = chain.proceed(request)

        if (response.code == 401) {
            response.close()
            val relogged = tokenManager.attemptRelogin()
            if (relogged) {
                tokenManager.getToken()?.let { token2 ->
                    val retryReq = request.newBuilder().header("Authorization", "Bearer $token2").build()
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

        val params = fragment.split(';').associate {
            val (k, v) = it.split('=', limit = 2)
            k to v
        }
        val eps = params["lezhin_eid"]?.toIntOrNull() ?: return chain.proceed(request)
        val cols = params["cols"]?.toIntOrNull() ?: 5

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response

        val bytes = try {
            response.body.bytes()
        } catch (_: Throwable) {
            null
        } ?: return response

        val descrambled = try {
            LezhinDescrambler.descramble(bytes, eps, cols)
        } catch (_: Throwable) {
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
            val now = System.currentTimeMillis()
            val token = preferences.getString(PREF_TOKEN, "") ?: ""
            val expires = preferences.getLong(PREF_TOKEN_EXPIRES, 0L)
            if (token.isNotBlank() && (expires == 0L || now < expires)) return token

            try {
                val req = Request.Builder().url(baseUrl).get().build()
                val res = network.cloudflareClient.newCall(req).execute()
                val body = res.body.string()
                Regex("""accessToken['"]?\s*:\s*['"]([A-Za-z0-9._=\-]+)['"]""").find(body)?.let {
                    val found = it.groupValues[1]
                    if (found.isNotBlank()) {
                        saveToken(found, 0L)
                        return found
                    }
                }
            } catch (_: Throwable) {}

            val email = preferences.getString(PREF_EMAIL, "") ?: ""
            val password = preferences.getString(PREF_PASSWORD, "") ?: ""
            if (email.isNotBlank() && password.isNotBlank()) {
                try {
                    if (loginAndSave(email, password)) {
                        return preferences.getString(PREF_TOKEN, "")
                    }
                } catch (_: Throwable) {}
            }

            return null
        }

        fun initializeIfNeeded() {
            try {
                getToken()
            } catch (_: Throwable) {}
        }

        @Synchronized
        fun attemptRelogin(): Boolean {
            val email = preferences.getString(PREF_EMAIL, "") ?: ""
            val password = preferences.getString(PREF_PASSWORD, "") ?: ""
            if (email.isNotBlank() && password.isNotBlank()) {
                try {
                    return loginAndSave(email, password)
                } catch (_: Throwable) {}
            }
            return false
        }

        fun clearTokens() {
            preferences.edit().remove(PREF_TOKEN).remove(PREF_TOKEN_EXPIRES).remove(PREF_USER_ID).apply()
        }

        private fun loginAndSave(email: String, password: String): Boolean {
            try {
                val apiLoginUrl = if (baseUrl.endsWith("/")) "$baseUrl/api/authentication/login" else "$baseUrl/api/authentication/login"
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
                    res.close()
                    return false
                }
                val txt = res.body.string()
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
                    return true
                }
            } catch (_: Throwable) {}
            return false
        }

        private fun saveToken(access: String, expiresInSeconds: Long) {
            val expiryTs = if (expiresInSeconds > 0L) System.currentTimeMillis() + expiresInSeconds * 1000L else 0L
            preferences.edit()
                .putString(PREF_TOKEN, access)
                .putLong(PREF_TOKEN_EXPIRES, expiryTs)
                .apply()
        }
    }
}
