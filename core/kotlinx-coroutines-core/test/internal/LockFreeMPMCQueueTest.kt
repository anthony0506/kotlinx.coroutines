/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.internal

import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

class LockFreeMPMCQueueTest : TestBase() {
    @Test
    fun testBasic() {
        val q = LockFreeMPMCQueue<Node>()
        assertEquals(null, q.removeFirstOrNull())
        assertTrue(q.isEmpty())
        q.addLast(Node(1))
        assertEquals(1, q.size)
        assertEquals(Node(1), q.removeFirstOrNull())
        assertEquals(null, q.removeFirstOrNull())
        assertTrue(q.isEmpty())
    }

    private data class Node(val v: Int) : LockFreeMPMCQueueNode<Node>()
}