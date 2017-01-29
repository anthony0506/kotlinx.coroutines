package examples

import kotlinx.coroutines.experimental.*

fun main(args: Array<String>) = runBlocking {
    val job = launch(Here) { // create new coroutine and keep a reference to its Job
        delay(1000L)
        println("World!")
    }
    println("Hello,")
    job.join() // wait until children coroutine completes
}
