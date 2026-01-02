@file:Suppress("unused")

package eu.kanade.tachiyomi.extension.all.batotov3

import eu.kanade.tachiyomi.extension.all.batotov3.BatoToV3.Companion.DATE_FORMATTER
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import java.util.Locale

@Serializable
class Data<T>(
    val data: T,
)

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
    val id: String,
    val name: String,
    val slug: String,
    val summary: SummaryDto?,
    val altNames: List<String>? = emptyList(),
    val authors: List<String>? = emptyList(),
    val artists: List<String>? = emptyList(),
    val genres: List<String>? = emptyList(),
    val originalStatus: String?,
    val uploadStatus: String?,
    @SerialName("urlCoverOri") val coverOriginal: String?,
    @SerialName("urlCover600") val coverMedium: String?,
    @SerialName("urlCover300") val coverLow: String?,
) {
    fun toSManga(cover: CoverQuality = CoverQuality.Original): SManga = SManga.create().apply {
        title = name.trim()
        url = id
        author = authors?.joinToString { it.trim() }
        artist = artists?.joinToString { it.trim() }
        description = summary?.text?.trim()
        if (!altNames.isNullOrEmpty()) {
            description += altNames.joinToString(
                prefix = "\n\nAlternative Names: \n* ",
                separator = "\n* ",
            ) { it.trim() }
        }
        genre = genres?.joinToString { genre ->
            genre
                .replace("_", " ")
                .replaceFirstChar {
                    if (it.isLowerCase()) {
                        it.titlecase(Locale.ROOT)
                    } else {
                        it.toString()
                    }
                }
        }
        thumbnail_url = when (cover) {
            CoverQuality.Medium -> coverMedium
            CoverQuality.Low -> coverLow
            else -> coverOriginal
        }
        status = parseStatus(originalStatus, uploadStatus)
    }

    private fun parseStatus(originalStatus: String?, uploadStatus: String?): Int {
        return when (originalStatus) {
            null -> SManga.UNKNOWN
            "ongoing" -> SManga.ONGOING
            "cancelled" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            "completed" -> when (uploadStatus) {
                "ongoing" -> SManga.PUBLISHING_FINISHED
                else -> SManga.COMPLETED
            }
            else -> SManga.UNKNOWN
        }
    }

    @Serializable
    data class SummaryDto(
        val text: String?,
    )
}

enum class CoverQuality {
    Original,
    Medium,
    Low,
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
            class ChapterDto(
                val id: String? = null,
                val urlPath: String? = null,
                val comicId: String? = null,
                val serial: Float? = null,
                @SerialName("chaNum") val chaNum: Float? = null,
                @SerialName("dname") val dname: String? = null,
                @SerialName("displayName") val displayNameAlt: String? = null,
                val title: String? = null,
                val dateCreate: JsonPrimitive? = null,
                val dateModify: JsonPrimitive? = null,
                @SerialName("userNode") val userNode: Data<Name?>? = null,
                @SerialName("groupNodes") val groupNodes: List<Data<Name?>?>? = null,
            ) {
                @Serializable
                class Name(
                    val name: String? = null,
                )

                fun toSChapter(): SChapter = SChapter.create().apply {
                    url = (urlPath ?: id ?: "").trim()

                    val display = (dname ?: displayNameAlt ?: "").trim()

                    val chapNum = serial ?: chaNum ?: 0f

                    name = buildString {
                        val number = chapNum.toString().substringBefore(".0")
                        if (display.isEmpty()) {
                            append("Chapter ", number)
                        } else {
                            if (!display.contains(number)) {
                                append("Chapter ", number, ": ")
                            }
                            append(display)
                        }
                        if (!title.isNullOrEmpty()) {
                            if (isNotEmpty()) append(": ")
                            append(title)
                        }
                    }

                    chapter_number = chapNum

                    date_upload = dateModify?.parseDate() ?: dateCreate?.parseDate() ?: 0L

                    scanlator = groupNodes?.filter { it?.data?.name != null }
                        ?.joinToString { it!!.data!!.name!! }
                        ?: userNode?.data?.name ?: "\u200B"
                }

                private fun JsonPrimitive.parseDate(): Long? {
                    return runCatching {
                        if (this.isString) {
                            DATE_FORMATTER.parse(this.content)!!.time
                        } else {
                            this.long
                        }
                    }.getOrNull()
                }
            }
        }
    }
}

@Serializable
class ApiChapterListVariables(
    val comicId: String,
    val start: Int, // set to -1 to grab all chapters
)

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
    @SerialName("variables")
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
data class ApiQueryPayload<T>(
    val variables: T,
    val query: String,
) {
    @Serializable
    data class Variables(
        val id: String,
    )
}
