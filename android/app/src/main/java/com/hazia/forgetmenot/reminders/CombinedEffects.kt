package com.hazia.forgetmenot.reminders

import com.hazia.forgetmenot.data.DataEffects

/**
 * [com.hazia.forgetmenot.data.Records.effects] is a single var; this fans it out. AppContainer
 * passes the reminder scheduler now, and P6 adds its widget pusher to the same list.
 */
class CombinedEffects(private vararg val parts: DataEffects) : DataEffects {
    override fun pushWidgets() = parts.forEach { it.pushWidgets() }
    override fun rescheduleReminders() = parts.forEach { it.rescheduleReminders() }
}
