package eu.kanade.tachiyomi.extension.all.yaoimangaonline

import android.text.Html
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class YaoiMangaOnline : ParsedHttpSource() {
    override val lang = "all"
    override val name = "Yaoi Manga Online"
    override val baseUrl = "https://yaoimangaonline.com"
    override val supportsLatest = false

    private var categoryData: Array<Pair<String, String>> = arrayOf("ALL" to "-1")
    private var tagData: Array<Pair<String, String>> = arrayOf("ALL" to "")
    private var filtersInitialized = false

    private fun fetchFilters() {
        if (filtersInitialized) return
        try {
            val response = client.newCall(GET(baseUrl, headers)).execute()
            val doc = response.asJsoup()

            categoryData = arrayOf("ALL" to "-1") + doc
                .select("#cat option[value]")
                .mapNotNull { option ->
                    val value = option.attr("value")
                    val name = option.text().trim()
                    if (value == "-1" || name.isEmpty()) null
                    else Pair(name.replace(Regex("\\s*\\(\\d[\\d,]*\\)$"), "").trim(), value)
                }
                .toTypedArray()

            tagData = arrayOf("ALL" to "") + doc
                .select(".tagcloud a.tag-cloud-link")
                .mapNotNull { a ->
                    val name = a.text().trim()
                    val slug = a.attr("href")
                        .trimEnd('/')
                        .substringAfterLast("/tag/")
                        .trimEnd('/')
                    if (name.isEmpty() || slug.isEmpty()) null
                    else Pair(name, slug)
                }
                .toTypedArray()

            filtersInitialized = true
        } catch (_: Exception) {
        }
    }

    override fun getFilterList(): FilterList {
        fetchFilters()
        return FilterList(
            CategoryFilter(categoryData),
            TagFilter(tagData),
        )
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun popularMangaSelector() = searchMangaSelector()
    override fun popularMangaNextPageSelector() = searchMangaNextPageSelector()
    override fun popularMangaRequest(page: Int) = GET("$baseUrl/page/$page/", headers)
    override fun popularMangaFromElement(element: Element) = searchMangaFromElement(element)
    override fun searchMangaSelector() = ".post:not(.category-gay-movies):not(.category-yaoi-anime) > div > a"
    override fun searchMangaNextPageSelector() = ".herald-pagination > .next"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = baseUrl.toHttpUrl().newBuilder().run {
        filters.forEach {
            when (it) {
                is CategoryFilter -> if (it.state != 0) addQueryParameter("cat", it.toString())
                is TagFilter -> if (it.state != 0) addEncodedPathSegments("tag/$it")
                else -> {}
            }
        }
        addEncodedPathSegments("page/$page")
        addQueryParameter("s", query)
        GET(toString(), headers)
    }

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        title = element.attr("title")
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.select("h1.entry-title").text()
        title = title.substringBeforeLast("by").trim()
        thumbnail_url = document
            .selectFirst(".herald-post-thumbnail img")?.attr("src")
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
        genre = document.select(".meta-tags > a").joinToString { it.text() }
        author = document.select(".entry-content > p:matches((?i)(Author|Mangaka):)")
            .text()
            .replace(Regex("(?i)(Author|Mangaka):"), "")
            .substringBefore("Language:")
            .trim()
        status = when {
            document.selectFirst(".meta-category a[href*='/ongoing/']") != null -> SManga.ONGOING
            document.selectFirst(".meta-category a[href*='/completed/']") != null -> SManga.COMPLETED
            document.selectFirst(".meta-category a[href*='/hiatus/']") != null -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListSelector() = ".mpp-toc a"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        name = element.ownText()
        setUrlWithoutDomain(element.attr("href") ?: element.baseUri())
    }

    override fun chapterListParse(response: Response) = super.chapterListParse(response).ifEmpty {
        SChapter.create().apply {
            name = "Chapter"
            url = response.request.url.encodedPath
        }.let(::listOf)
    }.reversed()

    override fun pageListParse(document: Document) = document.select(".entry-content img").mapIndexed { idx, img ->
        Page(idx, "", img.attr("src"))
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()
}
