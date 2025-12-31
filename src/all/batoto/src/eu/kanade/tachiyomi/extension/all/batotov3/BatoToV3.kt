package eu.kanade.tachiyomi.extension.all.batotov3

import android.app.Application
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.getValue
import kotlin.random.Random

open class BatoToV3(
    final override val lang: String,
    private val siteLang: String,
    private val preferences: SharedPreferences,
    ) : ConfigurableSource, HttpSource() {


    override val name: String = "Bato.to V3"

    override val baseUrl: String get() = getMirrorPref()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val mirrorPref = ListPreference(screen.context).apply {
            key = "${MIRROR_PREF_KEY}_$lang"
            title = MIRROR_PREF_TITLE
            entries = MIRROR_PREF_ENTRIES
            entryValues = MIRROR_PREF_ENTRY_VALUES
            setDefaultValue(MIRROR_PREF_DEFAULT_VALUE)
            summary = "%s"
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
        val userIdPref = EditTextPreference(screen.context).apply {
            key = "${USER_ID_PREF}_$lang"
            title = "User ID (Default Auto-Detect)"
            summary = if (getUserIdPref().isNotEmpty()) {
                "Manually Provided ID: ${getUserIdPref()}"
            } else {
                "Auto-detect from logged-in user"
            }
            setDefaultValue("")

            setOnPreferenceChangeListener { _, newValue ->
                val newId = newValue as String
                summary = if (newId.isNotEmpty()) {
                    // Validate it's numeric
                    if (newId.matches(Regex("\\d+"))) {
                        "Manually Provided ID: $newId"
                    } else {
                        Toast.makeText(screen.context, "User ID must be numeric", Toast.LENGTH_SHORT).show()
                        return@setOnPreferenceChangeListener false
                    }
                } else {
                    "Auto-detect from logged-in user"
                }
                true
            }
        }
        screen.addPreference(mirrorPref)
        screen.addPreference(removeOfficialPref)
        screen.addPreference(removeCustomPref)
        screen.addPreference(userIdPref)
    }

    private fun getMirrorPref(): String {
        if (System.getenv("CI") == "true") {
            return (MIRROR_PREF_ENTRY_VALUES.drop(1) + DEPRECATED_MIRRORS).joinToString("#, ")
        }

        return preferences.getString("${MIRROR_PREF_KEY}_$lang",
            MIRROR_PREF_DEFAULT_VALUE
        )
            ?.takeUnless { it == MIRROR_PREF_DEFAULT_VALUE }
            ?: let {
                /* Semi-sticky mirror:
                 * - Don't randomize on boot
                 * - Don't randomize per language
                 * - Fallback for non-Android platform
                 */
                val seed = runCatching {
                    val pm = Injekt.get<Application>().packageManager
                    pm.getPackageInfo(BuildConfig.APPLICATION_ID, 0).lastUpdateTime
                }.getOrElse {
                    BuildConfig.VERSION_NAME.hashCode().toLong()
                }

                MIRROR_PREF_ENTRY_VALUES.drop(1).random(Random(seed))
            }
    }

    private fun isRemoveTitleVersion(): Boolean {
        return preferences.getBoolean("${REMOVE_TITLE_VERSION_PREF}_$lang", false)
    }
    private fun customRemoveTitle(): String =
        preferences.getString("${REMOVE_TITLE_CUSTOM_PREF}_$lang", "")!!

    private fun getUserIdPref(): String =
        preferences.getString("${USER_ID_PREF}_$lang", "")!!

    internal fun migrateMirrorPref() {
        val selectedMirror = preferences.getString("${MIRROR_PREF_KEY}_$lang",
            MIRROR_PREF_DEFAULT_VALUE
        )!!

        if (selectedMirror in DEPRECATED_MIRRORS) {
            preferences.edit().putString("${MIRROR_PREF_KEY}_$lang",
                MIRROR_PREF_DEFAULT_VALUE
            ).apply()
        }
    }

    override val supportsLatest = true

    private val json: Json by injectLazy()


    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(4)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int) = searchMangaRequest(page, "", FilterList(SortFilter("field_score")))

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int) = searchMangaRequest(page, "", FilterList(SortFilter("field_upload")))

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("ID:")) {
            val id = query.substringAfter("ID:")
            val manga = SManga.create().apply { url = "/series/$id/" }
            return fetchMangaDetails(manga).map {
                MangasPage(listOf(it), false)
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val payloadObj = ApiSearchPayload(
            pageNumber = page,
            size = BROWSE_PAGE_SIZE,
            query = query.trim(),
            incTLangs = siteLang,
            sort = filters.firstInstanceOrNull<SortFilter>()?.selected,
            incGenres = filters.firstInstanceOrNull<GenreGroupFilter>()?.included,
            excGenres = filters.firstInstanceOrNull<GenreGroupFilter>()?.excluded,
            origStatus = filters.firstInstanceOrNull<OriginalStatusFilter>()?.selected,
            batoStatus = filters.firstInstanceOrNull<BatoStatusFilter>()?.selected,
            incOLangs = filters.firstInstanceOrNull<OriginGroupFilter>()?.selected,
            chapCount = filters.firstInstanceOrNull<ChapterCountFilter>()?.selected,
        )

        return apiRequest(payloadObj)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val searchResponse = response.parseAs<ApiSearchResponse>()

        val hasNextPage = searchResponse.data.search.paging.let {
            it.total > it.page
        }

        return searchResponse.data.search.items
            .map { items ->
                items.data.toSManga()
                    .apply { initialized = true }
            }
            .let { MangasPage(it, hasNextPage) }
    }

    override fun getFilterList() = filters

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.split("/")[2]

        val payloadObj = ApiQueryPayload(id, DETAILS_QUERY)

        return apiRequest(payloadObj)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaData = response.parseAs<ApiDetailsResponse>()

        return mangaData.data.comicNode.data.toSManga()
    }

    override fun getMangaUrl(manga: SManga): String {
        val url = manga.url.split("/")
        return "$baseUrl/title/${url[2]}-${url[3]}"
    }

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.split("/")[2]

        val payloadObj = ApiQueryPayload(id, CHAPTERS_QUERY)

        return apiRequest(payloadObj)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapterListResponse = response.parseAs<ApiChapterListResponse>().data.response

        return chapterListResponse
            .map {
                it.data.toSChapter().apply {
                    url = chapterIdRegex.find(url)?.groupValues?.get(1) ?: url
                }
            }
            .reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return "$baseUrl/${chapter.url}"
    }

    override fun pageListRequest(chapter: SChapter): Request {
        // Force current mirror if on a deprecated mirror
        val chapterUrl = if (chapter.url.startsWith("http")) {
            val httpUrl = chapter.url.toHttpUrl()
            if ("https://${httpUrl.host}" in DEPRECATED_MIRRORS) {
                val newHttpUrl = httpUrl.newBuilder().host(getMirrorPref().toHttpUrl().host)
                newHttpUrl.build().toString()
            } else {
                chapter.url
            }
        } else {
            getChapterUrl(chapter)
        }

        // Extract chapter ID from URL (format: /title/{titleId}-{title}/{chapterId}-{ch_chapterNumber})
        val id = chapterIdRegex.find(chapterUrl)?.groupValues?.get(1)
            ?: throw Exception("Could not extract chapter ID from URL: $chapterUrl")

        val apiVariables = ApiChapterNodeVariables(id = id)
        val query = CHAPTER_NODE_QUERY

        return sendGraphQLRequest(apiVariables, query)
    }

    override fun pageListParse(response: Response): List<Page> {
        val pages = response.parseAs<ApiPageListResponse>()

        return pages.data.pageList.data.imageFiles?.mapIndexed { index, image ->
            Page(index, "", image)
        } ?: emptyList()
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    private inline fun <reified R> List<*>.firstInstanceOrNull(): R? =
        filterIsInstance<R>().firstOrNull()

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString(body.string())

    private inline fun <reified T> apiRequest(payloadObj: T): Request {
        val payload = json.encodeToString(payloadObj)
            .toRequestBody(JSON_MEDIA_TYPE)

        val apiHeaders = headersBuilder()
            .add("Content-Length", payload.contentLength().toString())
            .add("Content-Type", payload.contentType().toString())
            .build()

        return POST("$baseUrl/apo", apiHeaders, payload)
    }

    companion object {
        private val titleIdRegex = Regex("""title/(\d+)""")
        private val idRegex = Regex("""(\d+)""")
        private val chapterIdRegex = Regex("""title/[^/]+/(\d+)""")
        private val userIdRegex = Regex("""/u/(\d+)""")
        private const val MIRROR_PREF_KEY = "MIRROR"
        private const val MIRROR_PREF_TITLE = "Mirror"
        private const val REMOVE_TITLE_VERSION_PREF = "REMOVE_TITLE_VERSION"
        private const val REMOVE_TITLE_CUSTOM_PREF = "REMOVE_TITLE_CUSTOM"
        private const val USER_ID_PREF = "USER_ID"
        private val MIRROR_PREF_ENTRIES = arrayOf(
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
            // "bato.si", // (v4)
            // "bato.ing", // (v4)
        )

        private val MIRROR_PREF_ENTRY_VALUES = MIRROR_PREF_ENTRIES.map { "https://$it" }.toTypedArray()
        private val MIRROR_PREF_DEFAULT_VALUE = MIRROR_PREF_ENTRY_VALUES[0]

        private val DEPRECATED_MIRRORS = listOf<String>()

        private const val BROWSE_PAGE_SIZE = 36
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

        private val titleRegex: Regex =
            Regex("\\([^()]*\\)|\\{[^{}]*\\}|\\[(?:(?!]).)*]|«[^»]*»|〘[^〙]*〙|「[^」]*」|『[^』]*』|≪[^≫]*≫|﹛[^﹜]*﹜|〖[^〖〗]*〗|\uD81A\uDD0D.+?\uD81A\uDD0D|《[^》]*》|⌜.+?⌝|⟨[^⟩]*⟩|【[^】]*】|([|].*)|([/].*)|([~].*)|-[^-]*-|‹[^›]*›|/Official|/ Official", RegexOption.IGNORE_CASE)
    }
}
