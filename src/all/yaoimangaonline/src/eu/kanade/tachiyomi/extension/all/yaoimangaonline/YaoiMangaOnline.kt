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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class YaoiMangaOnline : HttpSource() {
    override val lang = "all"
    override val name = "Yaoi Manga Online"
    override val baseUrl = "https://yaoimangaonline.com"
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
                    return@mapNotNull null
                }

                Log.d("YaoiMangaOnline", "Category: '$cleanName' -> '$value'")
                Pair(cleanName, value)
            }

            Log.d("YaoiMangaOnline", "Total matched categories: ${allCategories.size}")

            val djRegex = Regex("(?i)\\b(dj|DJ)$")

            typeData = arrayOf("ALL" to "") + allCategories
                .filter { !djRegex.containsMatchIn(it.first) }
                .map { Pair(it.first.trim(), it.second.trim()) }
                .also { Log.d("YaoiMangaOnline", "Type categories: ${it.size}") }
                .toTypedArray()

            doujinshiData = arrayOf("ALL" to "") + allCategories
                .filter { djRegex.containsMatchIn(it.first) }
                .map { Pair(it.first.trim(), it.second.trim()) }
                .also { Log.d("YaoiMangaOnline", "Doujinshi categories: ${it.size}") }
                .toTypedArray()

            val tagLinks = doc.select(".tagcloud a.tag-cloud-link")
            Log.d("YaoiMangaOnline", "Found ${tagLinks.size} tag links")

            tagData = arrayOf("ALL" to "") + tagLinks
                .mapNotNull { a ->
                    val tagName = a.text().trim()
                    val href = a.attr("href").trim()
                    val slug = href.trimEnd('/').substringAfterLast("/tag/").trimEnd('/').trim()
                    Log.d("YaoiMangaOnline", "Tag: $tagName -> href=$href slug=$slug")
                    if (tagName.isEmpty() || slug.isEmpty()) {
                        null
                    } else {
                        Pair(tagName, slug)
                    }
                }
                .toTypedArray()

            filtersInitialized = true
            Log.d(
                "YaoiMangaOnline",
                "Filters initialized: ${typeData.size} types, ${doujinshiData.size} doujinshi, ${tagData.size} tags",
            )
        } catch (e: Exception) {
            Log.e("YaoiMangaOnline", "Failed to fetch filters", e)
        }
    }

    override fun getFilterList(): FilterList {
        fetchFilters()
        return FilterList(
            TypeFilter(typeData),
            DoujinshiFilter(doujinshiData),
            TagFilter(tagData),
        )
    }

    // =================== Popular ===================

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(searchMangaSelector()).map { element ->
            searchMangaFromElement(element)
        }
        val hasNextPage = document.selectFirst(searchMangaNextPageSelector()) != null
        return MangasPage(mangas, hasNextPage)
    }

    // =================== Latest ===================

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // =================== Search ===================

    private fun searchMangaSelector() =
        ".post:not(.category-gay-movies):not(.category-yaoi-anime):not(.sticky) > div > a"

    private fun searchMangaNextPageSelector() = ".herald-pagination > .next"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var categoryId: String? = null

        filters.forEach {
            when (it) {
                is TypeFilter -> if (it.state != 0) categoryId = it.toString()
                is DoujinshiFilter -> if (it.state != 0) categoryId = it.toString()
                else -> {}
            }
        }

        return baseUrl.toHttpUrl().newBuilder().run {
            if (categoryId != null) {
                Log.d("YaoiMangaOnline", "Applying category filter: $categoryId")
                addQueryParameter("cat", categoryId)
            }
            filters.forEach {
                when (it) {
                    is TagFilter -> if (it.state != 0) {
                        Log.d("YaoiMangaOnline", "Applying tag filter: $it")
                        addEncodedPathSegments("tag/$it")
                    }
                    else -> {}
                }
            }
            addEncodedPathSegments("page/$page")
            addQueryParameter("s", query)
            val url = toString()
            Log.d("YaoiMangaOnline", "Search URL: $url")
            GET(url, headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private fun searchMangaFromElement(element: Element) = SManga.create().apply {
        title = element.attr("title").trim()
        setUrlWithoutDomain(element.attr("href").trim())
        thumbnail_url = element.selectFirst("img")?.attr("src")?.trim()
    }

    // =================== Details ===================

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val document = response.asJsoup()

        title = document.select("h1.entry-title").text().trim()
            .substringBeforeLast("by").trim()

        thumbnail_url = document
            .selectFirst(".herald-post-thumbnail img")?.attr("src")?.trim()

        description = document.select(".entry-content > p:not(:has(img)):not(:contains(You need to login))")
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

        genre = document.select(".meta-tags > a").joinToString { it.text().trim() }

        author = document.select(".entry-content > p:matches((?i)(Author|Mangaka):)")
            .text()
            .trim()
            .replace(Regex("(?i)^.*?(Author|Mangaka):\\s*"), "")
            .substringBefore("Language:")
            .trim()

        status = when {
            document.selectFirst(".meta-category a[href*='/ongoing/']") != null -> SManga.ONGOING
            document.selectFirst(".meta-category a[href*='/completed/']") != null -> SManga.COMPLETED
            document.selectFirst(".meta-category a[href*='/hiatus/']") != null -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    // =================== Chapters ===================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select(".mpp-toc a").map { element ->
            SChapter.create().apply {
                name = element.ownText().trim()
                setUrlWithoutDomain(element.attr("href").ifEmpty { element.baseUri() }.trim())
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

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select(".entry-content img").mapIndexed { idx, img ->
            Page(idx, "", img.attr("src").trim())
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
