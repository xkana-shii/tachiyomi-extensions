plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MyReadingManga"
    versionCode = 62
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    listOf("ar", "id", "zh", "zh-hant", "hr", "en", "fil", "fr", "de", "hu", "it", "ja", "ko", "lt", "fa", "pl", "pt", "pt-BR", "ru", "sk", "es", "sv", "th", "tr", "vi").forEach {
        source {
            lang = it
            baseUrl = "https://myreadingmanga.info"
        }
    }
}
