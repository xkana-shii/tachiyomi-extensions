package eu.kanade.tachiyomi.multisrc.kemono

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.kemono.KemonoCreatorDto.Companion.serviceName
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.lang.Thread.sleep
import java.net.URLEncoder
import java.util.TimeZone
import kotlin.math.min
import kotlin.time.Duration.Companion.minutes

open class Kemono(
    override val name: String,
    private val defaultBaseUrl: String,
    override val lang: String = "all",
) : HttpSource(),
    ConfigurableSource {
    override val supportsLatest = true

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, defaultBaseUrl) ?: defaultBaseUrl

    private val credentials: Credential
        get() = Credential(
            username = preferences.getString(PAWCHIVE_USERNAME_PREF, "") ?: "",
            password = preferences.getString(PAWCHIVE_PASSWORD_PREF, "") ?: "",
        )

    private data class Credential(val username: String, val password: String)

    @Volatile
    private var loginState = LoginState.UNKNOWN

    private val loginLock = Any()

    private enum class LoginState {
        UNKNOWN,
        LOGGED_IN,
        FAILED,
    }

    private val kemonoBaseUrl: String = "https://kemono.cr"

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.pathSegments.first() == "api") {
                chain.proceed(request.newBuilder().header("Accept", "text/css").build())
            } else {
                chain.proceed(request)
            }
        }
        .addInterceptor(::loginInterceptor)
        .apply {
            val index = networkInterceptors().indexOfFirst { it is BrotliInterceptor }
            if (index >= 0) interceptors().add(networkInterceptors().removeAt(index))
        }
        .cache(
            Cache(
                directory = File(Injekt.get<Application>().externalCacheDir, "network_cache_${name.lowercase()}"),
                maxSize = 50L * 1024 * 1024, // 50 MiB
            ),
        )
        .rateLimit(1)
        .build()

    private val creatorsClient = client.newBuilder()
        .readTimeout(5.minutes)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val preferences = getPreferences()

    private val apiPath = "api/v1"

    private val dataPath = "data"

    private val dataBaseUrl: String
        get() {
            val url = baseUrl.trimEnd('/')
            return when {
                url.contains("://pawchive.", ignoreCase = true) -> {
                    url.replace("://pawchive.", "://file.pawchive.", ignoreCase = true)
                }
                else -> url
            }
        }

    private val imgCdnUrl: String
        get() = baseUrl.replace("//", "//img.")

    private val kemonoImgCdnUrl: String
        get() = kemonoBaseUrl.replace("//", "//img.")

    private fun String.formatAvatarUrl(): String = removePrefix("https://").replaceBefore('/', imgCdnUrl)

    // For thumbnails, always use Kemono CDN
    private fun String.formatAvatarUrlThumbnail(): String = removePrefix("https://").replaceBefore('/', kemonoImgCdnUrl)

    private fun String.encodeQueryValue(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    private fun String.asPawchiveFileUrl(): String {
        val normalizedHost = when {
            contains("://pawchive.", ignoreCase = true) -> {
                replace("://pawchive.", "://file.pawchive.", ignoreCase = true)
            }
            else -> this
        }

        val parsed = normalizedHost.toHttpUrlOrNull() ?: return normalizedHost
        val fileName = parsed.queryParameter("f") ?: return normalizedHost

        return parsed.newBuilder()
            .removeAllQueryParameters("f")
            .addEncodedQueryParameter("f", fileName.encodeQueryValue())
            .build()
            .toString()
    }

    private fun buildDataUrl(pathWithFileName: String): String {
        val rawPath = pathWithFileName.substringBefore("?f=")
        val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        val fileName = pathWithFileName.substringAfter("?f=", missingDelimiterValue = "")

        val builder = "${dataBaseUrl.trimEnd('/')}/$dataPath$path"
            .toHttpUrl()
            .newBuilder()

        if (fileName.isNotBlank()) {
            builder.addEncodedQueryParameter("f", fileName.encodeQueryValue())
        }

        return builder.build().toString()
    }

    private fun resetLoginState() {
        loginState = LoginState.UNKNOWN
    }

    private fun shouldAutoLogin(): Boolean {
        val loginCredentials = credentials
        return baseUrl.contains("://pawchive.", ignoreCase = true) &&
            loginCredentials.username.isNotBlank() &&
            loginCredentials.password.isNotBlank()
    }

    private fun loginInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!shouldAutoLogin()) {
            return chain.proceed(request)
        }

        if (loginState == LoginState.UNKNOWN) {
            synchronized(loginLock) {
                if (loginState == LoginState.UNKNOWN) {
                    loginState = if (performLogin()) LoginState.LOGGED_IN else LoginState.FAILED
                }
            }
        }

        return chain.proceed(request)
    }

    private fun performLogin(): Boolean {
        val loginCredentials = credentials

        if (loginCredentials.username.isBlank() || loginCredentials.password.isBlank()) {
            return false
        }

        return try {
            val loginForm = FormBody.Builder()
                .add("username", loginCredentials.username)
                .add("password", loginCredentials.password)
                .add("location", "/artists")
                .build()

            val loginRequest = POST("$baseUrl/account/login", headers, loginForm)
            network.client.newCall(loginRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    showLoginFailedToast()
                    return false
                }
            }

            val checkRequest = GET(
                "$baseUrl/$apiPath/account/favorites",
                headers.newBuilder().set("Accept", "text/css").build(),
            )
            network.client.newCall(checkRequest).execute().use { response ->
                response.isSuccessful.also { success ->
                    if (!success) {
                        showLoginFailedToast()
                    }
                }
            }
        } catch (_: Exception) {
            showLoginFailedToast()
            false
        }
    }

    private fun showLoginFailedToast() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                Injekt.get<Application>(),
                "Pawchive login failed. Please check your username/email and password.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()

    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        searchMangas(page, sortBy = "pop" to "desc")
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        searchMangas(page, sortBy = "lat" to "desc")
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable.fromCallable {
        searchMangas(page, query, filters)
    }

    private fun searchMangas(page: Int = 1, title: String = "", filters: FilterList? = null, sortBy: Pair<String, String> = "" to ""): MangasPage {
        var sort = sortBy
        val typeIncluded: MutableList<String> = mutableListOf()
        val typeExcluded: MutableList<String> = mutableListOf()
        var fav: Boolean? = null
        filters?.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    sort = filter.getValue() to if (filter.state!!.ascending) "asc" else "desc"
                }

                is TypeFilter -> {
                    filter.state.filter { state -> state.isIncluded() }.forEach { tri ->
                        typeIncluded.add(tri.value)
                    }

                    filter.state.filter { state -> state.isExcluded() }.forEach { tri ->
                        typeExcluded.add(tri.value)
                    }
                }

                is FavoritesFilter -> {
                    fav = when (filter.state[0].state) {
                        0 -> null
                        1 -> true
                        else -> false
                    }
                }

                else -> {}
            }
        }

        val mangas = run {
            val favorites = if (fav != null) {
                val response = client.newCall(GET("$baseUrl/$apiPath/account/favorites", headers)).execute()

                if (response.isSuccessful) {
                    val favs = response.parseAs<List<KemonoFavoritesDto>>().filterNot { it.service.lowercase() == "discord" }
                    favs
                } else {
                    response.close()
                    val message = if (response.code == 401) "You are not logged in" else "HTTP error ${response.code}"
                    throw Exception("Failed to fetch favorites: $message")
                }
            } else {
                emptyList()
            }

            val creatorUrl = "$baseUrl/$apiPath/creators"
            val request = GET(
                creatorUrl,
                headers,
                CacheControl.Builder().maxStale(30.minutes).build(),
            )
            val response = creatorsClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw Exception("HTTP error ${response.code}")
            }
            val allCreators = response.parseAs<List<KemonoCreatorDto>>().filterNot { it.service.lowercase() == "discord" }

            val filtered = allCreators.filter {
                val includeType = typeIncluded.isEmpty() || typeIncluded.contains(it.service.serviceName().lowercase())
                val excludeType = typeExcluded.isNotEmpty() && typeExcluded.contains(it.service.serviceName().lowercase())

                val regularSearch = it.name.contains(title, true)

                val isFavorited = when (fav) {
                    true -> favorites.any { f -> f.id == it.id.also { _ -> it.fav = f.faved_seq } }
                    false -> favorites.none { f -> f.id == it.id }
                    else -> true
                }

                includeType && !excludeType && isFavorited &&
                    regularSearch
            }
            filtered
        }

        val sorted = when (sort.first) {
            "pop" -> {
                if (sort.second == "desc") {
                    mangas.sortedByDescending { it.favorited }
                } else {
                    mangas.sortedBy { it.favorited }
                }
            }

            "tit" -> {
                if (sort.second == "desc") {
                    mangas.sortedByDescending { it.name }
                } else {
                    mangas.sortedBy { it.name }
                }
            }

            "new" -> {
                if (sort.second == "desc") {
                    mangas.sortedByDescending { it.id }
                } else {
                    mangas.sortedBy { it.id }
                }
            }

            "fav" -> {
                if (fav != true) throw Exception("Please check 'Favorites Only' Filter")
                if (sort.second == "desc") {
                    mangas.sortedByDescending { it.fav }
                } else {
                    mangas.sortedBy { it.fav }
                }
            }

            else -> {
                if (sort.second == "desc") {
                    mangas.sortedByDescending { it.updatedDate }
                } else {
                    mangas.sortedBy { it.updatedDate }
                }
            }
        }
        val maxIndex = mangas.size
        val fromIndex = (page - 1) * PAGE_CREATORS_LIMIT
        val toIndex = min(maxIndex, fromIndex + PAGE_CREATORS_LIMIT)

        val final = sorted.subList(fromIndex, toIndex).map { it.toSManga(kemonoImgCdnUrl) }
        return MangasPage(final, toIndex != maxIndex)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        manga.thumbnail_url = manga.thumbnail_url!!.formatAvatarUrlThumbnail()
        return Observable.just(manga)
    }

    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url.replace("$apiPath/", "")}"

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        KemonoPostDto.dateFormat.timeZone = when (manga.author) {
            "Pixiv Fanbox", "Fantia" -> TimeZone.getTimeZone("GMT+09:00")
            else -> TimeZone.getTimeZone("GMT")
        }
        val prefMaxPost = preferences.getString(POST_PAGES_PREF, POST_PAGES_DEFAULT)!!
            .toInt().coerceAtMost(POST_PAGES_MAX) * PAGE_POST_LIMIT

        var offset = 0
        var hasNextPage = true
        val result = ArrayList<SChapter>()
        while (offset < prefMaxPost && hasNextPage) {
            val postsUrl = "$baseUrl/$apiPath${manga.url}/posts?o=$offset"
            val request = GET(postsUrl, headers)
            val response = retry(request)
            val page: List<KemonoPostDto> = response.parseAs()

            page.forEach { post ->
                if (post.images.isNotEmpty()) {
                    result.add(post.toSChapter())
                }
            }

            offset += PAGE_POST_LIMIT
            hasNextPage = page.size == PAGE_POST_LIMIT
        }
        result
    }

    private fun retry(request: Request): Response {
        var code = 0
        repeat(5) { attempt ->
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                return response
            }
            response.close()
            code = response.code
            if (code == 429) {
                sleep(10000)
            }
        }
        throw Exception("HTTP error $code")
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/$apiPath${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val responseBody = response.body!!.string()

        // Try parsing as wrapped (Kemono) first, then as direct post (Pawchive)
        val post = try {
            val wrapped = json.decodeFromString<KemonoPostDtoWrapped>(responseBody)
            wrapped.post ?: run {
                json.decodeFromString<KemonoPostDto>(responseBody)
            }
        } catch (_: Exception) {
            json.decodeFromString<KemonoPostDto>(responseBody)
        }

        val pages = post.images.mapIndexed { i, path ->
            val imageUrl = buildDataUrl(path)
            Page(i, imageUrl = imageUrl)
        }
        return pages
    }

    private fun String.asPawchiveThumbnailUrl(): String {
        val fileUrl = asPawchiveFileUrl()
        val parsed = fileUrl.toHttpUrlOrNull() ?: return fileUrl

        if (!parsed.host.contains("file.pawchive.", ignoreCase = true)) {
            return fileUrl
        }

        val thumbnailHost = parsed.host.replace("file.pawchive.", "img.pawchive.", ignoreCase = true)
        val dataPath = parsed.encodedPath.removePrefix("/thumbnail")

        // Pawchive thumbnails are served without the ?f= filename query.
        // Example: https://img.pawchive.st/thumbnail/data/c4/ad/hash.gif
        return "${parsed.scheme}://$thumbnailHost/thumbnail$dataPath"
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl!!.asPawchiveFileUrl()

        if (!preferences.getBoolean(USE_LOW_RES_IMG, false)) {
            return GET(imageUrl, headers)
        }

        if (imageUrl.contains("://file.pawchive.", ignoreCase = true)) {
            val url = imageUrl.asPawchiveThumbnailUrl()
            return GET(url, headers)
        }

        val index = imageUrl.indexOf('/', 8)
        val url = buildString {
            append(imageUrl, 0, index)
            append("/thumbnail")
            append(imageUrl.substring(index))
        }
        return GET(url, headers)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Domain/Mirror"
            summary = "Select which domain to use\nCurrently: %s"
            entryValues = arrayOf("https://kemono.cr", "https://pawchive.st")
            entries = arrayOf("Kemono", "Pawchive")
            setDefaultValue(defaultBaseUrl)
            setOnPreferenceChangeListener { _, _ ->
                resetLoginState()
                true
            }
        }.let { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = PAWCHIVE_USERNAME_PREF
            title = "Pawchive username/email"
            summary = "Optional. Used only when the selected mirror is Pawchive."
            setOnPreferenceChangeListener { _, _ ->
                resetLoginState()
                true
            }
        }.let { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = PAWCHIVE_PASSWORD_PREF
            title = "Pawchive password"
            summary = "Optional. Used only when the selected mirror is Pawchive."
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, _ ->
                resetLoginState()
                true
            }
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = POST_PAGES_PREF
            title = "Maximum posts to load"
            summary = "Loading more posts costs more time and network traffic.\nCurrently: %s"
            entryValues = Array(POST_PAGES_MAX) { (it + 1).toString() }
            entries = Array(POST_PAGES_MAX) { "${(it + 1)} pages (${(it + 1) * PAGE_POST_LIMIT} posts)" }
            setDefaultValue(POST_PAGES_DEFAULT)
        }.let { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = USE_LOW_RES_IMG
            title = "Use low resolution images"
            summary = "Reduce load time significantly. When turning off, clear chapter cache to remove cached low resolution images."
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    // Filters

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(
            "Sort by",
            Filter.Sort.Selection(0, false),
            getSortsList,
        ),
        TypeFilter("Types", getTypes),
        FavoritesFilter(),
    )

    open val getTypes: List<String> = emptyList()

    open val getSortsList: List<Pair<String, String>> = listOf(
        Pair("Popularity", "pop"),
        Pair("Date Indexed", "new"),
        Pair("Date Updated", "lat"),
        Pair("Alphabetical Order", "tit"),
        Pair("Service", "serv"),
        Pair("Date Favorited", "fav"),
    )

    open class TypeFilter(name: String, vals: List<String>) :
        Filter.Group<TriFilter>(
            name,
            vals.map { TriFilter(it, it.lowercase()) },
        )

    internal class FavoritesFilter :
        Filter.Group<TriFilter>(
            "Favorites",
            listOf(TriFilter("Favorites Only", "fav")),
        )

    open class TriFilter(name: String, val value: String) : Filter.TriState(name)

    internal open class SortFilter(name: String, selection: Selection, private val vals: List<Pair<String, String>>) : Filter.Sort(name, vals.map { it.first }.toTypedArray(), selection) {
        fun getValue() = vals[state!!.index].second
    }

    companion object {
        private const val PAGE_POST_LIMIT = 50
        private const val PAGE_CREATORS_LIMIT = 50
        const val PROMPT = "You can change how many posts to load in the extension preferences."

        private const val POST_PAGES_PREF = "POST_PAGES"
        private const val POST_PAGES_DEFAULT = "1"
        private const val POST_PAGES_MAX = 75

        private const val BASE_URL_PREF = "BASE_URL"
        private const val PAWCHIVE_USERNAME_PREF = "PAWCHIVE_USERNAME"
        private const val PAWCHIVE_PASSWORD_PREF = "PAWCHIVE_PASSWORD"
        private const val USE_LOW_RES_IMG = "USE_LOW_RES_IMG"
    }
}
