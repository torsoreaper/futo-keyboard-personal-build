package org.futo.inputmethod.latin.uix.settings.pages

import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.engine.GlobalIMEMessage
import org.futo.inputmethod.engine.GlobalUnlearnLearnedWords
import org.futo.inputmethod.engine.IMEMessage
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.localeFromString
import org.futo.inputmethod.latin.personalization.PersonalizationHelper
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.Tip
import java.util.Locale

private const val LEARNED_DICT_TIMEOUT_MS = 8000

private data class LearnedWord(val word: String, val probability: Int)

private fun learnedLocales(filesDir: java.io.File): List<Locale> {
    val prefix = "UserHistoryDictionary."
    val suffix = ".dict"
    val found = filesDir.listFiles()
        ?.mapNotNull { dir ->
            val name = dir.name
            if (!dir.isDirectory || !name.startsWith(prefix) || !name.endsWith(suffix)) {
                return@mapNotNull null
            }
            localeFromString(name.removePrefix(prefix).removeSuffix(suffix))
        }
        ?.distinct()
        .orEmpty()
    return found.ifEmpty { listOf(Locale.US) }
}

private fun readLearnedWords(context: android.content.Context): List<LearnedWord> {
    val seen = LinkedHashMap<String, LearnedWord>()
    for (locale in learnedLocales(context.filesDir)) {
        val dict = PersonalizationHelper.getUserHistoryDictionary(context, locale, null)
        val properties = dict.getAllWordProperties(LEARNED_DICT_TIMEOUT_MS) ?: continue
        for (property in properties) {
            val word = property.mWord
            if (word.isNullOrBlank()) continue
            val key = word.lowercase(locale)
            val existing = seen[key]
            if (existing == null || property.probability > existing.probability) {
                seen[key] = LearnedWord(word, property.probability)
            }
        }
    }
    return seen.values.sortedBy { it.word.lowercase() }
}

/**
 * Removes the words from every learned-history dictionary, flushes that to disk, then tells the
 * keyboard to reload so the copy it has in memory drops them too.
 */
private fun deleteLearnedWords(context: android.content.Context, words: List<String>): Boolean {
    if (words.isEmpty()) return true
    var ok = true
    for (locale in learnedLocales(context.filesDir)) {
        val dict = PersonalizationHelper.getUserHistoryDictionary(context, locale, null)
        if (!dict.removeWordsBlocking(words, LEARNED_DICT_TIMEOUT_MS)) ok = false
    }
    words.forEach { GlobalUnlearnLearnedWords.tryEmit(it) }
    GlobalIMEMessage.tryEmit(IMEMessage.ReloadResources)
    return ok
}

@Composable
fun LearnedWordsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var words by remember { mutableStateOf<List<LearnedWord>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        loading = true
        words = withContext(Dispatchers.IO) { readLearnedWords(context) }
        loading = false
    }

    val shown = remember(words, filter) {
        val needle = filter.trim()
        if (needle.isEmpty()) words
        else words.filter { it.word.contains(needle, ignoreCase = true) }
    }

    fun remove(targets: List<String>) {
        if (targets.isEmpty() || loading) return
        loading = true
        scope.launch {
            withContext(Dispatchers.IO) { deleteLearnedWords(context, targets) }
            refresh += 1
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.learned_words_delete_all)) },
            text = { Text(stringResource(R.string.learned_words_delete_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    remove(words.map { it.word })
                }) {
                    Text(stringResource(R.string.learned_words_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    LazyColumn {
        item {
            ScreenTitle(
                stringResource(R.string.learned_words_title),
                showBack = true,
                navController = navController
            )
            Tip(stringResource(R.string.learned_words_subtitle))
        }
        item {
            Row(modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 8.dp)) {
                TextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.learned_words_filter)) },
                    singleLine = true
                )
            }
        }
        if (!loading && words.isNotEmpty()) {
            item {
                SettingItem(
                    title = stringResource(R.string.learned_words_share),
                    onClick = {
                        val text = words.joinToString("\n") { it.word }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                ) {}
                SettingItem(
                    title = stringResource(R.string.learned_words_delete_all),
                    onClick = { confirmDeleteAll = true }
                ) {}
            }
        }
        if (loading) {
            item { Tip(stringResource(R.string.learned_words_loading)) }
        } else if (shown.isEmpty()) {
            item { Tip(stringResource(R.string.learned_words_none)) }
        }
        items(shown, key = { it.word.lowercase() }) { learned ->
            SettingItem(title = learned.word, subtitle = learned.probability.toString()) {
                IconButton(
                    onClick = { remove(listOf(learned.word)) },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.learned_words_delete)
                    )
                }
            }
        }
    }
}
