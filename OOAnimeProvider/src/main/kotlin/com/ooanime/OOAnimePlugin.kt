package com.ooanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class OOAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OOAnime())
    }
}
