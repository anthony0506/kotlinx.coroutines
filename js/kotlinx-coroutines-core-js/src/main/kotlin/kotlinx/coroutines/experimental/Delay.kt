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

import kotlin.coroutines.experimental.ContinuationInterceptor
import kotlin.coroutines.experimental.CoroutineContext

/**
 * This dispatcher _feature_ is implemented by [CoroutineDispatcher] implementations that natively support
 * scheduled execution of tasks.
 *
 * Implementation of this interface affects operation of
 * [delay][kotlinx.coroutines.experimental.delay] and [withTimeout] functions.
 */
public actual interface Delay {
    /**
     * Schedules resume of a specified [continuation] after a specified delay [time].
     *
     * Continuation **must be scheduled** to resume even if it is already cancelled, because a cancellation is just
     * an exception that the coroutine that used `delay` might wanted to catch and process. It might
     * need to close some resources in its `finally` blocks, for example.
     *
     * This implementation is supposed to use dispatcher's native ability for scheduled execution in its thread(s).
     * In order to avoid an extra delay of execution, the following code shall be used to resume this
     * [continuation] when the code is already executing in the appropriate dispatcher:
     *
     * ```kotlin
     * with(continuation) { resumeUndispatched(Unit) }
     * ```
     */
    fun scheduleResumeAfterDelay(time: Double, continuation: CancellableContinuation<Unit>)

    /**
     * Schedules invocation of a specified [block] after a specified delay [time].
     * The resulting [DisposableHandle] can be used to [dispose][DisposableHandle.dispose] of this invocation
     * request if it is not needed anymore.
     */
    fun invokeOnTimeout(time: Double, block: Runnable): DisposableHandle
}

/**
 * Delays coroutine for a given time without blocking and resumes it after a specified time.
 * This suspending function is cancellable.
 * If the [Job] of the current coroutine is cancelled or completed while this suspending function is waiting, this function
 * immediately resumes with [CancellationException].
 *
 * This function delegates to [Delay.scheduleResumeAfterDelay] if the context [CoroutineDispatcher]
 * implements [Delay] interface, otherwise it resumes using a built-in scheduler.
 *
 * @param time time in milliseconds.
 */
public actual suspend fun delay(time: Long) {
    val dt = time.toDouble()
    kotlin.require(dt >= 0) { "Delay time $time cannot be negative" }
    if (dt <= 0) return // don't delay
    return suspendCancellableCoroutine sc@ { cont: CancellableContinuation<Unit> ->
        cont.context.delay.scheduleResumeAfterDelay(dt, cont)
    }
}

/** Returns [Delay] implementation of the given context */
internal val CoroutineContext.delay: Delay get() = get(ContinuationInterceptor) as? Delay ?: DefaultExecutor
