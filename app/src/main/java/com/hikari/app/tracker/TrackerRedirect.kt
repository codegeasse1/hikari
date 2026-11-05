package com.hikari.app.tracker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The sign-in page's own redirect, arriving from OUTSIDE the app.
 *
 * Every tracker registration in the app is told to use [TrackerApi.REDIRECT_URI]
 * — `hikari://oauth` — and the dialog's WebView catches that redirect itself
 * (see `TrackerLoginDialog.shouldOverrideUrlLoading`), which is the flow that
 * works with no help from anybody.
 *
 * The OTHER route does not: the dialog's own "Open the login page in your
 * browser" button exists because some services (AniList's Cloudflare Turnstile
 * is the reliable one) refuse a WebView, so the user signs in with Chrome/the
 * device browser — and the browser then lands on `hikari://oauth#access_token=…`
 * with nowhere to put it. That is the reported "signing in from the browser
 * does not work in the app": the link was simply dropped on the floor, because
 * nothing in the app declared that it can receive it. The manifest declares it
 * now (see MainActivity's `<data android:scheme="hikari" android:host="oauth"/>`),
 * and MainActivity hands what arrives to [incoming].
 *
 * WHY IT IS A SHARED OBJECT rather than a callback the activity calls directly:
 * a code sign-in (MyAnimeList, Shikimori) can only be finished by the component
 * that started it, because the `state` the service checks is generated there and
 * is also MyAnimeList's PKCE `code_verifier` — a value the Activity has never
 * seen. When the dialog is on screen it is therefore the one that finishes the
 * link, through its own paste path (which already knows how to read a token or a
 * code out of a whole URL); MainActivity only acts when there is nothing on
 * screen to hand it to, and then only for the token flow, which needs nothing
 * but the stored client id (see `MainActivity.handleTrackerRedirect`).
 */
object TrackerRedirect {

    private val _incoming = MutableStateFlow<String?>(null)

    /** The last `hikari://oauth` link handed to the app from a browser. */
    val incoming: StateFlow<String?> = _incoming

    /**
     * True while a `TrackerLoginDialog` is on screen. Read — not collected — by
     * MainActivity the moment a redirect arrives, which is exactly what decides
     * whether the dialog or the activity finishes this one (see the class note).
     */
    @Volatile
    var dialogOpen: Boolean = false

    /** Offers a redirect to whoever can finish it. */
    fun deliver(url: String) {
        _incoming.value = url
    }

    /** Takes the current redirect, leaving nothing for the next reader. */
    fun consume() {
        _incoming.value = null
    }
}
