/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:UseExperimental(ExperimentalTypeInference::class)

package kotlinx.coroutines.reactor

import kotlinx.coroutines.*
import reactor.core.*
import reactor.core.publisher.*
import kotlin.coroutines.*
import kotlin.experimental.*

/**
 * Creates cold [mono][Mono] that will run a given [block] in a coroutine.
 * Every time the returned mono is subscribed, it starts a new coroutine.
 * Coroutine returns a single, possibly null value. Unsubscribing cancels running coroutine.
 *
 * | **Coroutine action**                  | **Signal to sink**
 * | ------------------------------------- | ------------------------
 * | Returns a non-null value              | `success(value)`
 * | Returns a null                        | `success`
 * | Failure with exception or unsubscribe | `error`
 *
 * Coroutine context is inherited from a [CoroutineScope], additional context elements can be specified with [context] argument.
 * If the context does not have any dispatcher nor any other [ContinuationInterceptor], then [Dispatchers.Default] is used.
 * The parent job is inherited from a [CoroutineScope] as well, but it can also be overridden
 * with corresponding [coroutineContext] element.
 *
 * @param context context of the coroutine.
 * @param block the coroutine code.
 */
@BuilderInference
fun <T> CoroutineScope.mono(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> T?
): Mono<T> = Mono.create { sink ->
    val newContext = newCoroutineContext(context)
    val coroutine = MonoCoroutine(newContext, sink)
    sink.onDispose(coroutine)
    coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
}

private class MonoCoroutine<in T>(
    parentContext: CoroutineContext,
    private val sink: MonoSink<T>
) : AbstractCoroutine<T>(parentContext, true), Disposable {
    override val cancelsParent: Boolean get() = true

    var disposed = false

    override fun onCompleted(value: T) {
        if (!disposed) {
            if (value == null) sink.success() else sink.success(value)
        }
    }

    override fun onCompletedExceptionally(exception: Throwable) {
        if (!disposed) sink.error(exception)
    }
    
    override fun dispose() {
        disposed = true
        cancel(cause = null)
    }

    override fun isDisposed(): Boolean = disposed
}

