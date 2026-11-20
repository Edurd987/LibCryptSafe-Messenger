package com.libcryptsafe.util

/**
 * Статусы доставки СВОИХ сообщений (значок — суффикс текста пузыря):
 *   PENDING   🕓  записано, релей ещё не подтвердил
 *   SENT      ✓   релей принял (relay_rcvd)
 *   DELIVERED ✓✓  получатель расшифровал (E2E-ACK)
 *   NONE / прочее — без значка (медиа до шага 1б; входящие значков не имеют).
 * render() сначала снимает прежний значок: markDelivered/onRelayAccepted перерисовывают
 * УЖЕ отрисованный текст, иначе вышло бы «текст 🕓 ✓ ✓✓».
 */
object StatusMark {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    private const val CLOCK = "  \uD83D\uDD53"
    private const val ONE = "  \u2713"
    private const val TWO = "  \u2713\u2713"

    fun strip(text: String): String = when {
        text.endsWith(TWO) -> text.removeSuffix(TWO)
        text.endsWith(ONE) -> text.removeSuffix(ONE)
        text.endsWith(CLOCK) -> text.removeSuffix(CLOCK)
        else -> text
    }

    // На пузыре сейчас часы? onRelayAccepted повышает статус только из этого состояния.
    fun isPending(text: String): Boolean = text.endsWith(CLOCK)

    fun render(text: String, isOwn: Boolean, status: String): String {
        if (!isOwn) return text
        val base = strip(text)
        return when (status) {
            PENDING -> base + CLOCK
            SENT -> base + ONE
            DELIVERED -> base + TWO
            else -> base
        }
    }
}
