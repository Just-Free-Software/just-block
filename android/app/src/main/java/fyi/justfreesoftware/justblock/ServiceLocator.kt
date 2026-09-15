package fyi.justfreesoftware.justblock

import android.content.Context
import fyi.justfreesoftware.justblock.core.DnsFilter
import fyi.justfreesoftware.justblock.data.BlocklistFetcher
import fyi.justfreesoftware.justblock.data.BlocklistRepository
import fyi.justfreesoftware.justblock.data.UserPreferences

object ServiceLocator {

    lateinit var dnsFilter: DnsFilter
        private set

    @android.annotation.SuppressLint("StaticFieldLeak")
    lateinit var blocklistRepository: BlocklistRepository
        private set

    fun init(context: Context) {
        if (::dnsFilter.isInitialized) return  // already set up
        val appContext = context.applicationContext
        dnsFilter = DnsFilter()
        blocklistRepository = BlocklistRepository(
            context         = appContext,
            dnsFilter       = dnsFilter,
            userPreferences = UserPreferences(appContext),
            fetcher         = BlocklistFetcher()
        )
    }
}