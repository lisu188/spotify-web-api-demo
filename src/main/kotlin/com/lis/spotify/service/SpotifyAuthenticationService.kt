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

package com.lis.spotify.service


import com.google.common.cache.CacheBuilder
import com.lis.spotify.controller.SpotifyAuthenticationController
import com.lis.spotify.domain.AuthToken
import org.bson.BsonDocument
import org.bson.BsonString
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.findAll
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.client.postForObject
import org.springframework.web.util.UriComponentsBuilder
import java.util.concurrent.TimeUnit


@Service
class SpotifyAuthenticationService(
    private val restTemplateBuilder: RestTemplateBuilder,
    val mongoTemplate: MongoTemplate
) {
    val tokenCache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(50)
        .build<String, AuthToken>()

    fun getHeaders(token: AuthToken): HttpHeaders {
        val headers = HttpHeaders()
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token.access_token)//TODO, auto get Token
        headers.set(HttpHeaders.ACCEPT, "application/json")
        return headers
    }


    fun getHeaders(clientId: String): HttpHeaders {
        val authToken = getAuthToken(clientId)
        if (authToken != null) {
            return getHeaders(authToken)
        }
        return HttpHeaders()
    }

    fun setAuthToken(token: AuthToken) {
        LoggerFactory.getLogger(javaClass).info("setAuthToken: {}", token.clientId)

        mongoTemplate.getCollection("auth")
            .deleteMany(BsonDocument("clientId", BsonString(token.clientId)))
        mongoTemplate.save(token, "auth")

        tokenCache.invalidate("clientId")
    }

    fun getAuthToken(clientId: String): AuthToken? {
        return try {
            tokenCache.get(clientId) {
                mongoTemplate.find(
                    Query().addCriteria(Criteria.where("clientId").`is`(clientId)),
                    AuthToken::class.java,
                    "auth"
                ).firstOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getAuthTokens(): List<AuthToken> {
        return mongoTemplate.findAll<AuthToken>("auth").shuffled()
    }

    fun refreshToken(clientId: String) {
        LoggerFactory.getLogger(javaClass).info("refreshToken: {}", clientId)

        val code = getAuthToken(clientId)?.refresh_token.orEmpty()

        val tokenUrl = UriComponentsBuilder.fromHttpUrl(SpotifyAuthenticationController.TOKEN_URL)
            .queryParam("grant_type", "refresh_token")
            .queryParam("refresh_token", code)
            .build().toUri()

        val authToken = restTemplateBuilder.basicAuthentication(
            SpotifyAuthenticationController.CLIENT_ID,
            SpotifyAuthenticationController.CLIENT_SECRET
        ).build().postForObject<AuthToken>(tokenUrl)//TODO: check error message

        authToken?.clientId = clientId
        authToken?.refresh_token = code

        authToken?.let { setAuthToken(it) }
    }

    fun isAuthorized(clientId: String) =
        clientId.isNotEmpty() && getAuthToken(clientId) != null
}


