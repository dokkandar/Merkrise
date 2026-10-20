package com.hsi.merkrise

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.hsi.merkrise.core.coreVersion

/**
 * Milestone-1 proof: the text shown here is returned by the Rust core
 * (`merkrise-core`) across the uniffi/JNI bridge, not by Kotlin.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            text = coreVersion()
            textSize = 26f
            gravity = Gravity.CENTER
        }
        setContentView(view)
    }
}
