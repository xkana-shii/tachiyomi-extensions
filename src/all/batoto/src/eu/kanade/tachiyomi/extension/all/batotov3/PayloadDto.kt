package eu.kanade.tachiyomi.extension.all.batotov3

import kotlinx.serialization.Serializable

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
        sort: String? = null,
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
