plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "DeviantArt"
    className = "DeviantArt"
    versionCode = 10
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    kmkVersionCode = 1

    deeplink {
        host("www.deviantart.com")
        host("deviantart.com")
        path("/..*/gallery/..*")
    }
}
