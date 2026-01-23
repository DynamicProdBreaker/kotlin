// WITH_STDLIB
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

// Performance test for intercepted() function
// Measures the overhead of calling intercepted() on continuations

var interceptedCallCount = 0

suspend fun testIntercepted(): String = suspendCoroutineUninterceptedOrReturn { continuation ->
    // Call intercepted() multiple times to measure its overhead
    repeat(10) {
        val intercepted = continuation.intercepted()
        if (intercepted != null) {
            interceptedCallCount++
        }
    }
    continuation.resume("OK")
    COROUTINE_SUSPENDED
}

class TestInterceptor : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        return continuation
    }
}

fun box(): String {
    interceptedCallCount = 0
    var result = "FAIL"
    
    val coroutine: suspend () -> String = {
        var accumulated = ""
        repeat(10) {
            val value = testIntercepted()
            accumulated = value
        }
        accumulated
    }
    
    coroutine.startCoroutine(object : Continuation<String> {
        override val context: CoroutineContext
            get() = TestInterceptor()
        
        override fun resumeWith(value: Result<String>) {
            result = value.getOrNull() ?: "FAIL"
        }
    })
    
    return if (interceptedCallCount == 100 && result == "OK") "OK" else "FAIL: $interceptedCallCount"
}
