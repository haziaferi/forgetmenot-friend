package com.hazia.forgetmenot.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import kotlin.random.Random

class RandomWidget : GlanceAppWidget() {
    // Glance Text has a fixed fontSize, so two hand-picked layouts stand in for autosizing:
    //   TINY     one cell: the name alone at 14sp, 8dp padding (16.8 + 16 = 33dp of 40dp).
    //   REGULAR  taller: label over name at 12sp / 16sp, two lines at most, and the log tick.
    override val sizeMode = SizeMode.Responsive(setOf(SMALL_CARD_TINY, SMALL_CARD_REGULAR))

    // Read through Glance state, not a plain call — see FriendSummaryState.kt.
    override val stateDefinition: GlanceStateDefinition<*> = FriendSummaryStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        refreshIfStale(context)
        provideContent {
            val state = currentState<WidgetState>()
            // Seeded from the friends: each Responsive bucket composes separately, and an unseeded
            // roll would show a different friend per size and re-roll on every recomposition.
            // Not from the theme, so a skin change does not pick someone new.
            SmallFriendCard("Thinking of", weightedRandom(state.friends, Random(state.friends.hashCode())), state.theme, state.friends)
        }
    }
}

class RandomWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RandomWidget()
}
