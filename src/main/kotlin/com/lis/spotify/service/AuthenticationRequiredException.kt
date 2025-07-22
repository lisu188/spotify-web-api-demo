package com.lis.spotify.service

/**
 * Thrown when a request fails due to missing or expired authentication.
 *
 * @param provider name of the provider that requires authentication
 */
class AuthenticationRequiredException(val provider: String) : RuntimeException()
