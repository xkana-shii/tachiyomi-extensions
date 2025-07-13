package eu.kanade.tachiyomi.extension.all.mangapark

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.lib.cookieinterceptor.CookieInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MangaPark(
    override val lang: String,
    private val siteLang: String = lang,
) : HttpSource(), ConfigurableSource {

    override val name = "MangaPark"

    override val supportsLatest = true

    override val versionId = 2

    // Renamed to 'preferences' to avoid clash with the getter, constructor parameter
    private val preferences: SharedPreferences = getPreferences()

    // Override the `preference` getter from ConfigurableSource to use the injected preferences
    override val preference: SharedPreferences
        get() = preferences

    private val domain =
        preference.getString(MIRROR_PREF_KEY, MIRROR_PREF_DEFAULT) ?: MIRROR_PREF_DEFAULT

    override val baseUrl = "https://$domain"

    private val apiUrl = "$baseUrl/apo/"

    override val client = network.cloudflareClient.newBuilder().apply {
        if (preference.getBoolean(ENABLE_NSFW, true)) {
            addInterceptor(::siteSettingsInterceptor)
            addNetworkInterceptor(CookieInterceptor(domain, "nsfw" to "2"))
        }
        rateLimitHost(apiUrl.toHttpUrl(), 1)
        // intentionally after rate limit interceptor so thumbnails are not rate limited
        addInterceptor(::thumbnailDomainInterceptor)
    }.build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int) = searchMangaRequest(page, "", SortFilter.POPULAR)
    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int) = searchMangaRequest(page, "", SortFilter.LATEST)
    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val payload = GraphQL(
            SearchVariables(
                SearchPayload(
                    page = page,
                    size = size,
                    query = query.takeUnless(String::isEmpty),
                    incGenres = filters.firstInstanceOrNull<GenreFilter>()?.included,
                    excGenres = filters.firstInstanceOrNull<GenreFilter>()?.excluded,
                    incTLangs = listOf(siteLang),
                    incOLangs = filters.firstInstanceOrNull<OriginalLanguageFilter>()?.checked,
                    sortby = filters.firstInstanceOrNull<SortFilter>()?.selected,
                    chapCount = filters.firstInstanceOrNull<ChapterCountFilter>()?.selected,
                    origStatus = filters.firstInstanceOrNull<OriginalStatusFilter>()?.selected,
                    siteStatus = filters.firstInstanceOrNull<UploadStatusFilter>()?.selected,
                ),
            ),
            SEARCH_QUERY,
        ).toJsonRequestBody()

        return POST(apiUrl, headers, payload)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.parseAs<SearchResponse>()
        val pageAsCover = preference.getString(UNCENSORED_COVER_PREF, "off")!!
        val shortenTitle = preference.getBoolean(SHORTEN_TITLE_PREF, false)
        val customTitleRegex = customRemoveTitle() // Retrieve custom regex
        val downloadedVersionRegex = getDownloadedVersionRegex() // Retrieve the downloaded regex
        val enableVersionRemoval = preference.getBoolean(REMOVE_TITLE_VERSION_PREF, false) // Check if feature is enabled

        val entries = result.data.searchComics.items.map {
            it.data.toSManga(
                shortenTitle,
                pageAsCover,
                customTitleRegex,
                downloadedVersionRegex, // Pass downloaded regex
                enableVersionRemoval, // Pass enable flag
            )
        }
        val hasNextPage = entries.size == size

        return MangasPage(entries, hasNextPage)
    }

    private var genreCache: List<Pair<String, String>> = emptyList()
    private var genreFetchAttempt = 0

    private fun getGenres() {
        if (genreCache.isEmpty() && genreFetchAttempt < 3) {
            val elements = runCatching {
                client.newCall(GET("$baseUrl/search", headers)).execute()
                    .use { it.asJsoup() }
                    .select("div.flex-col:contains(Genres) div.whitespace-nowrap")
            }.getOrNull().orEmpty()

            genreCache = elements.mapNotNull {
                val name = it.selectFirst("span.whitespace-nowrap")
                    ?.text()?.takeUnless(String::isEmpty)
                    ?: return@mapNotNull null

                val key = it.attr("q:key")
                    .takeUnless(String::isEmpty) ?: return@mapNotNull null

                Pair(name, key)
            }
            genreFetchAttempt++
        }
    }

    override fun getFilterList(): FilterList {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching(::getGenres)
        }

        val filters = mutableListOf<Filter<*>>(
            SortFilter(),
            OriginalLanguageFilter(),
            OriginalStatusFilter(),
            UploadStatusFilter(),
            ChapterCountFilter(),
        )

        if (genreCache.isEmpty()) {
            filters += listOf(
                Filter.Separator(),
                Filter.Header("Press 'reset' to attempt to load genres"),
            )
        } else {
            filters.addAll(1, listOf(GenreFilter(genreCache)))
        }

        return FilterList(filters)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val payload = GraphQL(
            IdVariables(manga.url.substringAfterLast("#")),
            DETAILS_QUERY,
        ).toJsonRequestBody()

        return POST(apiUrl, headers, payload)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<DetailsResponse>()
        val pageAsCover = preference.getString(UNCENSORED_COVER_PREF, "off")!!
        val shortenTitle = preference.getBoolean(SHORTEN_TITLE_PREF, false)
        val customTitleRegex = customRemoveTitle() // Retrieve custom regex
        val downloadedVersionRegex = getDownloadedVersionRegex() // Retrieve the downloaded regex
        val enableVersionRemoval = preference.getBoolean(REMOVE_TITLE_VERSION_PREF, false) // Check if feature is enabled

        return result.data.comic.data.toSManga(
            shortenTitle,
            pageAsCover,
            customTitleRegex,
            downloadedVersionRegex, // Pass downloaded regex
            enableVersionRemoval, // Pass enable flag
        )
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url.substringBeforeLast("#")

    override fun chapterListRequest(manga: SManga): Request {
        val payload = GraphQL(
            IdVariables(manga.url.substringAfterLast("#")),
            CHAPTERS_QUERY,
        ).toJsonRequestBody()

        return POST(apiUrl, headers, payload)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ChapterListResponse>()
        val enableRawChapters = preference.getBoolean(SHOW_RAW_CHAPTERS_PREF, false)

        return if (preference.getBoolean(DUPLICATE_CHAPTER_PREF_KEY, false)) {
            result.data.chapterList.flatMap { chapterData ->
                // Filter out raw chapters if the preference is off for the main chapter
                if (!enableRawChapters && chapterData.data.scanlator?.startsWith("raw", true) == true) {
                    emptyList()
                } else {
                    val mainChapter = chapterData.data.toSChapter()
                    // Filter duplicate chapters based on the raw chapters preference
                    val dupChapters = chapterData.data.dupChapters
                        .filter { dupChapterData ->
                            !(!enableRawChapters && dupChapterData.data.scanlator?.startsWith("raw", true) == true)
                        }
                        .map { it.data.toSChapter() }
                    listOf(mainChapter) + dupChapters
                }
            }.reversed() // Reverse after filtering and combining
        } else {
            result.data.chapterList.map { it.data.toSChapter() }
                .filter { chapterData -> // Apply filter for raw chapters here too
                    !(!enableRawChapters && chapterData.scanlator?.startsWith("raw", true) == true)
                }
                .reversed()
        }.sortedWith { c1, c2 -> // Ensure correct sorting after all filtering
            val chapterNum1 = c1.chapter_number
            val chapterNum2 = c2.chapter_number

            when {
                chapterNum1 != chapterNum2 -> chapterNum2.compareTo(chapterNum1)
                else -> c2.date_upload.compareTo(c1.date_upload) // If chapter numbers are same, sort by upload date
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url.substringBeforeLast("#")

    override fun pageListRequest(chapter: SChapter): Request {
        val payload = GraphQL(
            IdVariables(chapter.url.substringAfterLast("#")),
            PAGES_QUERY,
        ).toJsonRequestBody()

        return POST(apiUrl, headers, payload)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<PageListResponse>()

        return result.data.chapterPages.data.imageFile.urlList.mapIndexed { idx, url ->
            Page(idx, "", url)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = MIRROR_PREF_KEY
            title = "Preferred Mirror"
            entries = mirrors
            entryValues = mirrors
            setDefaultValue(MIRROR_PREF_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Restart the app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = DUPLICATE_CHAPTER_PREF_KEY
            title = "Fetch Duplicate Chapters"
            summary = "Refresh chapter list to apply changes"
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = ENABLE_NSFW
            title = "Enable NSFW content"
            summary = "Clear Cookies & Restart the app to apply changes."
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SHORTEN_TITLE_PREF
            title = "Remove extra information from title"
            summary = "Clear database to apply changes\n\n" +
                "Note: doesn't not work for entries in library"
            setDefaultValue(false)
        }.also(screen::addPreference)

        // New preference for online regex for version removal
        SwitchPreferenceCompat(screen.context).apply {
            key = REMOVE_TITLE_VERSION_PREF
            title = "Remove version information from title (online regex)"
            summary = "Downloads and applies a regex from an online source to remove version strings like '(v2)' from titles. Requires network."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    // Schedule the worker immediately if enabled
                    scheduleRegexRefresh(screen.context.applicationContext)
                    Toast.makeText(screen.context, "Attempting to download regex. Restart the app or refresh lists to apply changes.", Toast.LENGTH_LONG).show()
                } else {
                    // Cancel the worker if disabled
                    WorkManager.getInstance(screen.context.applicationContext).cancelUniqueWork("MangaParkRegexRefreshWork")
                }
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = REMOVE_TITLE_CUSTOM_PREF
            title = "Custom title regex"
            summary = "If not empty, replace title with custom regex. Example: `\\[\\d+\\]` for `[123]`"
            setDefaultValue("")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = UNCENSORED_COVER_PREF
            title = "Attempt to use Uncensored Cover for Hentai"
            summary = "Uses first or last chapter page as cover"
            entries = arrayOf("Off", "First Chapter", "Last Chapter")
            entryValues = arrayOf("off", "first", "last")
            setDefaultValue("off")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_RAW_CHAPTERS_PREF
            title = "Show raw chapters"
            summary = "Displays chapters marked as 'raw' in the chapter list."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private inline fun <reified T : Any> T.toJsonRequestBody() =
        toJsonString().toRequestBody(JSON_MEDIA_TYPE)

    private val cookiesNotSet = AtomicBoolean(true)
    private val latch = CountDownLatch(1)

    // sets necessary cookies to not block genres like `Hentai`
    private fun siteSettingsInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val settingsUrl = "$baseUrl/aok/settings-save"

        if (
            request.url.toString() != settingsUrl &&
            request.url.host == domain
        ) {
            if (cookiesNotSet.getAndSet(false)) {
                val payload =
                    """{"data":{"general_autoLangs":[],"general_userLangs":[],"general_excGenres":[],"general_prefLangs":[]}}"""
                        .toRequestBody(JSON_MEDIA_TYPE)

                client.newCall(POST(settingsUrl, headers, payload)).execute().close()

                latch.countDown()
            } else {
                latch.await()
            }
        }

        return chain.proceed(request)
    }

    private fun thumbnailDomainInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        return if (url.host == THUMBNAIL_LOOPBACK_HOST) {
            val newUrl = url.newBuilder()
                .host(domain)
                .build()

            val newRequest = request.newBuilder()
                .url(newUrl)
                .build()

            chain.proceed(newRequest)
        } else {
            chain.proceed(request)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    private fun customRemoveTitle(): Regex {
        val regex = preference.getString(REMOVE_TITLE_CUSTOM_PREF, "") ?: ""
        return if (regex.isNotBlank()) Regex(regex) else Regex("")
    }

    private fun getDownloadedVersionRegex(): Regex {
        // Retrieves the regex string downloaded by the worker from SharedPreferences
        val regex = preference.getString(REMOVE_TITLE_VERSION_DOWNLOADED_REGEX_KEY, "") ?: ""
        return if (regex.isNotBlank()) Regex(regex) else Regex("")
    }

    companion object {
        private const val size = 24
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

        private const val MIRROR_PREF_KEY = "pref_mirror"
        private const val MIRROR_PREF_DEFAULT = "mangapark.net"
        private val mirrors = arrayOf(
            "mangapark.net",
            "mangapark.com",
            "mangapark.org",
            "mangapark.me",
            "mangapark.io",
            "mangapark.to",
            "comicpark.org",
            "comicpark.to",
            "readpark.org",
            "readpark.net",
            "parkmanga.com",
            "parkmanga.net",
            "parkmanga.org",
            "mpark.to",
        )

        private const val ENABLE_NSFW = "pref_nsfw"
        private const val DUPLICATE_CHAPTER_PREF_KEY = "pref_dup_chapters"
        private const val SHORTEN_TITLE_PREF = "pref_shorten_title"
        private const val REMOVE_TITLE_CUSTOM_PREF = "pref_custom_title_regex"

        // New preference keys for the downloaded regex feature
        private const val REMOVE_TITLE_VERSION_PREF = "pref_remove_version_title"

        // Key for the actual downloaded regex string, accessible by the worker
        const val REMOVE_TITLE_VERSION_DOWNLOADED_REGEX_KEY = "pref_downloaded_version_regex"

        private const val UNCENSORED_COVER_PREF = "pref_uncensored_cover"
        private const val SHOW_RAW_CHAPTERS_PREF = "show_raw_chapters" // New preference key for raw chapters
    }
}

const val THUMBNAIL_LOOPBACK_HOST = "127.0.0.1"

/**
 * [RegexRefreshWorker] is a CoroutineWorker responsible for periodically fetching
 * the latest regex pattern from a remote URL and saving it to SharedPreferences.
 */
class RegexRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val client = OkHttpClient() // Ensure OkHttpClient is available. This usually comes from the app's DI or is globally accessible in a larger project.
            val request = Request.Builder()
                .url("https://raw.githubusercontent.com/xkana-shii/tachiyomi-extensions/master/regex.txt")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                // Log.e("RegexRefreshWorker", "Failed to fetch regex: ${response.code} ${response.message}")
                return Result.retry()
            }

            val regexText = response.body?.string()
                ?: run {
                    // Log.e("RegexRefreshWorker", "Fetched regex body is null.")
                    return Result.failure()
                }

            val preferences = applicationContext.getSharedPreferences(
                "MangaPark_Preferences", // THIS IS CRUCIAL: VERIFY THIS PREFERENCE FILE NAME
                // It needs to match how 'getPreferences()' in MangaPark
                // obtains its SharedPreferences instance.
                // If getPreferences() uses the default name for the source,
                // you might need a different way to get the preferences here.
                // For many Tachiyomi extensions, it's context.getSharedPreferences("source_name_preferences", MODE_PRIVATE)
                // For 'getPreferences()' in util, it often uses the package name or a specific key.
                // You may need to inspect `keiyoushi.utils.getPreferences` to confirm the exact name.
                // A common pattern is "${source.id}_preferences" or "${source.name}_preferences"
                Context.MODE_PRIVATE,
            )

            preferences.edit()
                .putString(MangaPark.REMOVE_TITLE_VERSION_DOWNLOADED_REGEX_KEY, regexText)
                .apply()

            Result.success()
        } catch (e: Exception) {
            // Log.e("RegexRefreshWorker", "Error refreshing regex", e)
            Result.retry()
        }
    }
}

/**
 * Schedules the periodic WorkManager task to refresh the online regex.
 * This function should be called when the application starts or when the user
 * explicitly enables the preference for online regex removal.
 *
 * @param context The application context.
 */
fun scheduleRegexRefresh(context: Context) {
    // Only schedule if the preference is actually enabled.
    // This check is important if this function is called outside of the preference change listener.
    val preferences = context.getSharedPreferences("MangaPark_Preferences", Context.MODE_PRIVATE) // Match the name used in worker
    if (preferences.getBoolean(MangaPark.REMOVE_TITLE_VERSION_PREF, false)) {
        val refreshRequest = PeriodicWorkRequestBuilder<RegexRefreshWorker>(
            12,
            TimeUnit.HOURS, // Repeat every 12 hours
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag("mangapark_regex_refresh_worker_tag")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "MangaParkRegexRefreshWork",
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing work if it's already scheduled
            refreshRequest,
        )
    } else {
        // If the preference is disabled, cancel any existing work
        WorkManager.getInstance(context).cancelUniqueWork("MangaParkRegexRefreshWork")
    }
}