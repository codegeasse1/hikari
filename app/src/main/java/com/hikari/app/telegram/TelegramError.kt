package com.hikari.app.telegram

/**
 * TDLib's error strings, said in English.
 *
 * TDLib answers a refused request with a result like `PHONE_NUMBER_INVALID` or
 * `FLOOD_WAIT_34` — codes meant for a log, not for a person standing in a phone
 * field. Every login step in the Telegram tab shows what came back, so the
 * messages have to be worth reading: what happened, and what to do about it.
 *
 * Matching is done on the raw code with [String.contains], because TDLib
 * sometimes wraps the code in a sentence ("400: PHONE_NUMBER_INVALID") and
 * sometimes adds the offending value. Anything not recognised is passed through
 * with the code in brackets rather than swallowed: an unexplained refusal is
 * still a thousand times better than a button that appears to do nothing.
 */
object TelegramError {

    fun explain(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Telegram refused the request, and gave no reason."

        val upper = text.uppercase()

        // A flood wait carries the seconds in the code itself.
        val wait = Regex("FLOOD_WAIT_?(\\d+)").find(upper)
        if (wait != null) {
            val seconds = wait.groupValues[1].toIntOrNull() ?: 0
            return when {
                seconds >= 3600 ->
                    "Telegram is making this account wait " + (seconds / 3600) + " hour(s) " +
                        "before the next attempt. That is a limit on Telegram's side, not an " +
                        "error in the app."
                seconds > 60 ->
                    "Telegram is making this account wait " + (seconds / 60) + " minute(s) " +
                        "before the next attempt."
                seconds > 0 -> "Telegram is making this account wait " + seconds + " second(s)."
                else -> "Telegram is rate-limiting this account — wait a little, then try again."
            }
        }

        return when {
            upper.contains("API_ID_INVALID") ->
                "Telegram rejected this api_id / api_hash pair. Open my.telegram.org → " +
                    "API development tools and check that the NUMBER is the api_id and the " +
                    "32 hex characters are the api_hash, and that BOTH come from the same " +
                    "app — a pair made of two different apps' values is the usual cause."
            upper.contains("API_ID_PUBLISHED_FLOOD") ->
                "This api_id is a well-known published one and Telegram has blocked it " +
                    "for new logins. Create your own pair at my.telegram.org."
            upper.contains("PHONE_NUMBER_INVALID") ->
                "Telegram does not accept that phone number. Type it with its country " +
                    "code, e.g. +91 98512 27864."
            upper.contains("PHONE_NUMBER_BANNED") ->
                "That phone number is banned from Telegram."
            upper.contains("PHONE_NUMBER_OCCUPIED") ->
                "That number is already in use by another account."
            upper.contains("PHONE_NUMBER_FLOOD") ||
                upper.contains("PHONE_NUMBER_TOO_MANY") ->
                "Telegram has sent too many codes to that number. Wait, then try again."
            upper.contains("PHONE_CODE_INVALID") ->
                "That login code is not right. Type the code from the Telegram message or " +
                    "SMS exactly as it is."
            upper.contains("PHONE_CODE_EMPTY") -> "Type the login code Telegram sent you."
            upper.contains("PHONE_CODE_EXPIRED") ->
                "That code has expired. Go back and ask for a new one."
            upper.contains("PHONE_CODE_HASH_EMPTY") ->
                "The code request was lost — ask for a new code."
            upper.contains("PASSWORD_HASH_INVALID") ->
                "That two-step password is not right."
            upper.contains("SESSION_PASSWORD_NEEDED") ->
                "This account has a two-step password, and Telegram is waiting for it."
            upper.contains("PASSWORD_RECOVERY_NA") ->
                "This account has no recovery e-mail, so the two-step password cannot be " +
                    "reset from here."
            upper.contains("AUTH_RESTART") ->
                "Telegram asked to restart the login — press Start over."
            upper.contains("AUTH_KEY_UNREGISTERED") || upper.contains("AUTH_KEY_INVALID") ->
                "This login session was rejected. Press Start over and sign in again."
            upper.contains("USER_DEACTIVATED") ->
                "This Telegram account has been deactivated or deleted."
            upper.contains("SESSION_REVOKED") -> "The session was revoked from another device."
            upper.contains("NETWORK") || upper.contains("TIMEOUT") ||
                upper.contains("CONNECTION") ->
                "Telegram could not be reached — check the connection and try again."
            else ->
                // Unmapped: still show it, because a refusal with no reason is
                // exactly what this screen used to do.
                "Telegram refused: " + text.replace('_', ' ')
        }
    }
}
