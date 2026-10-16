package com.lumina.social

/**
 * What a book in transit is allowed to cost this host.
 *
 * Sending a book puts its file on the server's disk until the other person's
 * app collects it, so each of these is a guard rather than a preference: this
 * box shares a nearly full disk with unrelated production services, and one
 * person who does not open their app for a month must not be able to fill it.
 *
 * @param directory where files in transit are kept - a Docker volume, so they
 *   never land in the image and survive a restart.
 * @param maxFileBytes the largest single book accepted. EPUB and FB2 are
 *   normally under 10 MB; this leaves room for a scanned PDF without letting
 *   one upload swallow the disk.
 * @param maxStoreBytes the ceiling on everything waiting to be collected, all
 *   users together. An upload that would cross it is refused with an
 *   explanation instead of filling the disk.
 * @param ttlDays how long an uncollected book waits before the sweeper drops
 *   it; without this, files for someone who stopped using the app stay for ever.
 */
data class TransferLimits(
    val directory: String = "/data/transfers",
    val maxFileBytes: Long = 50L * 1024 * 1024,
    val maxStoreBytes: Long = 300L * 1024 * 1024,
    val ttlDays: Int = 14,
) {
    val ttlMillis: Long get() = ttlDays.toLong() * 24 * 60 * 60 * 1000
}

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
    val transfers: TransferLimits = TransferLimits(),
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val adminToken = env["ADMIN_TOKEN"]?.takeIf { it.isNotBlank() }
                ?: error("ADMIN_TOKEN is required (set it in the environment or .env)")
            val defaults = TransferLimits()
            return ServerConfig(
                port = env["PORT"]?.toIntOrNull() ?: 8080,
                databaseUrl = env["DATABASE_URL"] ?: "jdbc:postgresql://db:5432/lumina",
                databaseUser = env["DATABASE_USER"] ?: "lumina",
                databasePassword = env["DATABASE_PASSWORD"] ?: "lumina",
                adminToken = adminToken,
                transfers = TransferLimits(
                    directory = env["TRANSFER_DIR"]?.takeIf { it.isNotBlank() } ?: defaults.directory,
                    maxFileBytes = positiveLong(env["MAX_TRANSFER_BYTES"], defaults.maxFileBytes),
                    maxStoreBytes = positiveLong(env["MAX_TRANSFER_STORE_BYTES"], defaults.maxStoreBytes),
                    ttlDays = positiveInt(env["TRANSFER_TTL_DAYS"], defaults.ttlDays),
                ),
            )
        }

        /** A limit is only honoured when it parses to something above zero; nonsense keeps the default. */
        private fun positiveLong(raw: String?, default: Long): Long =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: default

        private fun positiveInt(raw: String?, default: Int): Int =
            raw?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: default
    }
}
