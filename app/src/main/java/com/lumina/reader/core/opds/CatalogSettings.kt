package com.lumina.reader.core.opds

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * JSON form of the user's catalogues as stored in DataStore. Every field is
 * nullable with a default because Gson ignores Kotlin nullability; decoding
 * never throws and drops entries without a usable URL.
 */
object CatalogSettingsCodec {
    private val gson = Gson()

    private data class StoredCatalog(
        @SerializedName("id") val id: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("url") val url: String? = null,
        @SerializedName("username") val username: String? = null,
        @SerializedName("password") val password: String? = null,
        @SerializedName("enabled") val enabled: Boolean? = null
    )

    fun encodeUserCatalogs(catalogs: List<OpdsCatalogConfig>): String =
        gson.toJson(
            catalogs.filterNot { it.builtIn }.map {
                StoredCatalog(
                    id = it.id,
                    name = it.name,
                    url = it.url,
                    username = it.username,
                    password = it.password,
                    enabled = it.enabled
                )
            }
        )

    fun decodeUserCatalogs(json: String?): List<OpdsCatalogConfig> {
        if (json.isNullOrBlank()) return emptyList()
        val stored: Array<StoredCatalog?> = try {
            gson.fromJson(json, Array<StoredCatalog?>::class.java) ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        return stored.filterNotNull().mapNotNull { item ->
            val url = item.url?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            OpdsCatalogConfig(
                id = item.id?.takeIf { it.isNotBlank() } ?: newUserCatalogId(),
                name = item.name?.trim()?.takeIf { it.isNotEmpty() } ?: (OpdsUrls.host(url) ?: url),
                url = url,
                username = item.username.orEmpty(),
                password = item.password.orEmpty(),
                enabled = item.enabled ?: true,
                builtIn = false
            )
        }
    }

    /** Built-ins first (with their enabled flag applied), then the user's catalogues. */
    fun merge(
        builtIns: List<OpdsCatalogConfig>,
        disabledBuiltInIds: Set<String>,
        userCatalogs: List<OpdsCatalogConfig>
    ): List<OpdsCatalogConfig> =
        builtIns.map { it.copy(enabled = it.id !in disabledBuiltInIds) } + userCatalogs

    fun newUserCatalogId(): String = "user:" + UUID.randomUUID().toString()
}
