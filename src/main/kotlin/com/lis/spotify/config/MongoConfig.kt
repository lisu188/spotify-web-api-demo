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
        return parseUrl(MONGODB_URI).getOrDefault("databaseName", "db");
    }

    @Bean
    override fun mongoClient(): MongoClient {
        if (MONGODB_URI.isEmpty()) {
            return MongoClient()
        } else {
            val parseUrl = parseUrl(MONGODB_URI)
            return MongoClient(ServerAddress(parseUrl.getOrDefault("host", ""),
                    parseUrl.getOrDefault("port", "").toInt()),
                    MongoCredential.createCredential(
                            parseUrl.getOrDefault("userName", ""),
                            parseUrl.getOrDefault("databaseName", ""),
                            parseUrl.getOrDefault("password", "").toCharArray()),
                    MongoClientOptions.builder().build())
        }
    }

    private fun parseUrl(url: String): Map<String, String> {
        if (url.isNotEmpty()) {
            val uri = URI.create(url)
            val userInfo = uri.userInfo.split(":")
            val userName = userInfo[0]
            val databaseName = uri.path.split("/")[1]
            val password = userInfo[1]
            return mapOf("host" to uri.host, "port" to uri.port.toString(), "userName" to userName, "databaseName" to databaseName, "password" to password)
        }
        return mapOf()
    }
}