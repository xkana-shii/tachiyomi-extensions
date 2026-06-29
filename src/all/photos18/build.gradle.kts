plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Photos18"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    kmkVersionCode = 1

    source {
        lang = "all"
        baseUrl = "https://www.photos18.com"
    }
}
