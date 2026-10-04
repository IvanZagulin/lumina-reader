package com.lumina.reader.ui.catalog

/**
 * Android builds the catalogue services in `LuminaApp.onCreate`: they need a
 * Context and the importer, which lives in :app. Reaching this means the app
 * forgot to install them.
 */
internal actual fun defaultCatalogServices(): CatalogServices =
    error("CatalogServices are not installed: install them from LuminaApp.onCreate (CatalogServicesHolder.install)")
