package com.hazia.forgetmenot.spec

import java.io.File

/**
 * Checks that SPEC.md's `file:line` citations still point at what they claim. Port of the
 * web build's tools/spec.mjs, run by SpecCitationsTest so CI needs nothing but Gradle.
 *
 * Why this exists: sections 1-9 of SPEC.md carry a few hundred citations of the form
 * `CachedStore.kt:120`, and nothing else keeps them honest. Inserting ten lines near the top
 * of a file slides every later citation by ten, and the document still reads as
 * authoritative. SPEC.md's own preamble says that where it and the code disagree the code is
 * right, so those bugs should be findable by running something rather than by noticing.
 *
 * Three checks, weakest to strongest:
 *
 *   1. RESOLVES  - the cited path names a real file, either as a repo-relative path or as a
 *                  tail (basename, or basename plus directories) unique across the tree.
 *   2. IN RANGE  - the cited line number is inside that file.
 *   3. ANCHORED  - when the prose names a symbol immediately before the citation, as in
 *                  `classify()` (`Classify.kt:40`), that symbol has to still own the cited
 *                  line: the line falls inside the symbol's top-level declaration (its
 *                  leading comment and annotations included), or the name appears within
 *                  three lines of it. This is the check that catches a slide.
 *
 * Check 3 is deliberately conservative: it applies only when nothing but punctuation
 * separates the symbol from the citation. A citation it declines to check is not a failure;
 * guessing at which of a sentence's five backticked names is the subject produces false
 * alarms, and a checker that cries wolf gets turned off.
 *
 * Only sections 1-9 are checked. The appendix is a dated historical record that SPEC.md
 * freezes on purpose, so a citation there may point at code that has since moved or gone.
 */
object SpecCitations {
    class Report(val problems: List<String>, val checked: Int, val anchored: Int)

    private val SKIP_ANYWHERE = setOf("node_modules", ".git", ".gradle", ".kotlin", ".idea", "build")
    // Other sessions' worktrees live under the main checkout's .claude/; indexing them would
    // make every basename ambiguous.
    private val SKIP_PATHS = setOf(".claude/worktrees")

    private const val CITED_EXT = "html|css|kts|kt|mjs|js|gradle|pro|xml|json|properties|md|java|yml|py|toml"
    // A path (with or without directories) followed by :line, optionally :lo-hi.
    private val CITE = Regex(
        "((?:[\\w.-]+/)*[\\w.-]+\\.(?:$CITED_EXT)|(?:[\\w.-]+/)*\\.gitignore):(\\d+)(?:\\s*[-–—]\\s*(\\d+))?")
    // The same citation written as two backticked halves: `f.kt:1080`-`1092`. Both backticks
    // are required, or prose like "f.kt:12 - 3 friends" reads as a backwards range.
    private val SPLIT_HI = Regex("^`\\s*[-–—]\\s*`(\\d+)")
    // A backticked symbol, e.g. `classify()`, `resolveDirs(..., create = true)`, `memCache`.
    private val SYMBOL = Regex("`([A-Za-z_$][\\w$.]*(?:\\([^)]*\\))?[\\w$.]*)`")
    // What may sit between that symbol and the citation for the anchor check to apply.
    private val TIGHT = Regex("^[\\s,;:]*(?:at\\s+|in\\s+)?\\(?`?$")
    private val BARE = Regex("`:\\d+(?:[-–]\\d+)?`")
    // Anchors are only meaningful where a symbol name is a real token in the file.
    private val ANCHORABLE = setOf("html", "js", "mjs", "kt", "kts")
    private const val MIN_ANCHOR = 6

    // A top-level declaration starts at column 0. Kotlin modifiers and an extension receiver
    // (`fun Friend.classify`) may come before the name.
    private val DECL = Regex(
        "^(?:(?:export|async|private|internal|public|protected|const|data|enum|sealed|abstract|open|" +
            "inline|suspend|value|annotation|lateinit)\\s+)*" +
            "(?:function|const|let|var|val|class|fun|object|interface|typealias)\\s+" +
            "(?:<[^>]*>\\s*)?(?:[A-Za-z_]\\w*\\.)*([A-Za-z_$][\\w$]*)")
    // A declaration owns the comment block and annotations directly above it, because SPEC.md
    // routinely cites the comment rather than the code it explains.
    private val COMMENT = Regex("^\\s*(?://|/\\*|\\*|<!--|#|@)")

    fun check(root: File): Report {
        val tree = mutableListOf<String>()
        walk(root, root, tree)
        val inTree = tree.toSet()
        val byBasename = tree.groupBy { it.substringAfterLast('/') }
        val lineCache = HashMap<String, List<String>>()
        fun linesOf(rel: String) = lineCache.getOrPut(rel) { File(root, rel).readText().split(Regex("\r?\n")) }
        val rangeCache = HashMap<String, Map<String, IntRange>>()
        fun ranges(rel: String) = rangeCache.getOrPut(rel) { topLevelRanges(linesOf(rel)) }

        // Exact-case match against the walked tree, not File.exists(): Windows and macOS would
        // accept ANDROID/app/build.gradle and the citation would then fail on Linux.
        fun resolve(cited: String): List<String> = when {
            cited in inTree -> listOf(cited)
            '/' in cited -> tree.filter { it.endsWith("/$cited") }
            else -> byBasename[cited].orEmpty()
        }

        val spec = File(root, "SPEC.md").readText().split(Regex("\r?\n"))
        val appendixAt = spec.indexOfFirst { Regex("^#\\s+Appendix").containsMatchIn(it) }
        val lastChecked = if (appendixAt == -1) spec.size else appendixAt

        val problems = mutableListOf<String>()
        var checked = 0
        var anchored = 0
        for (i in 0 until lastChecked) {
            val line = spec[i]
            val where = "SPEC.md:${i + 1}"
            // A bare `:3069` inherits its file from the surrounding prose, which this check
            // can't follow, so it could never be checked and would go stale unnoticed.
            for (b in BARE.findAll(line)) {
                problems += "$where  BARE CITATION      ${b.value} - write it as path:line so it can be checked"
            }
            for (m in CITE.findAll(line)) {
                val cited = m.groupValues[1]
                checked++
                if ("..." in cited) {
                    problems += "$where  ELIDED PATH        $cited - write the path out so it resolves"
                    continue
                }
                val hits = resolve(cited)
                if (hits.size > 1) { problems += "$where  AMBIGUOUS PATH     $cited - matches ${hits.joinToString()}"; continue }
                if (hits.isEmpty()) { problems += "$where  NO SUCH FILE       $cited"; continue }
                val path = hits[0]
                val lines = linesOf(path)
                val lo = m.groupValues[2].toInt()
                val split = if (m.groupValues[3].isEmpty()) SPLIT_HI.find(line.substring(m.range.last + 1)) else null
                val hi = m.groupValues[3].ifEmpty { split?.groupValues?.get(1) ?: m.groupValues[2] }.toInt()
                if (hi > lines.size) { problems += "$where  PAST END OF FILE   $cited:$hi - $path has ${lines.size} lines"; continue }
                if (lo < 1) { problems += "$where  LINE ZERO          $cited:$lo"; continue }
                if (lo > hi) { problems += "$where  BACKWARDS RANGE    $cited:$lo-$hi"; continue }

                if (path.substringAfterLast('.') !in ANCHORABLE) continue
                val name = anchorFor(line, m.range.first) ?: continue
                anchored++
                // The citation holds if the symbol is written in the lines cited, or if those
                // lines fall inside the symbol's own top-level declaration: a constant is often
                // declared in one place and cited where it is read, and a function body is
                // routinely cited at a line that does not repeat its name.
                val owned = ranges(path)[name]
                val word = Regex("(^|[^\\w$])" + Regex.escape(name) + "(?![\\w$])")
                val nearby = word.containsMatchIn(lines.subList(maxOf(0, lo - 4), minOf(lines.size, hi + 3)).joinToString("\n"))
                val ok = nearby || (owned != null && owned.first <= hi && owned.last >= lo)
                if (!ok) {
                    val span = if (hi != lo) "$lo-$hi" else "$lo"
                    val at = if (owned != null) {
                        "$name now spans $path:${owned.first}-${owned.last}, and is written nowhere near the cited line"
                    } else {
                        "$name is written nowhere near $path:$lo"
                    }
                    problems += "$where  MOVED              $cited:$span - $at"
                }
            }
        }
        return Report(problems, checked, anchored)
    }

    private fun walk(dir: File, root: File, out: MutableList<String>) {
        for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
            if (f.name in SKIP_ANYWHERE) continue
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (rel in SKIP_PATHS) continue
            if (f.isDirectory) walk(f, root, out) else out += rel
        }
    }

    /** Every top-level declaration in a file, as name -> 1-based line range. */
    private fun topLevelRanges(lines: List<String>): Map<String, IntRange> {
        val starts = lines.indices.mapNotNull { i -> DECL.find(lines[i])?.let { it.groupValues[1] to i + 1 } }
        val ranges = HashMap<String, IntRange>()
        for ((k, start) in starts.withIndex()) {
            var from = start.second
            while (from > 1 && COMMENT.containsMatchIn(lines[from - 2])) from--
            val to = if (k + 1 < starts.size) starts[k + 1].second - 1 else lines.size
            // A name declared twice owns the union, which is looser but never wrongly fails a
            // citation that points at either one.
            val prev = ranges[start.first]
            ranges[start.first] = if (prev != null) minOf(prev.first, from)..maxOf(prev.last, to) else from..to
        }
        return ranges
    }

    /** The symbol a citation is about, or null when the prose does not put one right before it. */
    private fun anchorFor(line: String, citeIndex: Int): String? {
        val before = line.substring(0, citeIndex)
        val last = SYMBOL.findAll(before).lastOrNull() ?: return null
        if (!TIGHT.containsMatchIn(before.substring(last.range.last + 1))) return null
        // For a call, the subject is the callee, not the last thing inside the parentheses:
        // `hydrateAvatar(f)` is about hydrateAvatar, and "f" is too short to anchor on.
        val inner = last.groupValues[1]
        val callee = Regex("^([A-Za-z_$][\\w$.]*)\\(").find(inner)
        val name = if (callee != null) {
            callee.groupValues[1].substringAfterLast('.')
        } else {
            Regex("[A-Za-z_$][\\w$]*").findAll(inner).lastOrNull()?.value
        }
        return name?.takeIf { it.length >= MIN_ANCHOR }
    }
}
