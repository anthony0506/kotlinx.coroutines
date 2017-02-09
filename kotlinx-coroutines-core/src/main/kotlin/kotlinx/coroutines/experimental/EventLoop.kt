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

import kotlinx.coroutines.experimental.internal.LockFreeLinkedListHead
import kotlinx.coroutines.experimental.internal.LockFreeLinkedListNode
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Implemented by [CoroutineDispatcher] implementations that have event loop inside and can
 * be asked to process next event from their event queue. It is used by [runBlocking] to
 * continue processing events when invoked from the event dispatch thread.
 */
public interface EventLoop {
    /**
     * Processes next event in this event loop and returns `true` or returns `false` if there are
     * no events to process or when invoked from the wrong thread.
     */
    public fun processNextEvent(): Boolean

    public companion object Factory {
        /**
         * Creates a new event loop that is bound the specified [thread] (current thread by default) and
         * stops accepting new events when [parentJob] completes. Every continuation that is scheduled
         * onto this event loop unparks the specified thread via [LockSupport.unpark].
         *
         * The main event-processing loop using the resulting `eventLoop` object should look like this:
         * ```
         * while (needsToBeRunning) {
         *     if (Thread.interrupted()) break // or handle somehow
         *     if (!eventLoop.processNextEvent()) LockSupport.park() // event loop will unpark
         * }
         * ```
         */
        public operator fun invoke(thread: Thread = Thread.currentThread(), parentJob: Job? = null): CoroutineDispatcher =
            EventLoopImpl(thread).apply {
                if (parentJob != null) initParentJob(parentJob)
            }
    }
}

internal class EventLoopImpl(
    val thread: Thread
) : CoroutineDispatcher(), EventLoop {
    val queue = LockFreeLinkedListHead()
    var parentJob: Job? = null

    fun initParentJob(coroutine: Job) {
        require(this.parentJob == null)
        this.parentJob = coroutine
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        schedule(Dispatch(block))
    }

    fun schedule(node: Node): Boolean {
        val added = if (parentJob == null) {
            queue.addLast(node)
            true
        } else
            queue.addLastIf(node) { !parentJob!!.isCompleted }
        if (added) {
            if (Thread.currentThread() !== thread)
                LockSupport.unpark(thread)
        } else {
            node.run()
        }
        return added
    }

    override fun processNextEvent(): Boolean {
        if (Thread.currentThread() !== thread) return false
        (queue.removeFirstOrNull() as? Runnable)?.apply {
            run()
            return true
        }
        return false
    }

    abstract class Node : LockFreeLinkedListNode(), Runnable

    class Dispatch(block: Runnable) : Node(), Runnable by block

    override fun toString(): String = "EventLoopImpl@${Integer.toHexString(System.identityHashCode(this))}"
}

