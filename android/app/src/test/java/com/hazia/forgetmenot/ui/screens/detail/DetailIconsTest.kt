package com.hazia.forgetmenot.ui.screens.detail

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compose's path parser reads numbers greedily, so SVG's packed arc flags ("a3 3 0 100-6") parse as
 * one number and the arc draws garbled. The detail icons spell their flags out; this holds them to it.
 */
class DetailIconsTest {
    private val paths = DetailIcons.paths.toList()

    @Test fun everyIconIsChecked() {
        assertEquals(11, paths.size)
    }

    @Test fun everyArcSpellsOutItsFlags() {
        for (p in paths) {
            for (args in arcArguments(p)) {
                assertEquals("argument count in $p", 0, args.size % 7)
                args.chunked(7).forEach { arc -> assertTrue("flags in $p", arc[3] in FLAGS && arc[4] in FLAGS) }
            }
        }
    }

    @Test fun composeParsesOneArcPerSevenArguments() {
        for (p in paths) {
            val parsed = addPathNodes(p).count { it is PathNode.ArcTo || it is PathNode.RelativeArcTo }
            assertEquals(p, arcArguments(p).sumOf { it.size } / 7, parsed)
        }
    }

    @Test fun theCheckCatchesPackedFlags() {
        val packed = arcArguments("M18 8a3 3 0 100-6 3 3 0 000 6z")
        assertTrue(packed.any { it.size % 7 != 0 || it.chunked(7).any { a -> a.size < 7 || a[3] !in FLAGS || a[4] !in FLAGS } })
    }

    private fun arcArguments(path: String): List<List<String>> =
        Regex("[aA]([^a-zA-Z]*)").findAll(path).map { m -> NUMBER.findAll(m.groupValues[1]).map { it.value }.toList() }.toList()

    private companion object {
        val NUMBER = Regex("-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE]-?\\d+)?")
        val FLAGS = setOf("0", "1")
    }
}
