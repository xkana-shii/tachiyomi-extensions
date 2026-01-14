package eu.kanade.tachiyomi.extension.all.batotov3


private fun buildQuery(queryAction: () -> String): String {
    return queryAction()
        .trimIndent()
        .replace("%", "$")
        .replace(whitespace, " ")
}

val whitespace by lazy { Regex("\\s+") }

val SEARCH_QUERY: String = buildQuery {
    """
        query (%select: SearchComic_Select) {
            get_content_searchComic(select: %select) {
                paging {
                    pages
                    page
                }
                items {
                    data {
                        id
                        name
                        summary {
                            text
                        }
                        slug
                        altNames
                        authors
                        artists
                        genres
                        originalStatus
                        uploadStatus
                        urlCover600
                        urlCover300
                        urlCoverOri
                    }
                }
            }
        }
    """
}

val DETAILS_QUERY: String = buildQuery {
    """
        query (%id: ID!) {
        	get_content_comicNode(id: %id) {
                data {
                    id
                    name
                    summary {
                        text
                    }
                    slug
                    altNames
                    authors
                    artists
                    genres
                    originalStatus
                    uploadStatus
                    urlCover600
                    urlCover300
                    urlCoverOri
                }
        	}
        }
    """
}

val CHAPTERS_QUERY: String = buildQuery {
    """
        query (%id: ID!) {
            get_content_chapterList(comicId: %id) {
                data {
                    id
                    title
                    chaNum
                    urlPath
                    dateCreate
                    dateModify

                    userNode {
                        data {
                            name
                        }
                    }

                    groupNodes {
                        data {
                            name
                        }
                    }
                }
            }
        }
    """
}

val PAGES_QUERY: String = buildQuery {
    """
        query (%id: ID!) {
        	get_content_chapterNode(id: %id) {
        		data {
        			imageFiles
        		}
        	}
        }
    """
}
