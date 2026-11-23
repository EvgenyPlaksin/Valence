package com.lnight.valence.server

data class DatabaseConfiguration(
    val url: String,
    val username: String,
    val password: String,
    val maxConnections: Int,
    val autoMigrate: Boolean,
)
