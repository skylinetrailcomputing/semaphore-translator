package com.skylinetrailcomputing.semaphore

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Minimal app shell. The CameraX/ML Kit/LiteRT capture path lands in a later
 * epic; for now the app exists so the parity harness has a module to compile
 * against (Issue #14, Epic 2).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Semaphore Translator" })
    }
}
