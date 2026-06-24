package eu.kanade.tachiyomi.extension.all.kemono

import eu.kanade.tachiyomi.multisrc.kemono.Kemono
import eu.kanade.tachiyomi.source.model.FilterList

class Kemono : Kemono("Kemono", "https://kemono.cr", "all") {
    override val getTypes = listOf(
        "Patreon",
        "Pixiv Fanbox",
        "Discord",
        "Fantia",
        "Afdian",
        "Boosty",
        "Gumroad",
        "SubscribeStar",
    )

    override fun getFilterList(): FilterList {
        val typesForUrl = if (baseUrl.contains("pawchive")) {
            listOf("Patreon", "Pixiv Fanbox")
        } else {
            getTypes
        }

        val baseFilterList = super.getFilterList()

        return FilterList(
            baseFilterList[0], // Sort filter
            TypeFilter("Types", typesForUrl),
            baseFilterList[2], // Favorites filter
        )
    }
}
