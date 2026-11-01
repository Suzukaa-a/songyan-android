package app.songyan.ime.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Who may have a librime engine open in this process.
 *
 * librime is process-global: a second engine fails on the native single-engine guard. The input
 * method's [ImeEngineRuntime] starts its engine under [withLock]; work that needs an engine of its
 * own (a learning backup or merge) runs under [exclusive], which first asks every registered
 * [Holder] to close its engine and lets it start again afterwards.
 */
class EngineOwnership {
    /** Something that keeps an engine open between uses (the input method runtime). */
    interface Holder {
        /** Called with the lock held: close the engine without taking the lock again. */
        suspend fun releaseForExclusiveUse()

        /** Called after the exclusive work released the lock: open the engine again when needed. */
        fun exclusiveUseEnded()
    }

    private val mutex = Mutex()
    private val holders = CopyOnWriteArraySet<Holder>()

    fun register(holder: Holder) {
        holders += holder
    }

    fun unregister(holder: Holder) {
        holders -= holder
    }

    /** Runs [block] while no exclusive work runs; for a holder starting or replacing its engine. */
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    /** Runs [block] with every holder's engine closed, so it may open an engine of its own. */
    suspend fun <T> exclusive(block: suspend () -> T): T =
        try {
            mutex.withLock {
                for (holder in holders) holder.releaseForExclusiveUse()
                block()
            }
        } finally {
            for (holder in holders) holder.exclusiveUseEnded()
        }
}
