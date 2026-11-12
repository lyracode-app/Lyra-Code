package com.yukisoffd.lyracode.browser

/** Prefer a capable default browser, then known providers. Never pin an unverified package. */
internal fun customTabBrowserCandidates(
    defaultBrowserPackage: String?,
    supportsCustomTabs: (String) -> Boolean,
): Sequence<String> = sequence {
    val preferredPackages = listOf(
        "com.android.chrome",
        "com.microsoft.emmx",
        "org.mozilla.firefox",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.dev",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "com.sec.android.app.sbrowser",
        "com.brave.browser",
        "com.vivaldi.browser",
    )
    for (packageName in (listOfNotNull(defaultBrowserPackage) + preferredPackages).distinct()) {
        if (supportsCustomTabs(packageName)) yield(packageName)
    }
}
