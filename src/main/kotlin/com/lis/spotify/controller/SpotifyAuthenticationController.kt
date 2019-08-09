package com.lis.spotify.controller

import com.lis.spotify.domain.AuthToken
import com.lis.spotify.domain.User
import com.lis.spotify.service.SpotifyAuthenticationService
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.client.exchange
import org.springframework.web.client.postForObject
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.util.UriComponentsBuilder
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletResponse

@Controller
class SpotifyAuthenticationController(val spotifyAuthenticationService: SpotifyAuthenticationService, var restTemplateBuilder: RestTemplateBuilder) {
    companion object {
        val CALLBACK: String = "https://spotify-web-api-demo.herokuapp.com/callback"
        val CLIENT_ID: String = "1aa49071301549ad869e22dfb5e4982c"
        val CLIENT_SECRET: String = "000616d5bdef440bac9eacd0d3fd3fc2"
        val AUTH_URL = "https://accounts.spotify.com/authorize"
        val SCOPES = "user-top-read playlist-modify-public"
        val TOKEN_URL = "https://accounts.spotify.com/api/token"
    }

    fun getCurrentUserId(token: AuthToken): String? =
            restTemplateBuilder.build().exchange<User>("https://api.spotify.com/v1/me", HttpMethod.GET,
                    HttpEntity(null, spotifyAuthenticationService.getHeaders(token))).body?.id

    @GetMapping("/callback")
    fun callback(code: String, response: HttpServletResponse): String {
        val tokenUrl = UriComponentsBuilder.fromHttpUrl(TOKEN_URL)
                .queryParam("grant_type", "authorization_code")
                .queryParam("code", code)
                .queryParam("redirect_uri", CALLBACK)
                .build().toUri()

        val authToken = restTemplateBuilder.basicAuthentication(CLIENT_ID, CLIENT_SECRET).build().postForObject<AuthToken>(tokenUrl)//TODO: check error message

        authToken?.let { token: AuthToken ->
            getCurrentUserId(token)?.let { clientId: String ->
                authToken.clientId = clientId
                spotifyAuthenticationService.setAuthToken(token)
                response.addCookie(Cookie("clientId", clientId))
            }
        }
        return "redirect:/"
    }


    @GetMapping("/authorize")
    fun authorize(attributes: RedirectAttributes, response: HttpServletResponse, @CookieValue("clientId", defaultValue = "") clientId: String): String {
        val builder = UriComponentsBuilder.fromHttpUrl(AUTH_URL)
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("scope", SCOPES)
                .queryParam("redirect_uri", CALLBACK)

        return "redirect:" + builder.toUriString();
    }


}