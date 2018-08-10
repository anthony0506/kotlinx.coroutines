/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

// This file was automatically generated from coroutines-guide.md by Knit tool. Do not edit.
package kotlinx.coroutines.experimental.guide.exceptions05

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.exceptions.*
import java.io.*
import kotlin.coroutines.experimental.*

fun main(args: Array<String>) = runBlocking {
    val handler = CoroutineExceptionHandler { _, exception ->
           println("Caught $exception with suppressed ${exception.suppressed().contentToString()}")
       }
   
       val job = launch(handler + coroutineContext, parent = Job()) {
           launch(coroutineContext, start = CoroutineStart.ATOMIC) {
                try {
                    delay(Long.MAX_VALUE)
                } finally {
                    throw ArithmeticException()
                }
           }
           
           launch(coroutineContext) {
               throw IOException()
           }
   
           delay(Long.MAX_VALUE)
       }
   
       job.join()
}
