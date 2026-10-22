package com.hazia.forgetmenot.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hazia.forgetmenot.ui.theme.AppTheme
import kotlinx.coroutines.CompletableDeferred

// Port of www/js/ui/ask.js: the app's own confirm() and prompt(), one more sheet on top of
// whatever asked. Kept for what cannot be undone and for naming something (SPEC §7).

/** One open question. [input] non-null makes it a prompt, prefilled with [input]. */
data class AskRequest(
    val title: String,
    val body: String = "",
    val confirmLabel: String = "OK",
    val input: String? = null,
    val placeholder: String = "",
)

/**
 * Holds the question being asked. Screens keep one ([rememberAskState]), draw [AskHost] last, and
 * pass it to the sheets that ask. A second question replaces an unanswered first, which then
 * resolves to no.
 */
@Stable
class AskState {
    internal var request by mutableStateOf<AskRequest?>(null)
        private set
    private var pending: CompletableDeferred<Any?>? = null

    private suspend fun ask(r: AskRequest): Any? {
        pending?.complete(null)
        val d = CompletableDeferred<Any?>()
        pending = d
        request = r
        return try { d.await() } finally {
            if (pending === d) { pending = null; request = null }
        }
    }

    /** True on confirm; false however else the sheet closes (Cancel, Close, back, scrim). */
    suspend fun confirm(title: String, body: String = "", confirmLabel: String = "OK"): Boolean =
        ask(AskRequest(title, body, confirmLabel)) == true

    /** The typed text, trimmed and non-empty, on confirm; null however else it closes. */
    suspend fun prompt(title: String, body: String = "", confirmLabel: String = "OK", value: String = "", placeholder: String = ""): String? =
        ask(AskRequest(title, body, confirmLabel, input = value, placeholder = placeholder)) as? String

    internal fun answer(value: Any?) { pending?.complete(value) }
}

@Composable
fun rememberAskState(): AskState = remember { AskState() }

/** Draws [state]'s question, if any. Place it after the screen's other sheets. */
@Composable
fun AskHost(state: AskState) {
    val r = state.request ?: return
    // Keyed on the request, so a replacing question starts from its own text.
    androidx.compose.runtime.key(r) {
        var text by remember { mutableStateOf(r.input ?: "") }
        val field = remember { FocusRequester() }
        AppSheet(title = r.title, onDismiss = { state.answer(null) }) { close ->
            val confirm = {
                if (r.input == null) { state.answer(true); close() }
                else text.trim().takeIf { it.isNotEmpty() }?.let { state.answer(it); close() } ?: field.requestFocus()
            }
            if (r.body.isNotEmpty()) SheetIntro(r.body)
            if (r.input != null) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(field).testTag("ask-input"),
                    placeholder = { Text(r.placeholder) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                )
                LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                InlineTextButton("Cancel") { state.answer(null); close() }
                Button(onClick = { confirm() }, modifier = Modifier.height(AppTheme.sizes.primary).testTag("ask-confirm"), shape = MaterialTheme.shapes.medium) {
                    Text(r.confirmLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
