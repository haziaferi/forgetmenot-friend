package com.hazia.forgetmenot.ui.screens.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.hazia.forgetmenot.AppContainer
import com.hazia.forgetmenot.ui.AddRoute
import com.hazia.forgetmenot.ui.FriendRoute
import com.hazia.forgetmenot.ui.GroupDetailRoute
import com.hazia.forgetmenot.ui.HomeRoute

/**
 * The detail screen as AppNavHost calls it. Back follows the stack: home, or the group the
 * friend was opened from (the JS's BACK_TO sent detail home always; with a real back stack,
 * returning to the group is what back means).
 *
 * Archived friends: openFriendFromUrl() (www/js/main.js) refuses an archived id from a deep
 * link, but renderDetail() shows one reached from a group's member list, which is the only
 * in-app path to an archived friend. FriendRoute carries no "from a deep link" flag, so the
 * archived friend is allowed exactly when the entry below this one is a group's detail.
 */
@Composable
fun FriendDestination(navController: NavHostController, entry: NavBackStackEntry) {
    val records = AppContainer.from(LocalContext.current).records
    val id = entry.toRoute<FriendRoute>().id
    val fromGroup = remember(entry.id) {
        navController.previousBackStackEntry?.destination?.hasRoute<GroupDetailRoute>() == true
    }
    // Act only while this entry is on top, so a back press already under way is not doubled.
    val onTop = { navController.currentBackStackEntry?.id == entry.id }
    val home = { if (onTop()) navController.popBackStack(HomeRoute, inclusive = false); Unit }
    DetailScreen(
        records = records,
        friendId = id,
        allowArchived = fromGroup,
        onBack = { if (onTop()) navController.popBackStack() },
        onEdit = { friendId -> navController.navigate(AddRoute(editId = friendId)) },
        onLeave = { if (onTop()) navController.popBackStack() },
        onArchived = home,
    )
}
