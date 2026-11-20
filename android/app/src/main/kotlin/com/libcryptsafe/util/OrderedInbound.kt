package com.libcryptsafe.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Входящие кадры строго в порядке релея.
 * limitedParallelism(1) этого НЕ гарантирует: корутина, приостановленная на Room (decryptAnySession ->
 * getAllSessions, холодный SQLCipher), отпускает поток, и следующий кадр её обгоняет
 * (лог 19.11: чанки фото раньше своего INIT -> «нет ephKey», фото потеряно).
 * Здесь один потребитель выполняет каждый кадр ЦЕЛИКОМ, включая suspend-точки, и только потом берёт следующий.
 */
class OrderedInbound(scope: CoroutineScope, dispatcher: CoroutineDispatcher, private val onError: (Throwable) -> Unit = {}) {
    private val q = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(dispatcher) {
            for (job in q) {
                try { job() }
                catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
                catch (t: Throwable) { onError(t) }   // битый кадр не останавливает очередь
            }
        }
    }

    fun submit(block: suspend () -> Unit) { q.trySend(block) }
}
