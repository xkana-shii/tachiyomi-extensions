package eu.kanade.tachiyomi.extension.en.desirescans

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class DesireScans : KeiSource() {

    override val supportsFilterFetching = true

    override suspend fun getPopularManga(
        page: Int,
    ): MangasPage = getBrowsePage(
        page = page,
        forcedSort = POPULAR_SORT,
    )

    override suspend fun getLatestUpdates(
        page: Int,
    ): MangasPage = getBrowsePage(page = page)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = getBrowsePage(
        page = page,
        query = query,
        filters = filters,
    )

    private suspend fun getBrowsePage(
        page: Int,
        query: String = "",
        filters: FilterList = FilterList(),
        forcedSort: String? = null,
    ): MangasPage {
        val selectedTypes = filters
            .firstInstanceOrNull<TypeFilter>()
            ?.selectedValues
            ?: DEFAULT_TYPES

        if (selectedTypes.isEmpty()) {
            return MangasPage(
                mangas = emptyList(),
                hasNextPage = false,
            )
        }

        val urlBuilder = "$baseUrl/series"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter(
                "page",
                page.toString(),
            )
            .addQueryParameter(
                "type",
                selectedTypes.joinToString(),
            )

        query
            .takeIf { it.isNotBlank() }
            ?.let {
                urlBuilder.addQueryParameter(
                    "q",
                    it,
                )
            }

        val selectedSort = forcedSort
            ?: filters
                .firstInstanceOrNull<SortFilter>()
                ?.value
                .orEmpty()

        selectedSort
            .takeIf { it.isNotEmpty() }
            ?.let {
                urlBuilder.addQueryParameter(
                    "sort",
                    it,
                )
            }

        filters
            .firstInstanceOrNull<StatusFilter>()
            ?.value
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                urlBuilder.addQueryParameter(
                    "status",
                    it,
                )
            }

        filters
            .firstInstanceOrNull<OriginFilter>()
            ?.value
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                urlBuilder.addQueryParameter(
                    "origin",
                    it,
                )
            }

        filters
            .firstInstanceOrNull<OnSaleFilter>()
            ?.takeIf { it.state }
            ?.let {
                urlBuilder.addQueryParameter(
                    "sale",
                    "true",
                )
            }

        filters
            .firstInstanceOrNull<HasImagesFilter>()
            ?.takeIf { it.state }
            ?.let {
                urlBuilder.addQueryParameter(
                    "hasImages",
                    "true",
                )
            }

        filters
            .firstInstanceOrNull<MinimumChaptersFilter>()
            ?.state
            ?.trim()
            ?.takeIf {
                it.toIntOrNull() != null
            }
            ?.let {
                urlBuilder.addQueryParameter(
                    "minChapters",
                    it,
                )
            }

        filters
            .firstInstanceOrNull<MaximumChaptersFilter>()
            ?.state
            ?.trim()
            ?.takeIf {
                it.toIntOrNull() != null
            }
            ?.let {
                urlBuilder.addQueryParameter(
                    "maxChapters",
                    it,
                )
            }

        filters
            .firstInstanceOrNull<GenreFilter>()
            ?.let { genreFilter ->
                urlBuilder.addCsvParameter(
                    GENRE_PARAMETER,
                    genreFilter.includedValues,
                )

                urlBuilder.addCsvParameter(
                    EXCLUDED_GENRE_PARAMETER,
                    genreFilter.excludedValues,
                )
            }

        filters
            .firstInstanceOrNull<TagFilter>()
            ?.let { tagFilter ->
                urlBuilder.addCsvParameter(
                    TAG_PARAMETER,
                    tagFilter.includedValues,
                )

                urlBuilder.addCsvParameter(
                    EXCLUDED_TAG_PARAMETER,
                    tagFilter.excludedValues,
                )
            }

        val pageData = client
            .get(urlBuilder.build())
            .extractNextJs<BrowsePageDto> { element ->
                element is JsonObject &&
                    "initialSeries" in element &&
                    "initialHasMore" in element
            }
            ?: error(
                "Failed to parse DesireScans browse page",
            )

        return MangasPage(
            mangas = pageData.initialSeries.map {
                it.toSManga(baseUrl)
            },
            hasNextPage = pageData.initialHasMore,
        )
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val (pageData, authorName) = getSeriesPage(
            manga.url,
        )

        return SMangaUpdate(
            manga = pageData.series.toSManga(
                baseUrl = baseUrl,
                authorName = authorName,
            ),
            chapters = pageData.chapters
                .map { chapter ->
                    chapter.toSChapter(manga.url)
                }
                .sortedByDescending {
                    it.chapter_number
                },
        )
    }

    private suspend fun getSeriesPage(
        slug: String,
    ): Pair<SeriesPageDto, String?> {
        val document = client
            .get("$baseUrl/series/comic/$slug")
            .asJsoup()

        val pageData = document
            .extractNextJs<SeriesPageDto> { element ->
                element is JsonObject &&
                    "series" in element &&
                    "chapters" in element
            }
            ?: error(
                "Failed to parse DesireScans series page",
            )

        val authorName = document
            .getBookMetadata()
            ?.author
            ?.name

        return pageData to authorName
    }

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {
        val chapterUrl = getChapterUrl(chapter)

        val initialPages = client
            .get(chapterUrl)
            .asJsoup()
            .toPageList()

        if (initialPages.isNotEmpty()) {
            return initialPages
        }

        /*
         * DesireScans renders the reader images through client-side
         * JavaScript. When they are absent from the initial OkHttp
         * response, render the chapter in a WebView and parse the
         * resulting DOM.
         */
        val renderedHtml = runWebView<String> {
            onPageFinished { _ ->
                evaluateJs(
                    "document.documentElement.outerHTML",
                ) { html ->
                    resolve(html)
                }
            }

            loadUrl(chapterUrl)
        }

        return Jsoup
            .parse(
                renderedHtml,
                chapterUrl,
            )
            .toPageList()
    }

    private fun Document.toPageList(): List<Page> = select(
        "div[data-page] img[src], " +
            "img[alt^=\"Page\"][src]",
    )
        .mapNotNull { image ->
            image.extractImageUrl()
        }
        .distinct()
        .mapIndexed { index, imageUrl ->
            Page(
                index = index,
                imageUrl = imageUrl,
            )
        }

    override fun getMangaUrl(
        manga: SManga,
    ): String = "$baseUrl/series/comic/${manga.url}"

    override fun getChapterUrl(
        chapter: SChapter,
    ): String {
        val slug = chapter.url
            .substringBeforeLast("/")

        val chapterNumber = chapter.url
            .substringAfterLast("/")

        return "$baseUrl/series/comic/$slug/chapter/$chapterNumber"
    }

    override suspend fun getMangaByUrl(
        url: HttpUrl,
    ): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        val slug = url.extractSeriesSlug()
            ?: return null

        val (pageData, authorName) = getSeriesPage(slug)

        return pageData.series.toSManga(
            baseUrl = baseUrl,
            authorName = authorName,
        )
    }

    override suspend fun fetchFilterData(): JsonElement {
        val url = "$baseUrl/series"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter(
                "type",
                DEFAULT_TYPES.joinToString(),
            )
            .build()

        return client
            .get(url)
            .extractNextJs<JsonElement> { element ->
                element is JsonObject &&
                    "genres" in element &&
                    "tags" in element
            }
            ?: error(
                "Failed to parse DesireScans filter data",
            )
    }

    override fun getFilterList(
        data: JsonElement?,
    ): FilterList {
        val filterData = data?.let { element ->
            runCatching {
                element.parseAs<FilterDataDto>()
            }.getOrNull()
        }

        return FilterList(
            SortFilter(),
            TypeFilter(),
            StatusFilter(),
            OriginFilter(),
            OnSaleFilter(),
            HasImagesFilter(),
            MinimumChaptersFilter(),
            MaximumChaptersFilter(),
            GenreFilter(
                filterData?.genres.orEmpty(),
            ),
            TagFilter(
                filterData?.tags.orEmpty(),
            ),
        )
    }

    private fun Document.getBookMetadata(): BookDto? = select(
        "script[type=application/ld+json]",
    )
        .mapNotNull { script ->
            runCatching {
                script
                    .data()
                    .parseAs<BookDto>()
            }.getOrNull()
        }
        .firstOrNull {
            it.type == BOOK_SCHEMA_TYPE
        }

    private fun Element.extractImageUrl(): String? {
        val source = absUrl("src")
            .ifEmpty {
                attr("src")
            }
            .ifEmpty {
                absUrl("data-src")
            }
            .ifEmpty {
                attr("data-src")
            }
            .takeIf {
                it.isNotEmpty()
            }
            ?: return null

        val parsedSource = runCatching {
            source.toHttpUrl()
        }.getOrNull()

        val unwrappedSource = if (
            parsedSource?.encodedPath == NEXT_IMAGE_PATH
        ) {
            parsedSource.queryParameter(
                NEXT_IMAGE_URL_PARAMETER,
            ) ?: source
        } else {
            source
        }

        return resolveUrl(unwrappedSource)
    }

    private fun HttpUrl.extractSeriesSlug(): String? {
        val comicIndex = pathSegments.indexOf(
            COMIC_PATH_SEGMENT,
        )

        if (
            comicIndex <= 0 ||
            pathSegments.getOrNull(comicIndex - 1) !=
            SERIES_PATH_SEGMENT
        ) {
            return null
        }

        return pathSegments
            .getOrNull(comicIndex + 1)
            ?.takeIf {
                it.isNotEmpty()
            }
    }

    private fun HttpUrl.Builder.addCsvParameter(
        name: String,
        values: List<String>,
    ): HttpUrl.Builder {
        if (values.isNotEmpty()) {
            addQueryParameter(
                name,
                values.joinToString(),
            )
        }

        return this
    }

    private fun resolveUrl(
        url: String,
    ): String = baseUrl
        .toHttpUrl()
        .resolve(url)
        ?.toString()
        ?: url

    companion object {
        private val DEFAULT_TYPES = listOf(
            "Manhwa",
            "Manhua",
            "Manga",
            "Webtoon",
        )

        private const val POPULAR_SORT =
            "popular"

        private const val GENRE_PARAMETER =
            "genre"

        private const val TAG_PARAMETER =
            "tag"

        private const val EXCLUDED_GENRE_PARAMETER =
            "excludeGenre"

        private const val EXCLUDED_TAG_PARAMETER =
            "excludeTag"

        private const val SERIES_PATH_SEGMENT =
            "series"

        private const val COMIC_PATH_SEGMENT =
            "comic"

        private const val BOOK_SCHEMA_TYPE =
            "Book"

        private const val NEXT_IMAGE_PATH =
            "/_next/image"

        private const val NEXT_IMAGE_URL_PARAMETER =
            "url"
    }
}
