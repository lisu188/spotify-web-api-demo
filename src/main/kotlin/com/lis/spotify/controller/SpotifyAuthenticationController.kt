/*
 * MIT License
 *
 * Copyright (c) 2019 Andrzej Lis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

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
        val CLIENT_ID: String = System.getenv()["CLIENT_ID"].orEmpty()
        val CLIENT_SECRET: String = System.getenv()["CLIENT_SECRET"].orEmpty()

        val CALLBACK: String = "https://spotify-web-api-demo.herokuapp.com/callback"
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