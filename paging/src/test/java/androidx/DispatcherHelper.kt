package androidx

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.ThreadSafeHeap
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
@UseExperimental(InternalCoroutinesApi::class)
fun TestCoroutineDispatcher.isEmpty() : Boolean = TestCoroutineDispatcher::class.memberProperties.single { it.name == "queue" }.run {
    isAccessible = true
    return (getter.call(this@isEmpty) as ThreadSafeHeap<*>).isEmpty
}

