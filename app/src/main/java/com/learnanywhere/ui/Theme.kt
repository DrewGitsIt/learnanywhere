package com.learnanywhere.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App theme: Material 3 dynamic color (minSdk 34, so always available),
 * following the system light/dark setting.
 */
@Composable
fun LearnAnywhereTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
                 else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}
