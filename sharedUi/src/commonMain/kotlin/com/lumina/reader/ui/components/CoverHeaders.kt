package com.lumina.reader.ui.components

import coil3.network.NetworkHeaders

/**
 * The HTTP headers of a catalogue cover request (e.g. Basic auth of a login
 * catalogue) for Coil 3's `httpHeaders`: one `add` per entry, as Coil 2's
 * `addHeader` did.
 */
fun Map<String, String>.toNetworkHeaders(): NetworkHeaders =
    NetworkHeaders.Builder()
        .apply { forEach { (name, value) -> add(name, value) } }
        .build()
