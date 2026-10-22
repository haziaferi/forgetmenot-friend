package com.hazia.forgetmenot.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.hazia.forgetmenot.R

class TopThreeWidget : GlanceAppWidget() {
    // Responsive, so a resized widget picks a layout instead of being stretched and clipped:
    //   REGULAR  >= 120dp, the declared default: names, codes, 6dp row spacing.
    //   COMPACT  < 120dp: header plus three names, codes dropped, 2dp row spacing.
    //            Needs 12 + 3*(15.6+2) + 24dp padding = 89dp to render without clipping,
    //            which holds because names are kept to one line.
    //
    // Seen on the Pixel launcher (API 36 emulator, 2026-10-21): one row is about 103dp and
    // shows this branch. The test phone's launcher is unmeasured: the 32dp floor in
    // widget_topthree_info.xml is a guess at its cell threshold. If one cell there is
    // shorter than 89dp, this layout will clip and needs a third, smaller bucket.
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, REGULAR))

    // Read through Glance state, not a plain call — see FriendSummaryState.kt.
    override val stateDefinition: GlanceStateDefinition<*> = FriendSummaryStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        refreshIfStale(context)
        provideContent {
            val state = currentState<WidgetState>()
            TopThreeContent(topByWeight(state.friends, 3), state.theme, state.friends)
        }
    }

    companion object {
        val COMPACT = DpSize(180.dp, 90.dp)
        val REGULAR = DpSize(180.dp, 120.dp)
    }
}

@Composable
internal fun TopThreeContent(friends: List<FriendSummary>, theme: WidgetTheme, all: List<FriendSummary>) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_card_bg), colorFilter = ColorFilter.tint(fixedColor(theme.background)))
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(openFriendAction(context, null))
    ) {
        Text(
            "Maybe say hi",
            style = TextStyle(color = fixedColor(theme.muted), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        )
        if (friends.isEmpty()) {
            Text(
                emptyLabel(all),
                style = TextStyle(color = fixedColor(theme.text), fontSize = 13.sp),
                modifier = GlanceModifier.padding(top = 6.dp)
            )
        } else {
            val compact = LocalSize.current.height < TopThreeWidget.REGULAR.height
            friends.forEach { f ->
                // A row opens its own friend (the child's click wins over the card's). No
                // per-row log tick: ~18dp rows are too small a target. Spotlight has it.
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = if (compact) 2.dp else 6.dp)
                        .clickable(openFriendAction(context, f)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        f.name,
                        style = TextStyle(color = fixedColor(theme.text), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        // One line, so a long or multi-line name cannot push the third row out.
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    if (!compact) {
                        Text(f.code, style = TextStyle(color = fixedColor(theme.muted), fontSize = 12.sp))
                    }
                }
            }
        }
    }
}

class TopThreeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TopThreeWidget()
}
