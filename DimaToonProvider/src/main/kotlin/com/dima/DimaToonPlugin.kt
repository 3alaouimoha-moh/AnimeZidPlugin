package com.dima

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DimaToonPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DimaToonProvider())
    }
}
