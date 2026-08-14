package com.lumina.reader.core.update

/**
 * Small SemVer comparator kept independent from Android so update decisions can
 * be covered by regular JVM tests.
 */
object SemanticVersion {

    private val versionPattern = Regex(
        pattern = "^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$"
    )

    /** Returns true only when both values are valid and [candidate] is newer. */
    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current)?.let { it > 0 } == true

    /**
     * Compares two semantic versions. Build metadata is ignored, missing numeric
     * components are treated as zero, and a release is newer than a prerelease.
     * Returns null when either value cannot be parsed safely.
     */
    fun compare(left: String, right: String): Int? {
        val leftVersion = parse(left) ?: return null
        val rightVersion = parse(right) ?: return null

        val componentCount = maxOf(leftVersion.core.size, rightVersion.core.size)
        repeat(componentCount) { index ->
            val result = compareNumeric(
                leftVersion.core.getOrElse(index) { "0" },
                rightVersion.core.getOrElse(index) { "0" }
            )
            if (result != 0) return result
        }

        val leftPreRelease = leftVersion.preRelease
        val rightPreRelease = rightVersion.preRelease
        if (leftPreRelease == null && rightPreRelease == null) return 0
        if (leftPreRelease == null) return 1
        if (rightPreRelease == null) return -1

        val identifierCount = maxOf(leftPreRelease.size, rightPreRelease.size)
        repeat(identifierCount) { index ->
            val leftIdentifier = leftPreRelease.getOrNull(index) ?: return -1
            val rightIdentifier = rightPreRelease.getOrNull(index) ?: return 1
            val leftNumeric = leftIdentifier.all(Char::isDigit)
            val rightNumeric = rightIdentifier.all(Char::isDigit)
            val result = when {
                leftNumeric && rightNumeric -> compareNumeric(leftIdentifier, rightIdentifier)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> leftIdentifier.lowercase().compareTo(rightIdentifier.lowercase())
            }
            if (result != 0) return result.sign()
        }
        return 0
    }

    private fun parse(raw: String): ParsedVersion? {
        val match = versionPattern.matchEntire(raw.trim()) ?: return null
        val core = match.groupValues[1].split('.')
        val preRelease = match.groupValues[2]
            .takeIf(String::isNotEmpty)
            ?.split('.')
            ?.takeIf { identifiers -> identifiers.none(String::isEmpty) }
            ?: if (match.groupValues[2].isNotEmpty()) return null else null
        return ParsedVersion(core = core, preRelease = preRelease)
    }

    private fun compareNumeric(left: String, right: String): Int {
        val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
        val normalizedRight = right.trimStart('0').ifEmpty { "0" }
        return when {
            normalizedLeft.length != normalizedRight.length ->
                normalizedLeft.length.compareTo(normalizedRight.length).sign()

            else -> normalizedLeft.compareTo(normalizedRight).sign()
        }
    }

    private fun Int.sign(): Int = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }

    private data class ParsedVersion(
        val core: List<String>,
        val preRelease: List<String>?
    )
}
