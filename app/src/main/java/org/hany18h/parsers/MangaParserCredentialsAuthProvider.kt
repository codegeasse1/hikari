package org.hany18h.parsers

import java.io.IOException

/**
 * Authentication provider for sources that support an in-app credentials flow
 * instead of a browser-based login page.
 */
public interface MangaParserCredentialsAuthProvider : MangaParserAuthProvider {

	public suspend fun getAccount(): MangaAuthAccount?

	public suspend fun signIn(email: String, password: String): MangaAuthAccount

	public suspend fun signUp(
		displayName: String,
		email: String,
		password: String,
	): MangaAuthAccount

	public suspend fun refreshAccount(): MangaAuthAccount?

	public suspend fun sendVerificationEmail()

	public suspend fun sendPasswordReset(email: String)

	public suspend fun signOut()
}

public data class MangaAuthAccount(
	public val username: String,
	public val displayName: String?,
	public val isEmailVerified: Boolean,
)

public class MangaAuthException(
	public val code: String,
	message: String = code,
	cause: Throwable? = null,
) : IOException(message, cause)
