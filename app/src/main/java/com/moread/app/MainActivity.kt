package com.moread.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.moread.app.ui.nav.AppNavHost
import com.moread.app.ui.theme.MoReadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MoreadApp).container
        setContent {
            MoReadTheme {
                AppNavHost()
            }
        }
    }
}
