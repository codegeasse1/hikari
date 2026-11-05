package org.hany18h.parsers.exception

import okio.IOException
import org.hany18h.parsers.InternalParsersApi
import org.hany18h.parsers.model.MangaSource

/**
 * Authorization is required for access to the requested content
 */
public class AuthRequiredException @InternalParsersApi @JvmOverloads constructor(
	public val source: MangaSource,
	cause: Throwable? = null,
) : IOException("Authorization required", cause)
