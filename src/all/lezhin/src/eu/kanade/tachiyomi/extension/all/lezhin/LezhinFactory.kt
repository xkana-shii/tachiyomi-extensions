package eu.kanade.tachiyomi.extension.all.lezhin

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class LezhinFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        LezhinEN(),
        LezhinKO()
    )
}
