# Module kotlinx-coroutines-rx2

Utilities for [RxJava 2.x](https://github.com/ReactiveX/RxJava).

Coroutine builders:

| **Name**        | **Result**                              | **Scope**        | **Description**
| --------------- | --------------------------------------- | ---------------- | ---------------
| [rxCompletable] | `Completable`                           | [CoroutineScope] | Cold completable that starts coroutine on subscribe
| [rxSingle]      | `Single`                                | [CoroutineScope] | Cold single that starts coroutine on subscribe
| [rxObservable]  | `Observable`                            | [ProducerScope]  | Cold observable that starts coroutine on subscribe

Suspending extension functions and suspending iteration:

| **Name** | **Description**
| -------- | ---------------
| [CompletableSource.await][io.reactivex.CompletableSource.await] | Awaits for completion of the completable value 
| [SingleSource.await][io.reactivex.SingleSource.await] | Awaits for completion of the single value and returns it 
| [ObservableSource.awaitFirst][io.reactivex.ObservableSource.awaitFirst] | Awaits for the first value from the given observable
| [ObservableSource.awaitLast][io.reactivex.ObservableSource.awaitFirst] | Awaits for the last value from the given observable
| [ObservableSource.awaitSingle][io.reactivex.ObservableSource.awaitSingle] | Awaits for the single value from the given observable
| [ObservableSource.open][io.reactivex.ObservableSource.open] | Subscribes to observable and returns [ReceiveChannel] 
| [ObservableSource.iterator][io.reactivex.ObservableSource.iterator] | Subscribes to observable and returns [ChannelIterator]

Note, that `Flowable` is a subclass of [Reactive Streams](http://www.reactive-streams.org)
`Publisher` and extensions for it are covered by
[kotlinx-coroutines-reactive](../kotlinx-coroutines-reactive) module.

Conversion functions:

| **Name** | **Description**
| -------- | ---------------
| [Job.toCompletable][kotlinx.coroutines.experimental.Job.toCompletable] | Converts job to hot completable
| [Deferred.toSingle][kotlinx.coroutines.experimental.Deferred.toSingle] | Converts deferred value to hot single
| [ReceiveChannel.toObservable][kotlinx.coroutines.experimental.channels.ReceiveChannel.toObservable] | Converts streaming channel to hot observable

<!--- SITE_ROOT https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core -->
<!--- DOCS_ROOT kotlinx-coroutines-core/target/dokka/kotlinx-coroutines-core -->
<!--- INDEX kotlinx.coroutines.experimental -->
[CoroutineScope]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.experimental/-coroutine-scope/index.html
<!--- INDEX kotlinx.coroutines.experimental.channels -->
[ProducerScope]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.experimental.channels/-producer-scope/index.html
[ReceiveChannel]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.experimental.channels/-receive-channel/index.html
[ChannelIterator]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.experimental.channels/-channel-iterator/index.html
<!--- SITE_ROOT https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2 -->
<!--- DOCS_ROOT kotlinx-coroutines-rx2/target/dokka/kotlinx-coroutines-rx2 -->
<!--- INDEX kotlinx.coroutines.experimental.rx2 -->
[rxCompletable]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/rx-completable.html
[rxSingle]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/rx-single.html
[rxObservable]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/rx-observable.html
[io.reactivex.CompletableSource.await]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/io.reactivex.-completable-source/await.html
[io.reactivex.SingleSource.await]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/io.reactivex.-single-source/await.html
[io.reactivex.ObservableSource.awaitFirst]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/io.reactivex.-observable-source/await-first.html
[io.reactivex.ObservableSource.awaitSingle]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/io.reactivex.-observable-source/await-single.html
[io.reactivex.ObservableSource.open]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/io.reactivex.-observable-source/open.html
[io.reactivex.ObservableSource.iterator]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/io.reactivex.-observable-source/iterator.html
[kotlinx.coroutines.experimental.Job.toCompletable]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/kotlinx.coroutines.experimental.-job/to-completable.html
[kotlinx.coroutines.experimental.Deferred.toSingle]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/kotlinx.coroutines.experimental.-deferred/to-single.html
[kotlinx.coroutines.experimental.channels.ReceiveChannel.toObservable]: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-rx2/kotlinx.coroutines.experimental.rx2/kotlinx.coroutines.experimental.channels.-receive-channel/to-observable.html
<!--- END -->

# Package kotlinx.coroutines.experimental.rx2

Utilities for [RxJava 2.x](https://github.com/ReactiveX/RxJava).
