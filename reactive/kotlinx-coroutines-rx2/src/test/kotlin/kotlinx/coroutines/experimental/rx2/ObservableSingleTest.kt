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

import io.reactivex.*
import kotlinx.coroutines.experimental.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.*

/**
 * Tests emitting single item with [rxObservable].
 */
class ObservableSingleTest {
    @Test
    fun testSingleNoWait() {
        val observable = rxObservable(DefaultDispatcher) {
            send("OK")
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testSingleAwait() = runBlocking {
        assertEquals("OK", Observable.just("O").awaitSingle() + "K")
    }

    @Test
    fun testSingleEmitAndAwait() {
        val observable = rxObservable(DefaultDispatcher) {
            send(Observable.just("O").awaitSingle() + "K")
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testSingleWithDelay() {
        val observable = rxObservable(DefaultDispatcher) {
            send(Observable.timer(50, TimeUnit.MILLISECONDS).map { "O" }.awaitSingle() + "K")
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testSingleException() {
        val observable = rxObservable(DefaultDispatcher) {
            send(Observable.just("O", "K").awaitSingle() + "K")
        }

        checkErroneous(observable) {
            assertTrue(it is IllegalArgumentException)
        }
    }

    @Test
    fun testAwaitFirst() {
        val observable = rxObservable(DefaultDispatcher) {
            send(Observable.just("O", "#").awaitFirst() + "K")
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testAwaitFirstOrDefault() {
        val observable = rxObservable(DefaultDispatcher) {
            send(Observable.empty<String>().awaitFirstOrDefault("O") + "K")
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testAwaitFirstOrDefaultWithValues() {
        val observable = rxObservable(DefaultDispatcher) {
            send(Observable.just("O", "#").awaitFirstOrDefault("!") + "K")
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testAwaitFirstOrNull() {
        val observable = rxObservable<String>(DefaultDispatcher) {
            send(Observable.empty<String>().awaitFirstOrNull() ?: "OK")
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testAwaitFirstOrNullWithValues() {
        val observable = rxObservable(DefaultDispatcher) {
            send((Observable.just("O", "#").awaitFirstOrNull() ?: "!") + "K")
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testAwaitFirstOrElse() {
        val observable = rxObservable(DefaultDispatcher) {
            send(Observable.empty<String>().awaitFirstOrElse { "O" } + "K")
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testAwaitFirstOrElseWithValues() {
        val observable = rxObservable(DefaultDispatcher) {
            send(Observable.just("O", "#").awaitFirstOrElse { "!" } + "K")
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testAwaitLast() {
        val observable = rxObservable(DefaultDispatcher) {
            send(Observable.just("#", "O").awaitLast() + "K")
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testExceptionFromObservable() {
        val observable = rxObservable(DefaultDispatcher) {
            try {
                send(Observable.error<String>(RuntimeException("O")).awaitFirst())
            } catch (e: RuntimeException) {
                send(Observable.just(e.message!!).awaitLast() + "K")
            }
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testExceptionFromCoroutine() {
        val observable = rxObservable<String>(DefaultDispatcher) {
            error(Observable.just("O").awaitSingle() + "K")
        }

        checkErroneous(observable) {
            assertTrue(it is IllegalStateException)
            assertEquals("OK", it.message)
        }
    }

    @Test
    fun testObservableIteration() {
        val observable = rxObservable(DefaultDispatcher) {
            var result = ""
            Observable.just("O", "K").consumeEach { result += it }
            send(result)
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }

    @Test
    fun testObservableIterationFailure() {
        val observable = rxObservable(DefaultDispatcher) {
            try {
                Observable.error<String>(RuntimeException("OK")).consumeEach { fail("Should not be here") }
                send("Fail")
            } catch (e: RuntimeException) {
                send(e.message!!)
            }
        }

        checkSingleValue(observable) {
            assertEquals("OK", it)
        }
    }
}
