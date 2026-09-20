package com.learnanywhere

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.learnanywhere.ui.LearnAnywhereScreen
import com.learnanywhere.ui.UiController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = LearnAnywhereApp.get()
        val controller = app.ui
        setContent { LearnAnywhereScreen(controller) }
    }
}
