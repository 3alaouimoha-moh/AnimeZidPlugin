version = 1

cloudstream {
    description = "مشاهدة وتحميل أفلام ومسلسلات الانمي والكرتون المدبلج والمترجم اونلاين"
    authors = listOf("AnimeZid")
    status = 1
    tvTypes = listOf("Anime", "Movie", "TvSeries")
    language = "ar"
    iconUrl = "https://animezid.cam/uploads/custom-logo.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
