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

package kotlinx.coroutines.experimental.channels

import java.util.concurrent.locks.ReentrantLock

/**
 * Channel with array buffer of a fixed [capacity].
 * Sender suspends only when buffer is fully and receiver suspends only when buffer is empty.
 *
 * This implementation uses lock to protect the buffer, which is held only during very short buffer-update operations.
 * The lists of suspended senders or receivers are lock-free.
 */
public class ArrayChannel<E>(
    /**
     * Buffer capacity.
     */
    val capacity: Int
) : AbstractChannel<E>() {
    init {
        check(capacity >= 1) { "ArrayChannel capacity must be at least 1, but $capacity was specified" }
    }

    private val lock = ReentrantLock()
    private val buffer: Array<Any?> = arrayOfNulls<Any?>(capacity)
    private var head: Int = 0
    @Volatile
    private var size: Int = 0

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try { block() }
        finally { lock.unlock() }
    }

    override val hasBuffer: Boolean get() = true
    override val isBufferEmpty: Boolean get() = size == 0
    override val isBufferFull: Boolean get() = size == capacity

    // result is `OFFER_SUCCESS | OFFER_FAILED | Closed`
    override fun offerInternal(element: E): Any {
        var token: Any? = null
        var receive: ReceiveOrClosed<E>? = null
        locked {
            val size = this.size
            closedForSend?.let { return it }
            if (size < capacity) {
                // tentatively put element to buffer
                this.size = size + 1 // update size before checking queue (!!!)
                // check for receivers that were waiting on empty queue
                if (size == 0) {
                    while (true) {
                        receive = takeFirstReceiveOrPeekClosed() ?: break // break when no receivers queued
                        token = receive!!.tryResumeReceive(element)
                        if (token != null) {
                            this.size = size // restore size
                            return@locked
                        }
                    }
                }
                buffer[(head + size) % capacity] = element // actually queue element
                return OFFER_SUCCESS
            }
            // size == capacity: full
            return OFFER_FAILED
        }
        // breaks here if offer meets receiver
        receive!!.completeResumeReceive(token!!)
        return receive!!.offerResult
    }

    // result is `E | POLL_EMPTY | Closed`
    override fun pollInternal(): Any? {
        var token: Any? = null
        var send: Send? = null
        var result: Any? = null
        locked {
            val size = this.size
            if (size == 0) return closedForSend ?: POLL_EMPTY
            // size > 0: not empty -- retrieve element
            result = buffer[head]
            buffer[head] = null
            this.size = size - 1 // update size before checking queue (!!!)
            // check for senders that were waiting on full queue
            var replacement: Any? = POLL_EMPTY
            if (size == capacity) {
                while (true) {
                    send = takeFirstSendOrPeekClosed() ?: break
                    token = send!!.tryResumeSend()
                    if (token != null) {
                        replacement = send!!.pollResult
                        break
                    }
                }
            }
            if (replacement !== POLL_EMPTY && !isClosed(replacement)) {
                this.size = size // restore size
                buffer[(head + size) % capacity] = replacement
            }
            head = (head + 1) % capacity
        }
        // complete send the we're taken replacement from
        if (token != null)
            send!!.completeResumeSend(token!!)
        return result
    }
}