package com.hazia.forgetmenot.ui.sheets

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compose's path parser reads numbers greedily, so SVG's packed arc flags ("a8 8 0 100-16": flags 1 and
 * 0, then x 0) come out as one number and the arc draws garbled. Every icon spells its flags out:
 * the shared AppIcon set, and any path string a screen keeps for itself.
 */
class AppIconPathTest {
    @Test fun everyArcSpellsOutItsFlags() {
        for (icon in AppIcon.entries) {
            assertEquals("${icon.name}: ${arcProblems(icon.path)}", emptyList<String>(), arcProblems(icon.path))
        }
    }

    @Test fun composeParsesOneArcPerSevenArguments() {
        for (icon in AppIcon.entries) {
            val parsed = addPathNodes(icon.path).count { it is PathNode.ArcTo || it is PathNode.RelativeArcTo }
            assertEquals(icon.name, arcArguments(icon.path).sumOf { it.size } / 7, parsed)
        }
    }

    /** Screens that build their own vectors (a private strokeIcon, HomeIcon) are held to the same rule. */
    @Test fun everyPathLiteralInTheSourcesSpellsOutItsFlags() {
        val literals = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            PATH_LITERAL.findAll(file.readText()).map { "${file.name}: ${it.groupValues[1]}" }
        }.toList()
        assertTrue("found only ${literals.size} path literals; is the working directory the app module?", literals.size >= AppIcon.entries.size)
        val bad = literals.filter { arcProblems(it.substringAfter(": ")).isNotEmpty() }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun theCheckCatchesPackedFlags() {
        assertTrue(arcProblems("M11 19a8 8 0 100-16 8 8 0 000 16z").isNotEmpty())
        assertTrue(arcProblems("M8 6V4a2 2 0 012-2").isNotEmpty())
        assertTrue(arcProblems("M11 19a8 8 0 1 0 0 -16 8 8 0 0 0 0 16z").isEmpty())
    }

    /** Each a/A command's numbers, split the way Compose splits them. */
    private fun arcArguments(path: String): List<List<String>> =
        Regex("[aA]([^a-zA-Z]*)").findAll(path).map { m -> NUMBER.findAll(m.groupValues[1]).map { it.value }.toList() }.toList()

    private fun arcProblems(path: String): List<String> = arcArguments(path).flatMap { args ->
        buildList {
            if (args.size % 7 != 0) add("${args.size} arguments in ${args.joinToString(" ")}")
            args.chunked(7).forEach { arc ->
                if (arc.size == 7 && (arc[3] !in FLAGS || arc[4] !in FLAGS)) add("flags ${arc[3]} ${arc[4]}")
            }
        }
    }

    private companion object {
        val NUMBER = Regex("-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE]-?\\d+)?")
        val FLAGS = setOf("0", "1")

        /** A Kotlin string literal that is an SVG path: starts with a moveto, then only path characters. */
        val PATH_LITERAL = Regex("\"([Mm]\\s*[-.\\d][MmLlHhVvCcSsQqTtAaZz\\d.,\\s-]*)\"")
    }
}
