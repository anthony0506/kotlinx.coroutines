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

import io.reactivex.Observable
import io.reactivex.ObservableSource
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.experimental.channels.LinkedListChannel
import kotlinx.coroutines.experimental.channels.ReceiveChannel

/**
 * Return type for [Observable.open] that can be used to [receive] elements from the
 * subscription and to manually [close] it.
 */
public interface SubscriptionReceiveChannel<out T> : ReceiveChannel<T> {
    /**
     * Closes this subscription channel.
     */
    public fun close()
}

/**
 * Subscribes to this [Observable] and returns a channel to receive elements emitted by it.
 */
public fun <T> ObservableSource<T>.open(): SubscriptionReceiveChannel<T> {
    val channel = SubscriptionChannel<T>()
    subscribe(channel)
    return channel
}

/**
 * Subscribes to this [Observable] and returns an iterator to receive elements emitted by it.
 *
 * This is a shortcut for `open().iterator()`. See [open] if you need an ability to manually
 * unsubscribe from the observable.
 */
public operator fun <T> ObservableSource<T>.iterator() = open().iterator()

private class SubscriptionChannel<T> : LinkedListChannel<T>(), SubscriptionReceiveChannel<T>, Observer<T> {
    @Volatile
    var subscription: Disposable? = null

    // AbstractChannel overrides
    override fun afterClose(cause: Throwable?) {
        subscription?.dispose()
    }

    // Subscription overrides
    override fun close() {
        close(cause = null)
    }

    // Observer overrider
    override fun onSubscribe(sub: Disposable) {
        subscription = sub
    }

    override fun onNext(t: T) {
        offer(t)
    }

    override fun onComplete() {
        close(cause = null)
    }

    override fun onError(e: Throwable) {
        close(cause = e)
    }
}
