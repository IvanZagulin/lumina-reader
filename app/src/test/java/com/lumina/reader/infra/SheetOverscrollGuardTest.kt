package com.lumina.reader.infra

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every bottom sheet that holds something scrollable must absorb what that
 * scroll cannot use.
 *
 * A list inside a `ModalBottomSheet` hands its leftover scroll to the sheet,
 * which tries to move, snaps back and tries again, so the contents jump up and
 * down the moment the list reaches its end. `Modifier.absorbListOverscroll()`
 * swallows that leftover. The mistake is invisible in code review and has been
 * fixed one screen at a time three times already, so it is checked here across
 * the whole source tree instead: there is no unit test for a gesture, and an
 * instrumented one would not be run often enough to help.
 *
 * A file that opens a sheet and scrolls inside it has to mention the modifier.
 * That is coarse on purpose — it cannot tell which scrollable got it — but it
 * is what catches a new sheet written without it at all, which is how every one
 * of these bugs started.
 */
class SheetOverscrollGuardTest {

    @Test
    fun everySheetWithAScrollableAbsorbsItsOverscroll() {
        val offenders = sourceFiles()
            .filter { file ->
                val text = file.readText()
                "ModalBottomSheet(" in text &&
                    SCROLLABLE.any { it in text } &&
                    "absorbListOverscroll" !in text
            }
            .map { it.name }
            .sorted()

        assertTrue(
            "These sheets scroll inside themselves without absorbing the leftover scroll, " +
                "so their contents jump at the end of the list. " +
                "Add Modifier.absorbListOverscroll() to the scrollable: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theScanActuallySeesTheSources() {
        // Guards the test itself: a wrong working directory would make it pass empty.
        val files = sourceFiles()
        assertTrue("No Kotlin sources found under $repoRoot", files.size > 100)
        assertTrue(
            "No sheet found at all - the scan is looking in the wrong place",
            files.any { "ModalBottomSheet(" in it.readText() }
        )
    }

    private fun sourceFiles(): List<File> =
        SOURCE_ROOTS
            .map { repoRoot.resolve(it) }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }

    /** The directory holding settings.gradle.kts, from wherever Gradle runs the test. */
    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found above ${File(".").absolutePath}")
    }

    private companion object {
        val SOURCE_ROOTS = listOf("sharedUi/src", "app/src/main")
        val SCROLLABLE = listOf("verticalScroll(", "LazyColumn(", "LazyVerticalGrid(", "LazyVerticalStaggeredGrid(")
    }
}
