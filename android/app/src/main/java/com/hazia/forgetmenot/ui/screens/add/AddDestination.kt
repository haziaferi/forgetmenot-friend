package com.hazia.forgetmenot.ui.screens.add

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.hazia.forgetmenot.AppContainer
import com.hazia.forgetmenot.data.Records
import com.hazia.forgetmenot.ui.AddRoute
import com.hazia.forgetmenot.ui.HomeRoute

/** Keeps one visit's form across recomposition and rotation; a new visit is a new back-stack entry. */
private class AddVisit(val model: AddFormModel) : ViewModel()

/**
 * The add screen as AppNavHost calls it. Back follows the stack, which is BACK_TO
 * (www/js/main.js:318) by construction: add is entered from home, or from the friend being
 * edited. A new friend is saved to home; an edit returns to its friend.
 */
@Composable
fun AddDestination(
    navController: NavHostController,
    entry: NavBackStackEntry,
    records: Records = AppContainer.from(LocalContext.current).records,
) {
    val editId = entry.toRoute<AddRoute>().editId
    val visit = viewModel(viewModelStoreOwner = entry) { AddVisit(AddFormModel(records, editId)) }
    // Act only while this entry is on top, so a back press already under way is not doubled.
    val onTop = { navController.currentBackStackEntry?.id == entry.id }
    val back = { if (onTop() && !navController.popBackStack()) navController.navigate(HomeRoute); Unit }
    val home = { if (onTop()) navController.popBackStack(HomeRoute, inclusive = false); Unit }
    AddScreen(
        model = visit.model,
        records = records,
        onLeave = back,
        onSaved = { r -> if (r is SaveResult.Added) home() else back() },
        integrations = LocalAddIntegrations.current,
        onImported = home,
    )
}
