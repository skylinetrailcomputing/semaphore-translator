package com.skylinetrailcomputing.semaphore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.skylinetrailcomputing.semaphore.ui.SemaphoreScreen

/**
 * Hosts the live debug screen ([3.5], #23): camera preview + skeleton overlay +
 * per-frame decode. The Compose entry point; all UI lives in [SemaphoreScreen].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SemaphoreScreen() }
    }
}
