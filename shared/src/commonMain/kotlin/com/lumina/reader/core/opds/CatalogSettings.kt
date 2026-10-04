package com.lumina.reader.core.opds

import com.lumina.reader.platform.Ids
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json

/**
 * JSON form of the user's catalogues as stored in DataStore: an array of
 * objects with the keys below, every one optional. Versions before the move to
 * common code wrote it with Gson; kotlinx.serialization reads and writes the
 * same JSON (null fields are left out, unknown keys are ignored). Decoding
 * never throws and drops entries without a usable URL.
 */
object CatalogSettingsCodec {

    @Serializable
    private data class StoredCatalog(
        @SerialName("id") val id: String? = null,
        @SerialName("name") val name: String? = null,
        @SerialName("url") val url: String? = null,
        @SerialName("username") val username: String? = null,
        @SerialName("password") val password: String? = null,
        @SerialName("enabled") val enabled: Boolean? = null
    )

    private val format = Json {
        ignoreUnknownKeys = true
        // A null where a value is expected becomes the default (all are null).
        coerceInputValues = true
    }

    private val storedListSerializer = ListSerializer(StoredCatalog.serializer().nullable).nullable

    fun encodeUserCatalogs(catalogs: List<OpdsCatalogConfig>): String =
        format.encodeToString(
            storedListSerializer,
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
        val stored: List<StoredCatalog?> = try {
            format.decodeFromString(storedListSerializer, json) ?: return emptyList()
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

    fun newUserCatalogId(): String = "user:" + Ids.randomUuid()
}
