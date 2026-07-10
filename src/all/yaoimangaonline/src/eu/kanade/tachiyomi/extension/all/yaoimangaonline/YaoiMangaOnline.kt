package eu.kanade.tachiyomi.extension.all.yaoimangaonline

import android.text.Html
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

@Source
abstract class YaoiMangaOnline : HttpSource() {

    override val supportsLatest = false

    private val excludedCategoryIds = setOf(
        "2009", // Yaoi Anime
        "3017", // Gay Movies
        "1852", // Gay Novels
        "199", // Yaoi Games Online
        "15275", // Completed (subcategory of Yaoi Webtoons)
        "15442", // Hiatus (subcategory of Yaoi Webtoons)
        "15276", // Ongoing (subcategory of Yaoi Webtoons)
    )

    private val excludedCategoryNames = setOf(
        "Yaoi Anime",
        "Gay Movies",
        "Gay Novels",
        "Yaoi Games Online",
        "Completed",
        "Hiatus",
        "Ongoing",
    )

    private var typeData: Array<Pair<String, String>> = arrayOf("ALL" to "")
    private var doujinshiData: Array<Pair<String, String>> = arrayOf("ALL" to "")
    private var tagData: Array<Pair<String, String>> = arrayOf("ALL" to "")
    private var filtersInitialized = false

    private fun cleanCategoryName(raw: String): String = raw
        .replace(Regex("\\s*\\(\\d[\\d,]*\\)$"), "")
        .replace("&amp;", "&")
        .trim()

    private fun fetchFilters() {
        if (filtersInitialized) return

        try {
            val response = client.newCall(GET(baseUrl, headers)).execute()
            val doc = response.asJsoup()

            val categoryOptions = doc.select("#cat option[value]")
            Log.d("YaoiMangaOnline", "Found ${categoryOptions.size} category options")

            val allCategories = categoryOptions.mapNotNull { option ->
                val value = option.attr("value").trim()
                val rawName = option.text().trim()
                val cleanName = cleanCategoryName(rawName)

                if (
                    value == "-1" ||
                    value.isEmpty() ||
                    rawName.isEmpty() ||
                    value in excludedCategoryIds ||
                    cleanName in excludedCategoryNames
                ) {
                    null
                } else {
                    cleanName to value
                }
            }

            val djRegex = Regex("(?i)\\bdj\\b|\\bdoujinshi\\b")

            typeData = (
                listOf("ALL" to "") + allCategories
                    .filter { !djRegex.containsMatchIn(it.first) }
                    .map { it.first.trim() to it.second.trim() }
                )
                .toTypedArray()

            doujinshiData = (
                listOf("ALL" to "") + allCategories
                    .filter { djRegex.containsMatchIn(it.first) }
                    .map { it.first.trim() to it.second.trim() }
                )
                .toTypedArray()

            val tags = doc.select(".tagcloud a.tag-cloud-link")
                .mapNotNull { a ->
                    val name = a.text().trim()
                    val href = a.attr("href").trim()
                    val slug = href.trimEnd('/').substringAfterLast("/tag/").trimEnd('/').trim()
                    if (name.isEmpty() || slug.isEmpty()) null else name to slug
                }

            tagData = (listOf("ALL" to "") + tags).toTypedArray()

            filtersInitialized = true
            Log.d(
                "YaoiMangaOnline",
                "Filters initialized: ${typeData.size} types, ${doujinshiData.size} doujinshi, ${tagData.size} tags",
            )
        } catch (e: Exception) {
            Log.e("YaoiMangaOnline", "Failed to fetch filters", e)
        }
    }

    // =================== Popular ===================

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".post:not(.category-gay-movies):not(.category-yaoi-anime):not(.category-gay-novels):not(.category-yaoi-games-online) > div > a")
            .map { element ->
                SManga.create().apply {
                    title = element.attr("title")
                    setUrlWithoutDomain(element.absUrl("href"))
                    thumbnail_url = element.selectFirst("img")?.attr("src")
                }
            }
        val hasNextPage = document.selectFirst(".herald-pagination > .next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // =================== Latest ===================

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // =================== Search ===================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = baseUrl.toHttpUrl().newBuilder().run {
        var categoryId: String? = null
        filters.forEach {
            when (it) {
                is TypeFilter -> if (it.state != 0) categoryId = it.toString()
                is DoujinshiFilter -> if (it.state != 0) categoryId = it.toString()
                else -> {}
            }
        }
        if (categoryId != null) {
            addQueryParameter("cat", categoryId)
        }
        filters.forEach {
            when (it) {
                is TagFilter -> if (it.state != 0) {
                    addEncodedPathSegments("tag/$it")
                }
                else -> {}
            }
        }
        addEncodedPathSegments("page/$page")
        addQueryParameter("s", query)
        GET(toString(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // =================== Details ===================

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val document = response.asJsoup()
        title = document.select("h1.entry-title").text()
            .substringBeforeLast("by").trim()
        thumbnail_url = document.selectFirst(".herald-post-thumbnail img")?.attr("src")
        description = document
            .select(".entry-content > p:not(:has(img)):not(:contains(You need to login))")
            .joinToString("\n\n") {
                @Suppress("DEPRECATION")
                Html.fromHtml(it.html()).toString().trim()
            }
            .let { text ->
                val languageIndex = text.indexOf("Language:", ignoreCase = true)
                if (languageIndex != -1) {
                    text.substring(text.indexOf('\n', languageIndex).takeIf { it != -1 } ?: text.length)
                } else {
                    text
                }
            }
            .trim()

        // KNS
        genre = document.select(".meta-tags > a")
            .map { it.text().trim() }
            .filter {
                it.isNotEmpty() &&
                    it !in excludedCategoryNames &&
                    !it.equals("Yaoi Anime", ignoreCase = true) &&
                    !it.equals("Gay Movies", ignoreCase = true) &&
                    !it.equals("Gay Novels", ignoreCase = true) &&
                    !it.equals("Yaoi Games Online", ignoreCase = true)
            }
            .joinToString()
            .ifEmpty { null }
        // KNS

        author = document.select(".entry-content > p:matches((?i)(Author|Mangaka|Artist):)").text()
            .substringAfter(":")
            .substringBefore("Language:")
            .trim()
            .ifEmpty { null }

        // KNS
        artist = author
        // KNS

        // KNS
        status = when {
            document.selectFirst(".meta-category a[href*='/ongoing/']") != null -> SManga.ONGOING
            document.selectFirst(".meta-category a[href*='/completed/']") != null -> SManga.COMPLETED
            document.selectFirst(".meta-category a[href*='/hiatus/']") != null -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        // KNS
    }

    // =================== Chapters ===================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select(".mpp-toc a").map { element ->
            SChapter.create().apply {
                name = element.ownText()
                setUrlWithoutDomain(element.absUrl("href").ifEmpty { element.baseUri() })
            }
        }
        return chapters.ifEmpty {
            listOf(
                SChapter.create().apply {
                    name = "Chapter"
                    url = response.request.url.encodedPath
                },
            )
        }.reversed()
    }

    // =================== Pages ===================

    override fun pageListParse(response: Response) = response.asJsoup()
        .select(".entry-content img")
        .mapIndexed { idx, img -> Page(idx, imageUrl = img.attr("src")) }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList {
        fetchFilters()
        return FilterList(
            TypeFilter(typeData),
            DoujinshiFilter(doujinshiData),
            TagFilter(tagData),
        )
    }
}
