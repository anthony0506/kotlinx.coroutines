/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.flow.internal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal actual class AbortFlowException actual constructor(
    @JvmField @Transient actual val owner: FlowCollector<*>
) : CancellationException("Flow was aborted, no more elements needed") {

    override fun fillInStackTrace(): Throwable {
        if (DEBUG) return super.fillInStackTrace()
        // Prevent Android <= 6.0 bug, #1866
        stackTrace = emptyArray()
        return this
    }
}

internal actual class ChildCancelledException : CancellationException("Child of the scoped flow was cancelled") {
    override fun fillInStackTrace(): Throwable {
        if (DEBUG) return super.fillInStackTrace()
        // Prevent Android <= 6.0 bug, #1866
        stackTrace = emptyArray()
        return this
    }
}
