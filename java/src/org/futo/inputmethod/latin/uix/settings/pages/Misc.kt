package org.futo.inputmethod.latin.uix.settings.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsExporter
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.utils.DebugFileLogger

/**
 * Shares the debug log files through the system share sheet so they can be pulled off the device
 * without a cable. Files are shared read-only through a content provider.
 */
private fun shareDebugLogFiles(context: Context) {
    val files = DebugFileLogger.getLogFiles(context)
    if (files.isEmpty()) {
        Toast.makeText(context, "No debug log files to share", Toast.LENGTH_SHORT).show()
        return
    }

    val uris = ArrayList<Uri>()
    for (file in files) {
        uris.add(
            FileProvider.getUriForFile(context, "${context.packageName}.debuglogs", file)
        )
    }

    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share debug logs"))
}

val MiscMenu = UserSettingsMenu(
    title = R.string.misc_settings_title,
    navPath = "misc", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            ScreenTitle("Debug log file")
        },
        UserSetting(
            name = 0,
            appearsInSearch = false,
            component = {
                val context = LocalContext.current
                // Re-read the files each time the screen recomposes so the summary stays accurate.
                val fileSummaryVersion = remember { mutableStateOf(0) }
                Column {
                    var enabled by remember { mutableStateOf(DebugFileLogger.isEnabled()) }
                    SettingToggleRaw(
                        title = "Keep a debug log file",
                        enabled = enabled,
                        setValue = {
                            enabled = it
                            DebugFileLogger.setEnabled(context, it)
                            fileSummaryVersion.value = fileSummaryVersion.value + 1
                        },
                        subtitle = "Writes autocorrect decisions to 5 MB files, keeping the newest " +
                                "250 MB. Survives the keyboard being restarted or killed."
                    )
                    NavigationItem(
                        title = "Share debug log files",
                        subtitle = fileSummaryVersion.value.let {
                            val files = DebugFileLogger.getLogFiles(context)
                            if (files.isEmpty()) {
                                "No log files yet"
                            } else {
                                "${files.size} file(s), ${
                                    files.sumOf { file -> file.length() } / 1024 / 1024
                                } MB"
                            }
                        },
                        style = NavigationItemStyle.MiscNoArrow,
                        navigate = { shareDebugLogFiles(context) }
                    )
                    NavigationItem(
                        title = "Delete debug log files",
                        subtitle = "Frees the disk space used by the log files",
                        style = NavigationItemStyle.MiscNoArrow,
                        navigate = {
                            DebugFileLogger.clear(context)
                            fileSummaryVersion.value = fileSummaryVersion.value + 1
                        }
                    )
                }
            }
        ),

        userSettingDecorationOnly {
            ScreenTitle(stringResource(R.string.settings_export_configuration_title))
        },

        userSettingNavigationItem(
            title = (R.string.settings_export_configuration),
            subtitle = (R.string.settings_export_configuration_subtitle),
            style = NavigationItemStyle.Misc,
            navigateTo = "exportingcfg"
        ).copy(searchTags = R.string.settings_import_export_tags),
        userSettingNavigationItem(
            title = (R.string.settings_import_configuration),
            subtitle = (R.string.settings_import_configuration_subtitle),
            style = NavigationItemStyle.Misc,
            navigate = { nav ->
                SettingsExporter.triggerImportSettings(nav.context)
            }
        ).copy(searchTags = R.string.settings_import_export_tags),
    )
)