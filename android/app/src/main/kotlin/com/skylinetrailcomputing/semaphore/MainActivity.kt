package com.skylinetrailcomputing.semaphore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.skylinetrailcomputing.semaphore.ui.SemaphoreApp

/**
 * Compose entry point. Hosts [SemaphoreApp], whose `NavHost` opens on the
 * no-camera Home screen ([5a], #47) and forks to the camera modes — so no camera
 * or permission prompt fires on launch.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SemaphoreApp() }
    }
}
