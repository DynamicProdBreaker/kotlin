// WITH_STDLIB
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

// Performance test for intercepted() with interception
// Measures the overhead of getting intercepted continuations with an interceptor

var interceptionCount = 0

suspend fun suspendWithInterceptor(): String = suspendCoroutineUninterceptedOrReturn { continuation ->
    // Get intercepted continuation to trigger interception
    val intercepted = continuation.intercepted()
    continuation.resume("OK")
    COROUTINE_SUSPENDED
}

class CountingInterceptor : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        interceptionCount++
        return continuation
    }
}

fun box(): String {
    interceptionCount = 0
    var result = "FAIL"
    
    val coroutine: suspend () -> String = {
        var accumulated = ""
        repeat(10) {
            val value = suspendWithInterceptor()
            accumulated = value
        }
        accumulated
    }
    
    coroutine.startCoroutine(object : Continuation<String> {
        override val context: CoroutineContext
            get() = CountingInterceptor()
        
        override fun resumeWith(value: Result<String>) {
            result = value.getOrNull() ?: "FAIL"
        }
    })
    
    // Expect 1 interception for the initial coroutine
    return if (interceptionCount == 1 && result == "OK") "OK" else "FAIL: interceptions=$interceptionCount"
}
