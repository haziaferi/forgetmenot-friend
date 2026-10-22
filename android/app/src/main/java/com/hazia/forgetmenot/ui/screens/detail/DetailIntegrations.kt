package com.hazia.forgetmenot.ui.screens.detail

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.hazia.forgetmenot.domain.Friend

/**
 * The detail screen's Message / Plan something / Share row (messageFriend(), planWithFriend()
 * and shareFriendCard() in www/js/features/share.js). These open other apps (SMS, WhatsApp,
 * Telegram, Signal, email; a calendar or an .ics file; a vCard), which is P6's work. Until P6
 * provides an implementation through [LocalDetailIntegrations], the row is not drawn.
 */
interface DetailIntegrations {
    /** False hides the whole row. */
    val available: Boolean
    /**
     * True when it handed off to another app, so the screen asks "Talked to …?" on return
     * (messageFriend() set state.askAfterMessage only when a link opened). False after a toast.
     */
    fun message(context: Context, friend: Friend): Boolean
    fun plan(context: Context, friend: Friend)
    fun share(context: Context, friend: Friend)
}

/** The default: no row. */
object NoDetailIntegrations : DetailIntegrations {
    override val available = false
    override fun message(context: Context, friend: Friend) = false
    override fun plan(context: Context, friend: Friend) {}
    override fun share(context: Context, friend: Friend) {}
}

/** P6 provides its implementation here (e.g. around AppNavHost in MainActivity). */
val LocalDetailIntegrations = staticCompositionLocalOf<DetailIntegrations> { NoDetailIntegrations }
