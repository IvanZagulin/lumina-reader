package com.lumina.reader.platform

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Random identifiers for common code (replaces java.util.UUID.randomUUID()). */
object Ids {

    /** A random (version 4) UUID in the usual lower-case form, like `UUID.randomUUID().toString()`. */
    @OptIn(ExperimentalUuidApi::class)
    fun randomUuid(): String = Uuid.random().toString()
}
