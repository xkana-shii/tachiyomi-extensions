package eu.kanade.tachiyomi.extension.all.lezhin

import eu.kanade.tachiyomi.source.SourceFactory

class LezhinFactory : SourceFactory {
    override fun createSources() = listOf(
        LezhinEN(),
        LezhinKO(),
    )
}
