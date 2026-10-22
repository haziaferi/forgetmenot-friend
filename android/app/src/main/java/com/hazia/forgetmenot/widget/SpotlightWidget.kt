package com.hazia.forgetmenot.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.hazia.forgetmenot.R

class SpotlightWidget : GlanceAppWidget() {
    // Glance Text has a fixed fontSize, so two hand-picked layouts stand in for autosizing:
    //   TINY     one cell: the name alone at 14sp, 8dp padding (16.8 + 16 = 33dp of 40dp).
    //   REGULAR  label over name at 12sp / 16sp, name capped at two lines, log tick at the
    //            right. A two-cell card (~147 x 80dp) leaves ~79 x 60dp for that text.
    override val sizeMode = SizeMode.Responsive(setOf(SMALL_CARD_TINY, SMALL_CARD_REGULAR))

    // Read through Glance state, not a plain call — see FriendSummaryState.kt.
    override val stateDefinition: GlanceStateDefinition<*> = FriendSummaryStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        refreshIfStale(context)
        provideContent {
            val state = currentState<WidgetState>()
            SmallFriendCard("Maybe say hi to", topByWeight(state.friends, 1).firstOrNull(), state.theme, state.friends)
        }
    }
}

// Spotlight and Thinking of share one small card. REGULAR: the label on a line of its own, so
// it never wraps beside the tick, then the name (two lines at most) and the log tick. TINY (one
// cell): the name alone. With no one to name, a line saying why (emptyLabel()).
@Composable
internal fun SmallFriendCard(label: String, friend: FriendSummary?, theme: WidgetTheme, all: List<FriendSummary>) {
    val regular = LocalSize.current.height >= SMALL_CARD_REGULAR.height
    val card = GlanceModifier
        .fillMaxSize()
        .background(ImageProvider(R.drawable.widget_card_bg), colorFilter = ColorFilter.tint(fixedColor(theme.background)))
        .cornerRadius(16.dp)
        .padding(if (regular) 10.dp else 8.dp)
        .clickable(openFriendAction(LocalContext.current, friend))
    Column(modifier = card, verticalAlignment = Alignment.CenterVertically) {
        if (friend == null) {
            Text(
                emptyLabel(all, short = !regular),
                style = TextStyle(color = fixedColor(theme.text), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            )
        } else {
            if (regular) {
                Text(label, style = TextStyle(color = fixedColor(theme.text), fontSize = 12.sp), maxLines = 1)
            }
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    friend.name,
                    style = TextStyle(color = fixedColor(theme.accent), fontSize = if (regular) 16.sp else 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = if (regular) 2 else 1,
                    modifier = GlanceModifier.defaultWeight()
                )
                if (regular) LogTick(friend, theme)
            }
        }
    }
}

// "Logged it" without opening the app (LogReachOutAction.kt), for Spotlight and Thinking of.
// REGULAR only: TINY has no room for a thumb-sized target. The child's click wins over the
// card's, so the tick logs and the rest of the card opens the friend. 48dp: the touch-target floor.
@Composable
internal fun LogTick(friend: FriendSummary, theme: WidgetTheme) {
    Box(
        modifier = GlanceModifier
            .width(48.dp)
            .fillMaxHeight()
            .clickable(actionRunCallback<LogReachOutAction>(actionParametersOf(FriendIdKey to friend.id)))
            .semantics { contentDescription = "Log a reach-out with " + friend.name },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "✓",
            style = TextStyle(color = fixedColor(theme.accent), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        )
    }
}

class SpotlightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SpotlightWidget()
}
