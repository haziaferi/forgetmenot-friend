package com.hazia.forgetmenot.widget

import android.content.Intent
import com.hazia.forgetmenot.MainActivity
import com.hazia.forgetmenot.ui.friendIdFromUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** SPEC §5 "Sizes", "initialLayout", the receiver names (I2) and the friend tap (I3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetResourcesTest {
    private val res = File("src/main/res")
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun provider(name: String): Element {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(File(res, "xml/widget_${name}_info.xml")).documentElement
    }

    private fun Element.a(attr: String) = getAttributeNS(ns, attr)

    private fun assertProvider(name: String, minW: String, minH: String, minResizeH: String, cellW: String, cellH: String) {
        val p = provider(name)
        assertEquals(name, listOf(minW, minH, minResizeH, cellW, cellH),
            listOf(p.a("minWidth"), p.a("minHeight"), p.a("minResizeHeight"), p.a("targetCellWidth"), p.a("targetCellHeight")))
        assertEquals("10800000", p.a("updatePeriodMillis"))
        assertEquals("horizontal|vertical", p.a("resizeMode"))
        assertEquals("home_screen", p.a("widgetCategory"))
        assertEquals("@layout/glance_default_loading_layout", p.a("initialLayout"))
        assertEquals("@drawable/widget_preview_$name", p.a("previewImage"))
        for (d in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            assertTrue("$name preview at $d", File(res, "drawable-$d/widget_preview_$name.png").isFile)
        }
    }

    @Test fun spotlightProvider() = assertProvider("spotlight", "110dp", "68dp", "40dp", "2", "1")
    @Test fun randomProvider() = assertProvider("random", "110dp", "68dp", "40dp", "2", "1")
    @Test fun topThreeProvider() = assertProvider("topthree", "180dp", "120dp", "32dp", "3", "2")

    @Test fun layoutBucketsMatchTheProviders() {
        assertEquals(40f, SMALL_CARD_TINY.height.value)
        assertEquals(68f, SMALL_CARD_REGULAR.height.value)
        assertEquals(90f, TopThreeWidget.COMPACT.height.value)
        assertEquals(120f, TopThreeWidget.REGULAR.height.value)
    }

    // I2: placed widgets are bound to these class names.
    @Test fun receiverClassNamesAreKept() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (r in listOf("Spotlight", "TopThree", "Random")) {
            Class.forName("com.hazia.forgetmenot.widget.${r}WidgetReceiver")
            assertTrue(r, manifest.contains("android:name=\".widget.${r}WidgetReceiver\""))
        }
    }

    // I3: an explicit VIEW intent to MainActivity carrying forgetmenot://friend/<id>.
    @Test fun aTapOpensTheFriend() {
        val i = friendIntent(RuntimeEnvironment.getApplication(), FriendSummary("abc123", "Ana", "9d", 0.3, false))
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(MainActivity::class.java.name, i.component!!.className)
        assertEquals("forgetmenot://friend/abc123", i.dataString)
        assertEquals("abc123", friendIdFromUrl(i.dataString))
    }
}
