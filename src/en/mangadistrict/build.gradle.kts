import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga District"
    versionCode = 17
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"
    theme = "madara"
    kmkVersionCode = 1

    source {
        lang = "en"
        baseUrl = "https://mangadistrict.com"
    }
}
