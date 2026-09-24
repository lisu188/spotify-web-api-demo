package com.lis.spotify.config

import com.lis.spotify.AppEnvironment
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import java.security.MessageDigest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

internal object WebSecurity {
  fun secretsEqual(expected: String?, actual: String?): Boolean =
    !expected.isNullOrBlank() &&
      !actual.isNullOrBlank() &&
      MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        actual.toByteArray(Charsets.UTF_8),
      )

  fun secureCookies(request: HttpServletRequest): Boolean =
    request.isSecure ||
      runCatching { URI(AppEnvironment.BASE_URL).scheme.equals("https", true) }.getOrDefault(false)

  fun sameOrigin(origin: String, target: String): Boolean =
    runCatching {
        val source = URI(origin)
        val destination = URI(target)
        fun effectivePort(uri: URI) =
          if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        source.scheme in listOf("http", "https") &&
          source.host != null &&
          source.userInfo == null &&
          source.rawQuery == null &&
          source.rawFragment == null &&
          source.path.orEmpty().isEmpty() &&
          source.scheme.equals(destination.scheme, true) &&
          source.host.equals(destination.host, true) &&
          effectivePort(source) == effectivePort(destination)
      }
      .getOrDefault(false)
}

/** Cookie-authenticated writes require a same-origin AJAX request; CORS is never enabled. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class BrowserSecurityFilter : OncePerRequestFilter() {
  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    response.setHeader("X-Content-Type-Options", "nosniff")
    response.setHeader("X-Frame-Options", "DENY")
    response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin")
    response.setHeader(
      "Content-Security-Policy",
      "frame-ancestors 'none'; object-src 'none'; base-uri 'self'",
    )
    if (
      request.method !in setOf("GET", "HEAD", "OPTIONS") &&
        request.servletPath != "/refreshConfiguredTopPlaylists"
    ) {
      val origin = request.getHeader("Origin")
      val validOrigin =
        origin == null ||
          WebSecurity.sameOrigin(origin, request.requestURL.toString()) ||
          runCatching { WebSecurity.sameOrigin(origin, AppEnvironment.BASE_URL) }
            .getOrDefault(false)
      if (
        request.getHeader("X-Requested-With") != "XMLHttpRequest" ||
          !validOrigin ||
          request.getHeader("Sec-Fetch-Site") == "cross-site"
      ) {
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = "application/json"
        response.writer.write("{\"error\":\"A same-origin application request is required\"}")
        return
      }
    }
    chain.doFilter(request, response)
  }
}
