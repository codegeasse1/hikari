package com.hikari.app.music.potoken

class PoTokenException(message: String) : Exception(message)

/** Thrown when the WebView of the device is too old / broken to run BotGuard. */
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception =
    if (error.contains("SyntaxError")) BadWebViewException(error) else PoTokenException(error)
