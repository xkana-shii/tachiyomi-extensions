package eu.kanade.tachiyomi.extension.all.batotov3

import eu.kanade.tachiyomi.extension.all.batotov3.BatoToV3.Companion.DATE_FORMATTER
import eu.kanade.tachiyomi.extension.all.batotov3.BatoToV3.Companion.chapterIdRegex
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import kotlin.text.isNullOrEmpty

@Serializable
data class ApiSearchResponse(
    val data: SearchData,
) {
    @Serializable
    data class SearchData(
        @SerialName("get_content_searchComic") val search: SearchComic,
    ) {
        @Serializable
        data class SearchComic(
            val paging: PageInfo,
            val items: List<SearchItems>,
        ) {
            @Serializable
            data class PageInfo(
                @SerialName("pages") val total: Int,
                val page: Int,
            )

            @Serializable
            data class SearchItems(
                val data: SeriesDto,
            )
        }
    }
}

@Serializable
data class ApiDetailsResponse(
    val data: ComicNode,
) {
    @Serializable
    data class ComicNode(
        @SerialName("get_content_comicNode") val comicNode: MangaData,
    ) {
        @Serializable
        data class MangaData(
            val data: SeriesDto,
        )
    }
}

@Serializable
data class SeriesDto(
    private val id: String,
    private val name: String,
    private val altNames: List<String>? = null,
    private val authors: List<String>? = null,
    private val artists: List<String>? = null,
    private val originalStatus: String? = null,
    private val uploadStatus: String? = null,
    private val genres: List<String>? = null,
    private val summary: String? = null,
    private val extraInfo: String? = null,
    private val urlCoverOri: String? = null,
) {
    fun toSManga(baseUrl: String, cleanTitle: (String) -> String): SManga = SManga.create().apply {
        url = id
        title = cleanTitle(name)
        author = authors?.joinToString()
        artist = artists?.joinToString()
        genre = genres?.joinToString { genre ->
            genreOptions.firstOrNull { it.second == genre }?.first ?: genre
        }
        status = run {
            val statusToCheck = originalStatus ?: uploadStatus
            when {
                statusToCheck == null -> SManga.UNKNOWN
                statusToCheck.contains("pending") -> SManga.UNKNOWN
                statusToCheck.contains("ongoing") -> SManga.ONGOING
                statusToCheck.contains("cancelled") -> SManga.CANCELLED
                statusToCheck.contains("hiatus") -> SManga.ON_HIATUS
                statusToCheck.contains("completed") -> when {
                    uploadStatus?.contains("ongoing") == true -> SManga.PUBLISHING_FINISHED
                    else -> SManga.COMPLETED
                }
                else -> SManga.UNKNOWN
            }
        }
        thumbnail_url = urlCoverOri?.let { "$baseUrl$it" }
        description = buildString {
            if (!summary.isNullOrEmpty()) {
                append("\n\n----\n#### **Summary**\n$summary")
            }
            if (!extraInfo.isNullOrEmpty()) {
                append("\n\n----\n#### **Extra Info**\n$extraInfo")
            }
            if (!altNames.isNullOrEmpty()) {
                append("\n\n----\n#### **Alternative Titles**\n")
                append(altNames.joinToString("\n- ", prefix = "- "))
            }
        }.trim()
        initialized = true
    }
}

@Serializable
data class ApiChapterListResponse(
    val data: ChapterList,
) {
    @Serializable
    data class ChapterList(
        @SerialName("get_content_chapterList") val chapters: List<ChapterData>,
    ) {
        @Serializable
        data class ChapterData(
            val data: ChapterDto,
        ) {
            @Serializable
            data class ChapterDto(
                val id: String,
                val title: String? = null,
                val chaNum: Float,
                val urlPath: String,
                val dateCreate: JsonPrimitive? = null,
                val dateModify: JsonPrimitive? = null,
                @SerialName("userNode") val user: ScanlatorNode? = null,
                @SerialName("groupNodes") val groups: List<ScanlatorNode>? = emptyList(),
            ) {
                fun toSChapter() = SChapter.create().apply {
                    url = urlPath
                    name = "Chapter ${chaNum.parseChapterNumber()}"
                    if (!title.isNullOrEmpty()) {
                        name += ": $title"
                    }
                    chapter_number = chaNum
                    if (!groups.isNullOrEmpty()) {
                        scanlator = groups
                            .filterNot { it.data.name.isNullOrEmpty() }
                            .joinToString { it.data.name?.trim().toString() }
                    } else if (user != null && user.data.name != null) {
                        scanlator = "Uploaded by ${user.data.name.trim()}"
                    }
                    date_upload = dateModify?.parseDate() ?: dateCreate?.parseDate() ?: 0L
                }

                private fun Float.parseChapterNumber(): String {
                    return this.toString().replace(chapterIdRegex, "")
                }

                private fun JsonPrimitive.parseDate(): Long? {
                    // api sometimes return string and sometimes long 🗿
                    return runCatching {
                        if (this.isString) {
                            DATE_FORMATTER.parse(this.toString())!!.time
                        } else {
                            return this.long
                        }
                    }.getOrNull()
                }

                @Serializable
                data class ScanlatorNode(
                    val data: NameDto,
                ) {
                    @Serializable
                    data class NameDto(
                        val name: String? = null,
                    )
                }
            }
        }
    }
}

@Serializable
data class ApiPageListResponse(
    val data: ChapterNode,
) {
    @Serializable
    data class ChapterNode(
        @SerialName("get_content_chapterNode") val pageList: PageList,
    ) {
        @Serializable
        data class PageList(
            val data: PageData,
        ) {
            @Serializable
            data class PageData(
                val imageFiles: List<String>? = emptyList(),
            )
        }
    }
}

@Serializable
data class ApiSearchPayload(
    val variables: Variables,
    val query: String,
) {
    @Serializable
    data class Variables(
        val select: Select,
    )

    @Serializable
    data class Select(
        val page: Int,
        val size: Int,
        val where: String,
        val word: String,
        val sort: String,
        val incGenres: List<String>,
        val excGenres: List<String>,
        val incOLangs: List<String>,
        val incTLangs: List<String>,
        val origStatus: String,
        val batoStatus: String,
        val chapCount: String,
    )

    constructor(
        pageNumber: Int,
        size: Int,
        sort: String?,
        query: String = "",
        where: String = "browse",
        incGenres: List<String>? = emptyList(),
        excGenres: List<String>? = emptyList(),
        incOLangs: List<String>? = emptyList(),
        incTLangs: List<String>? = emptyList(),
        origStatus: String? = "",
        batoStatus: String? = "",
        chapCount: String? = "",
    ) : this(
        Variables(
            Select(
                page = pageNumber,
                size = size,
                where = where,
                word = query,
                sort = sort ?: "",
                incGenres = incGenres ?: emptyList(),
                excGenres = excGenres ?: emptyList(),
                incOLangs = incOLangs ?: emptyList(),
                incTLangs = incTLangs ?: emptyList(),
                origStatus = origStatus ?: "",
                batoStatus = batoStatus ?: "",
                chapCount = chapCount ?: "",
            ),
        ),
        SEARCH_QUERY,
    )
}

@Serializable
data class ApiQueryPayload(
    val variables: Variables,
    val query: String,
) {
    @Serializable
    data class Variables(
        val id: String,
    )

    constructor(
        id: String,
        query: String,
    ) : this(
        Variables(id),
        query,
    )
}
