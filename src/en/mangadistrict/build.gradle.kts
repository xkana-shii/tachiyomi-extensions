plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga District"
    versionCode = 16
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "madara"
    kmkVersionCode = 2

    source {
        lang = "en"
        baseUrl = "https://mangadistrict.com"
    }
}
