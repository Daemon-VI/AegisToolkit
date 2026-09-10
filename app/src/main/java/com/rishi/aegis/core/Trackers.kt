package com.rishi.aegis.core

/**
 * A small, hand-curated, fully-offline signature list of common third-party tracking / analytics /
 * ad SDKs, in the spirit of Exodus Privacy. Each tracker is matched by the package prefixes its SDK
 * uses for the components it registers in an app's manifest (activities, services, receivers,
 * providers). Most analytics/ad SDKs register at least one such component, so scanning declared
 * component class names catches them without opening the APK's dex.
 *
 * This is a heuristic, not a decompiler: it will miss a tracker that ships zero manifest components,
 * and it reports SDK *presence*, not that data is actually being exfiltrated. Honest and useful.
 */
object Trackers {

    private data class Sig(val name: String, val prefixes: List<String>)

    private val SIGS = listOf(
        Sig("Google Firebase Analytics", listOf("com.google.firebase.analytics", "com.google.android.gms.measurement")),
        Sig("Google AdMob", listOf("com.google.android.gms.ads")),
        Sig("Google Crashlytics", listOf("com.google.firebase.crashlytics", "com.crashlytics.android")),
        Sig("Google Tag Manager", listOf("com.google.android.gms.tagmanager")),
        Sig("Google DoubleClick", listOf("com.google.android.gms.ads.doubleclick")),
        Sig("Facebook (Meta) SDK", listOf("com.facebook.ads", "com.facebook.appevents", "com.facebook.login", "com.facebook.internal")),
        Sig("AppsFlyer", listOf("com.appsflyer")),
        Sig("Adjust", listOf("com.adjust.sdk")),
        Sig("Flurry", listOf("com.flurry")),
        Sig("Branch", listOf("io.branch")),
        Sig("Amplitude", listOf("com.amplitude")),
        Sig("Mixpanel", listOf("com.mixpanel.android")),
        Sig("Segment", listOf("com.segment.analytics")),
        Sig("OneSignal", listOf("com.onesignal")),
        Sig("Braze (Appboy)", listOf("com.appboy", "com.braze")),
        Sig("Unity Ads", listOf("com.unity3d.ads", "com.unity3d.services")),
        Sig("ironSource", listOf("com.ironsource")),
        Sig("AppLovin", listOf("com.applovin")),
        Sig("Vungle", listOf("com.vungle")),
        Sig("InMobi", listOf("com.inmobi")),
        Sig("Chartboost", listOf("com.chartboost")),
        Sig("Bugsnag", listOf("com.bugsnag")),
        Sig("Sentry", listOf("io.sentry")),
        Sig("Microsoft App Center", listOf("com.microsoft.appcenter")),
        Sig("Yandex Metrica", listOf("com.yandex.metrica")),
        Sig("Kochava", listOf("com.kochava")),
        Sig("MoEngage", listOf("com.moengage")),
        Sig("CleverTap", listOf("com.clevertap")),
        Sig("Localytics", listOf("com.localytics")),
        Sig("Tapjoy", listOf("com.tapjoy")),
        Sig("comScore", listOf("com.comscore")),
        Sig("Mopub", listOf("com.mopub")),
        Sig("Startapp", listOf("com.startapp")),
        Sig("Pangle (TikTok)", listOf("com.bytedance.sdk.openadsdk", "com.pgl.sys")),
    )

    /** Names of trackers whose signature prefixes appear among [classNames]. */
    fun detect(classNames: List<String>): List<String> {
        if (classNames.isEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        for (sig in SIGS) {
            val hit = classNames.any { cn -> sig.prefixes.any { cn.startsWith(it) } }
            if (hit) found += sig.name
        }
        return found.toList()
    }

    val count: Int get() = SIGS.size
}
