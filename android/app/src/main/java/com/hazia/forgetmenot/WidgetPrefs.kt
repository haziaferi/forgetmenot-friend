package com.hazia.forgetmenot

// The widgets' SharedPreferences file and keys. widget/WidgetPusher.kt writes them; the widgets
// read them through widget/FriendSummaryState.kt.
const val WIDGET_PREFS_NAME = "forgetmenot_widget_data"
// The active friends' summaries: a JSON array of {id, name, code, weight, paused}.
const val WIDGET_PREFS_KEY = "friends_json"
// The active skin's colours as JSON {background, text, muted, accent}; read by readWidgetTheme().
const val WIDGET_THEME_KEY = "theme_json"
// The local day (YYYY-MM-DD) of the last push; a widget composing on a later day asks for a new one.
const val WIDGET_PUSHED_DAY_KEY = "pushed_day"
