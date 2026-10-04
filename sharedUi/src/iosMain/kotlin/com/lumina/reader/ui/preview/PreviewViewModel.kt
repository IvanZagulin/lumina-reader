package com.lumina.reader.ui.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * The first multiplatform ViewModel in the iPhone app (stage 8b groundwork).
 *
 * It only holds the preview's search text, but it proves the path the real
 * screens will use: `org.jetbrains.androidx.lifecycle`'s `viewModel { }` creates
 * and retains a [ViewModel] inside the iOS ComposeUIViewController, exactly as it
 * does on Android. The library, reader and catalog view models move here next.
 */
class PreviewViewModel : ViewModel() {
    var query by mutableStateOf("")
        private set

    fun onQueryChange(value: String) {
        query = value
    }
}
