package com.hikari.app.tracker

import com.hikari.app.data.TrackerAccount
import com.hikari.app.data.TrackerClient
import com.hikari.app.data.TrackerKind
import com.hikari.app.data.TrackerMatch
import com.hikari.app.data.TrackerMedia
import com.hikari.app.data.matchScore
import com.hikari.app.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Every tracker's HTTP surface, in one place (Settings → Trackers).
 *
 * Six services with six different sign-in mechanisms, six search shapes and six
 * ways of writing progress — and one thing they have in common, which is what
 * this file is built around: **they all answer with a status code and a body,
 * and the user has to be told what it said.** Every call here returns a
 * `Result`, and the failure carries a sentence ("AniList: the sign-in was
 * refused (HTTP 401) — sign out and sign in again"), because a tracker that
 * silently does nothing is indistinguishable from a tracker that works until
 * the user checks their list a week later and finds it empty.
 *
 * The client credentials are the user's own ([TrackerClient]): each service
 * needs an app registered with it before anyone may log in, and Hikari does not
 * ship somebody else's registration (see [TrackerKind]).
 */
object TrackerApi {

    /**
     * The redirect URI every app registration must be given, verbatim.
     *
     * The sign-in happens in an in-app WebView that watches for exactly this
     * URL (see [com.hikari.app.ui.components.TrackerLoginDialog]): nothing is
     * listening for the scheme outside the app, so the hand-off is intercepted
     * before Android ever sees it — no manifest entry, no second app.
     */
    const val REDIRECT_URI = "hikari://oauth"

    /** A sign-in the user confirms elsewhere: a code to show, and a page. */
    data class DeviceLogin(
        val userCode: String,
        val verifyUrl: String,
        /** What the poll call needs: the user code (Simkl) or device code (Trakt). */
        val pollCode: String,
        val intervalSec: Int = 5,
        val expiresInSec: Int = 600,
    )

    // ------------------------------------------------------------------ replies

    /** One HTTP answer, already read into a string. */
    private class Reply(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
        fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
        fun array(): JSONArray? = runCatching { JSONArray(body) }.getOrNull()

        /** A GraphQL answer is HTTP 200 even when it failed — see [graphql]. */
        fun graphQlError(): String? {
            val errors = json()?.optJSONArray("errors") ?: return null
            if (errors.length() == 0) return null
            val first = errors.optJSONObject(0) ?: return "the service refused the request"
            return first.optString("message").ifBlank { "the service refused the request" }
        }
    }

    private fun replyOf(r: okhttp3.Response): Reply = r.use {
        Reply(it.code, runCatching { it.body?.string() ?: "" }.getOrDefault(""))
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): Reply =
        try {
            replyOf(Http.get(url, headers))
        } catch (e: Exception) {
            Reply(-1, e.message ?: "network error")
        }

    private fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): Reply = try {
        replyOf(Http.post(url, body, headers, contentType))
    } catch (e: Exception) {
        Reply(-1, e.message ?: "network error")
    }

    private fun send(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): Reply = try {
        replyOf(Http.request(method, url, body, headers, contentType))
    } catch (e: Exception) {
        Reply(-1, e.message ?: "network error")
    }

    /** A form body, the way the OAuth token endpoints want it. */
    private fun form(vararg pairs: Pair<String, String?>): String =
        pairs.filter { it.second != null }
            .joinToString("&") { enc(it.first) + "=" + enc(it.second!!) }

    private fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    /**
     * [PATCH], then [PUT] if the service says the method is not allowed — MAL
     * accepts both spellings of the same call depending on when the app was
     * registered against it, and a 405 is not a real failure, just the other
     * spelling of the same request.
     */
    private fun sendAny(
        methods: List<String>,
        url: String,
        body: String,
        headers: Map<String, String>,
        contentType: String = "application/json; charset=utf-8",
    ): Reply {
        var last = Reply(-1, "no attempt")
        for (m in methods) {
            last = send(m, url, body, headers, contentType)
            if (last.code != 405) return last
        }
        return last
    }

    /** One sentence a user can act on, from whatever went wrong. */
    private fun problem(reply: Reply, what: String): String {
        val detail = explain(reply.body)
        return when {
            reply.code == -1 -> "$what: no connection (${detail.ifBlank { "the request failed" }})."
            reply.code == 401 || reply.code == 403 ->
                "$what: the service refused the sign-in (HTTP ${reply.code})" +
                    if (detail.isBlank()) "." else " — $detail"
            reply.code == 404 -> "$what: not found on the service (HTTP 404)."
            reply.code == 429 ->
                "$what: the service is rate-limiting (HTTP 429) — try again in a few minutes."
            reply.code in 500..599 -> "$what: the service is having trouble (HTTP ${reply.code})."
            else -> "$what: HTTP ${reply.code}" + if (detail.isBlank()) "." else " — $detail"
        }
    }

    /** The service's own words about the failure, when it sent any. */
    private fun explain(body: String): String {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val text = o?.let {
            listOf("error_description", "message", "error", "detail", "title")
                .firstNotNullOfOrNull { k -> it.optString(k).takeIf { s -> s.isNotBlank() } }
        } ?: body.take(160)
        return text.replace(Regex("\\s+"), " ").trim().take(200)
    }

    // -------------------------------------------------------------- sign-in: url

    /**
     * The page the login WebView opens, or "" when this service has no browser
     * step (Kitsu's password grant) — see [loginPassword].
     *
     * [clientId] is the app the user registered, [state] is echoed back so the
     * redirect we intercept is provably ours.
     */
    fun authorizeUrl(kind: TrackerKind, clientId: String, state: String): String = when (kind) {
        // AniList's implicit grant: the token itself comes back in the redirect.
        TrackerKind.ANILIST ->
            "https://anilist.co/api/v2/oauth/authorize?client_id=${enc(clientId)}&response_type=token"

        TrackerKind.MAL ->
            "https://myanimelist.net/v1/oauth2/authorize?response_type=code" +
                "&client_id=${enc(clientId)}" +
                "&code_challenge=${enc(state)}&code_challenge_method=plain" +
                "&redirect_uri=${enc(REDIRECT_URI)}&state=${enc(state)}"

        TrackerKind.SHIKIMORI ->
            "https://shikimori.one/oauth/authorize?response_type=code" +
                "&client_id=${enc(clientId)}&redirect_uri=${enc(REDIRECT_URI)}&state=${enc(state)}"

        TrackerKind.SIMKL ->
            "https://simkl.com/oauth/authorize?response_type=code" +
                "&client_id=${enc(clientId)}&redirect_uri=${enc(REDIRECT_URI)}&state=${enc(state)}"

        // No browser step: Kitsu takes the account's own credentials, and Trakt
        // is a device code (see [startDevice]).
        TrackerKind.KITSU, TrackerKind.TRAKT -> ""
    }

    /** The `state` we sent, so the redirect can be checked against it. */
    fun newState(): String =
        // Two UUIDs' worth of hex: MAL wants a PKCE code_verifier of 43–128
        // characters and the state IS the verifier (the `plain` method), so a
        // short one would make its token endpoint refuse the exchange.
        (java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString())
            .replace("-", "")

    /** The `access_token` an implicit-grant redirect carries (AniList), if any. */
    fun tokenFromRedirect(redirect: String): String? =
        paramOf(redirect, "access_token")?.takeIf { it.isNotBlank() }

    /** The authorization `code` a redirect carries, if any. */
    fun codeFromRedirect(redirect: String): String? =
        paramOf(redirect, "code")?.takeIf { it.isNotBlank() }

    /** The `error`/`error_description` a redirect carries, if any. */
    fun errorFromRedirect(redirect: String): String? {
        val e = paramOf(redirect, "error") ?: paramOf(redirect, "error_description")
        return e?.takeIf { it.isNotBlank() }
    }

    /**
     * A parameter out of a redirect URL — from the query OR the fragment,
     * because AniList's implicit grant puts the token in the fragment and every
     * other service puts the code in the query.
     */
    private fun paramOf(url: String, name: String): String? {
        val hash = url.substringAfter('#', "")
        val query = url.substringAfter('?', "").substringBefore('#')
        for (part in listOf(query, hash)) {
            for (pair in part.split('&')) {
                if (pair.substringBefore('=').equals(name, ignoreCase = true)) {
                    return runCatching { java.net.URLDecoder.decode(pair.substringAfter('='), "UTF-8") }
                        .getOrDefault(pair.substringAfter('='))
                }
            }
        }
        return null
    }

    /** The redirect URI to paste when registering (shown on the card). */
    fun redirectHint(): String = REDIRECT_URI

    // --------------------------------------------------------- sign-in: token in

    /** Confirms an implicit-grant token and asks the service who the user is. */
    suspend fun signInWithToken(client: TrackerClient, token: String): Result<TrackerAccount> = when (client.kind) {
        TrackerKind.ANILIST -> {
            val reply = graphql(token, "{ Viewer { id name } }")
            val viewer = reply.json()?.optJSONObject("data")?.optJSONObject("Viewer")
            val name = viewer?.optString("name").orEmpty()
            if (viewer == null || name.isBlank()) {
                Result.failure(Exception(reply.graphQlError()?.let { "AniList: $it" }
                    ?: problem(reply, "AniList sign-in")))
            } else {
                Result.success(
                    TrackerAccount(
                        kind = client.kind,
                        user = name,
                        userId = viewer.optInt("id", 0).toString(),
                        token = token,
                    )
                )
            }
        }
        // Every other flow hands us a code instead — see [exchangeCode].
        else -> Result.failure(Exception("${client.kind.label} has no token sign-in."))
    }

    /** Exchanges an authorization code for a token, per service. */
    suspend fun exchangeCode(
        client: TrackerClient,
        code: String,
        state: String,
        redirect: String = REDIRECT_URI,
    ): Result<TrackerAccount> = when (client.kind) {
        TrackerKind.MAL -> {
            // PKCE with `plain`: the verifier IS the state we sent, so the
            // exchange needs nothing but the code — which is what makes a
            // public client (no secret) work at all.
            val body = form(
                "grant_type" to "authorization_code",
                "client_id" to client.id,
                "client_secret" to client.secret.takeIf { it.isNotBlank() },
                "code" to code,
                "code_verifier" to state,
                "redirect_uri" to redirect,
            )
            tokenAccount(
                client,
                post("https://myanimelist.net/v1/oauth2/token", body, contentType = FORM),
                which = "MyAnimeList",
                me = { token -> malMe(token) },
            )
        }

        TrackerKind.SHIKIMORI -> {
            val body = form(
                "grant_type" to "authorization_code",
                "client_id" to client.id,
                "client_secret" to client.secret,
                "code" to code,
                "redirect_uri" to redirect,
            )
            tokenAccount(
                client,
                post("https://shikimori.one/oauth/token", body, contentType = FORM),
                which = "Shikimori",
                me = { token -> shikimoriMe(token) },
            )
        }

        TrackerKind.SIMKL -> {
            val body = JSONObject().apply {
                put("code", code)
                put("client_id", client.id)
                put("client_secret", client.secret)
                put("grant_type", "authorization_code")
                put("redirect_uri", redirect)
            }.toString()
            tokenAccount(
                client,
                post("https://api.simkl.com/oauth/token", body),
                which = "Simkl",
                me = { token -> simklMe(client.id, token) },
            )
        }

        else -> Result.failure(Exception("${client.kind.label} does not use a code."))
    }

    /**
     * Kitsu's password grant — the one service here that needs no browser: the
     * user types the e-mail and password they use on Kitsu itself.
     */
    suspend fun loginPassword(
        client: TrackerClient,
        username: String,
        password: String,
    ): Result<TrackerAccount> {
        if (client.kind != TrackerKind.KITSU) {
            return Result.failure(Exception("${client.kind.label} does not use a password sign-in."))
        }
        val body = form(
            "grant_type" to "password",
            "username" to username,
            "password" to password,
            "client_id" to client.id,
            "client_secret" to client.secret,
        )
        return tokenAccount(
            client,
            post("https://kitsu.io/api/oauth/token", body, contentType = FORM),
            which = "Kitsu",
            me = { token -> kitsuMe(token) },
        )
    }

    /** The shared half of every token exchange: read it, then who am I. */
    private fun tokenAccount(
        client: TrackerClient,
        reply: Reply,
        which: String,
        me: (String) -> Pair<String, String>?,
    ): Result<TrackerAccount> {
        val token = reply.json()?.optString("access_token").orEmpty()
        if (!reply.ok || token.isBlank()) {
            return Result.failure(Exception(problem(reply, "$which sign-in")))
        }
        val o = reply.json()
        val expiresIn = o?.optLong("expires_in", 0L) ?: 0L
        val identity = me(token)
        val account = TrackerAccount(
            kind = client.kind,
            user = identity?.first.orEmpty(),
            userId = identity?.second.orEmpty(),
            token = token,
            refresh = o?.optString("refresh_token").orEmpty(),
            expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000L else 0L,
        )
        // The token is valid even when the "who am I" call failed (a hiccup on
        // the profile endpoint is not a reason to throw the sign-in away), but
        // the card then says "signed in" with no name — which is honest.
        return Result.success(account)
    }

    // ------------------------------------------------------- sign-in: device/PIN

    /** Starts a code-based sign-in (Simkl's PIN, Trakt's device code). */
    suspend fun startDevice(client: TrackerClient): Result<DeviceLogin> = when (client.kind) {
        TrackerKind.SIMKL -> {
            val reply = get("https://api.simkl.com/oauth/pin?client_id=${enc(client.id)}")
            val o = reply.json()
            val userCode = o?.optString("user_code").orEmpty()
            if (!reply.ok || userCode.isBlank()) {
                Result.failure(Exception(problem(reply, "Simkl sign-in")))
            } else {
                Result.success(
                    DeviceLogin(
                        userCode = userCode,
                        verifyUrl = o?.optString("verification_url").orEmpty().ifBlank { "https://simkl.com/pin" },
                        pollCode = userCode,
                        intervalSec = o?.optInt("interval", 5)?.coerceIn(2, 30) ?: 5,
                        expiresInSec = o?.optInt("expires_in", 600) ?: 600,
                    )
                )
            }
        }

        TrackerKind.TRAKT -> {
            val reply = post(
                "https://api.trakt.tv/oauth/device/code",
                JSONObject().put("client_id", client.id).toString(),
            )
            val o = reply.json()
            val deviceCode = o?.optString("device_code").orEmpty()
            val userCode = o?.optString("user_code").orEmpty()
            if (!reply.ok || deviceCode.isBlank() || userCode.isBlank()) {
                Result.failure(Exception(problem(reply, "Trakt sign-in")))
            } else {
                Result.success(
                    DeviceLogin(
                        userCode = userCode,
                        verifyUrl = o?.optString("verification_url").orEmpty().ifBlank { "https://trakt.tv/activate" },
                        pollCode = deviceCode,
                        intervalSec = o?.optInt("interval", 5)?.coerceIn(2, 30) ?: 5,
                        expiresInSec = o?.optInt("expires_in", 600) ?: 600,
                    )
                )
            }
        }

        else -> Result.failure(Exception("${client.kind.label} has no code sign-in."))
    }

    /**
     * Polls a code sign-in. Success with a null account means "the user has not
     * confirmed it yet" — the caller keeps waiting (and stops at
     * [DeviceLogin.expiresInSec]).
     */
    suspend fun pollDevice(client: TrackerClient, login: DeviceLogin): Result<TrackerAccount?> =
        when (client.kind) {
            TrackerKind.SIMKL -> {
                val reply = get(
                    "https://api.simkl.com/oauth/pin/${enc(login.pollCode)}?client_id=${enc(client.id)}"
                )
                val token = reply.json()?.optString("access_token").orEmpty()
                if (token.isNotBlank()) {
                    val who = simklMe(client.id, token)
                    Result.success(
                        TrackerAccount(
                            kind = client.kind,
                            user = who?.first.orEmpty(),
                            userId = who?.second.orEmpty(),
                            token = token,
                        )
                    )
                } else if (reply.ok) {
                    Result.success(null)
                } else {
                    Result.failure(Exception(problem(reply, "Simkl sign-in")))
                }
            }

            TrackerKind.TRAKT -> {
                val reply = post(
                    "https://api.trakt.tv/oauth/device/token",
                    JSONObject().apply {
                        put("code", login.pollCode)
                        put("client_id", client.id)
                    }.toString(),
                )
                val o = reply.json()
                val token = o?.optString("access_token").orEmpty()
                when {
                    token.isNotBlank() -> {
                        val who = traktMe(client.id, token)
                        val expiresIn = o?.optLong("expires_in", 0L) ?: 0L
                        Result.success(
                            TrackerAccount(
                                kind = client.kind,
                                user = who?.first.orEmpty(),
                                userId = who?.second.orEmpty(),
                                token = token,
                                refresh = o?.optString("refresh_token").orEmpty(),
                                expiresAt = if (expiresIn > 0) {
                                    System.currentTimeMillis() + expiresIn * 1000L
                                } else {
                                    0L
                                },
                            )
                        )
                    }
                    // Trakt answers 400 while the user has not confirmed yet,
                    // 409 when the code was already used, 410/418 when it is
                    // over — and the caller must be able to tell those apart.
                    reply.code == 400 -> Result.success(null)
                    reply.code == 409 -> Result.failure(Exception("Trakt: this code was already used — start again."))
                    reply.code == 410 -> Result.failure(Exception("Trakt: the code expired — start again."))
                    reply.code == 418 -> Result.failure(Exception("Trakt: the sign-in was refused on trakt.tv."))
                    else -> Result.failure(Exception(problem(reply, "Trakt sign-in")))
                }
            }

            else -> Result.failure(Exception("${client.kind.label} has no code sign-in."))
        }

    // ------------------------------------------------------------------ refresh

    /** Redeems a refresh token, or null when the service/token has none. */
    suspend fun refresh(client: TrackerClient, account: TrackerAccount): TrackerAccount? {
        if (account.refresh.isBlank()) return null
        val reply = when (client.kind) {
            TrackerKind.MAL -> post(
                "https://myanimelist.net/v1/oauth2/token",
                form(
                    "grant_type" to "refresh_token",
                    "refresh_token" to account.refresh,
                    "client_id" to client.id,
                    "client_secret" to client.secret.takeIf { it.isNotBlank() },
                ),
                contentType = FORM,
            )

            TrackerKind.SHIKIMORI -> post(
                "https://shikimori.one/oauth/token",
                form(
                    "grant_type" to "refresh_token",
                    "refresh_token" to account.refresh,
                    "client_id" to client.id,
                    "client_secret" to client.secret,
                ),
                contentType = FORM,
            )

            TrackerKind.KITSU -> post(
                "https://kitsu.io/api/oauth/token",
                form(
                    "grant_type" to "refresh_token",
                    "refresh_token" to account.refresh,
                    "client_id" to client.id,
                    "client_secret" to client.secret,
                ),
                contentType = FORM,
            )

            TrackerKind.TRAKT -> post(
                "https://api.trakt.tv/oauth/token",
                JSONObject().apply {
                    put("refresh_token", account.refresh)
                    put("client_id", client.id)
                    put("client_secret", client.secret)
                    put("redirect_uri", REDIRECT_URI)
                    put("grant_type", "refresh_token")
                }.toString(),
            )

            // AniList tokens do not expire; Simkl's live until revoked and are
            // replaced by signing in again.
            TrackerKind.ANILIST, TrackerKind.SIMKL -> return null
        }
        val o = reply.json() ?: return null
        val token = o.optString("access_token")
        if (!reply.ok || token.isBlank()) return null
        val expiresIn = o.optLong("expires_in", 0L)
        return account.copy(
            token = token,
            refresh = o.optString("refresh_token").ifBlank { account.refresh },
            expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000L else 0L,
        )
    }

    // ------------------------------------------------------------------- search

    /**
     * The service's candidates for [media]'s title, best first.
     *
     * For a service that only keeps anime, the search IS the filter: a
     * live-action title simply finds nothing above the match threshold, which is
     * why nothing here has to know whether the title is an anime.
     */
    suspend fun search(
        client: TrackerClient,
        token: String,
        media: TrackerMedia,
    ): Result<List<TrackerMatch>> {
        val title = media.title.trim()
        if (title.isBlank()) return Result.success(emptyList())
        return try {
            when (client.kind) {
                TrackerKind.ANILIST -> Result.success(anilistSearch(token, title, media))
                TrackerKind.MAL -> Result.success(malSearch(token, title, media))
                TrackerKind.KITSU -> Result.success(kitsuSearch(token, title, media))
                TrackerKind.SHIKIMORI -> Result.success(shikimoriSearch(title, media))
                TrackerKind.SIMKL -> Result.success(simklSearch(client.id, title, media))
                TrackerKind.TRAKT -> Result.success(traktSearch(client.id, token, title, media))
            }
        } catch (e: Exception) {
            Result.failure(Exception("${client.kind.label} search failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    // --------------------------------------------------------------------- push

    /**
     * Writes [media]'s progress onto the service, as "watched up to episode N".
     *
     * Returns a sentence for the user ("AniList: Naruto — episode 12 of 220"),
     * because this is the only feedback they ever get about it.
     */
    suspend fun push(
        client: TrackerClient,
        account: TrackerAccount,
        match: TrackerMatch,
        media: TrackerMedia,
    ): Result<String> = try {
        when (client.kind) {
            TrackerKind.ANILIST -> anilistPush(account.token, match, media)
            TrackerKind.MAL -> malPush(account.token, match, media)
            TrackerKind.KITSU -> kitsuPush(client, account, match, media)
            TrackerKind.SHIKIMORI -> shikimoriPush(account.token, account.userId, match, media)
            TrackerKind.SIMKL -> simklPush(client.id, account.token, match, media)
            TrackerKind.TRAKT -> traktPush(client.id, account.token, match, media)
        }
    } catch (e: Exception) {
        Result.failure(Exception("${client.kind.label}: ${e.message ?: e.javaClass.simpleName}"))
    }

    /** "episode 12 of 220" / "watched" — the tail of a push sentence. */
    private fun doneNote(media: TrackerMedia, total: Int): String = when {
        media.movie || media.episode <= 0 -> "watched"
        total > 0 -> "episode ${media.episode} of $total"
        else -> "episode ${media.episode}"
    }

    private fun completed(media: TrackerMedia, total: Int): Boolean = when {
        media.movie -> true
        media.episode <= 0 -> false
        total > 0 -> media.episode >= total
        else -> false
    }

    // -------------------------------------------------------------------- AniList

    private fun graphql(token: String?, query: String, variables: JSONObject? = null): Reply {
        val body = JSONObject().apply {
            put("query", query)
            if (variables != null) put("variables", variables)
        }
        val headers: Map<String, String> = if (token.isNullOrBlank()) emptyMap()
        else mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        return post("https://graphql.anilist.co/", body.toString(), headers)
    }

    private fun anilistSearch(token: String, title: String, media: TrackerMedia): List<TrackerMatch> {
        val query = """
            query (${'$'}search: String) {
              Page(perPage: 12) {
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                  id
                  episodes
                  format
                  seasonYear
                  title { romaji english native }
                }
              }
            }
        """.trimIndent()
        val reply = graphql(token, query, JSONObject().put("search", title))
        val list = reply.json()?.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            ?: return emptyList()
        val out = ArrayList<TrackerMatch>(list.length())
        for (i in 0 until list.length()) {
            val m = list.optJSONObject(i) ?: continue
            val titles = m.optJSONObject("title")
            val names = listOfNotNull(
                titles?.optString("romaji"),
                titles?.optString("english"),
                titles?.optString("native"),
            ).filter { it.isNotBlank() }
            val best = names.map { it to matchScore(title, it, media.year, m.optInt("seasonYear", 0)) }
                .maxByOrNull { it.second }
            if (best == null) continue
            out.add(
                TrackerMatch(
                    id = m.optInt("id", 0).toString(),
                    title = names.firstOrNull().orEmpty(),
                    total = m.optInt("episodes", 0),
                    category = m.optString("format"),
                    score = best.second,
                    year = m.optInt("seasonYear", 0),
                )
            )
        }
        return out.sortedByDescending { it.score }
    }

    private fun anilistPush(token: String, match: TrackerMatch, media: TrackerMedia): Result<String> {
        val progress = if (media.movie) 1 else media.episode.coerceAtLeast(1)
        val status = if (completed(media, match.total)) "COMPLETED" else "CURRENT"
        val query = """
            mutation (${'$'}id: Int, ${'$'}progress: Int, ${'$'}status: MediaListStatus) {
              SaveMediaListEntry(mediaId: ${'$'}id, progress: ${'$'}progress, status: ${'$'}status) {
                id
                progress
                status
              }
            }
        """.trimIndent()
        val variables = JSONObject().apply {
            put("id", match.id.toIntOrNull() ?: 0)
            put("progress", progress)
            put("status", status)
        }
        val reply = graphql(token, query, variables)
        reply.graphQlError()?.let { return Result.failure(Exception("AniList: $it")) }
        if (!reply.ok) return Result.failure(Exception(problem(reply, "AniList update")))
        val saved = reply.json()?.optJSONObject("data")?.optJSONObject("SaveMediaListEntry")
        return if (saved == null) {
            Result.failure(Exception("AniList: the update was refused — sign in again."))
        } else {
            Result.success("AniList: ${match.title} — ${doneNote(media, match.total)}")
        }
    }

    // ------------------------------------------------------------------------ MAL

    private fun malHeaders(token: String) =
        mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")

    private fun malMe(token: String): Pair<String, String>? {
        val reply = get("https://api.myanimelist.net/v2/users/@me?fields=id,name", malHeaders(token))
        val o = reply.json() ?: return null
        val name = o.optString("name")
        if (name.isBlank()) return null
        return name to o.optString("id")
    }

    private fun malSearch(token: String, title: String, media: TrackerMedia): List<TrackerMatch> {
        // `nsfw=true` so a title the user is watching is not invisible in the
        // search: the adult-content switch already governs what Hikari shows,
        // and this call is about the title they are watching right now.
        val url = "https://api.myanimelist.net/v2/anime" +
            "?q=${enc(title)}&limit=12&nsfw=true" +
            "&fields=id,title,alternative_titles,num_episodes,start_date,media_type"
        val reply = get(url, malHeaders(token))
        val arr = reply.json()?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<TrackerMatch>(arr.length())
        for (i in 0 until arr.length()) {
            val node = arr.optJSONObject(i)?.optJSONObject("node") ?: continue
            val name = node.optString("title")
            val alt = node.optJSONObject("alternative_titles")
            val names = listOfNotNull(name, alt?.optString("en"), alt?.optString("ja"))
                .filter { it.isNotBlank() } + synopsisTitles(alt)
            val year = node.optString("start_date").take(4).toIntOrNull() ?: 0
            val best = names.map { it to matchScore(title, it, media.year, year) }.maxByOrNull { it.second }
            out.add(
                TrackerMatch(
                    id = node.optInt("id", 0).toString(),
                    title = name,
                    total = node.optInt("num_episodes", 0),
                    category = node.optString("media_type"),
                    score = best?.second ?: 0.0,
                    year = year,
                )
            )
        }
        return out.sortedByDescending { it.score }
    }

    /** MAL's "synonyms" — the other spellings of a title, as a list. */
    private fun synopsisTitles(alt: JSONObject?): List<String> {
        val arr = alt?.optJSONArray("synonyms") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun malPush(token: String, match: TrackerMatch, media: TrackerMedia): Result<String> {
        val id = match.id.toIntOrNull()
            ?: return Result.failure(Exception("MyAnimeList: bad title id."))
        val watched = if (media.movie) 1 else media.episode.coerceAtLeast(1)
        val status = if (completed(media, match.total)) "completed" else "watching"
        val body = form(
            "num_watched_episodes" to watched.toString(),
            "status" to status,
        )
        val headers = malHeaders(token) + ("Content-Type" to FORM)
        val reply = sendAny(
            listOf("PATCH", "PUT"),
            "https://api.myanimelist.net/v2/anime/$id/my_list_status",
            body,
            headers,
            FORM,
        )
        if (!reply.ok) return Result.failure(Exception(problem(reply, "MyAnimeList update")))
        return Result.success("MyAnimeList: ${match.title} — ${doneNote(media, match.total)}")
    }

    // ---------------------------------------------------------------------- Kitsu

    private fun kitsuHeaders(token: String) =
        mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.api+json")

    private fun kitsuMe(token: String): Pair<String, String>? {
        val reply = get("https://kitsu.io/api/edge/users?filter[self]=true", kitsuHeaders(token))
        val first = reply.json()?.optJSONArray("data")?.optJSONObject(0) ?: return null
        val name = first.optJSONObject("attributes")?.optString("name").orEmpty()
            .ifBlank { first.optJSONObject("attributes")?.optString("slug").orEmpty() }
        if (name.isBlank()) return null
        return name to first.optString("id")
    }

    private fun kitsuSearch(token: String, title: String, media: TrackerMedia): List<TrackerMatch> {
        val url = "https://kitsu.io/api/edge/anime?filter[text]=${enc(title)}&page[limit]=12"
        val reply = get(url, kitsuHeaders(token))
        val arr = reply.json()?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<TrackerMatch>(arr.length())
        for (i in 0 until arr.length()) {
            val node = arr.optJSONObject(i) ?: continue
            val attrs = node.optJSONObject("attributes") ?: continue
            val names = (attrs.optJSONObject("titles")?.let { t ->
                t.keys().asSequence()
                    .mapNotNull { t.optString(it).takeIf { s -> s.isNotBlank() } }
                    .toList()
            } ?: emptyList()) + listOfNotNull(attrs.optString("canonicalTitle").takeIf { it.isNotBlank() })
            val year = attrs.optString("startDate").take(4).toIntOrNull() ?: 0
            val best = names.map { it to matchScore(title, it, media.year, year) }.maxByOrNull { it.second }
            out.add(
                TrackerMatch(
                    id = node.optString("id"),
                    title = attrs.optString("canonicalTitle"),
                    total = attrs.optInt("episodeCount", 0),
                    category = attrs.optString("subtype"),
                    score = best?.second ?: 0.0,
                    year = year,
                )
            )
        }
        return out.sortedByDescending { it.score }
    }

    /**
     * Kitsu's list is a `library-entries` resource, so an update is a PATCH of
     * the existing entry (found by user + anime) or a POST of a new one.
     */
    private fun kitsuPush(
        client: TrackerClient,
        account: TrackerAccount,
        match: TrackerMatch,
        media: TrackerMedia,
    ): Result<String> {
        val userId = account.userId
        if (userId.isBlank()) {
            return Result.failure(Exception("Kitsu: the account was not identified — sign in again."))
        }
        val watched = if (media.movie) 1 else media.episode.coerceAtLeast(1)
        val status = if (completed(media, match.total)) "completed" else "current"
        val headers = kitsuHeaders(account.token) + ("Content-Type" to JSONAPI)
        val existing = get(
            "https://kitsu.io/api/edge/library-entries" +
                "?filter[userId]=${enc(userId)}&filter[animeId]=${enc(match.id)}&page[limit]=1",
            kitsuHeaders(account.token),
        ).json()?.optJSONArray("data")?.optJSONObject(0)?.optString("id").orEmpty()

        val attributes = JSONObject().apply {
            put("progress", watched)
            put("status", status)
        }
        val payload: String
        val method: String
        val url: String
        if (existing.isNotBlank()) {
            method = "PATCH"
            url = "https://kitsu.io/api/edge/library-entries/$existing"
            payload = JSONObject().apply {
                put(
                    "data",
                    JSONObject().apply {
                        put("id", existing)
                        put("type", "library-entries")
                        put("attributes", attributes)
                    },
                )
            }.toString()
        } else {
            method = "POST"
            url = "https://kitsu.io/api/edge/library-entries"
            payload = JSONObject().apply {
                put(
                    "data",
                    JSONObject().apply {
                        put("type", "library-entries")
                        put("attributes", attributes)
                        put(
                            "relationships",
                            JSONObject().apply {
                                put(
                                    "user",
                                    JSONObject().put(
                                        "data",
                                        JSONObject().put("type", "users").put("id", userId),
                                    ),
                                )
                                put(
                                    "anime",
                                    JSONObject().put(
                                        "data",
                                        JSONObject().put("type", "anime").put("id", match.id),
                                    ),
                                )
                            },
                        )
                    },
                )
            }.toString()
        }
        val reply = send(method, url, payload, headers, JSONAPI)
        if (!reply.ok) return Result.failure(Exception(problem(reply, "Kitsu update")))
        return Result.success("Kitsu: ${match.title} — ${doneNote(media, match.total)}")
    }

    // ------------------------------------------------------------------ Shikimori

    private fun shikimoriHeaders(token: String) =
        mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/json",
            // Shikimori answers 400 to a request whose User-Agent looks like a
            // library's (`okhttp/4.12`); the app's own name is what it asks for.
            "User-Agent" to "Hikari/" + com.hikari.app.BuildConfig.VERSION_NAME,
        )

    private fun shikimoriMe(token: String): Pair<String, String>? {
        val o = get("https://shikimori.one/api/users/whoami", shikimoriHeaders(token)).json() ?: return null
        val name = o.optString("nickname").ifBlank { o.optString("name") }
        if (name.isBlank()) return null
        return name to o.optInt("id", 0).toString()
    }

    private fun shikimoriSearch(title: String, media: TrackerMedia): List<TrackerMatch> {
        val url = "https://shikimori.one/api/animes?search=${enc(title)}&limit=12&order=popularity"
        val arr = get(
            url,
            mapOf(
                "Accept" to "application/json",
                "User-Agent" to "Hikari/" + com.hikari.app.BuildConfig.VERSION_NAME,
            ),
        ).array() ?: return emptyList()
        val out = ArrayList<TrackerMatch>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val names = listOfNotNull(
                o.optString("name").takeIf { it.isNotBlank() },
                o.optString("russian").takeIf { it.isNotBlank() },
            )
            val year = o.optString("aired_on").take(4).toIntOrNull() ?: 0
            val best = names.map { it to matchScore(title, it, media.year, year) }.maxByOrNull { it.second }
            out.add(
                TrackerMatch(
                    id = o.optInt("id", 0).toString(),
                    title = names.firstOrNull().orEmpty(),
                    total = o.optInt("episodes", 0),
                    category = o.optString("kind"),
                    score = best?.second ?: 0.0,
                    year = year,
                )
            )
        }
        return out.sortedByDescending { it.score }
    }

    private fun shikimoriPush(
        token: String,
        userId: String,
        match: TrackerMatch,
        media: TrackerMedia,
    ): Result<String> {
        val targetId = match.id.toIntOrNull()
            ?: return Result.failure(Exception("Shikimori: bad title id."))
        val headers = shikimoriHeaders(token) + ("Content-Type" to "application/json")
        val watched = if (media.movie) 1 else media.episode.coerceAtLeast(1)
        val status = if (completed(media, match.total)) "completed" else "watching"
        // Find this user's rate first: Shikimori's list is a collection of
        // `user_rate`s and POSTing a second rate for the same title is refused.
        val existing = runCatching {
            get(
                "https://shikimori.one/api/v2/user_rates?user_id=0&target_id=$targetId&target_type=Anime",
                shikimoriHeaders(token),
            ).array()
        }.getOrNull()
        val rateId = existing?.optJSONObject(0)?.optInt("id", 0) ?: 0
        val rate = JSONObject().apply {
            put("target_id", targetId)
            put("target_type", "Anime")
            put("status", status)
            put("episodes", watched)
            put("score", 0)
        }
        val reply = if (rateId > 0) {
            send(
                "PATCH",
                "https://shikimori.one/api/v2/user_rates/$rateId",
                JSONObject().put("user_rate", rate).toString(),
                headers,
            )
        } else {
            rate.put("user_id", userId.toIntOrNull() ?: 0)
            post(
                "https://shikimori.one/api/v2/user_rates",
                JSONObject().put("user_rate", rate).toString(),
                headers,
            )
        }
        if (!reply.ok) return Result.failure(Exception(problem(reply, "Shikimori update")))
        return Result.success("Shikimori: ${match.title} — ${doneNote(media, match.total)}")
    }

    // ---------------------------------------------------------------------- Simkl

    private fun simklHeaders(clientId: String, token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "simkl-api-key" to clientId,
        "Content-Type" to "application/json",
    )

    private fun simklMe(clientId: String, token: String): Pair<String, String>? {
        // Settings is a POST on Simkl's API (an oddity of theirs, but it is the
        // documented call) — see the CloudStream client this was verified against.
        val reply = post("https://api.simkl.com/users/settings", "", simklHeaders(clientId, token))
        val o = reply.json() ?: return null
        val name = o.optJSONObject("user")?.optString("name").orEmpty()
        if (name.isBlank()) return null
        return name to (o.optJSONObject("account")?.optInt("id", 0)?.toString() ?: "")
    }

    private fun simklSearch(clientId: String, title: String, media: TrackerMedia): List<TrackerMatch> {
        // Simkl splits its catalogue by kind, so the search path is the filter:
        // anime first (what most Hikari extensions carry), then the kind the
        // player's own type suggests.
        val paths = buildList {
            add("anime")
            if (media.movie) add("movie") else add("tv")
            if (media.movie) add("tv") else add("movie")
        }
        val out = ArrayList<TrackerMatch>()
        val seen = HashSet<String>()
        for (path in paths) {
            val arr = get(
                "https://api.simkl.com/search/$path?client_id=${enc(clientId)}&q=${enc(title)}&extended=full",
                mapOf("simkl-api-key" to clientId, "Accept" to "application/json"),
            ).array() ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optJSONObject("ids")?.optInt("simkl", 0) ?: 0
                if (id <= 0 || !seen.add(id.toString())) continue
                val name = o.optString("title")
                val year = o.optInt("year", 0)
                out.add(
                    TrackerMatch(
                        id = id.toString(),
                        title = name,
                        total = o.optInt("total_episodes", o.optInt("ep_count", 0)),
                        // The path the result came from is what tells the push
                        // which Simkl collection to write into.
                        category = if (path == "anime") "anime" else o.optString("type", path),
                        score = matchScore(title, name, media.year, year),
                        year = year,
                    )
                )
            }
        }
        return out.sortedByDescending { it.score }
    }

    private fun simklPush(
        clientId: String,
        token: String,
        match: TrackerMatch,
        media: TrackerMedia,
    ): Result<String> {
        val id = match.id.toIntOrNull()
            ?: return Result.failure(Exception("Simkl: bad title id."))
        val headers = simklHeaders(clientId, token)
        val ids = JSONObject().put("simkl", id)
        val payload: JSONObject
        if (media.movie) {
            payload = JSONObject().apply { put("movies", JSONArray().put(JSONObject().put("ids", ids))) }
        } else if (match.category == "anime") {
            // Simkl's anime form lists episodes directly under the title.
            payload = JSONObject().apply {
                put(
                    "anime",
                    JSONArray().put(
                        JSONObject().apply {
                            put("ids", ids)
                            put(
                                "episodes",
                                JSONArray().put(JSONObject().put("number", media.episode.coerceAtLeast(1))),
                            )
                        }
                    ),
                )
            }
        } else {
            val season = media.season.coerceAtLeast(1)
            payload = JSONObject().apply {
                put(
                    "shows",
                    JSONArray().put(
                        JSONObject().apply {
                            put("ids", ids)
                            put(
                                "seasons",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("number", season)
                                        put(
                                            "episodes",
                                            JSONArray().put(
                                                JSONObject().put("number", media.episode.coerceAtLeast(1))
                                            ),
                                        )
                                    }
                                ),
                            )
                        }
                    ),
                )
            }
        }
        var reply = post("https://api.simkl.com/sync/history", payload.toString(), headers)
        // Simkl accepts the "anime" form for most titles and the "shows" form for
        // the rest (their catalogue is not consistent about it), so a refusal of
        // one is retried as the other rather than reported as a failure the user
        // cannot act on.
        if (!reply.ok && match.category == "anime" && media.episode > 0) {
            val alternate = JSONObject().apply {
                put(
                    "shows",
                    JSONArray().put(
                        JSONObject().apply {
                            put("ids", ids)
                            put(
                                "seasons",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("number", media.season.coerceAtLeast(1))
                                        put(
                                            "episodes",
                                            JSONArray().put(
                                                JSONObject().put("number", media.episode.coerceAtLeast(1))
                                            ),
                                        )
                                    }
                                ),
                            )
                        }
                    ),
                )
            }
            reply = post("https://api.simkl.com/sync/history", alternate.toString(), headers)
        }
        if (!reply.ok) return Result.failure(Exception(problem(reply, "Simkl update")))
        return Result.success("Simkl: ${match.title} — ${doneNote(media, match.total)}")
    }

    // ---------------------------------------------------------------------- Trakt

    private fun traktHeaders(clientId: String, token: String?): Map<String, String> {
        val h = HashMap<String, String>()
        h["trakt-api-key"] = clientId
        h["trakt-api-version"] = "2"
        h["Content-Type"] = "application/json"
        if (!token.isNullOrBlank()) h["Authorization"] = "Bearer $token"
        return h
    }

    private fun traktMe(clientId: String, token: String): Pair<String, String>? {
        val o = get("https://api.trakt.tv/users/me", traktHeaders(clientId, token)).json() ?: return null
        val name = o.optString("username")
        if (name.isBlank()) return null
        // Trakt's per-user ids are text (a `slug` and a `uuid`), not numbers.
        return name to o.optJSONObject("ids")?.optString("slug").orEmpty()
    }

    private fun traktSearch(
        clientId: String,
        token: String,
        title: String,
        media: TrackerMedia,
    ): List<TrackerMatch> {
        // Trakt keeps films and series apart, and its search is per kind — which
        // is why the player's own type is what picks the endpoint here.
        val kinds = if (media.movie) listOf("movie", "show") else listOf("show", "movie")
        val out = ArrayList<TrackerMatch>()
        for (kind in kinds) {
            val arr = get(
                "https://api.trakt.tv/search/$kind?query=${enc(title)}&limit=12&extended=full",
                traktHeaders(clientId, token),
            ).array() ?: continue
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val node = entry.optJSONObject(kind) ?: continue
                val name = node.optString("title")
                val year = node.optInt("year", 0)
                out.add(
                    TrackerMatch(
                        id = node.optJSONObject("ids")?.optInt("trakt", 0)?.toString() ?: "",
                        title = name,
                        total = node.optInt("aired_episodes", 0),
                        category = kind,
                        score = matchScore(title, name, media.year, year),
                        year = year,
                    )
                )
            }
        }
        return out.sortedByDescending { it.score }
    }

    private fun traktPush(
        clientId: String,
        token: String,
        match: TrackerMatch,
        media: TrackerMedia,
    ): Result<String> {
        val id = match.id.toIntOrNull()
            ?: return Result.failure(Exception("Trakt: bad title id."))
        val ids = JSONObject().put("trakt", id)
        val payload: JSONObject = if (media.movie || match.category == "movie") {
            JSONObject().apply { put("movies", JSONArray().put(JSONObject().put("ids", ids))) }
        } else {
            JSONObject().apply {
                put(
                    "shows",
                    JSONArray().put(
                        JSONObject().apply {
                            put("ids", ids)
                            put(
                                "seasons",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("number", media.season.coerceAtLeast(1))
                                        put(
                                            "episodes",
                                            JSONArray().put(
                                                JSONObject().put("number", media.episode.coerceAtLeast(1))
                                            ),
                                        )
                                    }
                                ),
                            )
                        }
                    ),
                )
            }
        }
        val reply = post("https://api.trakt.tv/sync/history", payload.toString(), traktHeaders(clientId, token))
        if (!reply.ok) return Result.failure(Exception(problem(reply, "Trakt update")))
        return Result.success("Trakt: ${match.title} — ${doneNote(media, match.total)}")
    }

    /** Kitsu's JSON:API media type, which is not the plain JSON one. */
    private const val FORM = "application/x-www-form-urlencoded"
    private const val JSONAPI = "application/vnd.api+json"
}
