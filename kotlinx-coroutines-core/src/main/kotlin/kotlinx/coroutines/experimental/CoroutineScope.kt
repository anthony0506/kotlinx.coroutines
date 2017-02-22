/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental

import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Receiver interface for generic coroutine builders, so that the code inside coroutine has a convenient access
 * to its [context] and its cancellation status via [isActive].
 */
public interface CoroutineScope {
    /**
     * Returns `true` when this coroutine is still active (has not completed yet).
     *
     * Check this property in long-running computation loops to support cancellation:
     * ```
     * while (isActive) {
     *     // do some computation
     * }
     * ```
     *
     * This property is a shortcut for `context[Job]!!.isActive`. See [context] and [Job].
     */
    public val isActive: Boolean

    /**
     * Returns the context of this coroutine.
     */
    public val context: CoroutineContext
}

/**
 * Abstract class to simplify writing of coroutine completion objects that
 * implement completion [Continuation], [Job], and [CoroutineScope] interfaces.
 * It stores the result of continuation in the state of the job.
 *
 * @param active when `true` coroutine is created in _active_ state, when `false` in _new_ state. See [Job] for details.
 * @suppress **This is unstable API and it is subject to change.**
 */
public abstract class AbstractCoroutine<in T>(
    active: Boolean
) : JobSupport(active), Continuation<T>, CoroutineScope {
    // context must be Ok for unsafe publishing (it is persistent),
    // so we don't mark this _context variable as volatile, but leave potential benign race here
    private var _context: CoroutineContext? = null // created on first need

    @Suppress("LeakingThis")
    public final override val context: CoroutineContext
        get() = _context ?: createContext().also { _context = it }

    protected abstract val parentContext: CoroutineContext

    protected open fun createContext() = parentContext + this

    protected open fun defaultResumeMode(): Int = MODE_DISPATCHED

    final override fun resume(value: T) = resume(value, defaultResumeMode())

    protected fun resume(value: T, mode: Int) {
        while (true) { // lock-free loop on state
            val state = this.state // atomic read
            when (state) {
                is Incomplete -> if (updateState(state, value, mode)) return
                is Cancelled -> return // ignore resumes on cancelled continuation
                else -> throw IllegalStateException("Already resumed, but got value $value")
            }
        }
    }

    final override fun resumeWithException(exception: Throwable) = resumeWithException(exception, defaultResumeMode())

    protected fun resumeWithException(exception: Throwable, mode: Int) {
        while (true) { // lock-free loop on state
            val state = this.state // atomic read
            when (state) {
                is Incomplete -> {
                    if (updateState(state, CompletedExceptionally(state.idempotentStart, exception), mode)) return
                }
                is Cancelled -> {
                    // ignore resumes on cancelled continuation, but handle exception if a different one is here
                    if (exception != state.exception) handleCoroutineException(context, exception)
                    return
                }
                else -> throw IllegalStateException("Already resumed, but got exception $exception", exception)
            }
        }
    }

    final override fun handleCompletionException(closeException: Throwable) {
        handleCoroutineException(context, closeException)
    }

    // for nicer debugging
    override fun toString(): String {
        val state = this.state
        val result = if (state is Incomplete) "" else "[$state]"
        return "${this::class.java.simpleName}{${stateToString(state)}}$result@${Integer.toHexString(System.identityHashCode(this))}"
    }
}
