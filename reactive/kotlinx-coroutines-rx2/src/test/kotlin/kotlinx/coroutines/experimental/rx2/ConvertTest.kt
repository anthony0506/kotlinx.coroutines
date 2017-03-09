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

package kotlinx.coroutines.experimental.rx2

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.produce
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertTest : TestBase() {
    class TestException(s: String): RuntimeException(s)

    @Test
    fun testToCompletableSuccess() = runBlocking<Unit> {
        expect(1)
        val job = launch(context) {
            expect(3)
        }
        val completable = job.toCompletable(context)
        completable.subscribe {
            expect(4)
        }
        expect(2)
        yield()
        finish(5)
    }

    @Test
    fun testToCompletableFail() = runBlocking<Unit> {
        expect(1)
        val job = async(context + NonCancellable) { // don't kill parent on exception
            expect(3)
            throw RuntimeException("OK")
        }
        val completable = job.toCompletable(context)
        completable.subscribe {
            expect(4)
        }
        expect(2)
        yield()
        finish(5)
    }

    @Test
    fun testToSingle() {
        val d = async(CommonPool) {
            delay(50)
            "OK"
        }
        val single1 = d.toSingle(Unconfined)
        checkSingleValue(single1) {
            assertEquals("OK", it)
        }
        val single2 = d.toSingle(Unconfined)
        checkSingleValue(single2) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testToSingleFail() {
        val d = async(CommonPool) {
            delay(50)
            throw TestException("OK")
        }
        val single1 = d.toSingle(Unconfined)
        checkErroneous(single1) {
            check(it is TestException && it.message == "OK") { "$it" }
        }
        val single2 = d.toSingle(Unconfined)
        checkErroneous(single2) {
            check(it is TestException && it.message == "OK") { "$it" }
        }
    }

    @Test
    fun testToObservable() {
        val c = produce(CommonPool) {
            delay(50)
            send("O")
            delay(50)
            send("K")
        }
        val observable = c.toObservable(Unconfined)
        checkSingleValue(observable.reduce { t1, t2 -> t1 + t2 }.toSingle()) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testToObservableFail() {
        val c = produce(CommonPool) {
            delay(50)
            send("O")
            delay(50)
            throw TestException("K")
        }
        val observable = c.toObservable(Unconfined)
        val single = rxSingle(Unconfined) {
            var result = ""
            try {
                for (x in observable)
                    result += x
            } catch(e: Throwable) {
                check(e is TestException)
                result += e.message
            }
            result
        }
        checkSingleValue(single) {
            assertEquals("OK", it)
        }
    }
}