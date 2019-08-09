package com.lis.spotify.service

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders.RETRY_AFTER
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.exchange

@Service
class SpotifyRestService(restTemplateBuilder: RestTemplateBuilder, val spotifyAuthenticationService: SpotifyAuthenticationService) {


    val restTemplate: RestTemplate = restTemplateBuilder.build();


    final suspend inline fun <reified U : Any> doRequest(url: String, httpMethod: HttpMethod, params: Map<String, Any> = HashMap(), body: Any? = null, clientId: String): U {
        return doRequest {
            LoggerFactory.getLogger(javaClass).debug("doRequest: {} {} {} {}", url, httpMethod, params, body)
            doExchange<U>(url, httpMethod, body, clientId, params)
        }
    }

    suspend fun <U> doRequest(task: () -> U): U {
        return GlobalScope.async {
            try {
                task()
            } catch (e: HttpClientErrorException.TooManyRequests) {
                e.responseHeaders?.get(RETRY_AFTER)?.first()?.toInt()?.let { delay((it) * 1000L + 500L) }
                doRequest(task)
            }
//            catch (e: HttpClientErrorException.Unauthorized){
//                //TODO: refresh Token
//            }
        }.await()
    }

    final inline fun <reified U : Any> doExchange(url: String, httpMethod: HttpMethod, body: Any?, clientId: String, params: Map<String, Any>): U {
        return restTemplate.exchange<U>(
                url,
                httpMethod,
                HttpEntity(body, spotifyAuthenticationService.getHeaders(clientId)),
                params).body ?: throw Exception()//TODO:
    }

    final suspend inline fun <reified U : Any> doGet(url: String, params: Map<String, Any> = HashMap(), body: Any? = null, clientId: String): U {
        return doRequest(url, HttpMethod.GET, params, body, clientId)
    }

    final suspend inline fun <reified U : Any> doDelete(url: String, params: Map<String, Any> = HashMap(), body: Any? = null, clientId: String): U {
        return doRequest(url, HttpMethod.DELETE, params, body, clientId)
    }

    final suspend inline fun <reified U : Any> doPost(url: String, params: Map<String, Any> = HashMap(), body: Any? = null, clientId: String): U {
        return doRequest(url, HttpMethod.POST, params, body, clientId)
    }
}