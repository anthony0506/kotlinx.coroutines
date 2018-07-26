package kotlinx.coroutines.experimental.scheduling

import kotlinx.atomicfu.*
import kotlinx.coroutines.experimental.*
import java.io.*
import java.util.concurrent.*
import kotlin.coroutines.experimental.*

// TODO make internal after integration wih Ktor
class ExperimentalCoroutineDispatcher(corePoolSize: Int = CORE_POOL_SIZE, maxPoolSize: Int = MAX_POOL_SIZE) : CoroutineDispatcher(), Delay, Closeable {

    private val coroutineScheduler = CoroutineScheduler(corePoolSize, maxPoolSize)

    override fun dispatch(context: CoroutineContext, block: Runnable): Unit = coroutineScheduler.dispatch(block)

    override fun dispatchYield(context: CoroutineContext, block: Runnable): Unit = coroutineScheduler.dispatch(block, fair = true)

    override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>): Unit =
            DefaultExecutor.scheduleResumeAfterDelay(time, unit, continuation)

    override fun close() = coroutineScheduler.close()

    override fun toString(): String {
        return "${super.toString()}[scheduler = $coroutineScheduler]"
    }

    /**
     * Creates new coroutine execution context with limited parallelism to execute tasks which may potentially block.
     * Resulting [CoroutineDispatcher] doesn't own any resources (its threads) and piggybacks on the original [ExperimentalCoroutineDispatcher],
     * executing tasks in this context, giving original dispatcher hint to adjust its behaviour.
     *
     * @param parallelism parallelism level, indicating how many threads can execute tasks in given context in parallel.
     */
    fun blocking(parallelism: Int = BLOCKING_DEFAULT_PARALLELISM): CoroutineDispatcher {
        require(parallelism > 0) { "Expected positive parallelism level, but have $parallelism" }
        return LimitingBlockingDispatcher(parallelism, TaskMode.PROBABLY_BLOCKING, this)
    }

    internal fun dispatchBlocking(block: Runnable, context: TaskMode, fair: Boolean): Unit = coroutineScheduler.dispatch(block, context, fair)
}

private class LimitingBlockingDispatcher(val parallelism: Int, val taskContext: TaskMode, val dispatcher: ExperimentalCoroutineDispatcher) : CoroutineDispatcher(), Delay {

    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val inFlightTasks = atomic(0)

    override fun dispatch(context: CoroutineContext, block: Runnable) = dispatch(block, false)

    private fun dispatch(block: Runnable, fair: Boolean) {
        var taskToSchedule = wrap(block)
        while (true) {
            // Commit in-flight tasks slot
            val inFlight = inFlightTasks.incrementAndGet()

            // Fast path, if parallelism limit is not reached, dispatch task and return
            if (inFlight <= parallelism) {
                dispatcher.dispatchBlocking(taskToSchedule, taskContext, fair)
                return
            }

            // Parallelism limit is reached, add task to the queue
            queue.add(taskToSchedule)

            /*
             * We're not actually scheduled anything, so rollback committed in-flight task slot:
             * If the amount of in-flight tasks is still above the limit, do nothing
             * If the amount of in-flight tasks is lesser than parallelism, then
             * it's a race with a thread which finished the task from the current context, we should resubmit the first task from the queue
             * to avoid starvation.
             *
             * Race example #1 (TN is N-th thread, R is current in-flight tasks number), execution is sequential:
             *
             * T1: submit task, start execution, R == 1
             * T2: commit slot for next task, R == 2
             * T1: finish T1, R == 1
             * T2: submit next task to local queue, decrement R, R == 0
             * Without retries, task from T2 will be stuck in the local queue
             */
            if (inFlightTasks.decrementAndGet() >= parallelism) {
                return
            }

            taskToSchedule = queue.poll() ?: return
        }
    }

    override fun toString(): String {
        return "${super.toString()}[dispatcher = $dispatcher]"
    }

    private fun wrap(block: Runnable): Runnable {
        return block as? WrappedTask ?: WrappedTask(block)
    }

    /**
     * Tries to dispatch tasks which were blocked due to reaching parallelism limit if there is any.
     *
     * Implementation note: blocking tasks are scheduled in a fair manner (to local queue tail) to avoid
     * non-blocking continuations starvation.
     * E.g. for
     * ```
     * foo()
     * blocking()
     * bar()
     * ```
     * it's more profitable to execute bar at the end of `blocking` rather than pending blocking task
     */
    private fun afterTask() {
        var next = queue.poll()
        // If we have pending tasks in current blocking context, dispatch first
        if (next != null) {
            dispatcher.dispatchBlocking(next, taskContext, true)
            return
        }
        inFlightTasks.decrementAndGet()

        /*
         * Re-poll again and try to submit task if it's required otherwise tasks may be stuck in the local queue.
         * Race example #2 (TN is N-th thread, R is current in-flight tasks number), execution is sequential:
         * T1: submit task, start execution, R == 1
         * T2: commit slot for next task, R == 2
         * T1: finish T1, poll queue (it's still empty), R == 2
         * T2: submit next task to the local queue, decrement R, R == 1
         * T1: decrement R, finish. R == 0
         *
         * The task from T2 is stuck is the local queue
         */
        next = queue.poll() ?: return
        dispatch(next, true)
    }

    private inner class WrappedTask(val runnable: Runnable) : Runnable {
        override fun run() {
            try {
                runnable.run()
            } finally {
                afterTask()
            }
        }
    }

    override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>) = dispatcher.scheduleResumeAfterDelay(time, unit, continuation)
}
