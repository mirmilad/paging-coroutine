package androidx.paging

/**
 * Helper functions that are workarounds to kotlin Runtime Exceptions when using kotlin.
 */

import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.stubbing.OngoingStubbing

/**
 * Returns Mockito.eq() as nullable type to avoid java.lang.IllegalStateException when
 * null is returned.
 *
 * Generic T is nullable because implicitly bounded by Any?.
 */
fun <T> eq(obj: T): T = Mockito.eq<T>(obj)


/**
 * Returns Mockito.any() as nullable type to avoid java.lang.IllegalStateException when
 * null is returned.
 */
fun <T> any(): T = Mockito.any<T>()


/**
 * Returns ArgumentCaptor.capture() as nullable type to avoid java.lang.IllegalStateException
 * when null is returned.
 */
fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()

fun <T> ArgumentCaptor<T>.captureForKotlin() = androidx.paging.capture(this)

/**
 * Helper function for creating an argumentCaptor in kotlin.
 */
inline fun <reified T : Any> argumentCaptor(): ArgumentCaptor<T> =
    ArgumentCaptor.forClass(T::class.java)

// To avoid having to use backticks for "when"
fun <T> whenever(methodCall: T): OngoingStubbing<T> =
    Mockito.`when`(methodCall)