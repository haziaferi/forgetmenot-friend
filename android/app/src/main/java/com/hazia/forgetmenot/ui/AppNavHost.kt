package com.hazia.forgetmenot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.hazia.forgetmenot.domain.isValidId
import com.hazia.forgetmenot.ui.screens.groups.GroupDetailDestination
import com.hazia.forgetmenot.ui.screens.groups.GroupsDestination
import com.hazia.forgetmenot.ui.screens.settings.SettingsDestination
import kotlinx.serialization.Serializable

// Type-safe routes (kotlinx.serialization). Every screen has a reserved route class and a
// reserved composable<…> line below. A P5 screen session replaces only its own line, and names
// its destination fully qualified (no new import), so parallel sessions never edit adjacent
// lines and their PRs merge without conflicts.
@Serializable object HomeRoute
@Serializable data class FriendRoute(val id: String)
@Serializable object SettingsRoute
@Serializable object GroupsRoute
@Serializable data class GroupDetailRoute(val id: String)
/** The add screen; [editId] set means editing that friend (state.editId in the JS). */
@Serializable data class AddRoute(val editId: String? = null)

const val FRIEND_URL_PREFIX = "forgetmenot://friend/"

/**
 * The friend id in forgetmenot://friend/<id>, sent by a widget tap (friendIntent() in
 * widget/WidgetData.kt), or null. MainActivity is exported, so any app could send one:
 * mirrors openFriendFromUrl() in www/js/main.js, including its isValidId check. Whether
 * the id names a current, unarchived friend is the detail screen's check (P5), not this one.
 */
fun friendIdFromUrl(url: String?): String? {
    if (url == null || !url.startsWith(FRIEND_URL_PREFIX)) return null
    val id = url.substring(FRIEND_URL_PREFIX.length)
        .substringBefore('/').substringBefore('?').substringBefore('#')
    return id.takeIf { isValidId(it) }
}

/**
 * The app's navigation graph. [pendingFriendId] is a deep link not yet acted on (cold start
 * or onNewIntent); it is opened once and handed back through [onFriendOpened].
 */
@Composable
fun AppNavHost(
    pendingFriendId: String?,
    onFriendOpened: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    LaunchedEffect(pendingFriendId) {
        val id = pendingFriendId ?: return@LaunchedEffect
        navController.navigate(FriendRoute(id)) { launchSingleTop = true }
        onFriendOpened()
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavHost(navController = navController, startDestination = HomeRoute) {
            composable<HomeRoute> { com.hazia.forgetmenot.ui.screens.home.HomeDestination(navController) }
            composable<SettingsRoute> { SettingsDestination(onBack = { navController.popBackStack() }, onGoHome = { navController.popBackStack(HomeRoute, inclusive = false) }) }
            composable<FriendRoute> { entry -> com.hazia.forgetmenot.ui.screens.detail.FriendDestination(navController, entry) }
            composable<GroupsRoute> { entry -> GroupsDestination(navController, entry) }
            composable<GroupDetailRoute> { entry -> GroupDetailDestination(navController, entry) }

            composable<AddRoute> { entry -> com.hazia.forgetmenot.ui.screens.add.AddDestination(navController, entry) }
        }
    }
}
