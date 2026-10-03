package com.lumina.reader.core.opds

/** OpenSearch 1.1 URL templates as used by OPDS catalogues. */
object OpenSearch {
    private val PARAMETER = Regex("\\{([^{}]+)\\}")

    /**
     * Fills a template such as `https://host/opds/search?q={searchTerms}&p={startPage?}`.
     * `searchTerms` gets the encoded query; optional parameters (`?`) are left
     * empty; required ones get the OpenSearch defaults.
     */
    fun expand(template: String, query: String): String {
        val encoded = OpdsUrls.encodeQuery(query.trim())
        return PARAMETER.replace(template) { match ->
            val raw = match.groupValues[1].trim()
            val optional = raw.endsWith("?")
            val name = raw.removeSuffix("?").substringAfterLast(':')
            when {
                name.equals("searchTerms", ignoreCase = true) -> encoded
                optional -> ""
                name.equals("startPage", ignoreCase = true) -> "1"
                name.equals("startIndex", ignoreCase = true) -> "1"
                name.equals("count", ignoreCase = true) -> "50"
                name.equals("language", ignoreCase = true) -> "*"
                name.equals("inputEncoding", ignoreCase = true) -> "UTF-8"
                name.equals("outputEncoding", ignoreCase = true) -> "UTF-8"
                else -> ""
            }
        }
    }

    fun hasSearchTerms(template: String): Boolean =
        template.contains("{searchTerms}", ignoreCase = true) ||
            template.contains("{searchTerms?}", ignoreCase = true)

    /**
     * Chooses the best `<Url>` of an OpenSearch description: an Atom/OPDS
     * result type first, then any template with `{searchTerms}`.
     */
    fun chooseTemplate(urls: List<Pair<String?, String>>): String? {
        val usable = urls.filter { (_, template) -> hasSearchTerms(template) }
        return usable.firstOrNull { (type, _) -> type?.contains("opds-catalog", ignoreCase = true) == true }?.second
            ?: usable.firstOrNull { (type, _) -> type?.contains("atom", ignoreCase = true) == true }?.second
            ?: usable.firstOrNull { (type, _) -> type == null || type.contains("xml", ignoreCase = true) }?.second
            ?: usable.firstOrNull()?.second
    }
}
