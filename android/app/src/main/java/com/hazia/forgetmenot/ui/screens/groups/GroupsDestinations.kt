package com.hazia.forgetmenot.ui.screens.groups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.hazia.forgetmenot.AppContainer
import com.hazia.forgetmenot.ui.FriendRoute
import com.hazia.forgetmenot.ui.GroupDetailRoute
import com.hazia.forgetmenot.ui.GroupsRoute

// The two destinations as AppNavHost calls them. Back follows the stack, which is BACK_TO
// (www/js/main.js:318) by construction: groups is only entered from home, and group-detail
// only from groups.

private const val TOAST_KEY = "groups.toast"

@Composable
fun GroupsDestination(navController: NavHostController, entry: NavBackStackEntry) {
    val records = AppContainer.from(LocalContext.current).records
    val message by entry.savedStateHandle.getStateFlow<String?>(TOAST_KEY, null).collectAsState()
    GroupsScreen(
        records = records,
        onBack = { navController.popBackStack() },
        onOpenGroup = { id -> navController.navigate(GroupDetailRoute(id)) },
        message = message,
        onMessageShown = { entry.savedStateHandle[TOAST_KEY] = null },
    )
}

@Composable
fun GroupDetailDestination(navController: NavHostController, entry: NavBackStackEntry) {
    val records = AppContainer.from(LocalContext.current).records
    val id = entry.toRoute<GroupDetailRoute>().id
    // Pop only while this entry is on top, so a back press already under way is not doubled.
    val back = { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack(); Unit }
    GroupDetailScreen(
        records = records,
        groupId = id,
        onBack = back,
        onOpenFriend = { friendId -> navController.navigate(FriendRoute(friendId)) },
        onDeleted = { name ->
            runCatching { navController.getBackStackEntry<GroupsRoute>() }.getOrNull()
                ?.savedStateHandle?.set(TOAST_KEY, "Deleted $name")
            back()
        },
    )
}
