package eu.kanade.tachiyomi.extension.all.batoto

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.batotov2.BatoToV2
import eu.kanade.tachiyomi.extension.all.batotov3.BatoToV3
import eu.kanade.tachiyomi.extension.all.batotov4.BatoToV4
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Response
import rx.Observable
import java.util.concurrent.ConcurrentHashMap

open class BatoTo(
    final override val lang: String,
    private val siteLang: String = lang,
) : ConfigurableSource, HttpSource() {

    override val name: String = "Bato.to"

    override val id: Long = when (lang) {
        "zh-Hans" -> 2818874445640189582
        "zh-Hant" -> 38886079663327225
        "ro-MD" -> 8871355786189601023
        else -> super.id
    }

    private val preferences by getPreferencesLazy()

    private fun siteVer(): String {
        return preferences.getString("${SITE_VER_PREF_KEY}_$lang", SITE_VER_PREF_DEFAULT_VALUE) ?: SITE_VER_PREF_DEFAULT_VALUE
    }

    private val _delegate: HttpSource
        get() = when (siteVer()) {
            "v4" -> BatoToV4(lang, siteLang, preferences)
            "v3" -> BatoToV3(lang, siteLang, preferences)
            else -> BatoToV2(lang, siteLang, preferences)
        }

    override val client get() = _delegate.client

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val siteVerPref = ListPreference(screen.context).apply {
            key = "${SITE_VER_PREF_KEY}_$lang"
            title = SITE_VER_PREF_TITLE
            entries = SITE_VER_PREF_ENTRIES
            entryValues = SITE_VER_PREF_ENTRIES
            setDefaultValue(SITE_VER_PREF_DEFAULT_VALUE)
            summary = "%s"
        }

        screen.addPreference(siteVerPref)
        (_delegate as ConfigurableSource).setupPreferenceScreen(screen)
    }

    override val baseUrl: String get() = _delegate.baseUrl

    override val supportsLatest get() = _delegate.supportsLatest

    // ************ Search ************ //
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun fetchLatestUpdates(page: Int) = _delegate.fetchLatestUpdates(page)

    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun fetchPopularManga(page: Int) = _delegate.fetchPopularManga(page)

    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()
    override fun fetchMangaDetails(manga: SManga) = _delegate.fetchMangaDetails(manga)

    override fun getMangaUrl(manga: SManga) = _delegate.getMangaUrl(manga)
    override fun getChapterUrl(chapter: SChapter) = _delegate.getChapterUrl(chapter)

    // searchMangaRequest is not used, see fetchSearchManga instead
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) = _delegate.fetchSearchManga(page, query, filters)

    override fun fetchChapterList(manga: SManga) = _delegate.fetchChapterList(manga)
    override fun fetchPageList(chapter: SChapter) = _delegate.fetchPageList(chapter)
    override fun fetchImageUrl(page: Page) = _delegate.fetchImageUrl(page)

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()
    override fun pageListParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun getFilterList() = _delegate.getFilterList()

    companion object {
        const val BATOTO_REMOVE_TITLE_CUSTOM_PREF = "BATOTO_REMOVE_TITLE_CUSTOM"
        private const val SITE_VER_PREF_KEY = "SITE_VER"
        private const val SITE_VER_PREF_TITLE = "Site version"
        private val SITE_VER_PREF_ENTRIES = arrayOf(
            "v2",
            "v3",
            "v4",
        )
        private val SITE_VER_PREF_DEFAULT_VALUE = SITE_VER_PREF_ENTRIES[0]
    }
}

/**
 * Manages image server fallback logic, including blacklisting and backoff tracking.
 */
class ImageServerManager() {
    val serverPattern = Regex("https://([a-zA-Z]\\d{2})")

    val fallbackServers = listOf(
        "n03", "n00", "n01", "n02", "n04", "n05", "n06", "n07", "n08", "n09", "n10",
    )
    val blacklist = listOf("k00", "k01", "k03", "k04", "k05", "k06", "k07", "k08", "k09")

    // Server status tracking
    data class ServerStatus(
        val canBackoff: Boolean,
        val statusCode: Int = 0,
        val timestamp: Long = System.currentTimeMillis(),
    )

    val serverStatus = ConcurrentHashMap<String, ServerStatus>()

    val BACKOFF_DURATION_MS = 3_600_000L // 1 hour

    fun shouldSkip(server: String): Boolean {
        return server in blacklist || isInBackoff(server)
    }

    fun isInBackoff(server: String): Boolean {
        val status = serverStatus[server] ?: return false
        return status.canBackoff && System.currentTimeMillis() - status.timestamp < BACKOFF_DURATION_MS
    }

    fun recordImageServerStatus(server: String, statusCode: Int) {
        val now = System.currentTimeMillis()
        serverStatus[server] = ServerStatus(
            canBackoff = statusCode in 500..599,
            statusCode = statusCode,
            timestamp = now,
        )
    }

    fun extractServerFromUrl(url: String): String? {
        return serverPattern.find(url)?.groups?.get(1)?.value
    }

    fun replaceServerInUrl(url: String, newServer: String): String {
        return url.replace(serverPattern, "https://$newServer")
    }
}
