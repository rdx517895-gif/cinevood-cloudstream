package com.rdx.cinevood

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class CineVoodPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CineVoodProvider())
    }
}
