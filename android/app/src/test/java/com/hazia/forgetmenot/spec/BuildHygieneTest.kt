package com.hazia.forgetmenot.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the manifest must keep (I2, I7), and that nothing of the web build is left behind.
 * The app shipped as a WebView build until P7; it is native now.
 */
class BuildHygieneTest {
    private val root = repoRoot()
    private val manifest = File(root, "android/app/src/main/AndroidManifest.xml").readText()

    @Test fun noInternetPermission() =
        assertFalse(Regex("""uses-permission[^>]*android\.permission\.INTERNET""").containsMatchIn(manifest))

    @Test fun backupIsOff() = assertTrue(manifest.contains("""android:allowBackup="false""""))

    // Launchers persist widgets by receiver class name, so a renamed receiver drops them (I2).
    @Test fun widgetReceiversKeepTheirNames() {
        for (name in listOf("Spotlight", "TopThree", "Random")) {
            assertTrue(name, manifest.contains("""android:name=".widget.${name}WidgetReceiver""""))
        }
    }

    @Test fun theWebBuildIsGone() {
        for (path in listOf("www", "overlay", needles[0] + ".config.json", "package.json", "package-lock.json")) {
            assertFalse("$path still exists", File(root, path).exists())
        }
    }

    // Spelled in pieces so this file does not find itself.
    private val needles = listOf("capa" + "citor", "cor" + "dova")

    /**
     * No file outside port/ (the porting prompts, which describe the old build on purpose) and
     * SPEC.md's appendix (dated history) names the old runtime. SPEC.md sections 1-9 may name it
     * only in the one sentence that says where the app came from.
     */
    @Test fun nothingNamesTheOldRuntime() {
        val hits = mutableListOf<String>()
        fun scan(dir: File) {
            for (f in dir.listFiles().orEmpty()) {
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (f.name in setOf(".git", ".gradle", ".kotlin", ".idea", "build", "node_modules")) continue
                if (rel == "port" || rel == ".claude/worktrees" || rel == "SPEC.md") continue
                if (f.isDirectory) { scan(f); continue }
                if (f.extension.lowercase() in setOf("png", "webp", "jpg", "ttf", "otf", "jar", "keystore", "jks")) continue
                f.readLines().forEachIndexed { i, line ->
                    if (needles.any { line.contains(it, ignoreCase = true) }) hits += "$rel:${i + 1}: ${line.trim()}"
                }
            }
        }
        scan(root)
        assertEquals("", hits.joinToString("\n"))

        val spec = File(root, "SPEC.md").readLines()
        val body = spec.take(spec.indexOfFirst { it.startsWith("# Appendix") }.takeIf { it >= 0 } ?: spec.size)
        val inBody = body.withIndex().filter { (_, l) -> needles.any { l.contains(it, ignoreCase = true) } }
        assertTrue("SPEC.md sections 1-9 name it on lines " + inBody.map { it.index + 1 }, inBody.size <= 1)
    }
}
