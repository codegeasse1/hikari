package com.hikari.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hikari.app.net.Updater
import com.hikari.app.ui.components.UpdateDialog
import com.hikari.app.ui.navigation.AppRoot
import com.hikari.app.ui.theme.HikariTheme
import com.hikari.app.ui.theme.HikariThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val store = (application as HikariApp).store
        setContent {
            val themeKey by store.themeFlow().collectAsState(initial = HikariThemeMode.DARK.key)
            val themeMode = HikariThemeMode.fromKey(themeKey)

            var showUpdateDialog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // One quiet check on launch — the dialog only appears when a
                // newer build exists on GitHub.
                runCatching { Updater.checkForUpdate() }
                    .getOrNull()
                    ?.takeIf { it.available }
                    ?.let { showUpdateDialog = true }
            }

            HikariTheme(themeMode) {
                AppRoot(themeMode.key)
                if (showUpdateDialog) {
                    UpdateDialog(
                        context = this@MainActivity,
                        onDismiss = { showUpdateDialog = false },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The CloudStream runtime's Torrent engine needs an activity reference
        // for its cache dir (it throws "No activity" otherwise).
        com.lagradost.cloudstream3.CommonActivity.setActivityInstance(this)
    }

    override fun onStop() {
        if (com.lagradost.cloudstream3.CommonActivity.activity === this) {
            com.lagradost.cloudstream3.CommonActivity.setActivityInstance(null)
        }
        super.onStop()
    }
}
