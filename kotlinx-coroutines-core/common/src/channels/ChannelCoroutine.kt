/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.channels

import kotlinx.coroutines.*
import kotlin.coroutines.*

internal open class ChannelCoroutine<E>(
    parentContext: CoroutineContext,
    protected val _channel: Channel<E>,
    initParentJob: Boolean,
    active: Boolean
) : AbstractCoroutine<Unit>(parentContext, initParentJob, active), Channel<E> by _channel {

    val channel: Channel<E> get() = this

    @Deprecated(level = DeprecationLevel.HIDDEN, message = "Since 1.2.0, binary compatibility with versions <= 1.1.x")
    override fun cancel() {
        cancelInternal(defaultCancellationException())
    }

    @Deprecated(level = DeprecationLevel.HIDDEN, message = "Since 1.2.0, binary compatibility with versions <= 1.1.x")
    final override fun cancel(cause: Throwable?): Boolean {
        cancelInternal(defaultCancellationException())
        return true
    }

    final override fun cancel(cause: CancellationException?) {
        if (isCancelled) return // Do not create an exception if the coroutine (-> the channel) is already cancelled
        cancelInternal(cause ?: defaultCancellationException())
    }

    override fun cancelInternal(cause: Throwable) {
        val exception = cause.toCancellationException()
        _channel.cancel(exception) // cancel the channel
        cancelCoroutine(exception) // cancel the job
    }
}
