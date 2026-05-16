package com.tangotori.app.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot pipe from MainActivity (which handles [android.content.Intent.ACTION_SEND]
 * for plain text) to [com.tangotori.app.ui.sentence.SentenceViewModel]. The
 * activity can't reach a Hilt ViewModel directly from outside Compose, so we
 * route incoming shares through this singleton SharedFlow instead.
 *
 * `extraBufferCapacity = 1` so emissions from the activity don't block when
 * the ViewModel hasn't subscribed yet — the latest share is held until the VM
 * starts collecting.
 */
@Singleton
class IncomingSentenceBus @Inject constructor() {
    private val _events = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 1,
    )
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun submit(sentence: String) {
        _events.tryEmit(sentence)
    }
}
