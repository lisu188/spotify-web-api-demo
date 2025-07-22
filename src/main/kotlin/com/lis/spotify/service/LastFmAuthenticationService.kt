package com.lis.spotify.service

import com.lis.spotify.AppEnvironment.LastFm
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

/**
 * LastFmAuthenticationService handles building the Last.fm authorization URL and exchanging an
 * authentication token for a session key using the auth.getSession API.
 *
 * It computes the required MD5 API signature by concatenating parameters in order, appending the
 * secret, and then generating an MD5 hash.
 */
@Service
class LastFmAuthenticationService {

  private val restTemplate = RestTemplate()
  val sessionCache = ConcurrentHashMap<String, Pair<String, Long>>()

  fun setSession(login: String, sessionKey: String) {
    sessionCache[login] = Pair(sessionKey, System.currentTimeMillis())
    logger.info("Stored session for {}", login)
  }

  internal fun isTokenExpired(timestamp: Long): Boolean {
    return System.currentTimeMillis() - timestamp > EXPIRATION_MS
  }

  private fun removeExpiredSessions() {
    val it = sessionCache.iterator()
    while (it.hasNext()) {
      val entry = it.next()
      if (isTokenExpired(entry.value.second)) {
        it.remove()
      }
    }
  }

  fun getSessionKey(login: String): String? {
    removeExpiredSessions()
    return sessionCache[login]?.first
  }

  fun isAuthorized(sessionKey: String): Boolean {
    removeExpiredSessions()
    return sessionKey.isNotEmpty() && sessionCache.values.any { it.first == sessionKey }
  }

  /**
   * Constructs the URL that the user will be redirected to in order to grant access.
   *
   * Format: http://www.last.fm/api/auth/?api_key=xxx&cb=<redirectUri>
   */
  fun getAuthorizationUrl(): String {
    logger.debug("getAuthorizationUrl() called")
    return UriComponentsBuilder.fromHttpUrl(LastFm.AUTHORIZE_URL)
      .queryParam("api_key", LastFm.API_KEY)
      .queryParam("cb", "{cb}")
      .encode()
      .buildAndExpand(LastFm.CALLBACK_URL)
      .toUriString()
  }

  /**
   * Exchanges the provided authentication token for a session by invoking Last.fm's auth.getSession
   * API.
   *
   * The request includes:
   * - api_key: Your API key.
   * - token: The authentication token.
   * - api_sig: An MD5 hash computed from the sorted parameters and the shared secret.
   * - format: json
   *
   * @param token The authentication token received at your callback.
   * @return A map containing session data if successful; otherwise, null.
   */
  fun getSession(token: String): Map<String, Any>? {
    logger.debug("getSession called with token {}", token)
    val method = "auth.getSession"
    // Create signature string:
    // "api_keyYOUR_API_KEYmethodauth.getSessiontokenYOUR_TOKENYOUR_API_SECRET"
    val signatureString =
      "api_key${LastFm.API_KEY}" + "method$method" + "token$token" + LastFm.API_SECRET
    val apiSig = md5(signatureString)

    val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }

    val body: MultiValueMap<String, String> =
      LinkedMultiValueMap<String, String>().apply {
        add("method", method)
        add("api_key", LastFm.API_KEY)
        add("token", token)
        add("api_sig", apiSig)
        add("format", "json")
      }

    val request = HttpEntity<MultiValueMap<String, String>>(body, headers)

    return try {
      val response = restTemplate.postForEntity(LastFm.API_URL, request, Map::class.java)
      logger.debug("getSession received status {}", response.statusCode)
      val body = response.body as? Map<String, Any>
      val session = body?.get("session") as? Map<*, *>
      val name = session?.get("name") as? String
      val key = session?.get("key") as? String
      if (!name.isNullOrEmpty() && !key.isNullOrEmpty()) {
        setSession(name, key)
        logger.info("Retrieved session for {}", name)
      }
      body
    } catch (ex: Exception) {
      logger.error("Error getting session for token $token", ex)
      null
    }
  }

  /**
   * Computes the MD5 hash of the input string.
   *
   * @param input The string to hash.
   * @return A 32-character hexadecimal MD5 hash.
   */
  private fun md5(input: String): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(input.toByteArray(Charsets.UTF_8))
    val bigInt = BigInteger(1, digest)
    return bigInt.toString(16).padStart(32, '0')
  }

  companion object {
    private val logger = LoggerFactory.getLogger(LastFmAuthenticationService::class.java)
    // Session entries older than five minutes are considered expired
    private const val EXPIRATION_MS = 5 * 60 * 1000L
  }
}
