package com.lis.spotify.service


import com.lis.spotify.domain.AuthToken
import com.lis.spotify.controller.SpotifyAuthenticationController
import org.bson.BsonDocument
import org.bson.BsonString
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.findAll
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.client.postForObject
import org.springframework.web.util.UriComponentsBuilder


@Service
class SpotifyAuthenticationService(private val restTemplateBuilder: RestTemplateBuilder, val mongoTemplate: MongoTemplate) {
    fun getHeaders(token: AuthToken): HttpHeaders {
        val headers = HttpHeaders();
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

        mongoTemplate.getCollection("auth").deleteMany(BsonDocument("clientId", BsonString(token.clientId)))
        mongoTemplate.save(token, "auth")
    }

    fun getAuthToken(clientId: String): AuthToken? {
        return mongoTemplate.findAll<AuthToken>("auth").find { it.clientId == clientId }
    }

    fun getAuthTokens(): List<AuthToken> {
        return mongoTemplate.findAll<AuthToken>("auth")
    }

    fun refreshToken(clientId: String) {
        LoggerFactory.getLogger(javaClass).info("refreshToken: {}", clientId)

        val code = getAuthToken(clientId)?.refresh_token.orEmpty()

        val tokenUrl = UriComponentsBuilder.fromHttpUrl(SpotifyAuthenticationController.TOKEN_URL)
                .queryParam("grant_type", "refresh_token")
                .queryParam("refresh_token", code)
                .build().toUri()

        val authToken = restTemplateBuilder.basicAuthentication(SpotifyAuthenticationController.CLIENT_ID, SpotifyAuthenticationController.CLIENT_SECRET).build().postForObject<AuthToken>(tokenUrl)//TODO: check error message

        authToken?.clientId = clientId
        authToken?.refresh_token = code

        authToken?.let { setAuthToken(it) }
    }

    fun isAuthorized(clientId: String) =
            clientId.isNotEmpty() && getAuthToken(clientId) != null
}


