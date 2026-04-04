package com.rdx.cinevood

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class CineVoodPlugin : BasePlugin() {
    override fun load(context: Context) {
        registerMainAPI(CineVoodProvider())
    }
}
