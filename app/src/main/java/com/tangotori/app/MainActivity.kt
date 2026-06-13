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
import androidx.core.view.WindowCompat
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
        // Opt into edge-to-edge so Compose receives IME insets and can keep
        // the edit-screen buttons above the keyboard via the Scaffold's
        // safeDrawing contentWindowInsets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Soft hint to the system: prefer 120 Hz for this window. Unlike
        // `preferredDisplayModeId` (which pins the display and caused
        // system-wide jank earlier), this is just a preference — the
        // compositor can still drop us to 60 Hz if needed.
        window.attributes = window.attributes.apply {
            preferredRefreshRate = 120f
        }
        handleIncomingIntent(intent)
        setContent {
            TangoToriTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SentenceScreen()
                }
            }
        }
    }

    /**
     * Activity is `singleTop`, so subsequent ACTION_SEND / deep-link intents
     * arrive here instead of creating a new instance — keeping a single VM
     * scope and letting the user open sentence after sentence without
     * reopening the app.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Routes the two ways a sentence enters the app from outside:
     *  - ACTION_SEND: shared plain text (system share sheet).
     *  - ACTION_VIEW on a `tangotori://sentence?text=…` deep link, fired by the
     *    "Open in Tango Tori" button on Anki cards.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type != "text/plain") return
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isNotEmpty()) incoming.submit(text, fromDeepLink = false)
            }
            Intent.ACTION_VIEW -> {
                if (intent.data?.scheme != "tangotori") return
                val text = intent.data?.getQueryParameter("text")?.trim().orEmpty()
                if (text.isNotEmpty()) incoming.submit(text, fromDeepLink = true)
            }
        }
    }
}
