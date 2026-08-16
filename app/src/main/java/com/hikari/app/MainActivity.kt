package com.hikari.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hikari.app.ui.navigation.AppRoot
import com.hikari.app.ui.theme.HikariTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            HikariTheme {
                AppRoot()
            }
        }
    }
}
