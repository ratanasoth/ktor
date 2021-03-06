package io.ktor.http.cio.internals

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.internal.*
import java.time.*
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.*

/**
 * It provides ability to cancel jobs and schedule coroutine with timeout. Unlike regular withTimeout
 * this implementation is never scheduling timer tasks but only checks for current time. This makes timeout measurement
 * much cheaper and doesn't require any watchdog thread.
 *
 * There are two limitations:
 *  - timeout period is fixed
 *  - job cancellation is not guaranteed if no new jobs scheduled
 *
 *  The last one limitation is generally unacceptable
 *  however in the particular use-case (closing IDLE connection) it is just fine
 *  as we really don't care about stalling IDLE connections if there are no more incoming
 */
class WeakTimeoutQueue(private val timeoutMillis: Long,
                       private val clock: Clock = Clock.systemUTC(),
                       private val exceptionFactory: () -> Exception = { TimeoutCancellationException("Timeout of $timeoutMillis ms exceeded") }) {
    private val head = LockFreeLinkedListHead()

    fun register(r: Job) : DisposableHandle {
        val now = clock.millis()
        val head = head

        val cancellable = JobTask(now + timeoutMillis, r)
        head.addLast(cancellable)

        process(now, head)
        return cancellable
    }

    suspend fun <T> withTimeout(block: suspend CoroutineScope.() -> T): T {
        return suspendCoroutineOrReturn { c ->
            val wrapped = WeakTimeoutCoroutine(c.context, c)
            val handle = register(wrapped)

            wrapped.initParentJob(c.context[Job])
            wrapped.disposeOnCompletion(handle)

            val result = try {
                block.startCoroutineUninterceptedOrReturn(receiver = wrapped, completion = wrapped)
            } catch (t: Throwable) {
                JobSupport.CompletedExceptionally(t)
            }

            unwrapResult(wrapped, handle, result)
        }
    }

    private fun unwrapResult(c: WeakTimeoutCoroutine<*>, handle: DisposableHandle, result: Any?): Any? {
        val suspended = COROUTINE_SUSPENDED
        return when {
            result === suspended -> result
            c.isCompleted -> suspended
            c is JobSupport.CompletedExceptionally -> {
                handle.dispose()
                throw c.exception
            }
            else -> {
                handle.dispose()
                result
            }
        }
    }

    private fun process(now: Long, head: LockFreeLinkedListHead) {
        while (true) {
            val p = head.next as? Cancellable ?: break
            if (p.deadline > now) break
            if (p.isActive && p.remove()) p.cancel(exceptionFactory())
        }
    }

    private abstract class Cancellable(val deadline: Long) : LockFreeLinkedListNode(), DisposableHandle {
        open val isActive: Boolean
            get() = !isRemoved

        abstract fun cancel(t: Throwable)

        override fun dispose() {
            remove()
        }
    }

    private class JobTask(deadline: Long, private val job: Job) : Cancellable(deadline) {
        override val isActive: Boolean
            get() = super.isActive && job.isActive

        override fun cancel(t: Throwable) {
            job.cancel(t)
        }
    }

    private class WeakTimeoutCoroutine<T>(context: CoroutineContext, val delegate: Continuation<T>) : AbstractCoroutine<T>(context, true), Continuation<T> {
        override fun afterCompletion(state: Any?, mode: Int) {
            if (state is CompletedExceptionally) {
                delegate.resumeWithExceptionMode(state.exception, mode)
            } else {
                @Suppress("UNCHECKED_CAST")
                delegate.resumeMode(state as T, mode)
            }
        }
    }

}