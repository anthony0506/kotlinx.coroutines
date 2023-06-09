/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.internal

import java.lang.ThreadLocal

@Suppress("ACTUAL_WITHOUT_EXPECT") // internal visibility
internal actual typealias CommonThreadLocal<T> = ThreadLocal<T>

internal actual fun<T> commonThreadLocal(name: Symbol): CommonThreadLocal<T> = ThreadLocal()
