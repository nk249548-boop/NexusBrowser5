package com.nexus.browser

object AdBlocker {

    // Common ad/tracker domains to block
    private val blockedDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adnxs.com", "adform.net", "adsrvr.org", "advertising.com",
        "moatads.com", "scorecardresearch.com", "quantserve.com",
        "amazon-adsystem.com", "media.net", "outbrain.com", "taboola.com",
        "criteo.com", "criteo.net", "rubiconproject.com", "pubmatic.com",
        "openx.net", "appnexus.com", "sharethrough.com", "spotxchange.com",
        "indexww.com", "3lift.com", "casalemedia.com", "contextweb.com",
        "brightmountainmedia.com", "adcolony.com", "mopub.com", "smaato.net",
        "unity3d.com", "unityads.unity3d.com", "chartboost.com", "applovin.com",
        "facebook.com/tr", "connect.facebook.net", "analytics.twitter.com",
        "ads.twitter.com", "ads.linkedin.com", "snap.licdn.com",
        "bing.com/bat.js", "bat.bing.com", "browser.sentry-cdn.com",
        "cdn.heapanalytics.com", "cdn.segment.com", "cdn.amplitude.com",
        "hotjar.com", "mouseflow.com", "fullstory.com", "logrocket.com",
        "newrelic.com", "nr-data.net", "dynatrace.com", "pingdom.net",
        "cdn.speedcurve.com", "buysellads.com", "carbonads.com",
        "adservice.google.com", "pagead2.googlesyndication.com",
        "stats.g.doubleclick.net", "secure.adnxs.com", "ads.yahoo.com",
        "gemini.yahoo.com", "ib.adnxs.com", "s.yimg.com",
        "imrworldwide.com", "adtech.de", "smartadserver.com", "adhese.com",
        "adition.com", "advertising.aol.com", "mathtag.com",
        "turn.com", "rlcdn.com", "nexac.com", "demdex.net",
        "omtrdc.net", "2mdn.net", "adsymptotic.com", "yieldmo.com",
        "yieldlab.net", "lijit.com", "sovrn.com", "freewheel.tv",
        "spotx.tv", "vidazoo.com", "33across.com", "conversantmedia.com",
        "undertone.com", "teads.tv", "adagio.io", "smartclip.net",
        "revcontent.com", "zergnet.com", "mgid.com", "trafficjunky.net",
        "exoclick.com", "juicyads.com", "trafficfactory.biz",
        "propellerads.com", "popads.net", "popcash.net", "popunder.ru",
        "adsterra.com", "hilltopads.net", "adcash.com",
        "woot.com", "analytics.tiktok.com", "business.snapchat.com",
        "pixel.adsafeprotected.com", "cdn.doubleverify.com", "cdn.ias.com",
        "cdn.moatads.com", "ads.pubmatic.com", "image2.pubmatic.com",
        "simage2.pubmatic.com", "sync.pubmatic.com",
        "sync.rubiconproject.com", "pixel.rubiconproject.com",
        "cdn.branch.io", "app.link", "track.adjust.com",
        "app.adjust.com", "appsflyer.com", "kochava.com", "singular.net"
    )

    // URL substrings to block.
    //
    // BUG FIX (Phase 4): several of these were bare, unanchored substrings
    // ("popup", "popover", "googleads", "adclick", "clicktrack", ...) that
    // matched far more than ad/tracker URLs. shouldBlock() is used both for
    // sub-resources (shouldInterceptRequest) AND for top-level navigation
    // (shouldOverrideUrlLoading), so a false match here could silently
    // blank out a legitimate page resource or even cancel navigation to a
    // legitimate page entirely. Examples that were wrongly blocked before
    // this fix: ".../assets/js/popover.bundle.js", ".../modal-popup-
    // handler.js", ".../banner/welcome-banner.png", ".../promo/summer-
    // sale". All patterns below are now anchored to path separators or
    // otherwise scoped so they only match genuine ad/tracker URL shapes.
    private val blockedPatterns = listOf(
        "/ads/", "/ad/", "/advertisement/", "/advertising/",
        "/advert/", "/adsense/", "/adserver/", "/adservice/",
        "/tracking/", "/tracker/", "/analytics/", "/pixel/",
        "/beacon/", "/collect/", "/telemetry/",
        "?ad=", "&ad=", "?adid=", "&adid=",
        "ad.doubleclick", "/pagead/", "/adclick/", "/adclick.",
        "/popup/", "/popunder/", "/popover-ad/",
        "/sponsored/", "/promo-ad/", "/promotions/ad/",
        "/googleads/", "/adsystem/", "/adnxs/",
        "/banner-ad/", "/banners/ad/",
        "/clicktrack/", "/click-track/", "/click_track/",
        "impression.php", "impression.gif", "impression.png",
        "/survey-ad/", "/survey-tracker.js",
        "/mobileads/", "/mobile-ads/"
    )

    fun shouldBlock(url: String): Boolean {
        if (url.isBlank()) return false

        val lowerUrl = url.lowercase()

        // Check exact domain matches
        try {
            val host = android.net.Uri.parse(url).host?.lowercase() ?: ""
            for (domain in blockedDomains) {
                if (host == domain || host.endsWith(".$domain")) {
                    return true
                }
            }
        } catch (e: Exception) { /* ignore */ }

        // Check URL pattern matches
        for (pattern in blockedPatterns) {
            if (lowerUrl.contains(pattern)) {
                return true
            }
        }

        return false
    }

    /**
     * Like shouldBlock(), but intended specifically for top-level page
     * navigation (shouldOverrideUrlLoading). Navigation should only ever
     * be cancelled for a known ad/tracker DOMAIN — never for a generic
     * path-pattern match — so a legitimate page is never prevented from
     * loading just because part of its URL resembles an ad-related word.
     * Sub-resource blocking (images, scripts, iframes) continues to use
     * the broader shouldBlock() via shouldInterceptRequest.
     */
    fun shouldBlockNavigation(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val host = android.net.Uri.parse(url).host?.lowercase() ?: return false
            blockedDomains.any { domain -> host == domain || host.endsWith(".$domain") }
        } catch (e: Exception) {
            false
        }
    }
}
