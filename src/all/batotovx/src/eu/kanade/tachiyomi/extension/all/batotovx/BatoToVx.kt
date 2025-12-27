package eu.kanade.tachiyomi.extension.all.batotovx

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.batoto.BatoTo
import eu.kanade.tachiyomi.extension.all.batotov4.BatoToV4
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Response
import rx.Observable
import kotlin.getValue

open class BatoToVx(
    final override val lang: String,
    private val siteLang: String,
) : ConfigurableSource, HttpSource() {

    override val name: String = "Bato.to Vx"

    override val baseUrl: String get() = _delegate.baseUrl

    private val preferences by getPreferencesLazy()

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

    private fun siteVer(): String {
        return preferences.getString("${SITE_VER_PREF_KEY}_$lang", SITE_VER_PREF_DEFAULT_VALUE) ?: SITE_VER_PREF_DEFAULT_VALUE
    }

    private val _delegate: HttpSource =
        when (siteVer()) {
            "v4" -> BatoToV4(lang, siteLang)
            else -> BatoTo(lang, siteLang)
        }

    override val supportsLatest = _delegate.supportsLatest

    // ************ Search ************ //
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return _delegate.fetchLatestUpdates(page)
    }

    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return _delegate.fetchPopularManga(page)
    }

    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return _delegate.fetchMangaDetails(manga)
    }

    // searchMangaRequest is not used, see fetchSearchManga instead
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return _delegate.fetchSearchManga(page, query, filters)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return _delegate.fetchChapterList(manga)
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return _delegate.fetchPageList(chapter)
    }

    override fun fetchImageUrl(page: Page): Observable<String> {
        return _delegate.fetchImageUrl(page)
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()
    override fun pageListParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun getFilterList() = _delegate.getFilterList()

    companion object {
        private const val SITE_VER_PREF_KEY = "SITE_VER"
        private const val SITE_VER_PREF_TITLE = "Site version"
        private val SITE_VER_PREF_ENTRIES = arrayOf(
            "v2",
            "v4",
        )
        private val SITE_VER_PREF_DEFAULT_VALUE = SITE_VER_PREF_ENTRIES[0]
    }
}
