package com.hikari.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.hikari.app.web.WebViewActivity

/** Hikari's Telegram group — help, bug reports, title requests and release
 *  news. Single source of truth for every place that links to it. */
const val TELEGRAM_CHANNEL_URL = "https://t.me/CodegeasseHikari"

/**
 * Opens a YouTube video in the YouTube app rather than Hikari's own WebView.
 *
 * The in-app WebView is the right place for extension/watch pages, but it is
 * the wrong place for YouTube: the site redirects `www.youtube.com` to
 * `m.youtube.com`, and the WebView's redirect protection (Settings → WebView
 * safety) blocks that hop, so the trailer page just sits on a black screen.
 * Handing the id to the YouTube app avoids the redirect entirely.
 *
 * Tried in order, first one that starts wins:
 *  1. the explicit `vnd.youtube:<id>` scheme — the reliable way to force the
 *     YouTube app, and to offer the system chooser when another handler
 *     (ReVanced, NewPipe...) is installed;
 *  2. the plain https watch link — the YouTube app again when its App Links
 *     are verified, else a browser;
 *  3. [WebViewActivity] as a last resort so the tap is never a no-op.
 *
 * Never uses [android.content.pm.PackageManager.resolveActivity] to probe:
 * on API 30+ package visibility would report "not installed" for the YouTube
 * app unless it is declared in `<queries>`, while starting the implicit
 * intent works regardless.
 */
fun openYouTubeVideo(context: Context, videoId: String, title: String? = null): Boolean {
    val id = videoId.trim()
    if (id.isEmpty()) return false
    val watchUrl = "https://www.youtube.com/watch?v=$id"

    if (launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")))) return true
    if (launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl)))) return true

    val webView = Intent(context, WebViewActivity::class.java).apply {
        putExtra("url", watchUrl)
        if (!title.isNullOrBlank()) putExtra("title", title)
    }
    if (launch(context, webView)) return true

    Toast.makeText(context, I18n.t("Couldn't open YouTube"), Toast.LENGTH_SHORT).show()
    return false
}

/**
 * Opens a Telegram channel/group (a `https://t.me/...` link) in the Telegram
 * app, falling back to the user's browser — deliberately NEVER Hikari's own
 * WebView. Telegram's t.me pages hand off to the app anyway, and the WebView's
 * redirect/popup protection turns that hand-off into a dead end, so the user
 * would just stare at a page they can't log into.
 *
 * The `tg://resolve?domain=<handle>` scheme is tried first because it targets
 * the app directly even on devices where Telegram's App Links for t.me are not
 * verified (where the https link would silently land in a browser).
 */
fun openTelegram(context: Context, url: String = TELEGRAM_CHANNEL_URL): Boolean {
    val handle = telegramHandle(url)
    if (handle != null &&
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$handle")))
    ) {
        return true
    }
    if (launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) return true

    Toast.makeText(context, I18n.t("Couldn't open Telegram"), Toast.LENGTH_SHORT).show()
    return false
}

/** The `@handle` out of a t.me link (or a `tg://resolve?domain=` link), so the
 *  app scheme can be built from the same URL the user sees. Null when [url] is
 *  not a recognizable Telegram link. */
private fun telegramHandle(url: String): String? {
    val trimmed = url.trim()
    val handle = when {
        trimmed.startsWith("tg://resolve?domain=", ignoreCase = true) ->
            trimmed.substringAfter("domain=").substringBefore('&')

        else -> trimmed.substringAfter("t.me/", "").substringBefore('/').substringBefore('?')
    }
    return handle.trim().ifBlank { null }
}

/** Starts [intent], tagging it with NEW_TASK when [context] is not an Activity
 *  (e.g. an application context) so Android doesn't reject the launch. Returns
 *  false when nothing can handle it — the caller falls through to the next
 *  option. */
private fun launch(context: Context, intent: Intent): Boolean {
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}
