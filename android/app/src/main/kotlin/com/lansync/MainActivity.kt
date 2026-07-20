package com.lansync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lansync.ui.SyncMainScreen
import com.lansync.ui.SyncViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep content out from under the status/nav bars. On Android 15+ the
        // default is edge-to-edge, which was pushing the Start Sync button under
        // the gesture navigation bar.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            val viewModel = viewModel<SyncViewModel>(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SyncViewModel(this@MainActivity) as T
                    }
                }
            )
            SyncMainScreen(viewModel)
        }
    }
}
