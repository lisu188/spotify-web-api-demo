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

package com.lis.spotify.config


import com.mongodb.MongoClient
import com.mongodb.MongoClientOptions
import com.mongodb.MongoCredential
import com.mongodb.ServerAddress
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.config.AbstractMongoConfiguration
import java.net.URI

@Configuration
class MongoConfig : AbstractMongoConfiguration() {
    companion object {
        val MONGODB_URI = System.getenv()["MONGODB_URI"].orEmpty()
    }

    override fun getDatabaseName(): String {
        return parseUrl(MONGODB_URI).getOrDefault("databaseName", "db")
    }

    @Bean
    override fun mongoClient(): MongoClient {
        if (MONGODB_URI.isEmpty()) {
            return MongoClient()
        } else {
            val parseUrl = parseUrl(MONGODB_URI)
            return MongoClient(
                ServerAddress(
                    parseUrl.getOrDefault("host", ""),
                    parseUrl.getOrDefault("port", "").toInt()
                ),
                MongoCredential.createCredential(
                    parseUrl.getOrDefault("userName", ""),
                    parseUrl.getOrDefault("databaseName", ""),
                    parseUrl.getOrDefault("password", "").toCharArray()
                ),
                MongoClientOptions.builder().build()
            )
        }
    }

    private fun parseUrl(url: String): Map<String, String> {
        if (url.isNotEmpty()) {
            val uri = URI.create(url)
            val userInfo = uri.userInfo.split(":")
            val userName = userInfo[0]
            val databaseName = uri.path.split("/")[1]
            val password = userInfo[1]
            return mapOf(
                "host" to uri.host,
                "port" to uri.port.toString(),
                "userName" to userName,
                "databaseName" to databaseName,
                "password" to password
            )
        }
        return mapOf()
    }
}