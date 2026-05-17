package com.tangotori.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.tangotori.app.data.IncomingSentenceBus
import com.tangotori.app.ui.sentence.SentenceScreen
import com.tangotori.app.ui.theme.TangoToriTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var incoming: IncomingSentenceBus

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run BEFORE super.onCreate so the splash theme is in place
        // when the system draws the first frame. The call swaps to the
        // post-splash theme (Theme.TangoTori) for us once content is ready.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Soft hint to the system: prefer 120 Hz for this window. Unlike
        // `preferredDisplayModeId` (which pins the display and caused
        // system-wide jank earlier), this is just a preference — the
        // compositor can still drop us to 60 Hz if needed.
        window.attributes = window.attributes.apply {
            preferredRefreshRate = 120f
        }
        handleShareIntent(intent)
        setContent {
            TangoToriTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SentenceScreen()
                }
            }
        }
    }

    /**
     * Activity is `singleTop`, so subsequent ACTION_SEND intents arrive here
     * instead of creating a new instance — keeping a single VM scope and
     * letting the user share sentence after sentence without reopening the app.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return
        incoming.submit(text)
    }
}
