# kotlinx.coroutines
Three libraries built upon Kotlin coroutines:
* `kotlinx-coroutines-async` with convenient interfaces/wrappers to commonly
used asynchronous API shipped with standard JDK, namely promise-like `CompletableFuture`
and asynchronous channels from `java.nio` package
* `kotlinx-coroutines-generate` provides ability to create `Sequence` objects
generated by coroutine body containing `yield` suspension points
* `kotlinx-coroutines-rx` allows to use `Observable` objects from
[RxJava](https://github.com/ReactiveX/RxJava) inside a coroutine body to suspend on them

## Examples
### Async
```kotlin
import kotlinx.coroutines.async
import java.util.concurrent.CompletableFuture

private fun startLongAsyncOperation(v: Int) =
        CompletableFuture.supplyAsync {
            Thread.sleep(1000)
            "Result: $v"
        }

fun main(args: Array<String>) {
    val future = async {
        (1..5).map {
            await(startLongAsyncOperation(it))
        }.joinToString("\n")
    }

    println(future.get())
}
```

Bear in mind that `async` library actively uses `CompletableFuture` from JDK 8, so
it will not work with earlier versions.

### Generate
```kotlin
import kotlinx.coroutines.generate

fun main(args: Array<String>) {
    val sequence = generate {
        for (i in 1..5) {
            yield(i)
        }
    }

    println(sequence.joinToString(" "))
}
```

### RxJava
```kotlin
import kotlinx.coroutines.asyncRx
import retrofit2.Retrofit
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import rx.Observable

interface GitHub {
    @GET("orgs/{user}/repos")
    fun orgRepos(@Path("user") user: String): Observable<List<Repo>>
}

data class Repo(val name: String)

fun main(args: Array<String>) {
    val retrofit = Retrofit.Builder().apply {
        baseUrl("https://api.github.com")
        addConverterFactory(GsonConverterFactory.create())
        addCallAdapterFactory(RxJavaCallAdapterFactory.create())
    }.build()

    val github = retrofit.create(GitHub::class.java)

    asyncRx {
        for (org in listOf("Kotlin", "ReactiveX")) {
            // `awaitSingle()` call here is a suspension point,
            // i.e. coroutine's code stops on it until request is not completed
            val repos = github.orgRepos(org).take(5).awaitSingle().joinToString()

            println("$org: $repos")
        }
    }
}
```

For more examples you can look at `kotlinx-coroutines-async-example-ui`
and `kotlinx-coroutines-rx-example` samples projects or in tests directories.

## Maven

Add the bintray repository

```xml
<repository>
    <snapshots>
        <enabled>false</enabled>
    </snapshots>
    <id>dl</id>
    <name>bintray</name>
    <url>http://dl.bintray.com/kotlin/kotlinx.coroutines</url>
</repository>
```

Add dependencies:

```xml
<dependency>
    <groupId>org.jetbrains.kotlinx</groupId>
    <artifactId>kotlinx-coroutines-generate</artifactId>
    <version>0.1-alpha-2</version>
</dependency>
<dependency>
    <groupId>org.jetbrains.kotlinx</groupId>
    <artifactId>kotlinx-coroutines-async</artifactId>
    <version>0.1-alpha-2</version>
</dependency>
<dependency>
    <groupId>org.jetbrains.kotlinx</groupId>
    <artifactId>kotlinx-coroutines-rx</artifactId>
    <version>0.1-alpha-2</version>
</dependency>
```

## Gradle

Just add dependencies:

```groovy
compile 'org.jetbrains.kotlinx:kotlinx-coroutines-generate:0.1-alpha-2'
compile 'org.jetbrains.kotlinx:kotlinx-coroutines-async:0.1-alpha-2'
compile 'org.jetbrains.kotlinx:kotlinx-coroutines-rx:0.1-alpha-2'
```

*NB:* As `async` library is built upon `CompletableFuture` it requires JDK 8 (24 Android API level)

Also you should include our bintray repository:

```groovy
repositories {
    maven {
        url "http://dl.bintray.com/kotlin/kotlinx.coroutines"
    }
}
```
