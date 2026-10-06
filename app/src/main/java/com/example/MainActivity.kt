package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.navigation.OlloAppNavigation
import com.example.ui.theme.OlloBackground
import com.example.ui.theme.OllOTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The official OpenCV Android package requires local native-library
        // initialization before Mat/imgproc operations are used.
        val openCvReady = OpenCVLoader.initLocal()

        if (!openCvReady) {
            Log.e(TAG, "OpenCV initialization failed")
        } else {
            Log.i(TAG, "OpenCV initialized successfully")
        }

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

    private companion object {
        const val TAG = "OllO"
    }
}
