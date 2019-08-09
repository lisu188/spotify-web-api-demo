package com.lis.spotify.domain

data class AuthToken(val access_token: String,
                     val token_type: String,
                     val scope: String,
                     val expires_in: Int,
                     var refresh_token: String?,
                     var clientId: String?)

data class Song(val artist: String,
                val title: String)