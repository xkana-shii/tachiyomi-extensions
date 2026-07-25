package eu.kanade.tachiyomi.extension.en.desirescans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Source
    class DesireScans : KeiSource() {

    override val supportsLatest = true

    // KNS
    private var filterCacheLoaded = false
    private var dynamicSorts: List<Pair<String, String>> = listOf("Recently Updated" to "updated")
    private var dynamicStatuses: List<Pair<String, String>> = listOf("All" to "")
    private var dynamicOrigins: List<Pair<String, String>> = listOf("All Origins" to "")
    private var dynamicTypes: List<Pair<String, String>> = listOf("All" to "")
    private var dynamicGenres: List<Pair<String, String>> = emptyList()
    private var dynamicTags: List<Pair<String, String>> = emptyList()
    // KNS

    override fun popularMangaRequest(page: Int): Request {
        // KNS
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("series")
            .addQueryParameter("sort", "popular")
            .addQueryParameter("page", page.toString())
            .build()
        // KNS
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        // KNS
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("series")
            .addQueryParameter("sort", "updated")
            .addQueryParameter("page", page.toString())
            .build()
        // KNS
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // KNS
        val activeFilters = if (filters.isEmpty()) getFilterList() else filters
        val builder = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("series")
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            builder.addQueryParameter("q", query)
        }

        activeFilters.forEach { filter ->
            when (filter) {
                is UriFilter -> filter.addToUrl(builder)
                is GenreFilterGroup -> {
                    val selected = filter.state.filter { it.state }.map { it.value }
                    if (selected.isNotEmpty()) {
                        builder.addQueryParameter("genres", selected.joinToString(","))
                    }
                }
                is TagFilterGroup -> {
                    val selected = filter.state.filter { it.state }.map { it.value }
                    if (selected.isNotEmpty()) {
                        builder.addQueryParameter("tags", selected.joinToString(","))
                    }
                }
                else -> {}
            }
        }

        val typeFilter = activeFilters.firstOrNull { it is DynamicTypeFilter } as? DynamicTypeFilter
        val selectedType = typeFilter?.entries?.getOrNull(typeFilter.state)?.second.orEmpty()
        if (selectedType.isBlank()) {
            builder.addQueryParameter("type", "Manhwa,Manhua,Manga,Webtoon")
        }

        // KNS
        return GET(builder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        // KNS
        val document = response.asDocument()
        ensureDynamicFilters(document)

        val mangas = document.select("a[href*=/series/], a[href^=/series/]")
            .distinctBy { it.absUrl("href") }
            .mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { return@mapNotNull null }
                val title = a.attr("title").ifBlank {
                    a.selectFirst("h1, h2, h3, h4, .title")?.text().orEmpty()
                }.trim()
                if (title.isBlank()) return@mapNotNull null

                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = a.selectFirst("img")?.let { img ->
                        img.absUrl("src")
                            .ifBlank { img.absUrl("data-src") }
                            .ifBlank { img.absUrl("data-lazy-src") }
                    }
                }
            }

        val hasNextPage = document.selectFirst("a[rel=next], a:matchesOwn((?i)next)") != null
        // KNS
        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asDocument()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst("img")?.absUrl("src")
            description = document.selectFirst("meta[name=description]")?.attr("content")
                ?: document.selectFirst(".description, .summary, .content")?.text()
            genre = document.select("a[href*=genre], a[href*=tag]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    override fun pageListParse(response: Response): List<Page> = emptyList()

    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("Dynamic filters are fetched from /series payload"),
            DynamicSortFilter(dynamicSorts),
            DynamicStatusFilter(dynamicStatuses),
            DynamicOriginFilter(dynamicOrigins),
            DynamicTypeFilter(dynamicTypes),
            GenreFilterGroup(dynamicGenres.ifEmpty { listOf("No genres loaded yet" to "") }),
            TagFilterGroup(dynamicTags.ifEmpty { listOf("No tags loaded yet" to "") }),
        )
    }

    // KNS
    private fun ensureDynamicFilters(document: Document) {
        if (filterCacheLoaded) return

        val raw = buildString {
            document.select("script").forEach {
                append(it.data())
                append('\n')
                append(it.html())
                append('\n')
            }
        }

        if (raw.isBlank()) return

        dynamicGenres = extractObjectNameSlugPairs(raw, "genres")
        dynamicTags = extractObjectNameSlugPairs(raw, "tags")

        dynamicSorts = extractSortOptions(raw).ifEmpty { dynamicSorts }
        dynamicStatuses = extractStringArray(raw, "A")
            .map { it to it }
            .ifEmpty { dynamicStatuses }

        dynamicOrigins = extractOriginOptions(raw).ifEmpty { dynamicOrigins }

        dynamicTypes = extractStringArray(raw, "z")
            .filter { it.contains("manhwa", true) || it.contains("manhua", true) || it.contains("manga", true) || it.contains("webtoon", true) }
            .filterNot { it.contains("novel", true) }
            .distinct()
            .map { it to it }
            .let { list -> if (list.isEmpty()) dynamicTypes else listOf("All" to "") + list }

        filterCacheLoaded = true
    }

    private fun extractObjectNameSlugPairs(input: String, key: String): List<Pair<String, String>> {
        val blockRegex = Regex(""""$key"\s*:\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
        val block = blockRegex.find(input)?.groupValues?.get(1).orEmpty()
        if (block.isBlank()) return emptyList()

        val itemRegex = Regex("""\{[^{}]*?"name"\s*:\s*"([^"]+)"[^{}]*?"slug"\s*:\s*"([^"]+)"[^{}]*?}""")
        return itemRegex.findAll(block)
            .map { it.groupValues[1].trim() to it.groupValues[2].trim() }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .distinctBy { it.second.lowercase() }
            .toList()
    }

    private fun extractStringArray(input: String, variableName: String): List<String> {
        val regex = Regex("""\b$variableName\s*=\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
        val body = regex.find(input)?.groupValues?.get(1).orEmpty()
        if (body.isBlank()) return emptyList()

        return Regex(""""([^"]+)"""").findAll(body)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun extractOriginOptions(input: String): List<Pair<String, String>> {
        val blockRegex = Regex("""B\s*=\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
        val block = blockRegex.find(input)?.groupValues?.get(1).orEmpty()
        if (block.isBlank()) return emptyList()

        val itemRegex = Regex("""\{[^{}]*?value:\s*"([^"]*)"[^{}]*?label:\s*"([^"]+)"[^{}]*?}""")
        val parsed = itemRegex.findAll(block)
            .map { it.groupValues[2].trim() to it.groupValues[1].trim() }
            .filter { it.first.isNotBlank() }
            .toList()

        return if (parsed.any { it.second.isEmpty() }) parsed else listOf("All Origins" to "") + parsed
    }

    private fun extractSortOptions(input: String): List<Pair<String, String>> {
        val itemRegex = Regex("""value:\s*"([^"]+)"\s*,\s*label:\s*"([^"]+)"""")
        val parsed = itemRegex.findAll(input)
            .map { it.groupValues[2].trim() to it.groupValues[1].trim() }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .distinctBy { it.second }
            .toList()

        return parsed
    }

    private fun Response.asDocument(): Document = Jsoup.parse(body.string(), request.url.toString())
    // KNS
}
