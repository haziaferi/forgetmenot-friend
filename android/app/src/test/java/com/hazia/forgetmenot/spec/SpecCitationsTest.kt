package com.hazia.forgetmenot.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The repository root: the first directory above the test's working dir (android/app) holding SPEC.md. */
internal fun repoRoot(): File =
    generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "SPEC.md").isFile }

/** SPEC.md sections 1-9 against the real tree. */
class SpecCitationsTest {
    @Test fun everyCitationInSections1to9Holds() {
        val report = SpecCitations.check(repoRoot())
        assertTrue("SPEC.md citation drift, ${report.problems.size} problem(s). Fix the line number in SPEC.md, " +
            "or the claim it was making:\n" + report.problems.joinToString("\n"), report.problems.isEmpty())
        // A checker pointed at the wrong root, or one whose pattern stopped matching, passes on
        // nothing. SPEC.md cites a few hundred lines; far fewer means the check went blind.
        assertTrue("only ${report.checked} citations found", report.checked > 150)
        assertTrue("only ${report.anchored} citations anchored", report.anchored > 30)
    }
}

/**
 * Shows that each check can fail: a small tree with nothing wrong in it passes, then one known
 * fault at a time is applied to a fresh copy and the right problem has to be named. A check
 * that has never been seen to fail is not known to work. Port of the web build's
 * tools/selftest.mjs (its spec.mjs cases).
 */
class SpecCitationsSelfTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File

    private val classifyKt = listOf(
        "package com.hazia.forgetmenot.domain",            // 1
        "",                                                 // 2
        "val CADENCE_PRESETS = listOf(7, 14, 30)",          // 3
        "",                                                 // 4
        "fun helper(a: Int): Int {",                        // 5
        "    return a + 1",                                 // 6
        "}",                                                // 7
        "",                                                 // 8
        "/** Turns a friend into the one derived value everything else reads. */", // 9
        "fun classify(f: Int): Int {",                      // 10
        "    return helper(f)",                             // 11
        "}",                                                // 12
        "",                                                 // 13
        "fun other() {}",                                   // 14
    ).joinToString("\n") + "\n"
    private val datesKt = "package com.hazia.forgetmenot.domain\n// Calendar days between then and now.\n" +
        "fun daysSince(iso: String): Int {\n    return 0\n}\n"
    private val fooKt = "package com.hazia.forgetmenot\nclass Foo\n"
    private val stylesXml = "<resources>\n</resources>\n"

    // One citation of every shape the checker resolves, all correct. The appendix carries a
    // citation that would fail every check if it were looked at: the standing proof that the
    // appendix is left alone.
    private val specMd = listOf(
        "# fixture spec",
        "",
        "## 1. Section",
        "",
        "Presets live at `domain/Classify.kt:3`. The class is `Foo.kt:2`, or by tail `forgetmenot/Foo.kt:2`.",
        "The helper spans `domain/Classify.kt:5-7`, also written `domain/Classify.kt:5`-`7`.",
        "`classify()` (`domain/Classify.kt:11`) is the anchored one.",
        "Dates too: `daysSince()` (`Dates.kt:4`), and the theme `values/styles.xml:1`.",
        "",
        "# Appendix",
        "",
        "`classify()` (`domain/Classify.kt:9999`) - frozen history, deliberately stale.",
        "",
    ).joinToString("\n")

    private val pkg = "android/app/src/main/java/com/hazia/forgetmenot"

    @Before fun build() {
        root = tmp.newFolder("repo")
        write("SPEC.md", specMd)
        write("$pkg/domain/Classify.kt", classifyKt)
        write("$pkg/domain/Dates.kt", datesKt)
        write("$pkg/Foo.kt", fooKt)
        write("android/app/src/main/res/values/styles.xml", stylesXml)
        // Build output and other worktrees are never indexed, or every basename would be ambiguous.
        write("android/app/build/tmp/Foo.kt", fooKt)
        write(".claude/worktrees/other/$pkg/Foo.kt", fooKt)
    }

    private fun write(rel: String, text: String) {
        val f = File(root, rel)
        f.parentFile.mkdirs()
        f.writeText(text)
    }

    private fun edit(rel: String, from: String, to: String) {
        val f = File(root, rel)
        val s = f.readText()
        require(from in s) { "fixture $rel does not contain \"$from\"" }
        f.writeText(s.replaceFirst(from, to))
    }

    private fun assertFault(expect: String) {
        val problems = SpecCitations.check(root).problems
        assertTrue("expected $expect, got:\n" + problems.joinToString("\n"), problems.any { Regex(expect).containsMatchIn(it) })
    }

    @Test fun cleanFixturePasses() {
        val report = SpecCitations.check(root)
        assertEquals(emptyList<String>(), report.problems)
        assertEquals(8, report.checked)
        assertEquals(2, report.anchored)
    }

    @Test fun pastEndOfFile() {
        edit("SPEC.md", "`domain/Classify.kt:3`", "`domain/Classify.kt:999`")
        assertFault("PAST END OF FILE")
    }

    @Test fun backwardsRange() {
        edit("SPEC.md", "`domain/Classify.kt:5-7`", "`domain/Classify.kt:7-5`")
        assertFault("BACKWARDS RANGE")
    }

    @Test fun backwardsRangeSplitForm() {
        edit("SPEC.md", "`domain/Classify.kt:5`-`7`", "`domain/Classify.kt:7`-`5`")
        assertFault("BACKWARDS RANGE")
    }

    @Test fun elidedPath() {
        edit("SPEC.md", "`domain/Classify.kt:3`", "`android/.../Classify.kt:3`")
        assertFault("ELIDED PATH")
    }

    @Test fun noSuchFile() {
        edit("SPEC.md", "`Foo.kt:2`", "`Bar.kt:2`")
        assertFault("NO SUCH FILE")
    }

    @Test fun noSuchFileWrongCase() {
        edit("SPEC.md", "`domain/Classify.kt:3`", "`Domain/Classify.kt:3`")
        assertFault("NO SUCH FILE")
    }

    @Test fun ambiguousPath() {
        write("android/app/src/test/java/com/hazia/forgetmenot/Foo.kt", fooKt)
        assertFault("AMBIGUOUS PATH")
    }

    // The slide: thirty lines above classify() move it away from the cited line while every
    // other citation stays in range. Only the anchored check can catch this.
    @Test fun movedAnchoredSymbol() {
        edit("$pkg/domain/Classify.kt", "/** Turns", "\n".repeat(30) + "/** Turns")
        assertFault("MOVED.*classify now spans")
    }

    @Test fun movedSymbolInAnotherFile() {
        edit("$pkg/domain/Dates.kt", "// Calendar", "\n".repeat(30) + "// Calendar")
        assertFault("MOVED.*daysSince now spans")
    }

    @Test fun pastEndOfFileInAResourceFile() {
        edit("SPEC.md", "`values/styles.xml:1`", "`values/styles.xml:40`")
        assertFault("PAST END OF FILE.*styles\\.xml")
    }

    @Test fun bareCitation() {
        edit("SPEC.md", "is the anchored one.", "is the anchored one (`:11`).")
        assertFault("BARE CITATION")
    }

    @Test fun lineZero() {
        edit("SPEC.md", "`domain/Classify.kt:3`", "`domain/Classify.kt:0`")
        assertFault("LINE ZERO")
    }

    // The negative: a citation wrong in every way, but in the appendix, is not a failure. If
    // this ever trips, the appendix rule has been lost.
    @Test fun appendixLeftAlone() {
        edit("SPEC.md", "frozen history", "frozen history `nowhere.kt:1` `android/.../x.kt:1`")
        assertEquals(emptyList<String>(), SpecCitations.check(root).problems)
    }
}
