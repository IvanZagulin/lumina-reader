package com.lumina.social

/**
 * Everything the server needs from its environment. The admin token has no
 * default on purpose: a server that mints invites must be told its secret
 * rather than fall back to a known one.
 */
data class ServerConfig(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val adminToken: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val adminToken = env["ADMIN_TOKEN"]?.takeIf { it.isNotBlank() }
                ?: error("ADMIN_TOKEN is required (set it in the environment or .env)")
            return ServerConfig(
                port = env["PORT"]?.toIntOrNull() ?: 8080,
                databaseUrl = env["DATABASE_URL"] ?: "jdbc:postgresql://db:5432/lumina",
                databaseUser = env["DATABASE_USER"] ?: "lumina",
                databasePassword = env["DATABASE_PASSWORD"] ?: "lumina",
                adminToken = adminToken,
            )
        }
    }
}
