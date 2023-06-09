# Module kotlinx-coroutines-reactor

Utilities for [Reactor](https://projectreactor.io).

Coroutine builders:

| **Name**        | **Result**  | **Scope**        | **Description**
| --------------- | ------------| ---------------- | ---------------
| [mono]          | `Mono`      | [CoroutineScope] | A cold Mono that starts the coroutine on subscription
| [flux]          | `Flux`      | [CoroutineScope] | A cold Flux that starts the coroutine on subscription

Note that `Mono` and `Flux` are subclasses of [Reactive Streams](https://www.reactive-streams.org)'
`Publisher` and extensions for it are covered by the
[kotlinx-coroutines-reactive](../kotlinx-coroutines-reactive) module.

Integration with [Flow]:

| **Name**        | **Result**     | **Description**
| --------------- | -------------- | ---------------
| [Flow.asFlux]   | `Flux`         | Converts the given flow to a TCK-compliant Flux.

This adapter is integrated with Reactor's `Context` and coroutines' [ReactorContext].

Conversion functions:

| **Name** | **Description**
| -------- | ---------------
| [Job.asMono][kotlinx.coroutines.Job.asMono] | Converts a job to a hot Mono
| [Deferred.asMono][kotlinx.coroutines.Deferred.asMono] | Converts a deferred value to a hot Mono
| [Scheduler.asCoroutineDispatcher][reactor.core.scheduler.Scheduler.asCoroutineDispatcher] | Converts a scheduler to a [CoroutineDispatcher]

<!--- MODULE kotlinx-coroutines-core -->
<!--- INDEX kotlinx.coroutines -->

[CoroutineScope]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-scope/index.html
[CoroutineDispatcher]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/index.html

<!--- INDEX kotlinx.coroutines.channels -->
<!--- INDEX kotlinx.coroutines.flow -->

[Flow]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-flow/index.html

<!--- MODULE kotlinx-coroutines-reactor -->
<!--- INDEX kotlinx.coroutines.reactor -->

[mono]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-reactor/kotlinx.coroutines.reactor/mono.html
[flux]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-reactor/kotlinx.coroutines.reactor/flux.html
[Flow.asFlux]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-reactor/kotlinx.coroutines.reactor/as-flux.html
[ReactorContext]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-reactor/kotlinx.coroutines.reactor/-reactor-context/index.html
[kotlinx.coroutines.Job.asMono]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-reactor/kotlinx.coroutines.reactor/as-mono.html
[kotlinx.coroutines.Deferred.asMono]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-reactor/kotlinx.coroutines.reactor/as-mono.html
[reactor.core.scheduler.Scheduler.asCoroutineDispatcher]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-reactor/kotlinx.coroutines.reactor/as-coroutine-dispatcher.html

<!--- END -->

# Package kotlinx.coroutines.reactor

Utilities for [Reactor](https://projectreactor.io).
