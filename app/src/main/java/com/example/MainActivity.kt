package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.navigation.OlloAppNavigation
import com.example.ui.theme.OlloBackground
import com.example.ui.theme.OllOTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OllOTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OlloBackground
                ) {
                    OlloAppNavigation()
                }
            }
        }
    }
}
