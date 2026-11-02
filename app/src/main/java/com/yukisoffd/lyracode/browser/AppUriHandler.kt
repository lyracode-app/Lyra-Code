package com.yukisoffd.lyracode.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.compose.ui.platform.UriHandler

/** One web-link entry point for settings, Markdown links and browser-based authorization. */
internal class AppUriHandler(
    private val context: Context,
    private val externalUriHandler: UriHandler,
    private val darkMode: Boolean,
    private val toolbarColor: Int,
) : UriHandler {
    override fun openUri(uri: String) {
        val target = Uri.parse(uri)
        if (!target.scheme.equals("https", ignoreCase = true) && !target.scheme.equals("http", ignoreCase = true)) {
            externalUriHandler.openUri(uri)
            return
        }

        val packageManager = context.packageManager
        // Resolve a generic web URL, not the destination: verified app links aren't browsers.
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val defaultPackage = runCatching {
            packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        val candidates = customTabBrowserCandidates(defaultPackage) { packageName ->
            runCatching {
                val activity = packageManager.resolveActivity(
                    Intent(browserIntent).setPackage(packageName), PackageManager.MATCH_DEFAULT_ONLY,
                )?.activityInfo
                val service = packageManager.resolveService(
                    Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).setPackage(packageName), 0,
                )?.serviceInfo
                activity != null && activity.enabled && activity.exported &&
                    service != null && service.enabled && service.exported
            }.getOrDefault(false)
        }
        for (packageName in candidates) {
            val tab = buildCustomTabIntent(darkMode, toolbarColor).apply {
                intent.setPackage(packageName)
                if (!context.hasActivity()) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                tab.launchUrl(context, target)
                return
            } catch (_: ActivityNotFoundException) {
                // The provider may have been removed/disabled since discovery. Try the next one.
            } catch (_: SecurityException) {
                // A browser may be visible but unavailable to this profile.
            }
        }

        // Do not gate this on resolveActivity/queryIntentActivities. Package visibility and OEM
        // browsers can hide valid handlers; let Android resolve the original URL without a package.
        val fallback = Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)
        if (defaultPackage == null || defaultPackage == "android") {
            context.launchLinkIntent(Intent.createChooser(fallback, null))
        } else {
            try {
                context.launchLinkIntent(fallback)
            } catch (_: ActivityNotFoundException) {
                context.launchLinkIntent(Intent.createChooser(fallback, null))
            } catch (_: SecurityException) {
                context.launchLinkIntent(Intent.createChooser(fallback, null))
            }
        }
    }
}

internal fun buildCustomTabIntent(darkMode: Boolean, toolbarColor: Int): CustomTabsIntent =
    CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setCloseButtonEnabled(true)
        .setShareState(CustomTabsIntent.SHARE_STATE_ON)
        .setBookmarksButtonEnabled(true)
        .setDownloadButtonEnabled(true)
        .setShareIdentityEnabled(true)
        .setEphemeralBrowsingEnabled(false)
        // Leave the browser's native site-info, overflow and minimize/PiP controls available.
        // Regular tabs reuse browser cookies/history; cross-device sync follows browser settings.
        .setColorScheme(if (darkMode) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
        .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
        // Keep the initial URL in the tab, but preserve external-app redirects during authorization.
        .setSendToExternalDefaultHandlerEnabled(true)
        .build()

private fun Context.launchLinkIntent(intent: Intent) {
    if (!hasActivity()) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

private tailrec fun Context.hasActivity(): Boolean = when (this) {
    is Activity -> true
    is ContextWrapper -> baseContext !== this && baseContext.hasActivity()
    else -> false
}
