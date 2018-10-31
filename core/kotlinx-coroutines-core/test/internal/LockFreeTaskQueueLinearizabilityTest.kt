/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
@file:Suppress("unused")

package kotlinx.coroutines.internal

import com.devexperts.dxlab.lincheck.*
import com.devexperts.dxlab.lincheck.annotations.*
import com.devexperts.dxlab.lincheck.paramgen.*
import com.devexperts.dxlab.lincheck.stress.*
import kotlinx.coroutines.*
import kotlin.test.*

@OpGroupConfigs(OpGroupConfig(name = "consumer", nonParallel = true))
@Param(name = "value", gen = IntGen::class, conf = "1:3")
class LockFreeTaskQueueLinearizabilityTest : TestBase() {
    private var singleConsumer = false
    private lateinit var q: LockFreeTaskQueue<Int>

    @Reset
    fun resetQueue() {
        q = LockFreeTaskQueue(singleConsumer)
    }

    @Operation
    fun close() = q.close()

    @Operation
    fun addLast(@Param(name = "value") value: Int) = q.addLast(value)

    /**
     * Note, that removeFirstOrNull is not linearizable w.r.t. to addLast, so here
     * we test only linearizability of close.
     */
//    @Operation(group = "consumer")
//    fun removeFirstOrNull() = q.removeFirstOrNull()

    @Test
    fun testLinearizabilitySC() {
        singleConsumer = true
        linTest()
    }

    @Test
    fun testLinearizabilityMC() {
        singleConsumer = false
        linTest()
    }

    private fun linTest() {
        val options = StressOptions()
            .iterations(100 * stressTestMultiplierSqrt)
            .invocationsPerIteration(1000 * stressTestMultiplierSqrt)
            .addThread(1, 3)
            .addThread(1, 3)
            .addThread(1, 3)
        LinChecker.check(LockFreeTaskQueueLinearizabilityTest::class.java, options)
    }
}