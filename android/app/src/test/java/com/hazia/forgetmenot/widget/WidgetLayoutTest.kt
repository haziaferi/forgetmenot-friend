package com.hazia.forgetmenot.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.GlanceNodeMatcher
import androidx.glance.testing.unit.MappedNode
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.text.EmittableText
import androidx.glance.testing.unit.hasText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** SPEC §5 "Sizes and the responsive layouts" and "The reverse path", composed per size bucket. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetLayoutTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val ana = FriendSummary("ana", "Ana María", "3m", 3.0, false)
    private val bo = FriendSummary("bo", "Bo", "9d", 0.3, false)
    private val cy = FriendSummary("cy", "Cy", "new", 0.0, false)
    private val paused = FriendSummary("di", "Di", "1y", 9.0, true)

    @Test fun smallCardRegularHasLabelNameAndTick() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(147.dp, 80.dp))
        provideComposable { SmallFriendCard("Maybe say hi to", ana, DEFAULT_WIDGET_THEME, listOf(ana)) }
        onNode(hasText("Maybe say hi to")).assertExists()
        onNode(hasText("Ana María")).assertExists()
        onNode(hasContentDescriptionEqualTo("Log a reach-out with Ana María"))
            .assert(hasRunCallbackClickAction<LogReachOutAction>(actionParametersOf(FriendIdKey to "ana")))
        onNode(hasText("Ana María")).assertExists()
    }

    @Test fun smallCardTinyIsTheNameAlone() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(110.dp, 40.dp))
        provideComposable { SmallFriendCard("Thinking of", ana, DEFAULT_WIDGET_THEME, listOf(ana)) }
        onNode(hasText("Ana María")).assertExists()
        onNode(hasText("Thinking of")).assertDoesNotExist()
        onNode(hasContentDescriptionEqualTo("Log a reach-out with Ana María")).assertDoesNotExist()
    }

    @Test fun smallCardSaysWhyWhenNobodyIsNamed() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(110.dp, 40.dp))
        provideComposable { SmallFriendCard("Maybe say hi to", null, DEFAULT_WIDGET_THEME, emptyList()) }
        onNode(hasText("Add someone")).assertExists()
    }

    @Test fun smallCardEveryonePaused() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(147.dp, 80.dp))
        provideComposable { SmallFriendCard("Maybe say hi to", null, DEFAULT_WIDGET_THEME, listOf(paused)) }
        onNode(hasText("Everyone's paused")).assertExists()
    }

    @Test fun smallCardTapOpensTheFriend() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(110.dp, 40.dp))
        provideComposable { SmallFriendCard("Thinking of", bo, DEFAULT_WIDGET_THEME, listOf(bo)) }
        onNode(hasStartActivityClickAction(friendIntent(context, bo))).assertExists()
    }

    @Test fun topThreeRegularShowsNamesAndCodes() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(TopThreeWidget.REGULAR)
        val all = listOf(bo, ana, paused, cy)
        provideComposable { TopThreeContent(topByWeight(all, 3), DEFAULT_WIDGET_THEME, all) }
        onNode(hasText("Maybe say hi")).assertExists()
        for (f in listOf(ana, bo, cy)) {
            onNode(hasText(f.name)).assertExists()
            onNode(hasText(f.code)).assertExists()
            onNode(hasStartActivityClickAction(friendIntent(context, f))).assertExists()
        }
        onNode(hasText("Di")).assertDoesNotExist()
    }

    @Test fun topThreeCompactDropsTheCodes() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(TopThreeWidget.COMPACT)
        val all = listOf(ana, bo)
        provideComposable { TopThreeContent(topByWeight(all, 3), DEFAULT_WIDGET_THEME, all) }
        onNode(hasText("Ana María")).assertExists()
        onNode(hasText("3m")).assertDoesNotExist()
    }

    // Seen on the Pixel launcher at one row: a wrapping name pushed the third row out.
    @Test fun topThreeNamesStayOnOneLine() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(TopThreeWidget.COMPACT)
        val long = FriendSummary("lo", "Alexandra Konstantinopoulou-Whitfield\nsecond line", "2m", 2.0, false)
        val all = listOf(long, bo, cy)
        provideComposable { TopThreeContent(topByWeight(all, 3), DEFAULT_WIDGET_THEME, all) }
        val oneLine = GlanceNodeMatcher<MappedNode>("maxLines = 1") { (it.value.emittable as? EmittableText)?.maxLines == 1 }
        onNode(hasText(long.name) and oneLine).assertExists()
        onNode(hasText("Cy") and oneLine).assertExists()
    }

    @Test fun topThreeEmpty() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(TopThreeWidget.REGULAR)
        provideComposable { TopThreeContent(emptyList(), DEFAULT_WIDGET_THEME, emptyList()) }
        onNode(hasText("Add someone to think of")).assertExists()
    }
}
