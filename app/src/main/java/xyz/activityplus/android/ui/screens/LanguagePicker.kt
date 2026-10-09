package xyz.activityplus.android.ui.screens

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.ui.components.Note
import java.util.Locale

/**
 * In-app language choice (Android 13+, through the system's per-app language). Names are shown in
 * their own language, so anyone finds theirs whatever the app is showing right now.
 */
object AppLanguage {
    /** Empty tag = follow the phone. */
    val options = listOf(
        "" to null,
        "en" to "English",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "pt-BR" to "Português (Brasil)",
        "ja" to "日本語",
        "zh-CN" to "简体中文",
    )

    val supported get() = Build.VERSION.SDK_INT >= 33

    fun current(context: Context): String {
        if (!supported) return ""
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) "" else locales[0].toLanguageTag()
    }

    fun set(context: Context, tag: String) {
        if (!supported) return
        // The system recreates the activity in the new language.
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    /** The phone's own language, named in itself: "Deutsch". */
    fun systemName(): String {
        val l = LocaleList.getDefault()[0] ?: Locale.getDefault()
        return l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }
    }

    fun label(context: Context, tag: String): String {
        val match = options.firstOrNull { (t, _) -> t.isNotEmpty() && (t == tag || tag.startsWith("$t-") || t.startsWith("$tag-")) }
        return match?.second ?: context.getString(R.string.lang_system, systemName())
    }
}

/** One row ("Language: Deutsch ▾") that opens the list. Hidden below Android 13. */
@Composable
fun LanguageRow(modifier: Modifier = Modifier) {
    if (!AppLanguage.supported) return
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val current = remember { AppLanguage.current(context) }
    Column(modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp)) {
        Text(
            stringResource(R.string.set_language) + ": " + AppLanguage.label(context, current) + "  ▾",
            style = MaterialTheme.typography.bodyLarge,
        )
        Note(stringResource(R.string.set_language_hint))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.set_language)) },
            text = {
                Column {
                    AppLanguage.options.forEach { (tag, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { open = false; AppLanguage.set(context, tag) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = AppLanguage.label(context, current) == AppLanguage.label(context, tag), onClick = {
                                open = false; AppLanguage.set(context, tag)
                            })
                            Text(name ?: stringResource(R.string.lang_system, AppLanguage.systemName()))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
